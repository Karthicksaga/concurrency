package com.localai.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.BuildConfig
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.KeyValue
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.SectionHeader
import com.localai.app.data.db.ChatDao
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.DEFAULT_SYSTEM_PROMPT
import com.localai.app.data.settings.LiteMode
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.ThemeMode
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.LlamaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val repo: SettingsRepository,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val chats: ChatDao,
    device: DeviceCapabilityService,
) : ViewModel() {
    val device = device.profile
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun set(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { repo.block() }
    fun eraseAll(onDone: () -> Unit) = viewModelScope.launch {
        engine.unload()
        models.installed.first().forEach { models.delete(it.id) }
        models.modelsDir.listFiles()?.forEach { it.delete() }
        chats.deleteAllMessages(); chats.deleteAllConversations()
        repo.clearAll()
        onDone()
    }
}

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val settings = s ?: return
    val policy = policyFor(settings, vm.device)
    var erase by remember { mutableStateOf(false) }
    var prompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionHeader("Lite mode")
        LaCard {
            Text("For 4 GB and 6 GB phones. Detected: ${vm.device.tier.label}.", fontSize = 13.sp, color = La.colors.text2)
            Spacer(Modifier.height(8.dp))
            FlowRowSimple { LiteMode.entries.forEach { m -> FilterChip(selected = settings.liteMode == m, onClick = { vm.set { setLiteMode(m) } }, label = { Text(m.label) }) } }
            Spacer(Modifier.height(10.dp))
            Text(if (policy.lite) "Lite mode is ON" else "Lite mode is off", fontWeight = FontWeight.SemiBold)
            KeyValue("Default context", "${policy.defaults.contextLength} tokens (max ${policy.defaults.maxContext})")
            KeyValue("Batch size", "${policy.defaults.batchSize}")
            KeyValue("Threads", "${policy.threads}")
            KeyValue("Max reply length", "${policy.defaults.maxNewTokens} tokens")
            KeyValue("Release model in background", "after ${policy.defaults.unloadAfterBackgroundMs / 1000} s")
            Text("Changes apply the next time a model is loaded.", fontSize = 11.5.sp, color = La.colors.text3)
        }

        SectionHeader("Inference")
        LaCard {
            Text("CPU threads", fontWeight = FontWeight.Medium)
            FlowRowSimple { listOf(0, 2, 4, 6, 8).forEach { n -> FilterChip(selected = settings.threads == n, onClick = { vm.set { setThreads(n) } }, label = { Text(if (n == 0) "Auto (${policy.threads})" else "$n") }) } }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Compressed context cache (q8_0)", "Halves context memory with negligible quality loss.", settings.kvQ8) { v -> vm.set { setKvQ8(v) } }
            ToggleRow("Show reasoning", "Let thinking models (e.g. Qwen3) reason before answering. Slower.", settings.showReasoning) { v -> vm.set { setShowReasoning(v) } }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Default system prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            Row { TextButton(onClick = { vm.set { setSystemPrompt(prompt) } }) { Text("Save prompt") }; TextButton(onClick = { prompt = DEFAULT_SYSTEM_PROMPT; vm.set { setSystemPrompt(DEFAULT_SYSTEM_PROMPT) } }) { Text("Reset") } }
        }

        SectionHeader("Downloads & privacy")
        LaCard {
            ToggleRow("Download on Wi-Fi only", "Avoid using mobile data for large model files.", settings.wifiOnly) { v -> vm.set { setWifiOnly(v) } }
            KeyValue("Internet access", "Model downloads only")
            KeyValue("Analytics", "None")
            KeyValue("Cloud backup", "Disabled")
        }

        SectionHeader("Appearance")
        LaCard { FlowRowSimple { ThemeMode.entries.forEach { t -> FilterChip(selected = settings.theme == t, onClick = { vm.set { setTheme(t) } }, label = { Text(t.label) }) } } }

        SectionHeader("About")
        LaCard {
            KeyValue("Version", BuildConfig.VERSION_NAME)
            KeyValue("Runtime", "llama.cpp b12100 (CPU)")
            KeyValue("Build", "Test build · not from Play Store")
        }
        OutlinedButton(onClick = { erase = true }, modifier = Modifier.fillMaxWidth()) { Text("Erase all models and chats", color = La.colors.bad) }
        Spacer(Modifier.height(24.dp))
    }
    if (erase) AlertDialog(onDismissRequest = { erase = false }, title = { Text("Erase everything?") },
        text = { Text("All downloaded models, chats and settings are permanently deleted from this phone.") },
        confirmButton = { TextButton(onClick = { erase = false; vm.eraseAll { nav.navigate("onboarding") { popUpTo(0) } } }) { Text("Erase", color = La.colors.bad) } },
        dismissButton = { TextButton(onClick = { erase = false }) { Text("Cancel") } })
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.sp, color = La.colors.text3) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
