// LOCAL AI native engine: a thin, stateful wrapper over llama.cpp exposed through JNI.
// Kotlin never sees llama.cpp types; see docs/MODEL_RUNTIME_DESIGN.md §2–3.
//
// Threading: all calls except la_abort are serialised by the Kotlin LlamaEngine (single thread),
// and additionally guarded by g_mu here.

#include <jni.h>
#include <android/log.h>
#include <android/set_abort_message.h>

#include <atomic>
#include <cmath>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "chat.h"
#include "json-schema-to-grammar.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "LocalAI-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::mutex                g_mu;
llama_model *             g_model = nullptr;
llama_context *           g_ctx   = nullptr;
common_chat_templates_ptr g_tmpls;
std::vector<llama_token>  g_cache;   // tokens currently held in the KV cache (sequence 0)
std::atomic<bool>         g_abort{false};
int                       g_n_batch = 512;

// Embedding model: a second, small context (docs/KNOWLEDGE_ENGINE_DESIGN.md §4).
std::mutex                g_emu;
llama_model *             g_emodel = nullptr;
llama_context *           g_ectx   = nullptr;

// Vision projector (mmproj) for multimodal models, bound to g_model.
mtmd_context *            g_mtmd   = nullptr;

constexpr int ST_OK = 0, ST_LENGTH = 1, ST_ABORTED = 2;
constexpr int ERR_NO_MODEL = -1, ERR_OVERFLOW = -2, ERR_TEMPLATE = -3, ERR_DECODE = -4;

bool abort_cb(void *) { return g_abort.load(); }

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
}

void free_all() {
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
    g_tmpls.reset();
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cache.clear();
}

// After an exception the KV cache may be half-written: forget it so the next turn starts clean.
int ERR_DECODE_RESET() {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_ctx) if (llama_memory_t m = llama_get_memory(g_ctx)) llama_memory_clear(m, true);
    g_cache.clear();
    return ERR_DECODE;
}

void free_all_locked() { std::lock_guard<std::mutex> lock(g_mu); free_all(); }

// Length of the longest prefix of `s` made of complete UTF-8 sequences.
size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0, last_ok = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > s.size()) break;
        i += len;
        last_ok = i;
    }
    return last_ok;
}

std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Decode tokens [from, end) in n_batch chunks. Returns false on error/abort.
bool decode_range(const std::vector<llama_token> & toks, size_t from, int & status) {
    for (size_t i = from; i < toks.size(); i += g_n_batch) {
        if (g_abort.load()) { status = ST_ABORTED; return false; }
        const int n = static_cast<int>(std::min<size_t>(g_n_batch, toks.size() - i));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(toks.data() + i), n);
        const int rc = llama_decode(g_ctx, batch);
        if (rc != 0) {
            status = (rc == 2 || g_abort.load()) ? ST_ABORTED : ERR_DECODE;
            LOGE("llama_decode failed rc=%d", rc);
            return false;
        }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + n);
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_init(JNIEnv * env, jobject, jstring nativeLibDir) {
    std::lock_guard<std::mutex> lock(g_mu);
    llama_log_set(log_cb, nullptr);
    // A failed GGML_ASSERT aborts the process. Put its message in the log and in the crash report's abort message,
    // so the in-app crash report says which check failed.
    ggml_set_abort_callback([](const char * msg) {
        __android_log_print(ANDROID_LOG_FATAL, TAG, "ggml abort: %s", msg ? msg : "");
        android_set_abort_message(msg ? msg : "ggml abort");
    });
    const std::string dir = jstr(env, nativeLibDir);
    ggml_backend_load_all_from_path(dir.c_str());   // picks the best CPU variant for this SoC
    llama_backend_init();
    LOGI("backend initialised from %s", dir.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

static jint loadModel_impl(JNIEnv * env, jobject, jstring jpath, jint nCtx,
                                                             jint nThreads, jint nBatch, jboolean kvQ8) {
    std::lock_guard<std::mutex> lock(g_mu);
    free_all();
    const std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                      // CPU first; GPU is an opt-in experiment (later)
    mp.load_mode    = LLAMA_LOAD_MODE_MMAP;   // weights stay file-backed: reclaimable under pressure
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) { LOGE("failed to load %s", path.c_str()); return -1; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(nCtx);
    cp.n_batch         = static_cast<uint32_t>(nBatch);
    cp.n_ubatch        = static_cast<uint32_t>(nBatch);
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    cp.abort_callback      = abort_cb;
    cp.abort_callback_data = nullptr;
    if (kvQ8) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;   // quantised V cache requires flash attention
    }
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("failed to create context"); free_all(); return -2; }

    try {
        g_tmpls = common_chat_templates_init(g_model, "");
    } catch (const std::exception & e) {
        LOGW("chat template init failed (%s); using chatml", e.what());
        g_tmpls = common_chat_templates_init(g_model, "chatml");
    }
    g_n_batch = nBatch;
    g_cache.clear();
    LOGI("model loaded: ctx=%d threads=%d batch=%d kvq8=%d", nCtx, nThreads, nBatch, (int) kvQ8);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_modelInfo(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_model) return env->NewStringUTF("");
    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    std::string s = std::string(desc) + "|" + std::to_string(llama_model_n_params(g_model)) + "|" +
                    std::to_string(llama_model_n_ctx_train(g_model)) + "|" + std::to_string(llama_n_ctx(g_ctx)) + "|" +
                    std::to_string(llama_model_n_layer(g_model));
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mu);
    free_all();
}

JNIEXPORT void JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_abort(JNIEnv *, jobject) {
    g_abort.store(true);   // deliberately lock-free: generation holds g_mu
}

static jint generate_impl(JNIEnv * env, jobject, jobjectArray jroles,
                                                            jobjectArray jcontents, jint maxTokens, jfloat temp,
                                                            jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
                                                            jint seed, jboolean enableThinking, jstring jgrammar,
                                                            jbyteArray jrgb, jint imgW, jint imgH, jobject callback) {
    std::lock_guard<std::mutex> lock(g_mu);
    g_abort.store(false);
    if (!g_model || !g_ctx) return ERR_NO_MODEL;

    jclass    cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)V");
    jmethodID onDone  = env->GetMethodID(cbClass, "onDone", "(IIIJJ)V");

    // 1. Render the conversation with the model's own chat template.
    common_chat_templates_inputs in;
    const jsize n = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n; ++i) {
        common_chat_msg m;
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jc = (jstring) env->GetObjectArrayElement(jcontents, i);
        m.role    = jstr(env, jr);
        m.content = jstr(env, jc);
        env->DeleteLocalRef(jr); env->DeleteLocalRef(jc);
        in.messages.push_back(std::move(m));
    }
    in.add_generation_prompt = true;
    in.enable_thinking       = enableThinking;
    common_chat_params cparams;
    try {
        in.use_jinja = true;
        cparams = common_chat_templates_apply(g_tmpls.get(), in);
    } catch (const std::exception & e) {
        LOGW("jinja template failed (%s); retrying with built-in templates", e.what());
        try { in.use_jinja = false; cparams = common_chat_templates_apply(g_tmpls.get(), in); }
        catch (const std::exception & e2) { LOGE("template failed: %s", e2.what()); return ERR_TEMPLATE; }
    }

    const int n_ctx = static_cast<int>(llama_n_ctx(g_ctx));
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t keep = 0, n_eval = 0, base_pos = 0;   // base_pos: tokens in the KV cache before generation
    int status = ST_OK;
    const auto t0 = std::chrono::steady_clock::now();

    if (jrgb != nullptr && g_mtmd != nullptr) {
        // 2b. Image turn: the picture is inserted as its own part right before the user's last message.
        //     Text parts keep the same special-token rules as below.
        const jsize len = env->GetArrayLength(jrgb);
        if (imgW <= 0 || imgH <= 0 || len != imgW * imgH * 3) return ERR_TEMPLATE;
        std::vector<unsigned char> px(static_cast<size_t>(len));
        env->GetByteArrayRegion(jrgb, 0, len, reinterpret_cast<jbyte *>(px.data()));
        mtmd_bitmap * bmp = mtmd_bitmap_init(static_cast<uint32_t>(imgW), static_cast<uint32_t>(imgH), px.data());
        const auto & parts = cparams.prompt.parts;
        int last_input = -1;
        for (size_t i = 0; i < parts.size(); ++i) if (parts[i].is_input) last_input = static_cast<int>(i);
        std::vector<mtmd_input_text> texts(parts.size());
        std::vector<mtmd_input_part> mparts; mparts.reserve(parts.size() + 1);
        for (size_t i = 0; i < parts.size(); ++i) {
            if (static_cast<int>(i) == last_input) mparts.push_back({nullptr, bmp});
            texts[i] = {parts[i].text.c_str(), parts[i].text.size(), false, !parts[i].is_input};
            mparts.push_back({&texts[i], nullptr});
        }
        if (last_input < 0) mparts.insert(mparts.end() - (mparts.empty() ? 0 : 1), {nullptr, bmp});
        std::vector<const mtmd_input_part *> ptrs; for (auto & m : mparts) ptrs.push_back(&m);
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        const int32_t rc = mtmd_tokenize_from_parts(g_mtmd, chunks, ptrs.data(), ptrs.size(), true);
        mtmd_bitmap_free(bmp);
        if (rc != 0) { mtmd_input_chunks_free(chunks); LOGE("image tokenize failed rc=%d", rc); return ERR_TEMPLATE; }
        n_eval = mtmd_helper_get_n_tokens(chunks);
        if (static_cast<int>(n_eval) + 16 > n_ctx) { mtmd_input_chunks_free(chunks); return ERR_OVERFLOW; }
        llama_memory_clear(mem, true);
        g_cache.clear();
        llama_pos new_past = 0;
        const int32_t erc = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, 0, 0, g_n_batch, true, &new_past);
        mtmd_input_chunks_free(chunks);
        if (erc != 0) { llama_memory_clear(mem, true); return g_abort.load() ? ST_ABORTED : ERR_DECODE; }
        g_cache.push_back(-1);   // KV now holds image embeddings: never reuse it as a text prefix
        base_pos = static_cast<size_t>(new_past);
    } else {
        // 2. Tokenize. User-provided text is tokenized WITHOUT special-token parsing, so a message or a
        //    document cannot inject control tokens such as <|im_end|>.
        std::vector<llama_token> toks;
        bool first = true;
        for (const auto & part : cparams.prompt.parts) {
            auto t = common_tokenize(g_ctx, part.text, /*add_special*/ first, /*parse_special*/ !part.is_input);
            toks.insert(toks.end(), t.begin(), t.end());
            first = false;
        }
        if (toks.empty()) return ERR_TEMPLATE;
        if (static_cast<int>(toks.size()) + 16 > n_ctx) return ERR_OVERFLOW;

        // 3. Reuse the KV cache for the longest common prefix with the previous turn.
        while (keep < g_cache.size() && keep < toks.size() && g_cache[keep] == toks[keep]) ++keep;
        if (keep == toks.size()) --keep;   // always decode at least one token to get fresh logits
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
            llama_memory_clear(mem, true);   // e.g. sliding-window caches cannot be partially trimmed
            keep = 0;
        }
        g_cache.resize(keep);
        n_eval = toks.size() - keep;
        base_pos = toks.size();
        if (!decode_range(toks, keep, status)) {
            if (status == ERR_DECODE) { llama_memory_clear(mem, true); g_cache.clear(); }
            return status;
        }
    }
    const auto t1 = std::chrono::steady_clock::now();

    // 4. Sample.
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    struct SamplerFree { llama_sampler *& p; ~SamplerFree() { if (p) llama_sampler_free(p); } } smpl_guard{smpl};
    // Grammar-constrained output (plans, tool calls): only tokens that keep the JSON valid can be sampled.
    const std::string grammar = jgrammar ? jstr(env, jgrammar) : std::string();
    if (!grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) { LOGE("invalid grammar"); return ERR_TEMPLATE; }
        llama_sampler_chain_add(smpl, g);
    }
    // top-k first: the repetition penalty is slow on the full vocabulary (see llama.h)
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 64));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeatPenalty, 0.0f, 0.0f));
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    std::string pending;
    int n_gen = 0;
    status = ST_LENGTH;
    while (n_gen < maxTokens) {
        if (g_abort.load()) { status = ST_ABORTED; break; }
        if (static_cast<int>(base_pos) + n_gen >= n_ctx - 1) { status = ST_LENGTH; break; }
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) { status = ST_OK; break; }

        pending += common_token_to_piece(g_ctx, id, /*special*/ false);
        const size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            jbyteArray bytes = env->NewByteArray(static_cast<jsize>(ok));
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(ok), reinterpret_cast<const jbyte *>(pending.data()));
            env->CallVoidMethod(callback, onToken, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ok);
            if (env->ExceptionCheck()) { env->ExceptionClear(); status = ST_ABORTED; break; }
        }

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) { status = g_abort.load() ? ST_ABORTED : ERR_DECODE; break; }
        g_cache.push_back(id);
        ++n_gen;
    }
    llama_sampler_free(smpl); smpl = nullptr;
    if (status == ERR_DECODE) { llama_memory_clear(mem, true); g_cache.clear(); }

    const auto t2 = std::chrono::steady_clock::now();
    const auto ms = [](auto a, auto b) { return static_cast<jlong>(std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count()); };
    env->CallVoidMethod(callback, onDone, static_cast<jint>(n_eval), static_cast<jint>(keep), n_gen, ms(t0, t1), ms(t1, t2));
    if (env->ExceptionCheck()) env->ExceptionClear();
    return status;
}

static jint loadProjector_impl(JNIEnv * env, jobject, jstring jpath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_model) return -1;
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu = false; p.n_threads = nThreads; p.print_timings = false; p.warmup = false;
    g_mtmd = mtmd_init_from_file(jstr(env, jpath).c_str(), g_model, p);
    if (!g_mtmd) return -2;
    if (!mtmd_support_vision(g_mtmd)) { mtmd_free(g_mtmd); g_mtmd = nullptr; return -3; }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_schemaToGrammar(JNIEnv * env, jobject, jstring jschema) {
    try {
        return env->NewStringUTF(json_schema_to_grammar(common_json::parse(jstr(env, jschema)), true).c_str());
    } catch (const std::exception & e) {
        LOGE("schema conversion failed: %s", e.what());
        return env->NewStringUTF("");
    }
}

static jint loadEmbedder_impl(JNIEnv * env, jobject, jstring jpath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_emu);
    if (g_ectx) { llama_free(g_ectx); g_ectx = nullptr; }
    if (g_emodel) { llama_model_free(g_emodel); g_emodel = nullptr; }
    const std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_emodel = llama_model_load_from_file(path.c_str(), mp);
    if (!g_emodel) return -1;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 512; cp.n_batch = 512; cp.n_ubatch = 512;
    cp.n_threads = nThreads; cp.n_threads_batch = nThreads;
    cp.embeddings = true;
    g_ectx = llama_init_from_model(g_emodel, cp);
    if (!g_ectx) { llama_model_free(g_emodel); g_emodel = nullptr; return -2; }
    return llama_model_n_embd_out(g_emodel);
}

JNIEXPORT void JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_unloadEmbedder(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_emu);
    if (g_ectx) { llama_free(g_ectx); g_ectx = nullptr; }
    if (g_emodel) { llama_model_free(g_emodel); g_emodel = nullptr; }
}

// Returns one L2-normalised vector per input text (texts are truncated to 512 tokens).
static jobjectArray embed_impl(JNIEnv * env, jobject, jobjectArray jtexts) {
    std::lock_guard<std::mutex> lock(g_emu);
    if (!g_ectx) return nullptr;
    const int n_embd = llama_model_n_embd_out(g_emodel);
    const jsize n = env->GetArrayLength(jtexts);
    jobjectArray out = env->NewObjectArray(n, env->FindClass("[F"), nullptr);
    std::vector<float> v(n_embd);
    for (jsize i = 0; i < n; ++i) {
        auto jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        auto toks = common_tokenize(g_ectx, jstr(env, jt), true, false);
        env->DeleteLocalRef(jt);
        if (toks.size() > 512) { const auto last = toks.back(); toks.resize(511); toks.push_back(last); }
        if (toks.empty()) continue;
        if (llama_memory_t m = llama_get_memory(g_ectx)) llama_memory_clear(m, true);
        if (llama_decode(g_ectx, llama_batch_get_one(toks.data(), (int) toks.size())) != 0) { LOGE("embed decode failed"); continue; }
        const float * e = llama_get_embeddings_seq(g_ectx, 0);
        if (!e) e = llama_get_embeddings_ith(g_ectx, -1);
        if (!e) continue;
        double norm = 0; for (int k = 0; k < n_embd; ++k) norm += (double) e[k] * e[k];
        const float inv = norm > 0 ? (float) (1.0 / std::sqrt(norm)) : 0.f;
        for (int k = 0; k < n_embd; ++k) v[k] = e[k] * inv;
        jfloatArray arr = env->NewFloatArray(n_embd);
        env->SetFloatArrayRegion(arr, 0, n_embd, v.data());
        env->SetObjectArrayElement(out, i, arr);
        env->DeleteLocalRef(arr);
    }
    return out;
}

// Every entry point that runs llama.cpp code is guarded: a C++ exception escaping into JNI (for example a grammar
// that rejects every token, or a malformed image or model file) would otherwise abort the whole app.
#define LA_GUARD(fallback, call) try { return call; } \
    catch (const std::exception & e) { LOGE("%s failed: %s", __func__, e.what()); } \
    catch (...) { LOGE("%s failed: unknown exception", __func__); } \
    return fallback;

JNIEXPORT jint JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_generate(JNIEnv * env, jobject o, jobjectArray jroles,
        jobjectArray jcontents, jint maxTokens, jfloat temp, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
        jint seed, jboolean enableThinking, jstring jgrammar, jbyteArray jrgb, jint imgW, jint imgH, jobject callback) {
    LA_GUARD(ERR_DECODE_RESET(), generate_impl(env, o, jroles, jcontents, maxTokens, temp, topP, topK, minP, repeatPenalty,
                                         seed, enableThinking, jgrammar, jrgb, imgW, imgH, callback))
}

JNIEXPORT jint JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_loadModel(JNIEnv * env, jobject o, jstring jpath, jint nCtx,
        jint nThreads, jint nBatch, jboolean kvQ8) {
    LA_GUARD((free_all_locked(), -1), loadModel_impl(env, o, jpath, nCtx, nThreads, nBatch, kvQ8))
}

JNIEXPORT jint JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_loadProjector(JNIEnv * env, jobject o, jstring jpath, jint nThreads) {
    LA_GUARD(-2, loadProjector_impl(env, o, jpath, nThreads))
}

JNIEXPORT jint JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_loadEmbedder(JNIEnv * env, jobject o, jstring jpath, jint nThreads) {
    LA_GUARD(-1, loadEmbedder_impl(env, o, jpath, nThreads))
}

JNIEXPORT jobjectArray JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_embed(JNIEnv * env, jobject o, jobjectArray jtexts) {
    LA_GUARD(nullptr, embed_impl(env, o, jtexts))
}

} // extern "C"
