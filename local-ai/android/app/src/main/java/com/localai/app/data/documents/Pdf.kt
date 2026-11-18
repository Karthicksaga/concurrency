package com.localai.app.data.documents

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.io.OutputStream

/**
 * Renders blocks to an A4 PDF with Android's PdfDocument: real text (selectable, searchable), automatic page
 * breaks, headings kept with the next line, tables with borders, code blocks, quotes and page numbers.
 * Any script the phone can display (Tamil, Hindi, Chinese…) renders through the system font fallback.
 */
object PdfWriter {
    fun write(blocks: List<Block>, title: String, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val r = PdfRenderer(doc, title)
            blocks.forEachIndexed { i, b -> r.block(b, blocks.getOrNull(i + 1)) }
            r.finish()
            doc.writeTo(out)
        } finally { doc.close() }
    }
}

private class PdfRenderer(private val doc: PdfDocument, private val title: String) {
    private val pw = 595; private val ph = 842; private val m = 56f
    private val width = (pw - 2 * m).toInt()
    private var page: PdfDocument.Page? = null
    private lateinit var canvas: Canvas
    private var y = m
    private var pageNo = 0

    private val ink = 0xFF1A1A1A.toInt(); private val headInk = 0xFF1F3864.toInt(); private val muted = 0xFF666666.toInt()
    private fun paint(size: Float, color: Int = ink, tf: Typeface = Typeface.DEFAULT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf }
    private val body = paint(11f)
    private val mono = paint(8.8f, 0xFF222222.toInt(), Typeface.MONOSPACE)
    private val quote = paint(11f, 0xFF555555.toInt(), Typeface.create(Typeface.DEFAULT, Typeface.ITALIC))
    private val footer = paint(8f, muted)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBFBFBF.toInt(); strokeWidth = 0.7f; style = Paint.Style.STROKE }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private fun headPaint(level: Int) = paint(when (level) { 1 -> 20f; 2 -> 16f; 3 -> 13.5f; else -> 12f }, if (level <= 2) headInk else ink, Typeface.DEFAULT_BOLD)

    private fun newPage() {
        finishPage()
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
        canvas = page!!.canvas
        y = m
    }

    private fun finishPage() {
        val p = page ?: return
        val label = TextUtils.ellipsize(title, footer, width * 0.7f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, m, ph - m / 2, footer)
        val n = "$pageNo"; canvas.drawText(n, pw - m - footer.measureText(n), ph - m / 2, footer)
        doc.finishPage(p); page = null
    }

    fun finish() { if (page == null) newPage(); finishPage() }

    private fun room() = ph - m - y
    private fun ensure(h: Float) { if (page == null || h > room()) newPage() }
    private fun gap(h: Float) { if (page != null && y > m) y += h }

    private fun spanned(spans: List<Span>): CharSequence {
        val sb = SpannableStringBuilder()
        spans.forEach { s ->
            val start = sb.length; sb.append(s.text); val end = sb.length
            if (start == end) return@forEach
            if (s.bold && s.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (s.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (s.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.code) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(0xFFEFEFEF.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun layout(text: CharSequence, p: TextPaint, w: Int, mult: Float = 1.25f): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, w.coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, mult).setIncludePad(false).build()

    /** Draws a layout, continuing on new pages as needed. [decorate] paints behind each page's slice. */
    private fun flow(l: StaticLayout, x: Float, decorate: ((top: Float, bottom: Float) -> Unit)? = null) {
        var ln = 0
        while (ln < l.lineCount) {
            val firstH = (l.getLineBottom(ln) - l.getLineTop(ln)).toFloat()
            if (page == null || firstH > room()) newPage()
            val startTop = l.getLineTop(ln)
            var end = ln
            while (end < l.lineCount && l.getLineBottom(end) - startTop <= room()) end++
            if (end == ln) end = ln + 1                                         // a line taller than a page: draw it anyway
            val h = (l.getLineBottom(end - 1) - startTop).toFloat()
            decorate?.invoke(y, y + h)
            canvas.save()
            canvas.translate(x, y - startTop)
            canvas.clipRect(0f, startTop.toFloat(), l.width.toFloat() + 4f, (startTop + h))
            l.draw(canvas)
            canvas.restore()
            y += h; ln = end
        }
    }

    fun block(b: Block, next: Block?) {
        when (b) {
            is Block.Heading -> {
                val l = layout(spanned(b.spans), headPaint(b.level), width, 1.15f)
                gap(if (b.level <= 2) 14f else 10f)
                // keep the heading with at least two lines of what follows
                ensure(l.height + if (next != null) 34f else 0f)
                flow(l, m)
                if (b.level == 1) { y += 3f; canvas.drawLine(m, y, m + width, y, line) }
                y += 5f
            }
            is Block.Para -> { val l = layout(spanned(b.spans), body, width); ensure(minOf(l.height.toFloat(), 30f)); flow(l, m); y += 7f }
            is Block.ListItem -> {
                val indent = 16f + 16f * b.level
                val marker = if (b.ordered) "${b.number}." else listOf("•", "◦", "▪", "•")[b.level.coerceIn(0, 3)]
                val l = layout(spanned(b.spans), body, (width - indent - 14f).toInt())
                ensure((l.getLineBottom(0) - l.getLineTop(0)).toFloat())
                canvas.drawText(marker, m + indent - body.measureText(marker) + 6f, y + l.getLineBaseline(0), body)
                flow(l, m + indent + 12f)
                y += if (next is Block.ListItem) 3f else 8f
            }
            is Block.Code -> {
                gap(2f)
                val l = layout(b.text, mono, width - 16, 1.15f)
                ensure(minOf(l.height.toFloat() + 12f, 40f))
                fill.color = 0xFFF3F3F3.toInt()
                y += 6f
                flow(l, m + 8f) { t, bt -> canvas.drawRect(m, t - 6f, m + width, bt + 6f, fill) }
                y += 14f
            }
            is Block.Quote -> {
                val l = layout(spanned(b.spans), quote, width - 18)
                ensure(minOf(l.height.toFloat(), 30f))
                fill.color = 0xFFBBBBBB.toInt()
                flow(l, m + 14f) { t, bt -> canvas.drawRect(m + 2f, t, m + 5f, bt, fill) }
                y += 9f
            }
            is Block.Rule -> { ensure(14f); y += 6f; canvas.drawLine(m, y, m + width, y, line); y += 8f }
            is Block.Table -> table(b)
        }
    }

    private fun table(t: Block.Table) {
        val cols = t.rows.maxOfOrNull { it.size } ?: return
        val cw = width.toFloat() / cols
        val pad = 5f
        val small = paint(10f); val bold = paint(10f, ink, Typeface.DEFAULT_BOLD)
        gap(4f)
        t.rows.forEachIndexed { r, row ->
            val header = r == 0 && t.header
            val ls = row.map { layout(spanned(it), if (header) bold else small, (cw - 2 * pad).toInt(), 1.15f) }
            val rh = (ls.maxOfOrNull { it.height } ?: 0) + 2 * pad
            ensure(minOf(rh, ph - 2 * m))
            if (header) { fill.color = 0xFFEDEDED.toInt(); canvas.drawRect(m, y, m + width, y + rh, fill) }
            ls.forEachIndexed { c, l ->
                val x = m + c * cw
                canvas.drawRect(x, y, x + cw, y + rh, line)
                canvas.save(); canvas.translate(x + pad, y + pad); canvas.clipRect(0f, 0f, cw - 2 * pad, rh); l.draw(canvas); canvas.restore()
            }
            y += rh
        }
        y += 10f
    }
}
