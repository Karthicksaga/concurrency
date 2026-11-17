package com.localai.app.data.knowledge

import android.content.Context
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A run of text with a citation location ("p. 4", "§ Heading", "lines 10–42"). */
data class Section(val text: String, val location: String?)

data class Extracted(val sections: List<Section>, val pages: Int?)

class ExtractionException(message: String) : Exception(message)

enum class DocKind { PDF, DOCX, MARKDOWN, CODE, TEXT, NOTE }

private val CODE_EXT = setOf("kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "cc", "swift", "m",
    "rb", "php", "cs", "scala", "sql", "sh", "yaml", "yml", "json", "xml", "gradle", "toml", "dart", "lua", "r")

fun kindFor(name: String, mime: String?): DocKind? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "pdf" || mime == "application/pdf" -> DocKind.PDF
        ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocKind.DOCX
        ext == "md" || ext == "markdown" -> DocKind.MARKDOWN
        ext in CODE_EXT -> DocKind.CODE
        ext == "txt" || ext == "csv" || ext == "log" || mime?.startsWith("text/") == true -> DocKind.TEXT
        else -> null
    }
}

/**
 * Local text extraction with size and time limits (docs/KNOWLEDGE_ENGINE_DESIGN.md §2).
 * Runs in the app process for now; moving it into an isolated process is tracked for hardening.
 */
object TextExtractor {
    private const val MAX_TEXT_CHARS = 4_000_000
    private const val MAX_PDF_PAGES = 600
    private const val MAX_DOCX_XML_BYTES = 60L * 1024 * 1024

    fun extract(context: Context, file: File, kind: DocKind): Extracted = when (kind) {
        DocKind.PDF -> pdf(context, file)
        DocKind.DOCX -> file.inputStream().use { docx(it) }
        DocKind.MARKDOWN -> Extracted(markdown(readText(file)), null)
        DocKind.CODE, DocKind.TEXT, DocKind.NOTE -> Extracted(listOf(Section(readText(file), null)), null)
    }

    private fun readText(file: File): String {
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.UTF_8).let { if (it.count { c -> c == '�' } > it.length / 50) String(bytes, Charsets.ISO_8859_1) else it }
        return if (text.length > MAX_TEXT_CHARS) text.substring(0, MAX_TEXT_CHARS) else text
    }

    private fun markdown(text: String): List<Section> {
        val out = ArrayList<Section>()
        var heading: String? = null
        val buf = StringBuilder()
        fun flush() { if (buf.isNotBlank()) out += Section(buf.toString().trim(), heading?.let { "§ $it" }); buf.clear() }
        text.lineSequence().forEach { line ->
            val h = Regex("^#{1,6}\\s+(.+)").find(line)
            if (h != null) { flush(); heading = h.groupValues[1].trim() }
            buf.append(line).append('\n')
        }
        flush()
        return out
    }

    private fun pdf(context: Context, file: File): Extracted {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) throw ExtractionException("This PDF is password-protected.")
            val pages = doc.numberOfPages
            val stripper = PDFTextStripper()
            val out = ArrayList<Section>()
            var total = 0
            for (p in 1..minOf(pages, MAX_PDF_PAGES)) {
                stripper.startPage = p; stripper.endPage = p
                val t = stripper.getText(doc).trim()
                if (t.isNotEmpty()) { out += Section(t, "p. $p"); total += t.length }
                if (total > MAX_TEXT_CHARS) break
            }
            if (out.sumOf { it.text.length } < 20) throw ExtractionException("No text layer found (scanned PDF). OCR is planned for a later release.")
            return Extracted(out, pages)
        }
    }

    /** DOCX = zip; read word/document.xml with a streaming parser (headings, paragraphs, tables). */
    private fun docx(input: InputStream): Extracted {
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name != "word/document.xml") continue
                val limited = object : InputStream() {
                    var read = 0L
                    override fun read(): Int { if (++read > MAX_DOCX_XML_BYTES) throw ExtractionException("Document is too large."); return zip.read() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = zip.read(b, off, len); if (n > 0) { read += n; if (read > MAX_DOCX_XML_BYTES) throw ExtractionException("Document is too large.") }; return n
                    }
                }
                val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(limited, "UTF-8") }
                val out = ArrayList<Section>()
                val para = StringBuilder(); val section = StringBuilder()
                var heading: String? = null; var isHeading = false
                fun flushSection() { if (section.isNotBlank()) out += Section(section.toString().trim(), heading?.let { "§ $it" }); section.clear() }
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "w:p" -> { para.clear(); isHeading = false }
                            "w:pStyle" -> if (parser.getAttributeValue(null, "w:val")?.startsWith("Heading", ignoreCase = true) == true) isHeading = true
                            "w:t" -> para.append(parser.nextText())
                            "w:tab" -> para.append('\t')
                            "w:br" -> para.append('\n')
                        }
                        XmlPullParser.END_TAG -> when (parser.name) {
                            "w:p" -> if (para.isNotBlank()) {
                                if (isHeading) { flushSection(); heading = para.toString().trim() }
                                section.append(para).append('\n')
                            }
                            "w:tc" -> section.append('\t')
                        }
                    }
                    parser.next()
                }
                flushSection()
                if (out.isEmpty()) throw ExtractionException("The document has no text.")
                return Extracted(out, null)
            }
        }
        throw ExtractionException("Not a valid Word (.docx) file.")
    }
}

/** Structure-aware chunking: paragraphs → sentences, ~4 characters per token. */
object Chunker {
    data class Chunk(val text: String, val location: String?, val approxTokens: Int)

    fun chunk(sections: List<Section>, kind: DocKind, chunkTokens: Int, overlapTokens: Int): List<Chunk> {
        val maxChars = chunkTokens * 4
        val overlapChars = overlapTokens * 4
        val out = ArrayList<Chunk>()
        for (s in sections) {
            if (kind == DocKind.CODE) { out += codeChunks(s.text, maxChars); continue }
            val pieces = s.text.split(Regex("\\n\\s*\\n")).flatMap { p -> if (p.length <= maxChars) listOf(p) else splitSentences(p, maxChars) }
            val buf = StringBuilder()
            for (p in pieces) {
                val t = p.trim(); if (t.isEmpty()) continue
                if (buf.isNotEmpty() && buf.length + t.length + 2 > maxChars) {
                    out += Chunk(buf.toString(), s.location, buf.length / 4)
                    val tail = if (overlapChars > 0) buf.takeLast(overlapChars).toString().substringAfter(' ', "") else ""
                    buf.clear(); if (tail.isNotBlank()) buf.append(tail).append("\n\n")
                }
                buf.append(t).append("\n\n")
            }
            if (buf.isNotBlank()) out += Chunk(buf.toString().trim(), s.location, buf.length / 4)
        }
        return out.filter { it.text.isNotBlank() }
    }

    private fun splitSentences(p: String, maxChars: Int): List<String> {
        val parts = p.split(Regex("(?<=[.!?。])\\s+"))
        val out = ArrayList<String>(); val b = StringBuilder()
        for (x in parts) {
            if (x.length > maxChars) { if (b.isNotEmpty()) { out += b.toString(); b.clear() }; x.chunked(maxChars).forEach { out += it }; continue }
            if (b.length + x.length + 1 > maxChars) { out += b.toString(); b.clear() }
            b.append(x).append(' ')
        }
        if (b.isNotBlank()) out += b.toString()
        return out
    }

    private fun codeChunks(text: String, maxChars: Int): List<Chunk> {
        val lines = text.lines()
        val out = ArrayList<Chunk>()
        var start = 0; val b = StringBuilder()
        lines.forEachIndexed { i, l ->
            val boundary = b.length > maxChars / 2 && l.isNotBlank() && !l.first().isWhitespace() && l.first() != '}'
            if (b.length + l.length > maxChars || boundary && b.length > maxChars * 0.8) {
                if (b.isNotBlank()) out += Chunk(b.toString(), "lines ${start + 1}–$i", b.length / 4)
                b.clear(); start = i
            }
            b.append(l).append('\n')
        }
        if (b.isNotBlank()) out += Chunk(b.toString(), "lines ${start + 1}–${lines.size}", b.length / 4)
        return out
    }
}
