package com.localai.app.data.documents

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Writes a real Word document (Office Open XML) with headings, lists, tables, code and quotes. No libraries. */
object DocxWriter {
    fun write(blocks: List<Block>, title: String, out: OutputStream) {
        val orderedLists = blocks.filterIsInstance<Block.ListItem>().filter { it.ordered }.map { it.listId }.distinct()
        val numIdFor = orderedLists.withIndex().associate { (i, id) -> id to i + 2 }      // numId 1 = bullets
        ZipOutputStream(out).use { z ->
            fun put(name: String, xml: String) { z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("word/_rels/document.xml.rels", DOC_RELS)
            put("word/styles.xml", STYLES)
            put("word/numbering.xml", numbering(numIdFor.values.toList()))
            put("docProps/core.xml", core(title))
            put("docProps/app.xml", APP)
            put("word/document.xml", document(blocks, numIdFor))
        }
    }

    private fun esc(s: String) = buildString {
        s.forEach { c ->
            when {
                c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;"); c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c == '\r' || c >= ' ' -> append(c)
                else -> Unit                                        // control characters are invalid in XML
            }
        }
    }

    private fun runs(spans: List<Span>, forceBold: Boolean = false) = spans.joinToString("") { s ->
        val pr = buildString {
            if (s.code) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
            if (s.bold || forceBold) append("<w:b/>")
            if (s.italic) append("<w:i/>")
            if (s.code) append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F1F1F1\"/>")
        }
        "<w:r>" + (if (pr.isNotEmpty()) "<w:rPr>$pr</w:rPr>" else "") + "<w:t xml:space=\"preserve\">${esc(s.text)}</w:t></w:r>"
    }

    private fun p(style: String?, body: String, extraPr: String = "") =
        "<w:p>" + (if (style != null || extraPr.isNotEmpty()) "<w:pPr>${style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: ""}$extraPr</w:pPr>" else "") + body + "</w:p>"

    private fun document(blocks: List<Block>, numIdFor: Map<Int, Int>): String {
        val body = StringBuilder()
        blocks.forEach { b ->
            body.append(when (b) {
                is Block.Heading -> p("Heading${b.level.coerceIn(1, 3)}", runs(b.spans))
                is Block.Para -> p(null, runs(b.spans))
                is Block.Quote -> p("Quote", runs(b.spans))
                is Block.ListItem -> p("ListParagraph", runs(b.spans),
                    "<w:numPr><w:ilvl w:val=\"${b.level}\"/><w:numId w:val=\"${if (b.ordered) numIdFor[b.listId] ?: 2 else 1}\"/></w:numPr>")
                is Block.Code -> p("Code", b.text.split('\n').joinToString("<w:r><w:br/></w:r>") { "<w:r><w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r>" })
                is Block.Rule -> p(null, "", "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr>")
                is Block.Table -> table(b)
            })
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    }

    private fun table(t: Block.Table): String {
        val cols = t.rows.maxOfOrNull { it.size } ?: 0
        val w = if (cols > 0) 9638 / cols else 9638
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$w\"/>") }
        sb.append("</w:tblGrid>")
        t.rows.forEachIndexed { r, row ->
            sb.append(if (r == 0 && t.header) "<w:tr><w:trPr><w:tblHeader/></w:trPr>" else "<w:tr>")
            row.forEach { cell ->
                val shade = if (r == 0 && t.header) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EDEDED\"/>" else ""
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>$shade</w:tcPr>").append(p(null, runs(cell, forceBold = r == 0 && t.header))).append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        return sb.append("</w:tbl>").append(p(null, "")).toString()
    }

    private fun numbering(orderedNumIds: List<Int>): String {
        val bulletLvls = (0..3).joinToString("") { l ->
            val sym = listOf("•", "◦", "▪", "•")[l]
            "<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$sym\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + 360 * l}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }
        val decLvls = (0..3).joinToString("") { l ->
            val fmt = listOf("decimal", "lowerLetter", "lowerRoman", "decimal")[l]
            "<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"%${l + 1}.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + 360 * l}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        }
        val nums = StringBuilder("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        (orderedNumIds.ifEmpty { listOf(2) }).forEach { id ->
            nums.append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>$bulletLvls</w:abstractNum><w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>$decLvls</w:abstractNum>$nums</w:numbering>"""
    }

    private fun core(title: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>LOCAL AI</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""
    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""
    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/></Relationships>"""
    private const val APP = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>LOCAL AI</Application></Properties>"""
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/><w:rPr><w:b/><w:sz w:val="48"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3864"/><w:sz w:val="36"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="280" w:after="100"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="28"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F3F3F3"/><w:spacing w:after="120" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/><w:sz w:val="19"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:pPr><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="BBBBBB"/></w:pBdr><w:ind w:left="360"/></w:pPr><w:rPr><w:i/><w:color w:val="555555"/></w:rPr></w:style><w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:left w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:right w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/></w:tblBorders><w:tblCellMar><w:left w:w="100" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style></w:styles>"""
}

/**
 * Reads a .docx into blocks: headings (Heading N / Title styles or outline level), bulleted and numbered lists
 * (from numbering.xml), tables, code-styled paragraphs, quotes and bold/italic/monospace runs.
 * A tiny tag scanner instead of an XML library keeps it testable on the JVM; inputs are size-limited.
 */
object DocxReader {
    private const val MAX_XML = 60L * 1024 * 1024

    fun read(input: InputStream): List<Block> {
        var docXml: String? = null; var numXml: String? = null
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when (e.name) {
                    "word/document.xml" -> docXml = readLimited(z)
                    "word/numbering.xml" -> numXml = readLimited(z)
                }
            }
        }
        val xml = docXml ?: throw IllegalArgumentException("Not a valid Word (.docx) file.")
        return parse(xml, bulletNumIds(numXml))
    }

    private fun readLimited(z: InputStream): String {
        val out = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16); var total = 0L
        while (true) { val n = z.read(buf); if (n < 0) break; total += n; if (total > MAX_XML) throw IllegalArgumentException("Document is too large."); out.write(buf, 0, n) }
        return out.toString("UTF-8")
    }

    private class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean, val selfClosing: Boolean)

    private val attrRe = Regex("([\\w:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    private fun tags(xml: String, onTag: (Tag) -> Unit, onText: (String) -> Unit) {
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) { onText(xml.substring(i)); break }
            if (lt > i) onText(xml.substring(i, lt))
            if (xml.startsWith("<!--", lt)) { i = xml.indexOf("-->", lt).let { if (it < 0) xml.length else it + 3 }; continue }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) { i = xml.indexOf('>', lt).let { if (it < 0) xml.length else it + 1 }; continue }
            val gt = xml.indexOf('>', lt); if (gt < 0) break
            val raw = xml.substring(lt + 1, gt)
            val closing = raw.startsWith("/")
            val self = raw.endsWith("/")
            val body = raw.removePrefix("/").removeSuffix("/").trim()
            val name = body.takeWhile { !it.isWhitespace() }
            val attrs = if (closing) emptyMap() else attrRe.findAll(body).associate { it.groupValues[1] to unescape(it.groupValues[3].ifEmpty { it.groupValues[4] }) }
            onTag(Tag(name, attrs, closing, self))
            i = gt + 1
        }
    }

    fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v == "amp" -> "&"; v == "lt" -> "<"; v == "gt" -> ">"; v == "quot" -> "\""; v == "apos" -> "'"
                v.startsWith("#x") -> String(Character.toChars(v.substring(2).toInt(16)))
                else -> String(Character.toChars(v.substring(1).toInt()))
            }
        }
    }

    /** numIds whose level-0 format is a bullet. */
    private fun bulletNumIds(xml: String?): Set<String> {
        if (xml == null) return emptySet()
        val absBullet = HashSet<String>(); val numToAbs = HashMap<String, String>()
        var abs: String? = null; var lvl: String? = null; var num: String? = null
        tags(xml, { t ->
            when (t.name) {
                "w:abstractNum" -> if (!t.closing) abs = t.attrs["w:abstractNumId"] else abs = null
                "w:lvl" -> if (!t.closing) lvl = t.attrs["w:ilvl"]
                "w:numFmt" -> if (abs != null && lvl == "0" && t.attrs["w:val"] == "bullet") absBullet += abs!!
                "w:num" -> if (!t.closing) num = t.attrs["w:numId"] else num = null
                "w:abstractNumId" -> if (num != null) t.attrs["w:val"]?.let { numToAbs[num!!] = it }
            }
        }, {})
        return numToAbs.filterValues { it in absBullet }.keys
    }

    private fun parse(xml: String, bullets: Set<String>): List<Block> {
        val out = ArrayList<Block>()
        // paragraph state
        var spans = ArrayList<Span>(); var style = ""; var numId: String? = null; var ilvl = 0; var outline: Int? = null; var border = false
        // run state
        var bold = false; var italic = false; var mono = false; var inRun = false; var inRPr = false; var inText = false
        // table state
        var tableDepth = 0; var rows = ArrayList<List<List<Span>>>(); var row = ArrayList<List<Span>>(); var cell = ArrayList<Span>()
        var listId = 0; var lastWasList = false; val counters = HashMap<String, Int>()

        fun addText(s: String) {
            if (s.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic && last.code == mono) spans[spans.size - 1] = last.copy(text = last.text + s)
            else spans += Span(s, bold, italic, mono)
        }
        fun endParagraph() {
            val text = spans.plain()
            if (tableDepth > 0) { if (cell.isNotEmpty() && text.isNotEmpty()) cell += Span(" "); cell.addAll(spans) }
            else {
                val s = style.lowercase()
                val headingLevel = when {
                    s == "title" -> 1
                    s.startsWith("heading") -> s.removePrefix("heading").trim().toIntOrNull()?.coerceIn(1, 6) ?: 1
                    outline != null -> (outline!! + 1).coerceIn(1, 6)
                    else -> null
                }
                when {
                    text.isBlank() -> { if (border) out += Block.Rule; lastWasList = false }
                    headingLevel != null -> { out += Block.Heading(headingLevel, spans.map { it.copy(bold = false) }); lastWasList = false }
                    numId != null && numId != "0" -> {
                        if (!lastWasList) listId++
                        val ordered = numId !in bullets
                        val key = "$numId/$ilvl"
                        val n = (counters[key] ?: 0) + 1; counters[key] = n
                        out += Block.ListItem(ordered, ilvl.coerceIn(0, 3), n, spans.toList(), listId); lastWasList = true
                    }
                    s.contains("code") || s.contains("preformatted") || (spans.isNotEmpty() && spans.all { it.code }) -> {
                        val prev = out.lastOrNull()
                        if (prev is Block.Code) out[out.size - 1] = Block.Code(prev.text + "\n" + text) else out += Block.Code(text)
                        lastWasList = false
                    }
                    s.contains("quote") -> { out += Block.Quote(spans.toList()); lastWasList = false }
                    else -> { out += Block.Para(spans.toList()); lastWasList = false }
                }
            }
            spans = ArrayList(); style = ""; numId = null; ilvl = 0; outline = null; border = false
        }
        fun on(v: String?) = v == null || (v != "0" && v != "false" && v != "none")

        tags(xml, { t ->
            when (t.name) {
                "w:p" -> if (t.closing || t.selfClosing) endParagraph()
                "w:pStyle" -> style = t.attrs["w:val"] ?: ""
                "w:pBdr" -> if (!t.closing) border = true
                "w:outlineLvl" -> outline = t.attrs["w:val"]?.toIntOrNull()?.takeIf { it < 6 }
                "w:numId" -> numId = t.attrs["w:val"]
                "w:ilvl" -> ilvl = t.attrs["w:val"]?.toIntOrNull() ?: 0
                "w:r" -> if (!t.closing) { inRun = true; bold = false; italic = false; mono = false } else inRun = false
                "w:rPr" -> inRPr = !t.closing && !t.selfClosing
                "w:b" -> if (inRun && inRPr) bold = on(t.attrs["w:val"])
                "w:i" -> if (inRun && inRPr) italic = on(t.attrs["w:val"])
                "w:rFonts" -> if (inRun && inRPr) mono = (t.attrs["w:ascii"] ?: t.attrs["w:hAnsi"] ?: "").let { f -> listOf("consolas", "courier", "mono", "menlo").any { f.lowercase().contains(it) } }
                "w:t" -> inText = !t.closing && !t.selfClosing
                "w:tab" -> if (inRun) addText("\t")
                "w:br", "w:cr" -> if (inRun) addText("\n")
                "w:tbl" -> if (!t.closing) { if (tableDepth++ == 0) rows = ArrayList() } else if (--tableDepth == 0) {
                    if (rows.isNotEmpty()) { val w = rows.maxOf { it.size }; out += Block.Table(rows.map { r -> r + List(w - r.size) { emptyList() } }, header = true) }
                    lastWasList = false
                }
                "w:tr" -> if (tableDepth == 1) { if (!t.closing) row = ArrayList() else rows += row.toList() }
                "w:tc" -> if (tableDepth == 1) { if (!t.closing) cell = ArrayList() else row += cell.toList() }
            }
        }, { text -> if (inText) addText(unescape(text)) })
        return out
    }
}
