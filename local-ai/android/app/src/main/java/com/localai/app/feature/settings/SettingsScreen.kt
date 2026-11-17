package com.localai.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    val email: com.localai.app.data.email.EmailService,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val device = device.profile
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun set(block: suspend SettingsRepository.() -> Unit) = viewModelScope.launch { repo.block() }

    // ---- text recognition language packs ----
    val ocrProgress = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    var ocrVersion by androidx.compose.runtime.mutableIntStateOf(0); private set
    var ocrError by androidx.compose.runtime.mutableStateOf<String?>(null)
    fun ocrInstalled(code: String) = com.localai.app.data.ocr.Ocr.isInstalled(context, code)
    fun ocrDownload(code: String) = viewModelScope.launch {
        ocrError = null; ocrProgress[code] = 0f
        com.localai.app.data.ocr.Ocr.download(context, code) { p -> ocrProgress[code] = p }
            .onSuccess { val s = repo.current(); repo.setOcrLanguages(s.ocrLanguages.split(',').filter { it.isNotBlank() } + code) }
            .onFailure { ocrError = it.message }
        ocrProgress.remove(code); ocrVersion++
    }
    fun ocrToggle(code: String, on: Boolean) = viewModelScope.launch {
        val cur = repo.current().ocrLanguages.split(',').filter { it.isNotBlank() }
        repo.setOcrLanguages(if (on) cur + code else cur - code)
    }
    fun ocrDelete(code: String) = viewModelScope.launch {
        com.localai.app.data.ocr.Ocr.delete(context, code); ocrToggle(code, false).join(); ocrVersion++
    }
    fun eraseAll(onDone: () -> Unit) = viewModelScope.launch {
        engine.unload()
        models.installed.first().forEach { models.delete(it.id) }
        models.modelsDir.listFiles()?.forEach { it.delete() }
        chats.deleteAllMessages(); chats.deleteAllConversations()
        listOf("chat_images", "exports").forEach { java.io.File(context.filesDir, it).deleteRecursively() }
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

        SectionHeader("Voice")
        LaCard {
            Text("Talk to the model and hear its answers. Speech is recognised on the phone when it has an offline speech pack " +
                "(Android settings › Speech recognition / Google › Offline speech); replies are spoken by the phone's text-to-speech voice.",
                fontSize = 12.5.sp, color = La.colors.text2)
            Spacer(Modifier.height(8.dp))
            Text("Language", fontWeight = FontWeight.Medium)
            FlowRowSimple {
                VOICE_LANGUAGES.forEach { (tag, label) ->
                    FilterChip(selected = settings.voiceLanguage == tag, onClick = { vm.set { setVoice(tag, settings.speechRate, settings.readAloud) } }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Speaking speed", fontWeight = FontWeight.Medium)
            FlowRowSimple {
                listOf(0.8f to "Slow", 1.0f to "Normal", 1.25f to "Fast", 1.5f to "Faster").forEach { (r, l) ->
                    FilterChip(selected = kotlin.math.abs(settings.speechRate - r) < 0.01f, onClick = { vm.set { setVoice(settings.voiceLanguage, r, settings.readAloud) } }, label = { Text(l) })
                }
            }
            Text("Interrupt by talking (voice mode)", fontWeight = FontWeight.Medium)
            FlowRowSimple {
                com.localai.app.data.voice.BargeInSensitivity.entries.forEach { b ->
                    FilterChip(selected = settings.bargeIn == b.name, onClick = { vm.set { setBargeIn(b.name) } }, label = { Text(b.label) })
                }
            }
            Text("Start talking while the assistant speaks and it stops, listens and answers you. Uses the phone's echo cancellation; earphones work best.", fontSize = 11.5.sp, color = La.colors.text3)
            ToggleRow("Read replies aloud", "Speak every answer in chat (you can also tap the speaker under any answer).", settings.readAloud) { v -> vm.set { setVoice(settings.voiceLanguage, settings.speechRate, v) } }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Text-to-speech settings") }
        }

        SectionHeader("Text recognition")
        LaCard {
            Text("Reads text in photos, screenshots and scanned PDFs on the phone (for chat, Scam check, Read it to me, Documents and Knowledge). English is built in; add the languages you read.",
                fontSize = 12.5.sp, color = La.colors.text2)
            val active = settings.ocrLanguages.split(',').filter { it.isNotBlank() }.toSet()
            @Suppress("UNUSED_VARIABLE") val v = vm.ocrVersion
            com.localai.app.data.ocr.OcrPacks.all.forEach { p ->
                val installed = vm.ocrInstalled(p.code)
                val prog = vm.ocrProgress[p.code]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(when { p.bundled -> "Built in"; prog != null -> "Downloading ${(prog * 100).toInt()}%"; installed -> "Installed · %.1f MB".format(p.sizeBytes / 1e6); else -> "%.1f MB download".format(p.sizeBytes / 1e6) },
                            fontSize = 12.sp, color = La.colors.text3)
                    }
                    when {
                        p.bundled -> Switch(checked = true, onCheckedChange = null, enabled = false)
                        prog != null -> androidx.compose.material3.CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(24.dp))
                        installed -> {
                            TextButton(onClick = { vm.ocrDelete(p.code) }) { Text("Remove", color = La.colors.text3) }
                            Switch(checked = p.code in active, onCheckedChange = { vm.ocrToggle(p.code, it) })
                        }
                        else -> TextButton(onClick = { vm.ocrDownload(p.code) }) { Text("Download") }
                    }
                }
            }
            vm.ocrError?.let { Text(it, color = La.colors.bad, fontSize = 12.sp) }
            Text("Packs come from the Tesseract project (pinned version, checksum-verified). Turn on only what you need: each extra language makes reading a little slower.", fontSize = 11.5.sp, color = La.colors.text3)
        }

        SectionHeader("Daily digest")
        LaCard {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var email by remember(settings.digestEmail) { mutableStateOf(settings.digestEmail) }
            fun save(on: Boolean = settings.digestEnabled, h: Int = settings.digestHour, m: Int = settings.digestMinute, e: String = email, sms: Boolean = settings.digestIncludeSms) =
                vm.set { setDigest(on, h, m, e, sms) }
            Text("Every day the phone summarises new documents, notes and workflows with the on-device model. You get a notification with an Email button that opens your email app pre-filled; nothing is sent until you press Send.",
                fontSize = 12.5.sp, color = La.colors.text2)
            ToggleRow("Daily digest", "At %02d:%02d every day".format(settings.digestHour, settings.digestMinute), settings.digestEnabled) { save(on = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    android.app.TimePickerDialog(ctx, { _, h, m -> save(h = h, m = m) }, settings.digestHour, settings.digestMinute, true).show()
                }) { Text("Change time") }
                OutlinedButton(onClick = { com.localai.app.data.automation.Automation.runDigestNow(ctx) }) { Text("Run now") }
            }
            OutlinedTextField(email, { email = it }, label = { Text("Email address (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { save() }) { Text("Save address") }
            Text("Runs only when the battery is not low. On small phones the model is released afterwards.", fontSize = 11.5.sp, color = La.colors.text3)
        }

        SectionHeader("Send the digest automatically")
        EmailCard(vm)

        SectionHeader("Folders")
        LaCard {
            ToggleRow("Sync linked folders", "Re-check folders linked to knowledge bases every 6 hours", settings.folderSync) { v -> vm.set { setFolderSync(v) } }
        }

        SectionHeader("Downloads & privacy")
        LaCard {
            ToggleRow("Download on Wi-Fi only", "Avoid using mobile data for large model files.", settings.wifiOnly) { v -> vm.set { setWifiOnly(v) } }
            KeyValue("Internet access", "Model and language-pack downloads (and your digest email, if turned on)")
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

/** Voice languages offered in Settings (BCP-47); availability depends on the phone's speech packs. */
val VOICE_LANGUAGES = listOf(
    "" to "Phone language", "en-US" to "English (US)", "en-IN" to "English (India)", "en-GB" to "English (UK)",
    "hi-IN" to "हिन्दी", "ta-IN" to "தமிழ்", "te-IN" to "తెలుగు", "kn-IN" to "ಕನ್ನಡ", "ml-IN" to "മലയാളം", "bn-IN" to "বাংলা", "mr-IN" to "मराठी",
    "es-ES" to "Español", "fr-FR" to "Français", "de-DE" to "Deutsch", "ar-SA" to "العربية", "zh-CN" to "中文", "ja-JP" to "日本語",
)

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.sp, color = La.colors.text3) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}


@Composable
private fun EmailCard(vm: SettingsViewModel) {
    val cfg by vm.email.config.collectAsStateWithLifecycle(com.localai.app.data.email.EmailConfig())
    var draft by remember(cfg) { mutableStateOf(cfg) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val preset = com.localai.app.data.email.SMTP_PRESETS.first { it.id == draft.presetId }
    suspend fun persist(): Boolean {
        val d = draft.copy(to = draft.to.ifBlank { draft.username })
        if (d.enabled && (d.username.isBlank() || (!d.hasPassword && password.isBlank()) || d.host.isBlank())) { status = "Fill in the email address and app password first."; return false }
        vm.email.save(d, password); password = ""; return true
    }
    LaCard {
        Text("Optional. The phone sends the digest itself through your email provider. It connects only to your mail server, only for this. " +
            "Your password is encrypted with the phone's hardware keystore and never leaves the phone.", fontSize = 12.5.sp, color = La.colors.text2)
        ToggleRow("Send automatically", if (cfg.ready) "To ${cfg.to} via ${cfg.host}" else "Set up your account below", draft.enabled) { draft = draft.copy(enabled = it) }
        FlowRowSimple {
            com.localai.app.data.email.SMTP_PRESETS.forEach { p ->
                FilterChip(draft.presetId == p.id, { draft = draft.copy(presetId = p.id, host = if (p.id == "custom") draft.host else p.host, port = p.port, security = p.security) }, { Text(p.name) })
            }
        }
        Text(preset.help, fontSize = 12.sp, color = La.colors.text3, modifier = Modifier.padding(vertical = 6.dp))
        if (draft.presetId == "custom") {
            OutlinedTextField(draft.host, { draft = draft.copy(host = it.trim()) }, label = { Text("SMTP server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.port.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(port = it) } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRowSimple { listOf(com.localai.app.data.email.Security.SSL, com.localai.app.data.email.Security.STARTTLS).forEach { sec ->
                FilterChip(draft.security == sec, { draft = draft.copy(security = sec) }, { Text(sec.label) }) } }
        }
        OutlinedTextField(draft.username, { draft = draft.copy(username = it.trim()) }, label = { Text("Your email address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(if (cfg.hasPassword) "App password (saved, type to replace)" else "App password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.to, { draft = draft.copy(to = it.trim()) }, label = { Text("Send to (default: yourself)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            androidx.compose.material3.Button(enabled = !busy, onClick = { scope.launch { if (persist()) status = "Saved." } }) { Text("Save") }
            OutlinedButton(enabled = !busy, onClick = { scope.launch {
                busy = true; status = "Sending test email…"
                if (persist()) { status = vm.email.send("LOCAL AI test email", "This is a test from LOCAL AI on your phone. Daily digests will arrive like this.")?.let { "Failed: $it" } ?: "Test email sent to ${draft.to.ifBlank { draft.username }}." }
                busy = false
            } }) { Text("Send test email") }
        }
        if (cfg.hasPassword) TextButton(onClick = { scope.launch { vm.email.forget(); status = "Email settings and password removed." } }) { Text("Forget account", color = La.colors.bad) }
        status?.let { Text(it, fontSize = 12.5.sp, color = if (it.startsWith("Failed") || it.startsWith("Fill")) La.colors.bad else La.colors.ok) }
    }
}
