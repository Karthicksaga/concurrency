package com.localai.app.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.KeyValue
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.StackBar
import com.localai.app.core.designsystem.StackSegment
import com.localai.app.core.designsystem.Tag
import com.localai.app.core.designsystem.VerdictPill
import com.localai.app.core.designsystem.fmtBytes
import com.localai.app.core.designsystem.fmtGiB
import com.localai.app.data.catalog.MemoryModel
import com.localai.app.data.catalog.Verdict
import com.localai.app.data.settings.ModelProfile
import com.localai.app.runtime.inference.EngineState
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun ModelDetailScreen(nav: NavController, vm: ModelsViewModel = hiltViewModel()) {
    val id = nav.currentBackStackEntry?.arguments?.getString("id") ?: return
    val ui by vm.ui.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    val row = ui.rows.firstOrNull { it.spec.id == id } ?: return
    val policy = ui.policy ?: return
    val dev = ui.device ?: return
    val spec = row.spec
    val defaultCtx = minOf(policy.defaults.contextLength, spec.ctxTrain)
    val profileFlow = remember(id, defaultCtx) { vm.profile(id, defaultCtx) }
    val saved by profileFlow.collectAsStateWithLifecycle(ModelProfile(defaultCtx, 0.7f, 0.9f))
    val maxCtx = minOf(spec.ctxTrain, policy.defaults.maxContext)
    var ctx by remember(saved, maxCtx) { mutableIntStateOf(saved.contextLength.coerceIn(512, maxCtx)) }
    var temp by remember(saved) { mutableFloatStateOf(saved.temperature) }
    var topP by remember(saved) { mutableFloatStateOf(saved.topP) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmForce by remember { mutableStateOf(false) }
    val est = MemoryModel.estimate(spec, ctx, ui.settings.kvQ8, policy.defaults.batchSize)
    val verdict = MemoryModel.verdict(spec, ctx, ui.settings.kvQ8, dev, policy.defaults.batchSize)
    val loading = (ui.engine as? EngineState.Loading)?.modelId == id

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(spec.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text("${spec.family} · ${spec.quant}", color = La.colors.text3, fontSize = 12.sp)
            }
            if (row.installed) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete model", tint = La.colors.text2) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRowSimple { spec.tags.forEach { Tag(it, La.colors.model) } }
            if (spec.description.isNotBlank()) Text(spec.description, color = La.colors.text2)
            LaCard {
                KeyValue("Parameters", if (spec.paramsB < 1) "${(spec.paramsB * 1000).toInt()}M" else "%.1fB".format(spec.paramsB))
                KeyValue("Quantisation", spec.quant)
                KeyValue("File size", fmtBytes(spec.totalBytes))
                KeyValue("Architecture", spec.arch)
                KeyValue("Trained context", "${spec.ctxTrain / 1024}K")
                KeyValue("Licence", spec.license)
            }
            LaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On this ${dev.tier.label} phone", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    VerdictPill(verdict)
                }
                Spacer(Modifier.height(4.dp))
                Text(when (verdict) {
                    Verdict.RUNS_WELL -> "Fits comfortably in the model budget with room for other apps."
                    Verdict.TIGHT_FIT -> "Fits, but Android may close background apps. A shorter context helps."
                    Verdict.NOT_RECOMMENDED -> "Needs more memory than this phone can safely give a model. Choose a smaller model."
                }, color = La.colors.text2, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row { Text("Estimated memory at ${ctx / 1024}K context", color = La.colors.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(fmtGiB(est.totalGiB), fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(8.dp))
                StackBar(listOf(
                    StackSegment(est.weightsGiB, La.colors.model, "Weights ${"%.2f".format(est.weightsGiB)}"),
                    StackSegment(est.kvGiB, La.colors.agent, "KV cache ${"%.2f".format(est.kvGiB)}"),
                    StackSegment(est.computeGiB + est.overheadGiB, La.colors.text3, "Compute ${"%.2f".format(est.computeGiB + est.overheadGiB)}"),
                ), maxOf(dev.modelBudgetGiB, est.totalGiB) * 1.05)
                Text("Model budget ${fmtGiB(dev.modelBudgetGiB)} · estimate, not a measurement", color = La.colors.text3, fontSize = 11.sp)
            }
            LaCard {
                Text("Inference settings", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                val steps = log2(maxCtx / 512.0).roundToInt().coerceAtLeast(1)
                LabeledSlider("Context length", "$ctx tokens", log2(ctx / 512.0).toFloat(), 0f..steps.toFloat(), steps - 1) { v ->
                    ctx = (512 * 2.0.pow(v.roundToInt())).toInt().coerceAtMost(maxCtx)
                }
                if (policy.lite) Text("Lite mode limits context to ${policy.defaults.maxContext} tokens.", color = La.colors.text3, fontSize = 11.5.sp)
                LabeledSlider("Temperature", "%.2f".format(temp), temp, 0f..1.5f, 29) { temp = it }
                LabeledSlider("Top-p", "%.2f".format(topP), topP, 0.1f..1f, 17) { topP = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.saveProfile(id, ModelProfile(ctx, temp, topP)) }) { Text("Save") }
                    TextButton(onClick = { vm.resetProfile(id) }) { Text("Reset") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (row.installed && spec.vision && vm.missingProjector(id)) {
            OutlinedButton(enabled = row.download == null, onClick = { vm.download(spec) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(if (row.download == null) "Add image support (${fmtBytes(spec.projectorSize)})" else "Downloading image support… ${(row.download!!.progress * 100).toInt()}%")
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                !row.installed && row.download == null -> Button(onClick = { vm.download(spec) }, Modifier.fillMaxWidth()) { Text("Download ${fmtBytes(spec.totalBytes)}") }
                !row.installed -> OutlinedButton(onClick = { nav.popBackStack() }, Modifier.fillMaxWidth()) { Text("Downloading… ${(row.download!!.progress * 100).toInt()}%") }
                row.loaded -> {
                    OutlinedButton(onClick = { vm.unload() }, Modifier.weight(1f)) { Text("Unload") }
                    Button(onClick = { vm.activate(id) }, Modifier.weight(1f)) { Text("Reload") }
                }
                else -> Button(enabled = !loading, onClick = { if (verdict == Verdict.NOT_RECOMMENDED) confirmForce = true else vm.activate(id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "Loading…" else "Load and use")
                }
            }
        }
    }
    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${spec.name}?") },
        text = { Text("Frees ${fmtBytes(spec.totalBytes)}. Chats are kept.") },
        confirmButton = { TextButton(onClick = { vm.delete(id); confirmDelete = false; nav.popBackStack() }) { Text("Delete", color = La.colors.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    if (confirmForce) AlertDialog(onDismissRequest = { confirmForce = false }, title = { Text("Load anyway?") },
        text = { Text("This model is likely too large for this phone and may be very slow or be closed by Android.") },
        confirmButton = { TextButton(onClick = { vm.activate(id, force = true); confirmForce = false }) { Text("Load anyway") } },
        dismissButton = { TextButton(onClick = { confirmForce = false }) { Text("Cancel") } })
}

@Composable
fun LabeledSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(value, fontSize = 12.sp, fontFamily = La.mono, color = La.colors.text2) }
        Slider(value = v, onValueChange = onChange, valueRange = range, steps = steps.coerceAtLeast(0))
    }
}
