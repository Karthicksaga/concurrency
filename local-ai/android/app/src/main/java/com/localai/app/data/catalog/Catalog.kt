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
)

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
            "Multilingual Gemma model (text only in this app).",
            listOf("General", "Multilingual"), hf("ggml-org/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf"),
            "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863", "gemma-3-4b-it-Q4_K_M.gguf"),
        ModelSpec("qwen2.5-coder-7b", "Qwen2.5 Coder 7B Instruct", "Qwen2.5", 7.6, "Q4_K_M", 4_683_074_336, "qwen2", 32768, 28, 4, 128, "Apache-2.0",
            "Code-specialised. Needs a 12 GB phone.",
            listOf("Code"), hf("bartowski/Qwen2.5-Coder-7B-Instruct-GGUF", "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf"),
            "1664fccab734674a50763490a8c6931b70e3f2f8ec10031b54806d30e5f956b6", "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf"),
        ModelSpec("llama-3.1-8b", "Llama 3.1 8B Instruct", "Llama", 8.03, "Q4_K_M", 4_920_739_232, "llama", 131072, 32, 8, 128, "Llama 3.1 Community License",
            "Capable 8B generalist. Best on 16 GB phones.",
            listOf("General", "Large"), hf("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            "7b064f5842bf9532c91456deda288a1b672397a54fa729aa665952863033557c", "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
    )

    fun byId(id: String) = models.firstOrNull { it.id == id }
}

private const val GIB = 1024.0 * 1024.0 * 1024.0

data class MemoryEstimate(val weightsGiB: Double, val kvGiB: Double, val computeGiB: Double, val overheadGiB: Double) {
    val totalGiB get() = weightsGiB + kvGiB + computeGiB + overheadGiB
}

enum class Verdict(val label: String) { RUNS_WELL("Runs well"), TIGHT_FIT("Tight fit"), NOT_RECOMMENDED("Not recommended") }

/** Mirrors docs/MODEL_RUNTIME_DESIGN.md §5–6 and prototype/js/ui.js. */
object MemoryModel {
    fun estimate(spec: ModelSpec, ctx: Int, kvQ8: Boolean, batch: Int = 512): MemoryEstimate {
        val bytesPerElem = if (kvQ8) 1.0625 else 2.0
        val kv = 2.0 * spec.layers * ctx * spec.kvHeads * spec.headDim * bytesPerElem / GIB
        val compute = (0.08 + spec.paramsB * 0.025) * (0.5 + 0.5 * batch / 512.0)
        return MemoryEstimate(spec.sizeBytes / GIB, kv, compute, 0.12)
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
