package com.localai.app.data.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.localai.app.data.knowledge.DocKind
import com.localai.app.data.knowledge.kindFor
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.ChatTurn
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.GenEvent
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.runtime.inference.SamplingParams
import com.localai.app.runtime.inference.StopReason
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class DocFormat(val ext: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    MD("md", "text/markdown", "Markdown");
    companion object { fun forFile(f: File) = entries.firstOrNull { it.ext == f.extension.lowercase() } }
}

enum class AiAction(val label: String) {
    NONE("Convert only"), TIDY("Fix grammar & tidy"), SUMMARIZE("Summarize"), TRANSLATE("Translate"), FORMAL("Make it formal"), SIMPLIFY("Simplify")
}

enum class DocKindChoice(val label: String, val hint: String) {
    REPORT("Report", "a structured report with an executive summary, sections and a conclusion"),
    LETTER("Letter", "a formal letter with date, recipient, subject line, body and sign-off placeholders"),
    EMAIL("Email", "a clear email with a subject line"),
    MINUTES("Meeting notes", "meeting minutes with attendees, agenda, decisions and action items in a table"),
    RESUME("Resume", "a one-page resume with summary, experience, education and skills"),
    ESSAY("Essay", "an essay with an introduction, body paragraphs and a conclusion"),
    PROPOSAL("Proposal", "a proposal with background, objectives, plan, timeline table and budget"),
    CUSTOM("Anything", "the document the user describes"),
}

enum class DocLength(val label: String, val tokens: Int) { SHORT("Short", 450), MEDIUM("Medium", 900), LONG("Long", 1800) }

class LoadedDoc(val name: String, val blocks: List<Block>) {
    val title get() = blocks.firstNotNullOfOrNull { (it as? Block.Heading)?.spans?.plain() } ?: name.substringBeforeLast('.')
    val chars get() = Markdown.render(blocks).length
}

/**
 * Document Studio: write documents with the on-device model, convert Word ⇄ PDF (optionally rewriting, summarizing
 * or translating on the way) and keep exports in app storage until the user saves or shares them.
 */
@Singleton
class DocumentStudio @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val device: DeviceCapabilityService,
) {
    val exportsDir: File get() = File(context.filesDir, "exports").apply { mkdirs() }
    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    fun refresh() { _files.value = exportsDir.listFiles()?.filter { it.isFile && DocFormat.forFile(it) != null }?.sortedByDescending { it.lastModified() } ?: emptyList() }

    // ---------- export ----------

    suspend fun export(blocks: List<Block>, title: String, format: DocFormat): File = withContext(Dispatchers.IO) {
        val f = uniqueFile(title, format.ext)
        val tmp = File(f.parentFile, f.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                when (format) {
                    DocFormat.PDF -> PdfWriter.write(blocks, title, out)
                    DocFormat.DOCX -> DocxWriter.write(blocks, title, out)
                    DocFormat.MD -> out.write(Markdown.render(blocks).toByteArray())
                }
            }
            if (!tmp.renameTo(f)) error("Could not save the file.")
        } finally { tmp.delete() }
        refresh(); f
    }

    suspend fun exportMarkdown(md: String, format: DocFormat, fallbackTitle: String = "Document"): File {
        val blocks = Markdown.parse(md)
        return export(blocks, titleOf(blocks, fallbackTitle), format)
    }

    fun titleOf(blocks: List<Block>, fallback: String) =
        blocks.firstNotNullOfOrNull { (it as? Block.Heading)?.spans?.plain()?.takeIf { t -> t.isNotBlank() } } ?: fallback

    private fun uniqueFile(title: String, ext: String): File {
        val base = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Document" }
        var f = File(exportsDir, "$base.$ext"); var n = 2
        while (f.exists()) { f = File(exportsDir, "$base ($n).$ext"); n++ }
        return f
    }

    fun delete(f: File) { if (f.parentFile == exportsDir) f.delete(); refresh() }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)

    fun openIntent(f: File): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), DocFormat.forFile(f)?.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(f: File): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType(DocFormat.forFile(f)?.mime ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, uriFor(f)).putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the file in a viewer app; returns false when no app can open this type. */
    fun open(f: File): Boolean = try { context.startActivity(openIntent(f)); true } catch (e: ActivityNotFoundException) { false }

    suspend fun copyTo(f: File, dest: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(dest)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("Cannot write there.")
    }

    // ---------- import ----------

    suspend fun load(uri: Uri): LoadedDoc = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "document"; var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        if (size > 40L * 1024 * 1024) error("This file is larger than 40 MB.")
        val kind = kindFor(name, cr.getType(uri)) ?: error("Choose a PDF, Word (.docx), photo, Markdown or text file.")
        val st = settings.current()
        val ocrLangs = com.localai.app.data.ocr.OcrPacks.spec(st.ocrLanguages.split(','), { com.localai.app.data.ocr.Ocr.isInstalled(context, it) })
        val tmp = File(context.cacheDir, "studio_input").apply { delete() }
        cr.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: error("Cannot open the file.")
        try {
            val blocks = when (kind) {
                DocKind.DOCX -> tmp.inputStream().use { DocxReader.read(it) }
                DocKind.PDF -> pdfText(tmp)?.let { Markdown.fromPlainText(it) } ?: run {
                    // scanned PDF: read the pages with on-device text recognition
                    val pages = com.localai.app.data.ocr.Ocr.recognizePdf(context, tmp, ocrLangs, if (policyFor(st, device.profile).lite) 20 else 60)
                    if (pages.sumOf { it.second.length } < 20) error("No readable text found, even with text recognition.")
                    Markdown.fromPlainText(pages.joinToString("\n\n") { it.second })
                }
                DocKind.IMAGE -> {
                    // "Scan to Word/PDF": photo of a page → editable document
                    val r = com.localai.app.data.ocr.Ocr.recognizeFile(context, tmp, ocrLangs)
                    if (r == null || r.text.count { it.isLetterOrDigit() } < 12) error("No readable text found in this photo. Try a sharper, well-lit photo taken straight on.")
                    listOf(Block.Heading(1, listOf(Span(name.substringBeforeLast('.'))))) + Markdown.fromPlainText(r.text)
                }
                DocKind.MARKDOWN -> Markdown.parse(tmp.readText())
                DocKind.CODE -> listOf(Block.Heading(1, listOf(Span(name))), Block.Code(tmp.readText().take(400_000)))
                DocKind.TEXT, DocKind.NOTE -> Markdown.fromPlainText(tmp.readText().take(2_000_000))
            }
            if (blocks.isEmpty()) error("No text found in this file.")
            LoadedDoc(name, blocks)
        } finally { tmp.delete() }
    }

    /** Text layer of a PDF, or null when it has none (scanned). */
    private fun pdfText(f: File): String? {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(f).use { doc ->
            if (doc.isEncrypted) error("This PDF is password-protected.")
            val s = PDFTextStripper().apply { lineSeparator = "\n"; paragraphEnd = "\n\n"; pageEnd = "\n\n"; endPage = minOf(doc.numberOfPages, 300) }
            val t = s.getText(doc)
            return t.takeIf { it.trim().length >= 20 }
        }
    }

    // ---------- AI ----------

    private suspend fun ready(): Int {
        models.ensureLoaded().getOrElse { throw IllegalStateException(it.message ?: "Choose a model first.") }
        return (engine.state.value as? EngineState.Ready)?.contextLength ?: 2048
    }

    private suspend fun run(system: String, user: String, maxTokens: Int, onText: (String) -> Unit): String {
        val sb = StringBuilder(); var failure: String? = null; var aborted = false
        engine.generate(listOf(ChatTurn("system", system), ChatTurn("user", user)),
            SamplingParams(temperature = 0.4f, topP = 0.9f, maxTokens = maxTokens, repeatPenalty = 1.08f)).collect { e ->
            when (e) {
                is GenEvent.Delta -> { sb.append(e.text); onText(cleanModelMarkdown(sb.toString())) }
                is GenEvent.Failed -> failure = e.error.message
                is GenEvent.Done -> aborted = e.reason == StopReason.ABORTED
            }
        }
        failure?.let { throw IllegalStateException(it) }
        if (aborted) throw kotlinx.coroutines.CancellationException("Stopped")
        return cleanModelMarkdown(sb.toString())
    }

    /** Writes a new document as Markdown, streaming the text into [onText]. */
    suspend fun write(kind: DocKindChoice, request: String, length: DocLength, language: String, onText: (String) -> Unit): String {
        val ctx = ready()
        val lite = policyFor(settings.current(), device.profile).lite
        val budget = (ctx - 120 - request.length / 3).coerceAtLeast(256)
        val maxTokens = minOf(length.tokens, budget, if (lite) 1100 else 4000)
        val system = "You are a professional writer. Write ${kind.hint}. Use Markdown: begin with a '# ' title, use '## ' section headings, " +
            "short paragraphs, bullet lists and tables where they help. Write complete text, not an outline; use [placeholders] for details you do not know. " +
            (if (language.isNotBlank()) "Write in $language. " else "") + "Output only the document."
        val lengthHint = when (length) { DocLength.SHORT -> "Keep it short (about 200 words)."; DocLength.MEDIUM -> "About 450 words."; DocLength.LONG -> "Detailed, about 900 words." }
        return run(system, "${request.trim()}\n\n$lengthHint", maxTokens, onText)
    }

    /**
     * Applies an AI action to a whole document, part by part so it fits small context windows (2K in lite mode).
     * [onProgress] gets (part, parts, text so far).
     */
    suspend fun transform(doc: List<Block>, action: AiAction, language: String, onProgress: (Int, Int, String) -> Unit): List<Block> {
        if (action == AiAction.NONE) return doc
        val ctx = ready()
        // input ≈ 30 % of the context, output ≤ the rest; ~3.5 characters per token
        val chunkChars = ((ctx * 0.3) * 3.5).toInt().coerceIn(800, 12_000)
        val parts = chunkDocument(doc, chunkChars)
        val instruction = when (action) {
            AiAction.TIDY -> "Correct spelling, grammar and punctuation and tidy the formatting. Keep the meaning, language, facts, numbers and structure."
            AiAction.FORMAL -> "Rewrite in a clear, professional, formal tone. Keep every fact, number and the structure."
            AiAction.SIMPLIFY -> "Rewrite in plain, simple language that is easy to read. Keep every fact and the structure."
            AiAction.TRANSLATE -> "Translate into ${language.ifBlank { "English" }}. Translate everything, including headings and table cells. Keep the Markdown structure."
            AiAction.SUMMARIZE -> "Summarize this part of a document as concise Markdown bullet points. Keep key facts, numbers, names and dates."
            AiAction.NONE -> ""
        }
        val system = "You edit documents. $instruction Reply with Markdown only, without comments about what you changed."
        val outputs = ArrayList<String>()
        val done = StringBuilder()
        parts.forEachIndexed { i, part ->
            val single = action == AiAction.SUMMARIZE && parts.size == 1
            val sys = if (single) "You summarize documents. Write a well-structured summary in Markdown: a '# ' title, a short overview paragraph, then '## ' sections with bullet points. Keep key facts, numbers, names and dates. Markdown only." else system
            val maxOut = (ctx - part.length / 3 - 150).coerceIn(200, 3000)
            val out = run(sys, part, maxOut) { t -> onProgress(i + 1, parts.size, done.toString() + t) }
            outputs += out; done.append(out).append("\n\n")
        }
        val md = if (action == AiAction.SUMMARIZE && parts.size > 1) {
            // reduce: combine partial notes (trimmed to fit)
            val notes = outputs.joinToString("\n\n").take(chunkChars * 2)
            run("You summarize documents. Combine these notes into one well-structured summary in Markdown: a '# ' title, a short overview paragraph, then '## ' sections with bullet points. Remove repetition. Markdown only.",
                notes, (ctx - notes.length / 3 - 150).coerceIn(200, 2000)) { t -> onProgress(parts.size, parts.size, t) }
        } else outputs.joinToString("\n\n")
        return Markdown.parse(md)
    }
}

/** Removes reasoning blocks and a code fence wrapped around the whole answer. */
fun cleanModelMarkdown(s: String): String {
    var t = s.replace(Regex("(?s)<think>.*?</think>"), "")
    if (t.contains("<think>")) t = t.substringBefore("<think>")
    t = t.trim()
    val fence = Regex("^```(?:markdown|md)?\\s*\\n(?s)(.*?)\\n?```$").find(t)
    if (fence != null) t = fence.groupValues[1]
    else if (t.startsWith("```markdown") || t.startsWith("```md")) t = t.substringAfter('\n')
    return t.trim()
}

/** Splits a document into Markdown chunks at block boundaries. */
fun chunkDocument(doc: List<Block>, maxChars: Int): List<String> {
    val out = ArrayList<String>(); var cur = ArrayList<Block>(); var len = 0
    doc.forEach { b ->
        val l = Markdown.render(listOf(b)).length
        if (len + l > maxChars && cur.isNotEmpty()) { out += Markdown.render(cur); cur = ArrayList(); len = 0 }
        if (l > maxChars && b is Block.Para) {
            // very long paragraph: split by sentences
            Markdown.spansToMd(b.spans).split(Regex("(?<=[.!?])\\s+")).fold(StringBuilder()) { acc, s ->
                if (acc.length + s.length > maxChars && acc.isNotEmpty()) { out += acc.toString(); acc.clear() }
                acc.append(s).append(' ')
            }.takeIf { it.isNotBlank() }?.let { out += it.toString() }
        } else { cur += b; len += l }
    }
    if (cur.isNotEmpty()) out += Markdown.render(cur)
    return out
}
