package com.localai.app.feature.models

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tag
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.VerdictPill
import com.localai.app.core.designsystem.fmtBytes
import com.localai.app.core.designsystem.fmtGiB
import com.localai.app.data.catalog.Verdict
import com.localai.app.data.downloads.DownloadPhase

@Composable
fun ModelsScreen(nav: NavController, vm: ModelsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    var filter by rememberSaveable { mutableStateOf("fits") }
    var confirm by remember { mutableStateOf<ModelRow?>(null) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }

    val download: (ModelRow) -> Unit = { row ->
        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (row.verdict == Verdict.NOT_RECOMMENDED) confirm = row else vm.download(row.spec)
    }
    val rows = ui.rows.filter {
        when (filter) { "fits" -> it.verdict != Verdict.NOT_RECOMMENDED || it.installed; "installed" -> it.installed || it.download != null; else -> true }
    }
    val dev = ui.device

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Models", style = MaterialTheme.typography.headlineSmall)
                if (dev != null) Text("${dev.tier.label} · ${fmtGiB(dev.modelBudgetGiB)} model budget", color = La.colors.text3, fontSize = 12.sp)
            }
            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.FileOpen, "Import a GGUF file", tint = La.colors.text2) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.policy?.lite == true) item {
                Banner("Lite mode is on. Showing models that fit a ${dev?.tier?.label ?: "small"} phone, with a ${ui.policy?.defaults?.contextLength}-token context and smaller batches to save memory.", Tone.RUN)
            }
            importing?.let { p -> item {
                LaCard { Text("Importing model…", fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp))
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("fits" to "Fits this phone", "installed" to "Installed", "all" to "All").forEach { (k, l) ->
                        FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(l) })
                    }
                }
            }
            items(rows, key = { it.spec.id }) { row ->
                ModelCard(row, onOpen = { nav.navigate("model/${row.spec.id}") }, onDownload = { download(row) },
                    onPause = { vm.pause(row.spec.id) }, onCancel = { vm.cancel(row.spec.id) }, onUse = { vm.activate(row.spec.id) })
            }
            if (rows.isEmpty()) item { Text("No models match this filter.", color = La.colors.text2, modifier = Modifier.padding(16.dp)) }
            item {
                Text("Model files come from Hugging Face and are checked against a SHA-256 hash before use. Licences vary per model; see each model's page.",
                    color = La.colors.text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    confirm?.let { row ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("This model may not run well") },
            text = { Text("${row.spec.name} needs about ${fmtGiB(row.estimateGiB)}, but this phone's model budget is ${fmtGiB(dev?.modelBudgetGiB ?: 0.0)}. It may be very slow or close unexpectedly. Download anyway?") },
            confirmButton = { TextButton(onClick = { vm.download(row.spec); confirm = null }) { Text("Download anyway") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
fun ModelCard(row: ModelRow, onOpen: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit, onUse: () -> Unit) {
    val s = row.spec
    LaCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${if (s.paramsB < 1) "${(s.paramsB * 1000).toInt()}M" else "%.1fB".format(s.paramsB)} params · ${s.quant} · ${s.arch}",
                    color = La.colors.text2, fontSize = 12.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            when {
                row.loaded -> StatusPill("Loaded", Tone.OK)
                row.active -> StatusPill("Active", Tone.RUN)
                row.installed -> FilledTonalButton(onClick = onUse, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Use") }
                row.download == null -> Button(onClick = onDownload, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(fmtBytes(s.sizeBytes))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRowSimple {
            VerdictPill(row.verdict)
            if (row.recommended) Tag("Recommended", La.colors.accent, Icons.Outlined.Star)
            Tag("≈ ${fmtGiB(row.estimateGiB)} in memory")
            if (!row.installed) Tag(fmtBytes(s.sizeBytes) + " download")
            s.tags.forEach { Tag(it, if (it == "Lite") La.colors.knowledge else null) }
        }
        row.download?.let { d ->
            Spacer(Modifier.height(12.dp))
            val label = when (d.phase) {
                DownloadPhase.QUEUED -> "Waiting for network…"
                DownloadPhase.RUNNING -> "${(d.progress * 100).toInt()}% · ${fmtBytes(d.bytes)} of ${fmtBytes(d.total)}" + if (d.speedBps > 0) " · ${"%.1f".format(d.speedBps / 1e6)} MB/s" else ""
                DownloadPhase.VERIFYING -> "Verifying checksum…"
                DownloadPhase.PAUSED -> "Paused at ${(d.progress * 100).toInt()}%"
                DownloadPhase.FAILED -> d.error ?: "Download failed"
            }
            if (d.phase == DownloadPhase.VERIFYING || d.phase == DownloadPhase.QUEUED) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth(),
                color = if (d.phase == DownloadPhase.PAUSED || d.phase == DownloadPhase.FAILED) La.colors.warn else La.colors.accent)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, color = if (d.phase == DownloadPhase.FAILED) La.colors.bad else La.colors.text2, modifier = Modifier.weight(1f))
                when (d.phase) {
                    DownloadPhase.RUNNING, DownloadPhase.QUEUED -> OutlinedButton(onClick = onPause, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Outlined.Pause, null, Modifier.size(16.dp)); Text("Pause") }
                    DownloadPhase.PAUSED, DownloadPhase.FAILED -> FilledTonalButton(onClick = onDownload, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)); Text("Resume") }
                    else -> Unit
                }
                if (d.phase != DownloadPhase.VERIFYING) IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel download", tint = La.colors.text3) }
            }
        }
    }
}

val buttonColorsDanger @Composable get() = ButtonDefaults.buttonColors(containerColor = La.colors.bad.copy(alpha = 0.15f), contentColor = La.colors.bad)
