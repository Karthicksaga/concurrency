package com.localai.app.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tag
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.data.agents.AgentRepository
import com.localai.app.data.tools.Calculator
import com.localai.app.data.tools.Permission
import com.localai.app.data.tools.Risk
import com.localai.app.data.tools.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToolsViewModel @Inject constructor(val registry: ToolRegistry, agents: AgentRepository) : ViewModel() {
    val permissions = registry.permissions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val log = registry.auditLog.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val agents = agents.agents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folder = registry.folder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun set(id: String, p: Permission) = viewModelScope.launch { registry.setPermission(id, p) }
    fun setFolder(uri: android.net.Uri?) = viewModelScope.launch { registry.setFolder(uri) }
}

@Composable
fun ToolsScreen(nav: NavController, vm: ToolsViewModel = hiltViewModel()) {
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setFolder(it) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var smsGranted by remember { mutableStateOf(com.localai.app.data.tools.PhoneAccess.canReadSms(ctx)) }
    var contactsGranted by remember { mutableStateOf(com.localai.app.data.tools.PhoneAccess.canReadContacts(ctx)) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { smsGranted = it }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsGranted = it }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column { Text("Tool registry", style = MaterialTheme.typography.titleLarge); Text("${vm.registry.tools.size} built-in tools · typed and validated", fontSize = 12.sp, color = La.colors.text3) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Banner("Agents can only call these built-in tools. Each call is validated and logged. No tool runs code, uses the internet or changes permissions.", Tone.RUN)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Tools", "Audit log").forEachIndexed { i, l -> SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) } }
            }
            if (tab == 0) vm.registry.tools.forEach { t ->
                LaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Tag(t.risk.label, when (t.risk) { Risk.READ -> La.colors.knowledge; Risk.WRITE_LOCAL -> La.colors.agent; Risk.PERSONAL -> La.colors.warn; Risk.EXTERNAL -> La.colors.accent; Risk.DELEGATE -> La.colors.model })
                    }
                    Text(t.description, fontSize = 13.sp, color = La.colors.text2)
                    Text("Input: ${t.inputFormat}", fontSize = 12.sp, color = La.colors.text3, fontFamily = La.mono, modifier = Modifier.padding(top = 4.dp))
                    val used = agents.filter { t.id in it.toolList }
                    Text("Used by ${if (used.isEmpty()) "no agents" else used.joinToString { it.name }}", fontSize = 12.sp, color = La.colors.text3, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    if (t.risk != Risk.DELEGATE) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Permission.entries.forEachIndexed { i, p -> SegmentedButton(perms[t.id] == p, {
                            vm.set(t.id, p)
                            if (t.id == "sms_reader" && p != Permission.OFF && !smsGranted) smsPerm.launch(android.Manifest.permission.READ_SMS)
                        }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(p.label) } }
                    }
                    if (t.id == "sms_reader") {
                        Text(if (smsGranted) "SMS permission granted. Messages are read on the phone only." else "Needs SMS permission. Works in this test build; Google Play allows it only for SMS apps.",
                            fontSize = 12.sp, color = if (smsGranted) La.colors.ok else La.colors.warn, modifier = Modifier.padding(top = 6.dp))
                        if (!smsGranted) OutlinedButton(onClick = { smsPerm.launch(android.Manifest.permission.READ_SMS) }) { Text("Allow SMS access") }
                    }
                    if (t.id == "phone_call") {
                        Text(if (contactsGranted) "Contacts allowed: you can say “call Amma”." else "Works with numbers. Allow contacts to call people by name.",
                            fontSize = 12.sp, color = La.colors.text3, modifier = Modifier.padding(top = 6.dp))
                        if (!contactsGranted) OutlinedButton(onClick = { contactsPerm.launch(android.Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts") }
                    }
                    if (t.id == "file_search") {
                        Spacer(Modifier.height(8.dp))
                        Text(if (folder == null) "No folder granted yet." else "Folder granted: ${android.net.Uri.parse(folder).lastPathSegment}", fontSize = 12.sp, color = La.colors.text2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (folder == null) "Choose folder" else "Change folder") }
                            if (folder != null) OutlinedButton(onClick = { vm.setFolder(null) }) { Text("Revoke") }
                        }
                    }
                    if (t.id == "calculator") {
                        var expr by remember { mutableStateOf("(1250 * 12) * 0.18") }
                        var result by remember { mutableStateOf<String?>(null) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(expr, { expr = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Try it") })
                            FilledTonalButton(onClick = { result = runCatching { "= " + Calculator.format(Calculator.eval(expr)) }.getOrElse { "Error: ${it.message}" } }, Modifier.padding(start = 8.dp)) { Text("Run") }
                        }
                        result?.let { Text(it, fontFamily = La.mono, color = if (it.startsWith("=")) La.colors.ok else La.colors.bad, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            } else {
                if (log.isEmpty()) Text("No tool calls yet. Calls made by agents appear here with their input, result and whether you approved them.", fontSize = 13.sp, color = La.colors.text3)
                log.forEach { l ->
                    LaCard(padding = 12) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vm.registry.spec(l.toolId)?.name ?: l.toolId, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            StatusPill(when (l.status) { "OK" -> "OK"; "DENIED" -> "Declined"; "INVALID" -> "Rejected"; else -> "Error" },
                                when (l.status) { "OK" -> Tone.OK; "DENIED" -> Tone.WARN; else -> Tone.BAD })
                        }
                        Text(l.input, fontFamily = La.mono, fontSize = 12.sp, color = La.colors.text2)
                        Text("→ ${l.output.take(200)}", fontSize = 12.sp, color = La.colors.text3)
                        Text("${agents.firstOrNull { it.id == l.agentId }?.name ?: "Agent"} · ${l.durationMs} ms · ${fmtAgo(l.createdAt)}", fontSize = 11.sp, color = La.colors.text3)
                    }
                }
            }
        }
    }
}
