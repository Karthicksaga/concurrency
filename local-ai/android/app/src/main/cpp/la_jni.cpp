// LOCAL AI native engine: a thin, stateful wrapper over llama.cpp exposed through JNI.
// Kotlin never sees llama.cpp types; see docs/MODEL_RUNTIME_DESIGN.md §2–3.
//
// Threading: all calls except la_abort are serialised by the Kotlin LlamaEngine (single thread),
// and additionally guarded by g_mu here.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "common.h"
#include "chat.h"

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

constexpr int ST_OK = 0, ST_LENGTH = 1, ST_ABORTED = 2;
constexpr int ERR_NO_MODEL = -1, ERR_OVERFLOW = -2, ERR_TEMPLATE = -3, ERR_DECODE = -4;

bool abort_cb(void *) { return g_abort.load(); }

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, "%s", text);
}

void free_all() {
    g_tmpls.reset();
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cache.clear();
}

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
    const std::string dir = jstr(env, nativeLibDir);
    ggml_backend_load_all_from_path(dir.c_str());   // picks the best CPU variant for this SoC
    llama_backend_init();
    LOGI("backend initialised from %s", dir.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jint JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_loadModel(JNIEnv * env, jobject, jstring jpath, jint nCtx,
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

JNIEXPORT jint JNICALL
Java_com_localai_app_runtime_inference_LlamaNative_generate(JNIEnv * env, jobject, jobjectArray jroles,
                                                            jobjectArray jcontents, jint maxTokens, jfloat temp,
                                                            jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
                                                            jint seed, jboolean enableThinking, jobject callback) {
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
        m.role    = jstr(env, (jstring) env->GetObjectArrayElement(jroles, i));
        m.content = jstr(env, (jstring) env->GetObjectArrayElement(jcontents, i));
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

    // 2. Tokenize. User-provided text is tokenized WITHOUT special-token parsing, so a message or a
    //    document cannot inject control tokens such as <|im_end|>.
    std::vector<llama_token> toks;
    bool first = true;
    for (const auto & part : cparams.prompt.parts) {
        auto t = common_tokenize(g_ctx, part.text, /*add_special*/ first, /*parse_special*/ !part.is_input);
        toks.insert(toks.end(), t.begin(), t.end());
        first = false;
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(g_ctx));
    if (toks.empty()) return ERR_TEMPLATE;
    if (static_cast<int>(toks.size()) + 16 > n_ctx) return ERR_OVERFLOW;

    // 3. Reuse the KV cache for the longest common prefix with the previous turn.
    size_t keep = 0;
    while (keep < g_cache.size() && keep < toks.size() && g_cache[keep] == toks[keep]) ++keep;
    if (keep == toks.size()) --keep;   // always decode at least one token to get fresh logits
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true);   // e.g. sliding-window caches cannot be partially trimmed
        keep = 0;
    }
    g_cache.resize(keep);

    const auto t0 = std::chrono::steady_clock::now();
    int status = ST_OK;
    if (!decode_range(toks, keep, status)) {
        if (status == ERR_DECODE) { llama_memory_clear(mem, true); g_cache.clear(); }
        return status;
    }
    const auto t1 = std::chrono::steady_clock::now();

    // 4. Sample.
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sp);
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
        if (static_cast<int>(g_cache.size()) >= n_ctx - 1) { status = ST_LENGTH; break; }
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
    llama_sampler_free(smpl);
    if (status == ERR_DECODE) { llama_memory_clear(mem, true); g_cache.clear(); }

    const auto t2 = std::chrono::steady_clock::now();
    const auto ms = [](auto a, auto b) { return static_cast<jlong>(std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count()); };
    env->CallVoidMethod(callback, onDone, static_cast<jint>(toks.size() - keep), static_cast<jint>(keep), n_gen, ms(t0, t1), ms(t1, t2));
    if (env->ExceptionCheck()) env->ExceptionClear();
    return status;
}

} // extern "C"
