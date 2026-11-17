package com.localai.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.Eyebrow
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.SectionHeader
import com.localai.app.core.designsystem.StackBar
import com.localai.app.core.designsystem.StackSegment
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tag
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtGiB
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.catalog.MemoryModel
import com.localai.app.data.chat.ChatService
import com.localai.app.data.db.ChatDao
import com.localai.app.data.downloads.DownloadManager
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.feature.chat.ConversationRow
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.device.LiveStats
import com.localai.app.runtime.device.thermalLabel
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.ui.goTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceService: DeviceCapabilityService,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val chat: ChatService,
    settings: SettingsRepository,
    downloads: DownloadManager,
    dao: ChatDao,
) : ViewModel() {
    val device = deviceService.profile
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val engineState = engine.state
    val installed = models.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = downloads.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val recent = dao.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val live = MutableStateFlow<LiveStats?>(null)
    val error = MutableStateFlow<String?>(null)

    suspend fun pollLive() { while (true) { live.value = deviceService.live(); delay(2000) } }
    fun load() = viewModelScope.launch { error.value = models.ensureLoaded().exceptionOrNull()?.message }
    fun unload() = viewModelScope.launch { engine.unload() }
    suspend fun newChat() = chat.newConversation()
}

@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val eng by vm.engineState.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val dls by vm.downloads.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.pollLive() }
    val dev = vm.device
    val settings = s ?: return
    val policy = policyFor(settings, dev)
    val active = installed.firstOrNull { it.id == settings.activeModelId }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LOCAL AI", style = MaterialTheme.typography.headlineSmall)
                Text("Everything here runs on this phone", color = La.colors.text3, fontSize = 12.sp)
            }
            IconButton(onClick = { nav.navigate("monitor") }) { Icon(Icons.Outlined.MonitorHeart, "Performance", tint = La.colors.text2) }
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Outlined.Settings, "Settings", tint = La.colors.text2) }
        }

        LaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Active model"); Spacer(Modifier.weight(1f))
                when (val e = eng) {
                    is EngineState.Ready -> StatusPill("Loaded", Tone.OK)
                    is EngineState.Loading -> StatusPill("Loading…", Tone.RUN)
                    is EngineState.Failed -> StatusPill("Failed", Tone.BAD)
                    EngineState.Idle -> StatusPill(if (active != null) "Not loaded" else "None", Tone.PENDING)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (active == null) {
                Text("No model yet", style = MaterialTheme.typography.titleMedium)
                Text("Recommended for this phone: ${Catalog.byId(policy.defaults.recommendedModelId)?.name}", color = La.colors.text2, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { nav.goTab("models") }) { Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)); Text("  Get a model") }
            } else {
                val ready = eng as? EngineState.Ready
                Text(active.name, style = MaterialTheme.typography.titleMedium)
                Text("${active.quant} · ${(ready?.config?.contextLength ?: policy.defaults.contextLength) / 1024}K context · ${policy.threads} threads · batch ${policy.defaults.batchSize}",
                    color = La.colors.text2, fontSize = 12.5.sp)
                if (eng is EngineState.Loading) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.localai.app.core.designsystem.ModelLoadingMark(40.dp)
                        Text("  Loading into memory…", fontSize = 12.5.sp, color = La.colors.text2)
                    }
                }
                (eng as? EngineState.Failed)?.let { Spacer(Modifier.height(8.dp)); Text(it.message, color = La.colors.bad, fontSize = 12.5.sp) }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = La.colors.bad, fontSize = 12.5.sp) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ready != null) TextButton(onClick = { vm.unload() }) { Text("Unload") }
                    else if (eng !is EngineState.Loading) FilledTonalButton(onClick = { vm.load() }) { Text("Load") }
                    TextButton(onClick = { nav.navigate("model/${active.id}") }) { Text("Details") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.AutoMirrored.Outlined.Chat, "New chat", Modifier.weight(1f)) { scope.launch { nav.navigate("chat/${vm.newChat()}") } }
            QuickAction(Icons.Outlined.GraphicEq, "Talk", Modifier.weight(1f)) { scope.launch { nav.navigate("voice/${vm.newChat()}") } }
            QuickAction(Icons.Outlined.PhotoCamera, "Ask a photo", Modifier.weight(1f)) { scope.launch { nav.navigate("chat/${vm.newChat()}?attach=1") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Outlined.Description, "Documents", Modifier.weight(1f)) { nav.navigate("studio") }
            QuickAction(Icons.Outlined.AccountTree, "Workflow", Modifier.weight(1f)) { nav.navigate("workflow") }
            QuickAction(Icons.Outlined.Memory, "Models", Modifier.weight(1f)) { nav.goTab("models") }
        }

        LaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("This phone"); Spacer(Modifier.weight(1f))
                Tag(dev.tier.label, if (policy.lite) La.colors.knowledge else La.colors.model)
            }
            Spacer(Modifier.height(8.dp))
            val avail = (live?.availRamBytes ?: dev.availRamBytes) / (1024.0 * 1024 * 1024)
            val modelUse = (eng as? EngineState.Ready)?.let { r -> active?.let { MemoryModel.estimate(it.toSpec(), r.contextLength, r.config.kvQ8, r.config.batch).totalGiB } } ?: 0.0
            Text("${"%.1f".format(avail)} GB free of ${"%.1f".format(dev.totalRamGiB)} GB", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            StackBar(listOf(
                StackSegment((dev.totalRamGiB - avail - modelUse).coerceAtLeast(0.0), La.colors.text3, "Android & apps"),
                StackSegment(modelUse, La.colors.accent, "Model ≈ ${"%.1f".format(modelUse)} GB"),
                StackSegment(avail, La.colors.ok.copy(alpha = 0.6f), "Free"),
            ), dev.totalRamGiB)
            Spacer(Modifier.height(10.dp))
            FlowRowSimple {
                Tag("Model budget ${fmtGiB(dev.modelBudgetGiB)}")
                live?.let { Tag("Thermal: ${thermalLabel(it.thermalStatus)}"); if (it.batteryPercent >= 0) Tag("Battery ${it.batteryPercent}%${if (it.charging) " · charging" else ""}") }
            }
            if (policy.lite) { Spacer(Modifier.height(10.dp)); Banner("Lite mode: ${policy.defaults.contextLength}-token context, batch ${policy.defaults.batchSize}, up to ${policy.defaults.maxNewTokens} tokens per reply, model released ${policy.defaults.unloadAfterBackgroundMs / 1000}s after you leave the app.", Tone.RUN) }
        }

        val activeDls = dls.values.toList()
        if (activeDls.isNotEmpty()) LaCard(onClick = { nav.goTab("models") }) {
            Text("${activeDls.size} download${if (activeDls.size > 1) "s" else ""}", fontWeight = FontWeight.SemiBold)
            activeDls.forEach { d ->
                Spacer(Modifier.height(8.dp))
                Row { Text(Catalog.byId(d.modelId)?.name ?: d.modelId, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("${(d.progress * 100).toInt()}% · ${d.phase.name.lowercase()}", fontSize = 12.sp, color = La.colors.text3) }
                LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        SectionHeader("Recent conversations", "All") { nav.goTab("chats") }
        if (recent.isEmpty()) Text("Your chats will appear here.", color = La.colors.text2, fontSize = 13.sp)
        recent.take(4).forEach { c -> ConversationRow(c) { nav.navigate("chat/${c.id}") } }

    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    LaCard(modifier, onClick = onClick, padding = 12) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(La.colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = La.colors.accentStrong)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

