package com.localai.app.core.designsystem

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Brand colours for the launch screen (always dark, so there is no flash from the window background). */
private object Brand {
    val bg = Color(0xFF0B0E13); val glow = Color(0xFF18203D)
    val accent = Color(0xFF8EA8FF); val accentStrong = Color(0xFFB3C4FF)
    val knowledge = Color(0xFF6FD3B0); val agent = Color(0xFFC39BFF); val model = Color(0xFF7CC7FF)
    val text = Color(0xFFE8EBF1); val text2 = Color(0xFFA7AEBB); val text3 = Color(0xFF707A8A); val ok = Color(0xFF5FD08E)
    val die = Color(0xFF1F2530); val dieEdge = Color(0xFF11151C)
}

/** True when the user turned animations off (Developer options or accessibility "Remove animations"). */
@Composable
private fun animationsOff(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/**
 * Launch animation: the "chip" assembles (die pops in, pins appear one by one), its core lights up and three
 * dots orbit it (knowledge, agents, models); then the wordmark rises letter by letter while status lines show
 * what the app is doing. Ends when the animation has played AND [ready] is true (settings loaded).
 */
@Composable
fun LaunchSplash(ready: Boolean, onFinished: () -> Unit) {
    val off = animationsOff()
    val t = remember { Animatable(0f) }                    // 0..1 over the intro
    val done by rememberUpdatedState(onFinished)
    val isReady by rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        if (!off) t.animateTo(1f, tween(1700, easing = LinearEasing)) else t.snapTo(1f)
        while (!isReady) kotlinx.coroutines.delay(30)
        if (!off) kotlinx.coroutines.delay(150)
        done()
    }
    val loop = rememberInfiniteTransition(label = "loop")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val p = t.value

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Brand.glow, Brand.bg), radius = 1400f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ChipMark(140.dp, assemble = p, pulse = pulse, spin = spin)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                "LOCAL·AI".forEachIndexed { i, c ->
                    val k = ((p - 0.30f - i * 0.03f) / 0.18f).coerceIn(0f, 1f)
                    androidx.compose.material3.Text(c.toString(), color = if (c == '·') Brand.accent else Brand.text, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        modifier = Modifier.graphicsLayer { alpha = k; translationY = (1 - k) * 24f })
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text("Private AI, on this phone", color = Brand.text2, fontSize = 13.sp,
                modifier = Modifier.graphicsLayer { alpha = ((p - 0.55f) / 0.2f).coerceIn(0f, 1f) })
            Spacer(Modifier.height(18.dp))
            val status = when { p < 0.35f -> "Checking this phone…"; p < 0.8f || !ready -> "Warming up…"; else -> "Ready" }
            androidx.compose.material3.Text(status, color = if (status == "Ready") Brand.ok else Brand.text3, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(140.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF1F2530))) {
                Box(Modifier.fillMaxWidth(FastOutSlowInEasing.transform(p)).height(3.dp).background(Brand.accent))
            }
        }
    }
}

/**
 * The brand mark: a chip with pins, a glowing core and three orbiting dots.
 * [assemble] 0..1 builds it up; [pulse] 0..1 breathes the core; [spin] degrees for the orbit.
 * Also used small as the "loading model" indicator.
 */
@Composable
fun ChipMark(size: Dp, assemble: Float = 1f, pulse: Float = 0f, spin: Float = 0f) {
    Canvas(Modifier.size(size)) { drawChip(assemble, pulse, spin) }
}

/** Looping chip used while a model loads (Home, chat). */
@Composable
fun ModelLoadingMark(size: Dp = 56.dp) {
    val loop = rememberInfiniteTransition(label = "load")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "s")
    ChipMark(size, 1f, pulse, spin)
}

private fun DrawScope.drawChip(assemble: Float, pulse: Float, spin: Float) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val dieSize = s * 0.5f
    // die pops in with a little overshoot during the first 30 % of the intro
    val pop = ((assemble) / 0.3f).coerceIn(0f, 1f)
    val popScale = if (pop >= 1f) 1f else (0.3f + 0.7f * pop) * (1f + 0.12f * sin(pop * PI.toFloat()))
    // orbit (behind the die)
    val orbitR = s * 0.47f
    val orbitAlpha = ((assemble - 0.2f) / 0.3f).coerceIn(0f, 1f)
    listOf(Brand.knowledge, Brand.agent, Brand.model).forEachIndexed { i, col ->
        val a = Math.toRadians((spin + i * 120f - 90f).toDouble())
        drawCircle(col.copy(alpha = orbitAlpha), radius = s * 0.028f, center = Offset(c.x + orbitR * cos(a).toFloat(), c.y + orbitR * sin(a).toFloat()))
    }
    // pins: 3 per side, appearing one after another
    val pinLen = s * 0.1f; val pinW = s * 0.03f; val gap = dieSize / 4
    for (side in 0 until 4) for (k in 0 until 3) {
        val idx = side * 3 + k
        val a = ((assemble - 0.15f - idx * 0.025f) / 0.12f).coerceIn(0f, 1f)
        if (a <= 0f) continue
        val along = -dieSize / 2 + gap * (k + 1)
        val outer = dieSize / 2 + s * 0.035f
        val (tl, sz) = when (side) {
            0 -> Offset(c.x + along - pinW / 2, c.y - outer - pinLen) to Size(pinW, pinLen)
            1 -> Offset(c.x + along - pinW / 2, c.y + outer) to Size(pinW, pinLen)
            2 -> Offset(c.x - outer - pinLen, c.y + along - pinW / 2) to Size(pinLen, pinW)
            else -> Offset(c.x + outer, c.y + along - pinW / 2) to Size(pinLen, pinW)
        }
        drawRoundRect(Brand.accentStrong.copy(alpha = 0.9f * a), tl, sz, CornerRadius(pinW / 2))
    }
    // die
    scale(popScale, c) {
        val tl = Offset(c.x - dieSize / 2, c.y - dieSize / 2)
        drawRoundRect(Brush.linearGradient(listOf(Brand.die, Brand.dieEdge), tl, tl + Offset(dieSize, dieSize)), tl, Size(dieSize, dieSize), CornerRadius(dieSize * 0.22f), alpha = pop)
        drawRoundRect(Brand.accent.copy(alpha = pop), tl, Size(dieSize, dieSize), CornerRadius(dieSize * 0.22f), style = Stroke(width = s * 0.012f))
        // core: lights up after assembly, then breathes
        val on = ((assemble - 0.35f) / 0.2f).coerceIn(0f, 1f)
        val core = dieSize * 0.42f * (1f + 0.12f * pulse * on)
        drawCircle(Brand.accent.copy(alpha = 0.25f * on * (1 - pulse * 0.5f)), radius = core * (0.9f + 0.5f * pulse), center = c)
        drawRoundRect(Brand.accent.copy(alpha = 0.35f + 0.65f * on), Offset(c.x - core / 2, c.y - core / 2), Size(core, core), CornerRadius(core * 0.28f))
    }
}
