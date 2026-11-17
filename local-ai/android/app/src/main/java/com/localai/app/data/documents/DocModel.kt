package com.localai.app.data.documents

/** Styled run of text. */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

/** Format-neutral document: what Markdown, Word and PDF are converted through. */
sealed interface Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Para(val spans: List<Span>) : Block
    data class ListItem(val ordered: Boolean, val level: Int, val number: Int, val spans: List<Span>, val listId: Int) : Block
    data class Code(val text: String) : Block
    data class Quote(val spans: List<Span>) : Block
    data class Table(val rows: List<List<List<Span>>>, val header: Boolean) : Block
    data object Rule : Block
}

fun List<Span>.plain() = joinToString("") { it.text }

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(md: String): List<Block> {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<Block>()
        val para = StringBuilder()
        var listId = 0; var inList = false
        fun flushPara() { if (para.isNotBlank()) out += Block.Para(inline(para.toString().trim())); para.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flushPara(); inList = false
                    val fence = t.take(3); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { code.append(lines[i]).append('\n'); i++ }
                    out += Block.Code(code.toString().trimEnd('\n'))
                }
                t.isEmpty() -> { flushPara(); if (i + 1 < lines.size && !isListLine(lines[i + 1])) inList = false }
                heading.matches(t) -> { flushPara(); inList = false; val m = heading.find(t)!!; out += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2])) }
                rule.matches(t) -> { flushPara(); inList = false; out += Block.Rule }
                t.startsWith(">") -> {
                    flushPara(); inList = false
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++ }
                    out += Block.Quote(inline(q.toString().trim())); continue
                }
                t.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flushPara(); inList = false
                    val rows = ArrayList<List<List<Span>>>()
                    rows += cells(t); i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    val width = rows.maxOf { it.size }
                    out += Block.Table(rows.map { r -> r + List(width - r.size) { emptyList() } }, header = true); continue
                }
                bullet.matches(line) || ordered.matches(line) -> {
                    flushPara()
                    if (!inList) { listId++; inList = true }
                    val b = bullet.find(line)
                    if (b != null) out += Block.ListItem(false, indent(b.groupValues[1]), 0, inline(b.groupValues[2]), listId)
                    else { val o = ordered.find(line)!!; out += Block.ListItem(true, indent(o.groupValues[1]), o.groupValues[2].toInt(), inline(o.groupValues[3]), listId) }
                }
                inList && line.startsWith("  ") && out.lastOrNull() is Block.ListItem -> {
                    val last = out.removeAt(out.size - 1) as Block.ListItem       // continuation line of a list item
                    out += last.copy(spans = last.spans + Span(" ") + inline(t))
                }
                else -> { inList = false; if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flushPara()
        return out
    }

    private fun isListLine(l: String) = bullet.matches(l) || ordered.matches(l)
    private fun indent(ws: String) = (ws.replace("\t", "    ").length / 2).coerceIn(0, 3)
    private fun cells(row: String) = row.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()) }

    /** **bold**, __bold__, *italic*, _italic_, `code`, [text](url) → text. */
    fun inline(s: String): List<Span> {
        val out = ArrayList<Span>()
        val buf = StringBuilder()
        var bold = false; var italic = false
        fun flush() { if (buf.isNotEmpty()) { out += Span(buf.toString(), bold, italic); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { buf.append(s[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) { flush(); out += Span(s.substring(i + 1, end), bold, italic, code = true); i = end + 1; continue }
                }
                (s.startsWith("**", i) || s.startsWith("__", i)) && (bold || s.indexOf(s.substring(i, i + 2), i + 2) > 0) -> { flush(); bold = !bold; i += 2; continue }
                (c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()) || (c == '_' && italic))) &&
                    (italic || (i + 1 < s.length && !s[i + 1].isWhitespace() && s.indexOf(c, i + 1) > 0)) -> { flush(); italic = !italic; i++; continue }
                c == '[' -> {
                    val close = s.indexOf("](", i)
                    val end = if (close > 0) s.indexOf(')', close) else -1
                    if (close > 0 && end > 0) { buf.append(s, i + 1, close); i = end + 1; continue }
                }
            }
            buf.append(c); i++
        }
        flush()
        return out
    }

    fun spansToMd(spans: List<Span>) = spans.joinToString("") { s ->
        var t = s.text
        if (s.code) t = "`$t`"
        if (s.italic && t.isNotBlank()) t = "*$t*"
        if (s.bold && t.isNotBlank()) t = "**$t**"
        t
    }

    fun render(blocks: List<Block>): String = buildString {
        var prev: Block? = null
        blocks.forEach { b ->
            if (prev != null && !(prev is Block.ListItem && b is Block.ListItem)) append('\n')
            when (b) {
                is Block.Heading -> append("#".repeat(b.level)).append(' ').append(spansToMd(b.spans)).append('\n')
                is Block.Para -> append(spansToMd(b.spans)).append('\n')
                is Block.ListItem -> append("  ".repeat(b.level)).append(if (b.ordered) "${b.number}. " else "- ").append(spansToMd(b.spans)).append('\n')
                is Block.Code -> append("```\n").append(b.text).append("\n```\n")
                is Block.Quote -> append("> ").append(spansToMd(b.spans)).append('\n')
                is Block.Rule -> append("---\n")
                is Block.Table -> b.rows.forEachIndexed { r, row ->
                    append("| ").append(row.joinToString(" | ") { spansToMd(it).replace("|", "\\|") }).append(" |\n")
                    if (r == 0 && b.header) append("|").append(row.joinToString("|") { " --- " }).append("|\n")
                }
            }
            prev = b
        }
    }.trimEnd() + "\n"

    /** Plain text paragraphs (e.g. from a PDF) → blocks, with light heuristics for bullets. */
    fun fromPlainText(text: String): List<Block> {
        val out = ArrayList<Block>(); var listId = 0; var inList = false
        text.replace("\r", "").split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
            val lines = p.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bulletLines = lines.all { it.startsWith("•") || it.startsWith("- ") || it.startsWith("* ") || Regex("^\\d{1,3}[.)]\\s").containsMatchIn(it) }
            if (bulletLines) {
                if (!inList) { listId++; inList = true }
                lines.forEach { l ->
                    val o = Regex("^(\\d{1,3})[.)]\\s+(.*)").find(l)
                    out += if (o != null) Block.ListItem(true, 0, o.groupValues[1].toInt(), listOf(Span(o.groupValues[2])), listId)
                    else Block.ListItem(false, 0, 0, listOf(Span(l.trimStart('•', '-', '*', ' '))), listId)
                }
            } else {
                inList = false
                val joined = lines.joinToString(" ").replace(Regex("(\\w)- (\\w)"), "$1$2")
                val headingLike = lines.size == 1 && joined.length <= 70 && !joined.endsWith('.') && !joined.endsWith(',') && joined.first().isUpperCase()
                out += if (headingLike) Block.Heading(2, listOf(Span(joined))) else Block.Para(listOf(Span(joined)))
            }
        }
        return out
    }
}
