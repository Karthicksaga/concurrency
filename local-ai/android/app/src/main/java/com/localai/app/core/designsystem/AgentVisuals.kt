package com.localai.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val AGENT_ICONS: Map<String, ImageVector> = mapOf(
    "search" to Icons.Outlined.Search, "code" to Icons.Outlined.Code, "sparkle" to Icons.Outlined.AutoAwesome,
    "file" to Icons.Outlined.Description, "school" to Icons.Outlined.School, "table" to Icons.Outlined.TableChart,
    "knowledge" to Icons.AutoMirrored.Outlined.MenuBook, "agent" to Icons.Outlined.SmartToy,
)
val HUES = listOf("model", "agent", "knowledge", "accent", "warn", "ok", "bad")

@Composable
fun hue(key: String): Color = with(La.colors) {
    when (key) { "model" -> model; "agent" -> agent; "knowledge" -> knowledge; "warn" -> warn; "ok" -> ok; "bad" -> bad; else -> accentStrong }
}

@Composable
fun AgentAvatar(icon: String, color: String, size: Dp = 40.dp) {
    val c = hue(color)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(c.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(AGENT_ICONS[icon] ?: Icons.Outlined.SmartToy, null, tint = c, modifier = Modifier.size(size * 0.55f))
    }
}
