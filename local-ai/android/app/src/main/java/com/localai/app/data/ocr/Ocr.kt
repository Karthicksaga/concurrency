package com.localai.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.localai.app.data.media.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A Tesseract language pack (tessdata_fast, pinned commit, checksum-verified). */
data class OcrPack(val code: String, val name: String, val sizeBytes: Long, val sha256: String, val bundled: Boolean = false) {
    val url get() = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$TESSDATA_COMMIT/$code.traineddata"
}

const val TESSDATA_COMMIT = "87416418657359cb625c412a48b6e1d6d41c29bd"

object OcrPacks {
    val all = listOf(
        OcrPack("eng", "English", 4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2", bundled = true),
        OcrPack("hin", "हिन्दी Hindi", 1_122_751, "4c73ffc59d497c186b19d1e90f5d721d678ea6b2e277b719bee4e2af12271825"),
        OcrPack("tam", "தமிழ் Tamil", 3_237_963, "d02fbec24be4b07e32e80d0ccfc3b6b67a3c5d61c9d0a7c8532677990912c6ec"),
        OcrPack("tel", "తెలుగు Telugu", 2_769_654, "d10691fddd5b67802e1c12800ebb321d3b8bcd8d24a2ac3ff206f93188c04ab5"),
        OcrPack("kan", "ಕನ್ನಡ Kannada", 3_608_331, "bd31e6b6ae93271e3bcf5383d306d8eefbb91542937cd6d735a5930c970e61d8"),
        OcrPack("mal", "മലയാളം Malayalam", 5_275_996, "bd05cbf1b197e7810d2903419aedb06f9ef77bfedf50b358673c1d18d707cdb4"),
        OcrPack("ben", "বাংলা Bengali", 855_841, "31163084c279aaebd376216f0c3d5c17ad4b5fee8db49dae79c20000b5de5964"),
        OcrPack("mar", "मराठी Marathi", 2_118_233, "0ba3f2d116972e72fe9e176bc84c38e81dfb6670f4ed1f7f6c8e16a27da7cb61"),
    )
    fun byCode(c: String) = all.firstOrNull { it.code == c }

    /** The OCR language that matches a voice/BCP-47 language tag, if we have one. */
    fun forLanguageTag(tag: String): String? = when (tag.substringBefore('-').lowercase()) {
        "hi" -> "hin"; "ta" -> "tam"; "te" -> "tel"; "kn" -> "kan"; "ml" -> "mal"; "bn" -> "ben"; "mr" -> "mar"; "en" -> "eng"; else -> null
    }

    /** Tesseract language string: installed packs only, English always included. */
    fun spec(selected: Collection<String>, installed: (String) -> Boolean): String =
        (listOf("eng") + selected.filter { it != "eng" && byCode(it) != null && installed(it) }).distinct().joinToString("+")
}

data class OcrResult(val text: String, val confidence: Int) {
    /** Enough real text to be worth using (not noise from a photo of a plant). */
    val useful get() = confidence >= 45 && text.count { it.isLetterOrDigit() } >= 12
}

/**
 * Offline text recognition with Tesseract (Apache-2.0) — no Google Play services, nothing leaves the phone.
 * English ships in the APK; other languages are downloaded once (1–5 MB each) and checksum-verified.
 * One engine at a time on its own thread; the instance is reused while the language set stays the same.
 */
object Ocr {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "ocr") }.asCoroutineDispatcher()
    private var api: TessBaseAPI? = null
    private var apiLangs: String? = null

    fun dataDir(context: Context) = File(context.filesDir, "ocr").apply { File(this, "tessdata").mkdirs() }
    private fun packFile(context: Context, code: String) = File(dataDir(context), "tessdata/$code.traineddata")

    fun isInstalled(context: Context, code: String): Boolean {
        val p = OcrPacks.byCode(code) ?: return false
        return p.bundled || packFile(context, code).let { it.exists() && it.length() == p.sizeBytes }
    }

    private fun ensureBundled(context: Context) {
        OcrPacks.all.filter { it.bundled }.forEach { p ->
            val f = packFile(context, p.code)
            if (!f.exists() || f.length() != p.sizeBytes) {
                val tmp = File(f.parentFile, f.name + ".tmp")
                context.assets.open("tessdata/${p.code}.traineddata").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(f)
            }
        }
    }

    /** Downloads a language pack (HTTPS, pinned commit, SHA-256 checked). */
    suspend fun download(context: Context, code: String, onProgress: (Float) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val p = OcrPacks.byCode(code) ?: error("Unknown language")
            if (isInstalled(context, code)) return@runCatching
            val dest = packFile(context, code); val tmp = File(dest.parentFile, dest.name + ".part")
            val conn = (URL(p.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; setRequestProperty("User-Agent", "LocalAI-Android/0.1")
            }
            if (conn.responseCode != 200) error("Download failed (HTTP ${conn.responseCode}).")
            val md = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input -> tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n); done += n; onProgress((done.toFloat() / p.sizeBytes).coerceAtMost(1f)) }
            } }
            conn.disconnect()
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (tmp.length() != p.sizeBytes || !sha.equals(p.sha256, true)) { tmp.delete(); error("Checksum mismatch. Try again on a stable connection.") }
            if (!tmp.renameTo(dest)) error("Could not save the language pack.")
        }
    }

    fun delete(context: Context, code: String) { if (OcrPacks.byCode(code)?.bundled == false) packFile(context, code).delete() }

    /** Recognises text in a bitmap. [langs] like "eng+tam". The bitmap is not recycled. */
    suspend fun recognize(context: Context, bitmap: Bitmap, langs: String): OcrResult = withContext(thread) {
        ensureBundled(context)
        val tess = api?.takeIf { apiLangs == langs } ?: run {
            api?.recycle(); api = null
            val t = TessBaseAPI()
            if (!t.init(dataDir(context).absolutePath, langs, 1 /* OcrEngineMode.OEM_LSTM_ONLY (constants of Java annotation types are not visible to Kotlin) */)) { t.recycle(); error("Text recognition could not start for $langs.") }
            t.setPageSegMode(3)   // PageSegMode.PSM_AUTO: full automatic page segmentation
            t.setVariable("user_defined_dpi", "300")
            api = t; apiLangs = langs; t
        }
        tess.setImage(bitmap)
        val text = tess.getUTF8Text().orEmpty()
        val conf = tess.meanConfidence()
        tess.clear()
        OcrResult(cleanup(text), conf)
    }

    /** Reads a stored photo at OCR resolution (longest side ≤ 2400 px). */
    suspend fun recognizeFile(context: Context, file: File, langs: String): OcrResult? {
        val bmp = withContext(Dispatchers.IO) {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, b)
            if (b.outWidth <= 0) return@withContext null
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = Images.sampleSize(b.outWidth, b.outHeight, 2400) })
        } ?: return null
        return try { recognize(context, bmp, langs) } finally { bmp.recycle() }
    }

    /** OCR for PDFs without a text layer: renders each page at ~200 dpi. Returns text per page (1-based). */
    suspend fun recognizePdf(context: Context, file: File, langs: String, maxPages: Int, onPage: (Int, Int) -> Unit = { _, _ -> }): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        withContext(Dispatchers.IO) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.use { fd ->
            PdfRenderer(fd).use { r ->
                val n = minOf(r.pageCount, maxPages)
                for (i in 0 until n) {
                    onPage(i + 1, n)
                    val bmp = withContext(Dispatchers.IO) {
                        r.openPage(i).use { page ->
                            val scale = 200f / 72f
                            val w = (page.width * scale).toInt().coerceAtMost(2400); val h = (page.height * w.toFloat() / page.width).toInt()
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                                Canvas(b).drawColor(Color.WHITE)
                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            }
                        }
                    }
                    try { recognize(context, bmp, langs).text.takeIf { it.isNotBlank() }?.let { out += (i + 1) to it } } finally { bmp.recycle() }
                }
            }
        }
        return out
    }

    /** Joins hyphenated line breaks (keeping hyphens inside links) and collapses runs of blank lines. */
    fun cleanup(s: String): String = s.replace("\r", "")
        .replace(Regex("[\\u200C\\u200D]+(?=\\s|$)"), "")                 // stray zero-width joiners at word ends (Tamil output)
        .replace(Regex("(\\S*[./]\\S*-)\\n(\\S)"), "$1$2")                  // a link broken after a hyphen: rejoin, keep the hyphen
        .replace(Regex("(\\p{L})-\\n(\\p{Ll})"), "$1$2")
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    fun release() { kotlinx.coroutines.runBlocking(thread) { api?.recycle(); api = null; apiLangs = null } }
}
