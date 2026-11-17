package com.localai.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localai.app.data.catalog.Verdict

enum class Tone { OK, WARN, BAD, RUN, PENDING }

@Composable
fun toneColor(t: Tone): Color = with(La.colors) { when (t) { Tone.OK -> ok; Tone.WARN -> warn; Tone.BAD -> bad; Tone.RUN -> accentStrong; Tone.PENDING -> pending } }

/** State encoded in form + colour (dot + tinted pill). */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = toneColor(tone)
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun VerdictPill(v: Verdict) = StatusPill(v.label, when (v) { Verdict.RUNS_WELL -> Tone.OK; Verdict.TIGHT_FIT -> Tone.WARN; Verdict.NOT_RECOMMENDED -> Tone.BAD })

@Composable
fun LaCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val c = La.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(20.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding.dp),
        content = content,
    )
}

@Composable
fun Tag(text: String, color: Color? = null, icon: ImageVector? = null) {
    val c = La.colors
    val fg = color ?: c.text2
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (color != null) color.copy(alpha = 0.14f) else c.surface3).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Eyebrow(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = La.colors.text3, fontWeight = FontWeight.SemiBold)

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(action, color = La.colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp))
    }
}

@Composable
fun Banner(text: String, tone: Tone, icon: ImageVector? = null) {
    val c = toneColor(tone)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.13f)).padding(12.dp), verticalAlignment = Alignment.Top) {
        if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = c, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

data class StackSegment(val value: Double, val color: Color, val label: String)

/** Horizontal stacked bar + legend (memory, storage). */
@Composable
fun StackBar(segments: List<StackSegment>, total: Double) {
    val c = La.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surface4)) {
            segments.forEach { s ->
                val w = (s.value / total).toFloat().coerceIn(0f, 1f)
                if (w > 0f) Box(Modifier.weight(w).height(10.dp).background(s.color))
            }
            val rest = (1 - segments.sumOf { it.value } / total).toFloat()
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
        FlowRowSimple { segments.forEach { s -> LegendItem(s.color, s.label) } }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, bottom = 2.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.5.sp, color = La.colors.text2)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowRowSimple(content: @Composable RowScope.() -> Unit) =
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, color = La.colors.text3, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(La.colors.surface2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, fontSize = 11.sp, color = La.colors.text3)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

fun fmtGiB(v: Double) = if (v >= 10) "%.1f GB".format(v) else "%.2f GB".format(v)
fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.0f MB".format(b / (1024.0 * 1024))
    else -> "%.0f KB".format(b / 1024.0)
}
fun fmtAgo(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60000
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; m < 2880 -> "yesterday"; else -> "${m / 1440} days ago" }
}
