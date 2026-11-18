package com.localai.app.feature.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.localai.app.core.designsystem.KeyValue
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.Stat
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtBytes
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.device.LiveStats
import com.localai.app.runtime.device.thermalLabel
import com.localai.app.runtime.inference.LlamaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class MonitorViewModel @Inject constructor(val device: DeviceCapabilityService, val engine: LlamaEngine) : ViewModel()

@Composable
fun MonitorScreen(nav: NavController, vm: MonitorViewModel = hiltViewModel()) {
    val stats by vm.engine.lastStats.collectAsStateWithLifecycle()
    val generating by vm.engine.generating.collectAsStateWithLifecycle()
    var live by remember { mutableStateOf<LiveStats?>(null) }
    val freeHistory = remember { mutableStateListOf<Float>() }
    val dev = vm.device.profile
    LaunchedEffect(Unit) {
        while (true) {
            val l = vm.device.live(); live = l
            freeHistory.add((l.availRamBytes / (1024.0 * 1024 * 1024)).toFloat()); if (freeHistory.size > 60) freeHistory.removeAt(0)
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column { Text("Performance", style = MaterialTheme.typography.titleLarge); Text("${dev.manufacturer} ${dev.model} · ${dev.tier.label}", fontSize = 12.sp, color = La.colors.text3) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Last generation", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (generating) StatusPill("Generating", Tone.RUN)
                }
                val s = stats
                if (s == null) Text("Send a chat message to measure real speed on this phone.", color = La.colors.text2, fontSize = 13.sp)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Stat("Generate", "%.1f tok/s".format(s.genTps), Modifier.weight(1f))
                    Stat("Prompt", "%.0f tok/s".format(s.promptTps), Modifier.weight(1f))
                    Stat("Reused", "${s.reusedTokens} tok", Modifier.weight(1f))
                }
            }
            LaCard {
                Text("Free memory (last 60 s)", fontWeight = FontWeight.SemiBold)
                val accent = La.colors.accent; val grid = La.colors.line
                Canvas(Modifier.fillMaxWidth().height(80.dp).padding(top = 10.dp)) {
                    val max = dev.totalRamGiB.toFloat()
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
                    if (freeHistory.size > 1) {
                        val p = Path()
                        freeHistory.forEachIndexed { i, v ->
                            val x = i * size.width / 59f; val y = size.height - (v / max) * size.height
                            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        drawPath(p, accent, style = Stroke(width = 4f))
                    }
                }
                live?.let { l ->
                    KeyValue("Free now", fmtBytes(l.availRamBytes))
                    KeyValue("This app (PSS)", fmtBytes(l.appPssBytes))
                }
            }
            live?.let { l ->
                LaCard {
                    KeyValue("Thermal status", thermalLabel(l.thermalStatus))
                    l.thermalHeadroom?.let { KeyValue("Thermal headroom (10 s)", "%.2f".format(it)) }
                    KeyValue("Battery", if (l.batteryPercent >= 0) "${l.batteryPercent}%${if (l.charging) " · charging" else ""}" else "Unknown")
                    KeyValue("Power saver", if (l.powerSave) "On" else "Off")
                }
            }
            LaCard {
                Text("Hardware", fontWeight = FontWeight.SemiBold)
                KeyValue("Processor", dev.socModel)
                KeyValue("Cores", "${dev.cpuCores} (${dev.performanceCores} used for inference)")
                KeyValue("CPU features", dev.cpuFeatures.joinToString(" · ").ifBlank { "baseline" })
                if (vm.engine.systemInfo.isNotBlank()) Text(vm.engine.systemInfo, fontFamily = La.mono, fontSize = 10.5.sp, color = La.colors.text3, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
