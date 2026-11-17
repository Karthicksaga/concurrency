package com.localai.app.data.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One file of a voice, pinned to a Hugging Face commit and checked with SHA-256. */
data class VoiceFile(val name: String, val size: Long, val sha256: String)

/**
 * A natural-sounding neural voice that runs on the phone (sherpa-onnx). [speed] is the measured real-time factor on a
 * 2-thread desktop CPU (lower is faster; a phone is roughly 2–4× slower), used to warn about slow voices.
 */
data class NaturalVoice(
    val id: String, val name: String, val language: String, val description: String, val engine: String,   // engine: vits | kitten
    val repo: String, val revision: String, val files: List<VoiceFile>, val speakers: Int = 1, val rtfDesktop: Float,
) {
    val sizeBytes get() = files.sumOf { it.size }
    val modelFile get() = files.first { it.name.endsWith(".onnx") }.name
    val fast get() = rtfDesktop <= 0.1f
    fun url(f: VoiceFile) = "https://huggingface.co/csukuangfj/$repo/resolve/$revision/${f.name}"
}

object NaturalVoices {
    private const val TOK_EN = "87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9"
    val all = listOf(
        NaturalVoice("amy", "Amy", "en", "American English, warm female voice. Fast enough for live conversation on any phone.", "vits",
            "vits-piper-en_US-amy-medium", "79936839cc9409b1a752a45c629fe21639e1c1b2",
            listOf(VoiceFile("en_US-amy-medium.onnx", 63_201_425, "fbaa8e36d8f26fe6f3ebb65cab461e629d8b37a5b7c5fb78fb64317db73e1c25"), VoiceFile("tokens.txt", 921, TOK_EN)), rtfDesktop = 0.06f),
        NaturalVoice("jenny", "Jenny", "en", "British English, clear female voice. Fast.", "vits",
            "vits-piper-en_GB-jenny_dioco-medium", "00bac9d2e32e93b1e60237d26b85971bba95092d",
            listOf(VoiceFile("en_GB-jenny_dioco-medium.onnx", 63_201_430, "bd5207a2752d8766a8b771437776fd7575031a9f9876eaf13b8db778b150993a"), VoiceFile("tokens.txt", 921, TOK_EN)), rtfDesktop = 0.06f),
        NaturalVoice("priyamvada", "Priyamvada", "hi", "हिन्दी · Hindi female voice. Fast.", "vits",
            "vits-piper-hi_IN-priyamvada-medium", "ec05e05b3eae80817ea62258d13f4d42e46cd1c2",
            listOf(VoiceFile("hi_IN-priyamvada-medium.onnx", 63_145_178, "8871f3e07adb6ca490f8dbcd3956a8647c53c35b5d0a1c2a8d097b3bf721a31b"), VoiceFile("tokens.txt", 940, "ef3a7e4a8d1af0c9d4dc45aaae1a6242ebe24a7ed6f3d025a49eb29682784c6d")), rtfDesktop = 0.05f),
        NaturalVoice("rohan", "Rohan", "hi", "हिन्दी · Hindi male voice. Fast.", "vits",
            "vits-piper-hi_IN-rohan-medium", "fe149a55f4949f711d42bfca3e214d5c6c8d797b",
            listOf(VoiceFile("hi_IN-rohan-medium.onnx", 62_946_428, "353d2a20bdb6dd03b74e1aff82b7a10a1646f677f8474c6c81fd606705536224"), VoiceFile("tokens.txt", 968, "e8a50ae0c75612d18cfcf3f90800dca79f961e3ed97f9574f3535194469187fd")), rtfDesktop = 0.05f),
        NaturalVoice("lessac", "Lessac HD", "en", "American English, higher quality. Slower: best on 8 GB+ phones.", "vits",
            "vits-piper-en_US-lessac-high", "a3cffbe82b4df3b9edefa73189f4443ed638d9a1",
            listOf(VoiceFile("en_US-lessac-high.onnx", 113_895_332, "1315b0b3746e9ff52f8ccc88d3d9eaa6c289001fc58951ed88c0fb3a0befe635"), VoiceFile("tokens.txt", 921, TOK_EN)), rtfDesktop = 0.34f),
        NaturalVoice("kitten", "Kitten Expressive", "en", "English, 8 expressive voices (pick one by ear). Small download, slower to speak.", "kitten",
            "kitten-nano-en-v0_2-fp16", "7d14a97d38072576ddca7a673ab5bb49c43bb169",
            listOf(VoiceFile("model.fp16.onnx", 23_804_897, "f24264d818087e643828da7a992e892e703c412bf147e152b825da567031d5ce"),
                VoiceFile("voices.bin", 8_192, "42a40a24a352a38657d6cb86ceee51bbc2b7780b29e04fb60bcdf959adccea01"),
                VoiceFile("tokens.txt", 1_064, "934a4188addc7665dd3410256bb622169242357fbb99d840d9351209b486dabb")), speakers = 8, rtfDesktop = 0.28f),
    )
    fun byId(id: String?) = all.firstOrNull { it.id == id }

    fun dir(context: Context, v: NaturalVoice) = File(context.filesDir, "voices/${v.id}")
    fun isInstalled(context: Context, v: NaturalVoice) = v.files.all { f -> File(dir(context, v), f.name).let { it.exists() && it.length() == f.size } }

    /** Downloads every file of a voice (HTTPS, pinned commit, SHA-256 verified); resumes nothing, ~25–115 MB. */
    suspend fun download(context: Context, v: NaturalVoice, onProgress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val d = dir(context, v).apply { mkdirs() }
            var done = 0L; val total = v.sizeBytes.toFloat()
            for (f in v.files) {
                val dest = File(d, f.name)
                if (dest.exists() && dest.length() == f.size) { done += f.size; continue }
                val tmp = File(d, f.name + ".part")
                val conn = (URL(v.url(f)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "LocalAI-Android/0.1")
                }
                if (conn.responseCode != 200) error("Download failed (HTTP ${conn.responseCode}).")
                val md = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n); done += n; onProgress((done / total).coerceAtMost(1f)) }
                } }
                conn.disconnect()
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (tmp.length() != f.size || sha != f.sha256) { tmp.delete(); error("Checksum mismatch for ${f.name}. Try again on a stable connection.") }
                if (!tmp.renameTo(dest)) error("Could not save the voice.")
            }
        }
    }

    fun delete(context: Context, v: NaturalVoice) { dir(context, v).deleteRecursively() }

    /** Which voice language a piece of text is in (by script); null when it has no letters. */
    fun scriptLanguage(text: String): String? {
        var latin = 0; var deva = 0; var other = 0
        text.forEach { c ->
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
                c.code in 0x0900..0x097F -> deva++
                c.isLetter() -> other++
            }
        }
        val max = maxOf(latin, deva, other)
        return when { max == 0 -> null; max == deva -> "hi"; max == latin -> "en"; else -> "other" }
    }

    /** Whether [v] should speak [text]; otherwise the phone's own voice reads it (e.g. Tamil text, or English with a Hindi voice). */
    fun canSpeak(v: NaturalVoice, text: String): Boolean = scriptLanguage(text).let { it == null || it == v.language }
}

/** Generated speech: mono float samples in [-1, 1]. */
class Speech(val samples: FloatArray, val sampleRate: Int)

/**
 * Loads one neural voice at a time (≈ 100–200 MB while loaded) and synthesizes sentences. Not thread-safe: callers
 * use it from a single synthesis thread.
 */
class NaturalTtsEngine(private val context: Context) {
    private var tts: OfflineTts? = null
    private var loadedId: String? = null

    private fun espeakDir(): File {
        val dest = File(context.filesDir, "espeak-ng-data")
        val marker = File(dest, ".complete")
        if (!marker.exists()) {
            dest.deleteRecursively()
            fun copy(asset: String, to: File) {
                val children = context.assets.list(asset).orEmpty()
                if (children.isEmpty()) { to.parentFile?.mkdirs(); context.assets.open(asset).use { i -> to.outputStream().use { i.copyTo(it) } } }
                else { to.mkdirs(); children.forEach { copy("$asset/$it", File(to, it)) } }
            }
            copy("espeak-ng-data", dest)
            marker.writeText("ok")
        }
        return dest
    }

    fun load(v: NaturalVoice, threads: Int): Boolean {
        if (loadedId == v.id && tts != null) return true
        release()
        val d = NaturalVoices.dir(context, v)
        val data = espeakDir().absolutePath
        val model = when (v.engine) {
            "kitten" -> OfflineTtsModelConfig(kitten = OfflineTtsKittenModelConfig(model = File(d, v.modelFile).path, voices = File(d, "voices.bin").path,
                tokens = File(d, "tokens.txt").path, dataDir = data), numThreads = threads, debug = false, provider = "cpu")
            else -> OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = File(d, v.modelFile).path, tokens = File(d, "tokens.txt").path, dataDir = data),
                numThreads = threads, debug = false, provider = "cpu")
        }
        tts = runCatching { OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1)) }.getOrNull()
        loadedId = if (tts != null) v.id else null
        return tts != null
    }

    fun generate(text: String, speaker: Int, speed: Float): Speech? {
        val t = tts ?: return null
        val a = runCatching { t.generate(text, speaker, speed) }.getOrNull() ?: return null
        return if (a.samples.isEmpty()) null else Speech(a.samples, a.sampleRate)
    }

    fun release() { runCatching { tts?.release() }; tts = null; loadedId = null }
}
