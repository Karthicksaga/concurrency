package com.localai.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localai.app.core.designsystem.La

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Bullet(val text: String, val marker: String) : Block
    data class Code(val lang: String, val code: String) : Block
}

private fun parse(md: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = md.split('\n')
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        if (l.trimStart().startsWith("```")) {
            val lang = l.trimStart().removePrefix("```").trim()
            val buf = StringBuilder(); i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { if (buf.isNotEmpty()) buf.append('\n'); buf.append(lines[i]); i++ }
            out += Block.Code(lang, buf.toString()); i++; continue   // an unclosed fence (still streaming) renders as code too
        }
        val h = Regex("^#{1,6}\\s+(.*)").find(l)
        val b = Regex("^\\s*([-*•]|\\d+[.)])\\s+(.*)").find(l)
        when {
            h != null -> out += Block.Heading(h.groupValues[1])
            b != null -> out += Block.Bullet(b.groupValues[2], if (b.groupValues[1].first().isDigit()) b.groupValues[1] else "•")
            l.isBlank() -> Unit
            else -> { val last = out.lastOrNull(); if (last is Block.Para && i > 0 && lines[i - 1].isNotBlank()) out[out.size - 1] = Block.Para(last.text + "\n" + l) else out += Block.Para(l) }
        }
        i++
    }
    return out
}

@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = La.colors.surface3
    return remember(text, codeBg) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                        val e = text.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, e)) }; i = e + 2
                    }
                    text[i] == '`' && text.indexOf('`', i + 1) > i + 1 -> {
                        val e = text.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(text.substring(i + 1, e)) }; i = e + 1
                    }
                    else -> { append(text[i]); i++ }
                }
            }
        }
    }
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val blocks = remember(md) { parse(md) }
    val clipboard = LocalClipboardManager.current
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is Block.Para -> Text(inline(b.text), fontSize = 15.sp, lineHeight = 23.sp)
                    is Block.Heading -> Text(inline(b.text), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    is Block.Bullet -> Row { Text(b.marker, color = La.colors.text3, modifier = Modifier.padding(end = 8.dp)); Text(inline(b.text), fontSize = 15.sp, lineHeight = 23.sp) }
                    is Block.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, La.colors.line, RoundedCornerShape(14.dp)).background(La.colors.surface1)) {
                        Row(Modifier.fillMaxWidth().background(La.colors.surface2).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.lang.ifBlank { "text" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = La.colors.text3, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(b.code)) }) { Icon(Icons.Outlined.ContentCopy, "Copy code", Modifier.size(16.dp), tint = La.colors.text3) }
                        }
                        Text(b.code, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), softWrap = false)
                    }
                }
            }
        }
    }
}
