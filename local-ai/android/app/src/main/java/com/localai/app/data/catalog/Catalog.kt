package com.localai.app.data.catalog

import com.localai.app.runtime.device.DeviceProfile

/** Architecture numbers needed for the memory estimate (read from GGUF for imported files). */
data class ModelSpec(
    val id: String,
    val name: String,
    val family: String,
    val paramsB: Double,
    val quant: String,
    val sizeBytes: Long,
    val arch: String,
    val ctxTrain: Int,
    val layers: Int,
    val kvHeads: Int,
    val headDim: Int,
    val license: String,
    val description: String,
    val tags: List<String>,
    val url: String? = null,
    val sha256: String? = null,
    val fileName: String,
    val purpose: String = "gen",          // gen | embed
    val queryPrefix: String = "",
    val passagePrefix: String = "",
    // Vision models: image encoder (mmproj) downloaded next to the model
    val projectorUrl: String? = null,
    val projectorSha256: String? = null,
    val projectorFile: String? = null,
    val projectorSize: Long = 0,
) {
    val vision get() = projectorFile != null
    val totalBytes get() = sizeBytes + projectorSize
}

/**
 * Curated catalog. URLs, sizes and SHA-256 hashes were checked against Hugging Face on 2026-11-17.
 * Downloads are verified against these hashes before a model can be used.
 */
object Catalog {
    private fun hf(repo: String, file: String) = "https://huggingface.co/$repo/resolve/main/$file"

    val models: List<ModelSpec> = listOf(
        ModelSpec("qwen3-0.6b", "Qwen3 0.6B", "Qwen3", 0.6, "Q8_0", 639_446_688, "qwen3", 40960, 28, 8, 128, "Apache-2.0",
            "Smallest model in the catalog. Fast on any phone; best for short questions and quick drafts.",
            listOf("Lite", "Multilingual"), hf("Qwen/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q8_0.gguf"),
            "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", "Qwen3-0.6B-Q8_0.gguf"),
        ModelSpec("llama-3.2-1b", "Llama 3.2 1B Instruct", "Llama", 1.24, "Q4_K_M", 807_694_464, "llama", 131072, 16, 8, 64, "Llama 3.2 Community License",
            "Recommended for 4 GB phones. Good everyday chat quality for its size.",
            listOf("Lite", "General"), hf("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        ModelSpec("gemma-3-1b", "Gemma 3 1B IT", "Gemma", 1.0, "Q4_K_M", 806_058_240, "gemma3", 32768, 26, 1, 256, "Gemma Terms of Use",
            "Compact Gemma model with a very small context cache. Good on 4 GB phones.",
            listOf("Lite", "Multilingual"), hf("ggml-org/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf"),
            "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135", "gemma-3-1b-it-Q4_K_M.gguf"),
        ModelSpec("qwen3-1.7b", "Qwen3 1.7B", "Qwen3", 1.7, "Q4_K_M", 1_107_409_472, "qwen3", 40960, 28, 8, 128, "Apache-2.0",
            "Recommended for 6 GB phones. Noticeably smarter than 1B models while staying light.",
            listOf("Lite", "Multilingual"), hf("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
            "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897", "Qwen3-1.7B-Q4_K_M.gguf"),
        ModelSpec("smollm2-1.7b", "SmolLM2 1.7B Instruct", "SmolLM", 1.7, "Q4_K_M", 1_055_609_824, "llama", 8192, 24, 32, 64, "Apache-2.0",
            "Fully open small model. English-focused.",
            listOf("Lite", "English"), hf("bartowski/SmolLM2-1.7B-Instruct-GGUF", "SmolLM2-1.7B-Instruct-Q4_K_M.gguf"),
            "77665ea4815999596525c636fbeb56ba8b080b46ae85efef4f0d986a139834d7", "SmolLM2-1.7B-Instruct-Q4_K_M.gguf"),
        ModelSpec("llama-3.2-3b", "Llama 3.2 3B Instruct", "Llama", 3.21, "Q4_K_M", 2_019_377_696, "llama", 131072, 28, 8, 128, "Llama 3.2 Community License",
            "Recommended for 8 GB phones. Solid general assistant.",
            listOf("General"), hf("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
        ModelSpec("qwen3-4b", "Qwen3 4B", "Qwen3", 4.0, "Q4_K_M", 2_497_280_256, "qwen3", 40960, 36, 8, 128, "Apache-2.0",
            "Strong all-rounder with reliable structured output. Best on 12 GB+ phones.",
            listOf("General", "Multilingual"), hf("Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf"),
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", "Qwen3-4B-Q4_K_M.gguf"),
        ModelSpec("phi-4-mini", "Phi-4-mini Instruct", "Phi", 3.84, "Q4_K_M", 2_491_874_688, "phi3", 131072, 32, 8, 128, "MIT",
            "Reasoning- and math-focused small model.",
            listOf("Reasoning"), hf("bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf"),
            "01999f17c39cc3074afae5e9c539bc82d45f2dd7faa3917c66cbef76fce8c0c2", "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf"),
        ModelSpec("gemma-3-4b", "Gemma 3 4B IT", "Gemma", 3.88, "Q4_K_M", 2_489_757_856, "gemma3", 131072, 34, 4, 256, "Gemma Terms of Use",
            "Multilingual Gemma model that can also look at photos (image encoder included in the download).",
            listOf("General", "Multilingual", "Vision"), hf("ggml-org/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf"),
            "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863", "gemma-3-4b-it-Q4_K_M.gguf",
            projectorUrl = hf("ggml-org/gemma-3-4b-it-GGUF", "mmproj-model-f16.gguf"),
            projectorSha256 = "8c0fb064b019a6972856aaae2c7e4792858af3ca4561be2dbf649123ba6c40cb", projectorFile = "gemma-3-4b-mmproj-f16.gguf", projectorSize = 851_251_104),
        ModelSpec("qwen2.5-coder-7b", "Qwen2.5 Coder 7B Instruct", "Qwen2.5", 7.6, "Q4_K_M", 4_683_074_336, "qwen2", 32768, 28, 4, 128, "Apache-2.0",
            "Code-specialised. Needs a 12 GB phone.",
            listOf("Code"), hf("bartowski/Qwen2.5-Coder-7B-Instruct-GGUF", "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf"),
            "1664fccab734674a50763490a8c6931b70e3f2f8ec10031b54806d30e5f956b6", "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf"),
        ModelSpec("llama-3.1-8b", "Llama 3.1 8B Instruct", "Llama", 8.03, "Q4_K_M", 4_920_739_232, "llama", 131072, 32, 8, 128, "Llama 3.1 Community License",
            "Capable 8B generalist. Best on 16 GB phones.",
            listOf("General", "Large"), hf("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            "7b064f5842bf9532c91456deda288a1b672397a54fa729aa665952863033557c", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
        // Vision models: understand photos (camera or gallery). Text model + image encoder, both checksum-verified.
        ModelSpec("smolvlm-500m", "SmolVLM 500M (vision)", "SmolVLM", 0.5, "Q8_0", 436_806_912, "llama", 8192, 32, 5, 64, "Apache-2.0",
            "Small vision model: describes photos, reads short text and labels. Fits 4–6 GB phones. Answers are short and basic.",
            listOf("Vision", "Lite"), hf("ggml-org/SmolVLM-500M-Instruct-GGUF", "SmolVLM-500M-Instruct-Q8_0.gguf"),
            "9d4612de6a42214499e301494a3ecc2be0abdd9de44e663bda63f1152fad1bf4", "SmolVLM-500M-Instruct-Q8_0.gguf",
            projectorUrl = hf("ggml-org/SmolVLM-500M-Instruct-GGUF", "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf"),
            projectorSha256 = "d1eb8b6b23979205fdf63703ed10f788131a3f812c7b1f72e0119d5d81295150", projectorFile = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf", projectorSize = 108_783_360),
        ModelSpec("smolvlm2-2.2b", "SmolVLM2 2.2B (vision)", "SmolVLM", 2.2, "Q4_K_M", 1_112_602_656, "llama", 8192, 24, 32, 64, "Apache-2.0",
            "Better image understanding and text reading. Best on 8 GB phones.",
            listOf("Vision"), hf("ggml-org/SmolVLM2-2.2B-Instruct-GGUF", "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf"),
            "0cf76814555b8665149075b74ab6b5c1d428ea1d3d01c1918c12012e8d7c9f58", "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf",
            projectorUrl = hf("ggml-org/SmolVLM2-2.2B-Instruct-GGUF", "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf"),
            projectorSha256 = "ae07ea1facd07dd3230c4483b63e8cda96c6944ad2481f33d531f79e892dd024", projectorFile = "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf", projectorSize = 592_523_200),
        ModelSpec("qwen2.5-vl-3b", "Qwen2.5-VL 3B (vision)", "Qwen2.5", 3.75, "Q4_K_M", 1_929_901_056, "qwen2vl", 32768, 36, 2, 128, "Qwen Research License",
            "Strong at reading documents, receipts and screenshots in photos. Needs a 12 GB phone.",
            listOf("Vision", "Multilingual"), hf("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"),
            "d02fe9b69ad8cadbbd228e387667af66612c44bed29ffc8eb1e7caf9ac486c12", "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf",
            projectorUrl = hf("ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf"),
            projectorSha256 = "980c9b2f78c04e6cff93d277ada09e768394f112d75db3b4e9dea8a69f9fb904", projectorFile = "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf", projectorSize = 844_757_728),
        // Embedding models for knowledge bases and agent memory. Each conversion was tested with the
        // native test harness (local-ai/android/native-test): some public conversions crash or rank badly.
        ModelSpec("e5-small", "multilingual-e5-small", "E5", 0.12, "Q8_0", 132_439_008, "bert", 512, 12, 12, 32, "MIT",
            "Default embedding model: about 100 languages, 384 dimensions. Needed for meaning-based search in knowledge bases and memory.",
            listOf("Embedding", "Multilingual"), hf("TwinSunsLLC/multilingual-e5-small-gguf", "multilingual-e5-small-q8_0.gguf"),
            "e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93", "multilingual-e5-small-q8_0.gguf",
            purpose = "embed", queryPrefix = "query: ", passagePrefix = "passage: "),
        ModelSpec("bge-small-en", "bge-small-en-v1.5", "BGE", 0.03, "Q8_0", 36_685_152, "bert", 512, 12, 12, 32, "MIT",
            "Small English-only embedding model. Fastest indexing; best for 4 GB phones with English documents.",
            listOf("Embedding", "English", "Lite"), hf("ggml-org/bge-small-en-v1.5-Q8_0-GGUF", "bge-small-en-v1.5-q8_0.gguf"),
            "f046db1dc724cf4f6f0a0c5917e922823b73eb1d27b8f9a9c2797f7866974804", "bge-small-en-v1.5-q8_0.gguf",
            purpose = "embed", queryPrefix = "Represent this sentence for searching relevant passages: "),
    )

    fun byId(id: String) = models.firstOrNull { it.id == id }
    fun isEmbedding(id: String?) = id != null && byId(id)?.purpose == "embed"
}

private const val GIB = 1024.0 * 1024.0 * 1024.0

data class MemoryEstimate(val weightsGiB: Double, val kvGiB: Double, val computeGiB: Double, val overheadGiB: Double) {
    val totalGiB get() = weightsGiB + kvGiB + computeGiB + overheadGiB
}

enum class Verdict(val label: String) { RUNS_WELL("Runs well"), TIGHT_FIT("Tight fit"), NOT_RECOMMENDED("Not recommended") }

/** Mirrors docs/MODEL_RUNTIME_DESIGN.md §5–6 and prototype/js/ui.js. */
object MemoryModel {
    fun estimate(spec: ModelSpec, ctx: Int, kvQ8: Boolean, batch: Int = 512): MemoryEstimate {
        if (spec.purpose == "embed") return MemoryEstimate(spec.sizeBytes / GIB, 0.0, 0.03, 0.02)
        val bytesPerElem = if (kvQ8) 1.0625 else 2.0
        val kv = 2.0 * spec.layers * ctx * spec.kvHeads * spec.headDim * bytesPerElem / GIB
        val compute = (0.08 + spec.paramsB * 0.025) * (0.5 + 0.5 * batch / 512.0)
        return MemoryEstimate(spec.totalBytes / GIB, kv, compute + if (spec.vision) 0.15 else 0.0, 0.12)
    }

    fun verdict(spec: ModelSpec, ctx: Int, kvQ8: Boolean, device: DeviceProfile, batch: Int = 512): Verdict {
        val total = estimate(spec, ctx, kvQ8, batch).totalGiB
        val budget = device.modelBudgetGiB
        return when {
            total <= budget * 0.85 -> Verdict.RUNS_WELL
            total <= budget -> Verdict.TIGHT_FIT
            else -> Verdict.NOT_RECOMMENDED
        }
    }

    fun gib(bytes: Long) = bytes / GIB
}
