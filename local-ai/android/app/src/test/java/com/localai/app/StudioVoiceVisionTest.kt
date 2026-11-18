package com.localai.app

import com.localai.app.data.chat.photoInScope
import com.localai.app.data.db.MessageEntity
import com.localai.app.data.documents.Block
import com.localai.app.data.documents.DocxReader
import com.localai.app.data.documents.DocxWriter
import com.localai.app.data.documents.Markdown
import com.localai.app.data.documents.Span
import com.localai.app.data.documents.chunkDocument
import com.localai.app.data.documents.cleanModelMarkdown
import com.localai.app.data.documents.plain
import com.localai.app.data.media.Images
import com.localai.app.data.voice.SentenceStream
import com.localai.app.data.voice.Speakable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class StudioVoiceVisionTest {
    private val sample = """
        # Quarterly report

        Sales grew **12 %** in *October*, led by `Model X`. See [the site](https://example.com).

        ## Highlights
        - New customers: 40
        - Churn fell
          to 2 %
        1. First step
        2. Second step

        > Keep the focus on retention.

        | Region | Sales |
        | --- | --- |
        | South | 120 |
        | North & East | 80 |

        ```
        val x = 1 < 2
        ```

        ---
        Thanks.
    """.trimIndent()

    @Test fun markdownParsesStructure() {
        val b = Markdown.parse(sample)
        assertEquals(Block.Heading(1, listOf(Span("Quarterly report"))), b[0])
        val p = b[1] as Block.Para
        assertEquals("Sales grew 12 % in October, led by Model X. See the site.", p.spans.plain())
        assertTrue(p.spans.any { it.bold && it.text == "12 %" })
        assertTrue(p.spans.any { it.italic && it.text == "October" })
        assertTrue(p.spans.any { it.code && it.text == "Model X" })
        val items = b.filterIsInstance<Block.ListItem>()
        assertEquals(4, items.size)
        assertEquals("Churn fell to 2 %", items[1].spans.plain())                // continuation line joined
        assertTrue(items.take(2).none { it.ordered } && items.drop(2).all { it.ordered })
        assertTrue(b.any { it is Block.Quote })
        val t = b.filterIsInstance<Block.Table>().single()
        assertEquals(3, t.rows.size); assertEquals("North & East", t.rows[2][0].plain())
        assertEquals("val x = 1 < 2", b.filterIsInstance<Block.Code>().single().text)
        assertTrue(b.contains(Block.Rule))
        assertEquals("Thanks.", (b.last() as Block.Para).spans.plain())
    }

    @Test fun markdownRenderRoundTrips() {
        val once = Markdown.parse(sample)
        assertEquals(once.map(::norm), Markdown.parse(Markdown.render(once)).map(::norm))
    }

    @Test fun docxIsValidXmlAndRoundTrips() {
        val blocks = Markdown.parse(sample)
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(blocks, "Quarterly <report>", it) }.toByteArray()
        // every part is well-formed XML
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                val xml = z.readBytes()
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            }
        }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml")))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(blocks.map(::norm), back.map(::norm))
    }

    @Test fun docxReaderHandlesForeignStylesAndEntities() {
        val doc = """<?xml version="1.0"?><w:document xmlns:w="w"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Plan &amp; budget</w:t></w:r></w:p>
            <w:p><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:r><w:t>Scope</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b w:val="0"/><w:i/></w:rPr><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>world&#8217;s</w:t></w:r></w:p>
            <w:p/></w:body></w:document>"""
        val bytes = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z -> z.putNextEntry(java.util.zip.ZipEntry("word/document.xml")); z.write(doc.toByteArray()); z.closeEntry() }
        }.toByteArray()
        val b = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(Block.Heading(1, listOf(Span("Plan & budget"))), b[0])
        assertEquals(2, (b[1] as Block.Heading).level)
        val p = b[2] as Block.Para
        assertEquals(listOf(Span("Hello ", italic = true), Span("world’s", bold = true)), p.spans)
        assertEquals(3, b.size)
    }

    @Test fun plainTextFromPdfBecomesBlocks() {
        val b = Markdown.fromPlainText("Introduction\n\nThis is a long para-\ngraph that wraps\nacross lines.\n\n• one\n• two\n\n1. a\n2. b")
        assertTrue(b[0] is Block.Heading)
        assertEquals("This is a long paragraph that wraps across lines.", (b[1] as Block.Para).spans.plain())
        assertEquals(4, b.filterIsInstance<Block.ListItem>().size)
    }

    @Test fun chunksRespectLimitAndKeepEverything() {
        val blocks = (1..40).map { Block.Para(listOf(Span("Paragraph $it has some words in it."))) } + Block.Para(listOf(Span("Long. ".repeat(400))))
        val parts = chunkDocument(blocks, 500)
        assertTrue(parts.size > 5)
        assertTrue(parts.all { it.length <= 520 })
        val joined = parts.joinToString(" ")
        assertTrue((1..40).all { joined.contains("Paragraph $it ") })
        assertEquals(400, Regex("Long\\.").findAll(joined).count())
    }

    @Test fun modelOutputIsCleaned() {
        assertEquals("# Title\n\nBody", cleanModelMarkdown("<think>plan</think>\n```markdown\n# Title\n\nBody\n```"))
        assertEquals("# T", cleanModelMarkdown("# T\n<think>unfinished"))
    }

    @Test fun sentencesStreamAsTheyComplete() {
        val s = SentenceStream()
        assertEquals(emptyList<String>(), s.feed("Hello there"))
        assertEquals(listOf("Hello there."), s.feed("Hello there. Dr. Smith"))
        assertEquals(listOf("Dr. Smith paid 3.5 dollars."), s.feed("Hello there. Dr. Smith paid 3.5 dollars. Then"))
        // code is held back until the fence closes, then spoken as a short note
        val code = "Hello there. Dr. Smith paid 3.5 dollars. Then run:\n```\nls -la. rm x\n```\nDone"
        val out = s.feed(code)
        assertEquals(listOf("Then run:", "(code shown on screen)."), out)
        assertEquals(listOf("Done"), s.flush(code))
    }

    @Test fun speakableStripsMarkdown() {
        assertEquals("Title. Use the bold tool see docs.", Speakable.clean("## Title\n- Use the **bold** `tool` [see docs](http://x) [2]."))
    }

    @Test fun photoScopeIsRecentUserPhoto() {
        val f = File.createTempFile("photo", ".jpg").apply { deleteOnExit() }
        fun m(role: String, img: String? = null, t: Long) = MessageEntity("$t", "c", role, "x", "COMPLETE", null, null, null, null, t, imagePath = img)
        assertEquals(f.path, photoInScope(listOf(m("user", f.path, 1), m("assistant", t = 2), m("user", t = 3)))?.path)
        assertNull(photoInScope(listOf(m("user", f.path, 1), m("assistant", t = 2), m("user", t = 3), m("assistant", t = 4), m("user", t = 5))))
        assertNull(photoInScope(listOf(m("user", "/nope/missing.jpg", 1))))
    }

    @Test fun imageHelpers() {
        assertEquals(1, Images.sampleSize(800, 600, 768))
        assertEquals(2, Images.sampleSize(1600, 1200, 768))
        assertEquals(4, Images.sampleSize(4000, 3000, 768))
        assertEquals(listOf(0x11, 0x22, 0x33, 0xFF, 0x00, 0x80), Images.packRgb(intArrayOf(0xFF112233.toInt(), 0x80FF0080.toInt())).map { it.toInt() and 0xFF })
    }

    /** Compares structure and text, ignoring list numbering ids. */
    private fun norm(b: Block): Any = when (b) {
        is Block.ListItem -> listOf("li", b.ordered, b.level, b.spans.plain())
        is Block.Heading -> listOf("h", b.level, b.spans.plain())
        is Block.Para -> listOf("p", b.spans)
        is Block.Table -> listOf("t", b.rows.map { r -> r.map { it.plain() } })
        is Block.Quote -> listOf("q", b.spans.plain())
        else -> b
    }
}
