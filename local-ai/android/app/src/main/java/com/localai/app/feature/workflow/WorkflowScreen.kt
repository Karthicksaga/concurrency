package com.localai.app.feature.workflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.SectionHeader
import com.localai.app.core.designsystem.Stat
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.data.agents.AgentRepository
import com.localai.app.data.db.RunStepEntity
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.data.workflow.WorkflowService
import com.localai.app.feature.chat.MarkdownText
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.LlamaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkflowViewModel @Inject constructor(
    savedState: SavedStateHandle, val service: WorkflowService, agents: AgentRepository, val engine: LlamaEngine,
    settings: SettingsRepository, device: DeviceCapabilityService,
) : ViewModel() {
    val shown = MutableStateFlow(savedState.get<String>("runId") ?: service.activeRunId.value)
    val agents = agents.agents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = service.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val run = shown.flatMapLatest { id -> id?.let { service.run(it) } ?: flowOf(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val steps = shown.flatMapLatest { id -> id?.let { service.steps(it) } ?: flowOf(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val live = service.live
    val lite = settings.settings.map { policyFor(it, device.profile).lite }.stateIn(viewModelScope, SharingStarted.Eagerly, device.profile.tier.isLite)
    fun start(request: String, agentIds: List<String>): Boolean { val id = service.start(request, agentIds, null) ?: return false; shown.value = id; return true }
}

private data class NodePos(val step: RunStepEntity?, val label: String, val sub: String, val x: Float, val y: Float)

@Composable
fun WorkflowScreen(nav: NavController, vm: WorkflowViewModel = hiltViewModel()) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val eng by vm.engine.state.collectAsStateWithLifecycle()
    val lite by vm.lite.collectAsStateWithLifecycle()
    var request by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var detail by remember { mutableStateOf<RunStepEntity?>(null) }
    var busyMsg by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run?.status) { while (run?.status == "RUNNING") { now = System.currentTimeMillis(); delay(1000) } }
    val running = run?.status == "RUNNING"

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Workflow", style = MaterialTheme.typography.titleLarge)
                Text("Supervisor · ${when (run?.status) { null -> "new run"; "RUNNING" -> "running"; "COMPLETED" -> "completed"; "PARTIAL" -> "partial result"; "CANCELLED" -> "cancelled"; else -> "failed" }}",
                    fontSize = 12.sp, color = La.colors.text3)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!running) LaCard {
                Text("New request", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(request, { request = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2, maxLines = 5,
                    placeholder = { Text("e.g. Summarise my lease and draft an email asking about the deposit") })
                Text("Agents (none selected = all)", fontSize = 12.sp, color = La.colors.text3, modifier = Modifier.padding(top = 8.dp))
                if (agents.isEmpty()) Text("No agents yet — a single general assistant will handle the request. Create agents in Agent Studio for real delegation.", fontSize = 12.sp, color = La.colors.warn)
                FlowRowSimple { agents.forEach { a -> FilterChip(a.id in selected, { selected = if (a.id in selected) selected - a.id else selected + a.id }, { Text(a.name) }) } }
                if (lite) Text("Lite mode: at most 3 tasks, no review step, shorter outputs, no tools inside workflows.", fontSize = 11.5.sp, color = La.colors.text3)
                Button(enabled = request.isNotBlank(), onClick = { busyMsg = if (vm.start(request.trim(), selected.toList())) null else "Another workflow is still running." },
                    modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Text(" Run") }
                busyMsg?.let { Text(it, color = La.colors.bad, fontSize = 12.sp) }
            }
            run?.let { r ->
                LaCard(padding = 12) { Text("REQUEST", fontSize = 11.sp, color = La.colors.text3, fontWeight = FontWeight.SemiBold); Text(r.request, fontSize = 14.sp) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) OutlinedButton(onClick = { vm.service.cancel() }) { Text("Cancel", color = La.colors.bad) }
                    steps.firstOrNull { it.kind == "TASK" && it.state == "FAILED" }?.takeIf { !running }?.let { f ->
                        FilledTonalButton(onClick = { vm.service.retry(r.id, f.id) }) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Text(" Retry ${f.title}") }
                    }
                    if (!running) TextButton(onClick = { request = r.request }) { Text("Edit & run again") }
                }
                FlowRowSimple { listOf("Pending" to Tone.PENDING, "Running" to Tone.RUN, "Completed" to Tone.OK, "Failed" to Tone.BAD, "Skipped" to Tone.WARN).forEach { (l, t) -> StatusPill(l, t) } }
                val holder = steps.firstOrNull { it.state == "RUNNING" }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(La.colors.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (holder != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  ${(eng as? EngineState.Ready)?.description?.substringBefore(' ')?.let { "Model ($it)" } ?: "One shared model"} · " +
                        (holder?.let { "serving ${it.title}" + (live?.phase?.takeIf { p -> p.isNotBlank() }?.let { p -> " · $p" } ?: "") } ?: "idle"), fontSize = 12.5.sp)
                }
                Graph(steps, r.status) { detail = it }
                val tasks = steps.filter { it.kind == "TASK" }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Tasks", "${tasks.count { it.state == "COMPLETED" }}/${tasks.size}", Modifier.weight(1f))
                    Stat("Tokens", "${if (r.tokens > 0) r.tokens else steps.sumOf { it.tokens }}", Modifier.weight(1f))
                    Stat("Time", "${(((r.finishedAt ?: now) - r.startedAt) / 1000)} s", Modifier.weight(1f))
                }
                live?.takeIf { it.runId == r.id && it.text.isNotBlank() }?.let { l ->
                    LaCard(padding = 12) {
                        Text("Writing: ${steps.firstOrNull { it.id == l.stepId }?.title ?: ""}", fontSize = 12.sp, color = La.colors.text3)
                        Text(l.text.takeLast(600) + " ▍", fontSize = 13.sp)
                    }
                }
                r.finalAnswer?.let { a -> SectionHeader("Final answer"); LaCard { MarkdownText(a) } }
                r.error?.let { Banner(it, Tone.BAD) }
                SectionHeader("Steps")
                steps.forEach { s ->
                    LaCard(onClick = { detail = s }, padding = 12) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            stepPill(s.state, s.attempt)
                        }
                        Text(s.instruction, fontSize = 12.5.sp, color = La.colors.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (s.output.isNotBlank()) Text(s.output.take(160), fontSize = 12.sp, color = La.colors.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (history.isNotEmpty()) {
                SectionHeader("History")
                history.take(10).forEach { h ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.shown.value = h.id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.request, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Text(" ${fmtAgo(h.startedAt)} ", fontSize = 11.sp, color = La.colors.text3)
                        stepPill(when (h.status) { "PARTIAL" -> "SKIPPED"; "COMPLETED" -> "COMPLETED"; "RUNNING" -> "RUNNING"; "CANCELLED" -> "CANCELLED"; else -> "FAILED" }, 1)
                    }
                }
            }
        }
    }
    detail?.let { s ->
        val fresh = steps.firstOrNull { it.id == s.id } ?: s
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(fresh.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stepPill(fresh.state, fresh.attempt)
                Text("Instruction", fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(fresh.instruction, fontSize = 13.sp)
                if (fresh.dependsOn.isNotBlank()) Text("Uses results from: " + fresh.dependsOn.split(',').mapNotNull { d -> steps.firstOrNull { it.id == d }?.title }.joinToString(), fontSize = 12.sp, color = La.colors.text3)
                Text("Output", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (fresh.output.isBlank()) Text(if (fresh.state == "RUNNING") "Working…" else "No output.", fontSize = 13.sp, color = La.colors.text3) else MarkdownText(fresh.output)
                fresh.error?.let { Text(it, color = La.colors.bad, fontSize = 12.sp) }
            } },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
}

@Composable
private fun stepPill(state: String, attempt: Int) = when (state) {
    "COMPLETED" -> StatusPill("Completed", Tone.OK); "RUNNING" -> StatusPill(if (attempt > 1) "Retrying" else "Running", Tone.RUN)
    "FAILED" -> StatusPill("Failed", Tone.BAD); "SKIPPED" -> StatusPill("Skipped", Tone.WARN); "CANCELLED" -> StatusPill("Cancelled", Tone.WARN)
    else -> StatusPill("Pending", Tone.PENDING)
}

/** Layered DAG: plan → tasks by dependency depth → review → aggregate → final. Edges animate while data flows. */
@Composable
private fun Graph(steps: List<RunStepEntity>, runStatus: String, onTap: (RunStepEntity) -> Unit) {
    if (steps.isEmpty()) return
    val byId = steps.associateBy { it.id }
    val depth = HashMap<String, Int>()
    fun d(s: RunStepEntity): Int = depth.getOrPut(s.id) {
        when (s.kind) {
            "PLAN" -> 0
            "TASK" -> 1 + (s.dependsOn.split(',').mapNotNull { byId[it] }.filter { it.kind == "TASK" }.maxOfOrNull { d(it) } ?: 0)
            else -> 0
        }
    }
    steps.filter { it.kind != "REVIEW" && it.kind != "AGGREGATE" }.forEach { d(it) }
    var maxTask = depth.values.maxOrNull() ?: 0
    steps.firstOrNull { it.kind == "REVIEW" }?.let { depth[it.id] = ++maxTask }
    steps.firstOrNull { it.kind == "AGGREGATE" }?.let { depth[it.id] = ++maxTask }
    val layers = steps.groupBy { depth[it.id] ?: 0 }.toSortedMap()
    val finalLayer = maxTask + 1
    val rowH = 84.dp; val nodeW = 140.dp; val nodeH = 52.dp
    val c = La.colors
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).border(1.dp, c.line, RoundedCornerShape(20.dp))
        .horizontalScroll(rememberScrollState())) {
        val maxPerLayer = layers.values.maxOf { it.size }
        val width = maxOf(maxWidth, nodeW * maxPerLayer + 24.dp * (maxPerLayer + 1))
        val height = rowH * (finalLayer + 1) + 16.dp
        val density = LocalDensity.current
        val pos = HashMap<String, Pair<Float, Float>>()   // center in dp
        layers.forEach { (layer, list) ->
            list.forEachIndexed { i, s -> pos[s.id] = (width.value * (i + 1) / (list.size + 1)) to (rowH.value * layer + 12 + nodeH.value / 2) }
        }
        val finalPos = width.value / 2 to (rowH.value * finalLayer + 12 + nodeH.value / 2)
        val agg = steps.firstOrNull { it.kind == "AGGREGATE" }
        Box(Modifier.width(width).height(height)) {
            Canvas(Modifier.width(width).height(height)) {
                fun edge(a: Pair<Float, Float>, b: Pair<Float, Float>, color: Color, dashed: Boolean) {
                    val (x1, y1) = with(density) { a.first.dp.toPx() to (a.second + nodeH.value / 2).dp.toPx() }
                    val (x2, y2) = with(density) { b.first.dp.toPx() to (b.second - nodeH.value / 2).dp.toPx() }
                    val p = Path().apply { moveTo(x1, y1); cubicTo(x1, (y1 + y2) / 2, x2, (y1 + y2) / 2, x2, y2) }
                    drawPath(p, color, style = Stroke(width = 3f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
                }
                fun colorFor(target: RunStepEntity?, source: RunStepEntity?) = when {
                    target?.state == "COMPLETED" -> c.ok
                    target?.state == "RUNNING" -> c.accent
                    target?.state == "SKIPPED" || source?.state == "FAILED" -> c.bad.copy(alpha = 0.6f)
                    else -> c.lineStrong
                }
                steps.forEach { s ->
                    val parents = when (s.kind) {
                        "TASK" -> s.dependsOn.split(',').mapNotNull { byId[it] }.ifEmpty { steps.filter { it.kind == "PLAN" } }
                        "REVIEW" -> steps.filter { it.kind == "TASK" }
                        "AGGREGATE" -> steps.filter { it.kind == "REVIEW" }.ifEmpty { steps.filter { it.kind == "TASK" } }
                        else -> emptyList()
                    }
                    val to = pos[s.id] ?: return@forEach
                    parents.forEach { p -> pos[p.id]?.let { from -> edge(from, to, colorFor(s, p), s.state == "RUNNING") } }
                }
                agg?.let { a -> pos[a.id]?.let { edge(it, finalPos, if (runStatus == "COMPLETED" || runStatus == "PARTIAL") c.ok else c.lineStrong, false) } }
            }
            steps.forEach { s ->
                val (x, y) = pos[s.id] ?: return@forEach
                Node(s.title, nodeSub(s), s.state, Modifier.offset((x - nodeW.value / 2).dp, (y - nodeH.value / 2).dp).width(nodeW).height(nodeH)) { onTap(s) }
            }
            Node("Final response", if (runStatus == "COMPLETED") "Delivered" else if (runStatus == "PARTIAL") "Partial" else "Waiting",
                if (runStatus == "COMPLETED" || runStatus == "PARTIAL") "COMPLETED" else if (runStatus == "RUNNING") "PENDING" else runStatus.let { if (it == "CANCELLED") "CANCELLED" else "FAILED" },
                Modifier.offset((finalPos.first - nodeW.value / 2).dp, (finalPos.second - nodeH.value / 2).dp).width(nodeW).height(nodeH)) { }
        }
    }
}

private fun nodeSub(s: RunStepEntity) = when (s.kind) { "PLAN" -> "Planner"; "REVIEW" -> "Review"; "AGGREGATE" -> "Aggregator"; else -> s.instruction }

@Composable
private fun Node(title: String, sub: String, state: String, modifier: Modifier, onTap: () -> Unit) {
    val c = La.colors
    val tone = when (state) { "COMPLETED" -> c.ok; "RUNNING" -> c.accent; "FAILED" -> c.bad; "SKIPPED", "CANCELLED" -> c.warn; else -> c.lineStrong }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(if (state == "RUNNING") c.accentSoft else if (state == "FAILED") c.bad.copy(alpha = 0.12f) else c.surface2)
        .border(1.5.dp, tone, RoundedCornerShape(12.dp)).clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone)); Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(sub, fontSize = 10.sp, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

