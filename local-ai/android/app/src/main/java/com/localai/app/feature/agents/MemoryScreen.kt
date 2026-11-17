package com.localai.app.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.core.designsystem.AgentAvatar
import com.localai.app.core.designsystem.Banner
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.SectionHeader
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.data.agents.AgentRepository
import com.localai.app.data.agents.MemoryService
import com.localai.app.data.db.MemoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(savedState: SavedStateHandle, val repo: AgentRepository, private val memory: MemoryService) : ViewModel() {
    val selected = MutableStateFlow(savedState.get<String>("agentId") ?: "none")
    val agents = repo.agents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items = selected.flatMapLatest { memory.observe(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun approve(m: MemoryEntity) = viewModelScope.launch { memory.approve(m) }
    fun delete(m: MemoryEntity) = viewModelScope.launch { memory.delete(m.id) }
    fun update(m: MemoryEntity) = viewModelScope.launch { memory.update(m) }
    fun forgetAll() = viewModelScope.launch { memory.forgetAll(selected.value) }
    fun add(text: String, kind: String) = viewModelScope.launch { memory.add(selected.value, kind, text, "ACTIVE", "Added by you") }
    fun setEnabled(on: Boolean) = viewModelScope.launch { repo.get(selected.value)?.let { repo.save(it.copy(memoryEnabled = on, memoryAutoSave = on && it.memoryAutoSave)) } }
}

@Composable
fun MemoryScreen(nav: NavController, vm: MemoryViewModel = hiltViewModel()) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == sel } ?: agents.firstOrNull()?.also { vm.selected.value = it.id }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) { Text("Agent memory", style = MaterialTheme.typography.titleLarge); Text("Stored only on this phone", fontSize = 12.sp, color = La.colors.text3) }
            IconButton(onClick = { explain = true }) { Icon(Icons.Outlined.Info, "How memory works", tint = La.colors.text2) }
        }
        if (agent == null) { Banner("Create an agent first. Memory belongs to agents.", Tone.PENDING); return@Column }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            agents.forEach { a -> FilterChip(a.id == agent.id, { vm.selected.value = a.id }, { Text(a.name) }) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(agent.icon, agent.color, 32.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(agent.name, fontWeight = FontWeight.SemiBold)
                        Text("${items.count { it.status == "ACTIVE" }} remembered · ${items.count { it.status == "PROPOSED" }} proposed · ${if (agent.memoryAutoSave) "auto-save" else "approval required"}", fontSize = 12.sp, color = La.colors.text3)
                    }
                    Switch(agent.memoryEnabled, { vm.setEnabled(it) })
                }
            }
            if (!agent.memoryEnabled) Banner("Memory is off. ${agent.name} won't remember anything between chats. Existing items stay until you delete them.", Tone.WARN)
            val inbox = items.filter { it.status == "PROPOSED" }
            if (inbox.isNotEmpty()) {
                SectionHeader("Inbox · needs your approval")
                inbox.forEach { m ->
                    LaCard(padding = 12) {
                        Row { KindTag(m.kind); Spacer(Modifier.size(8.dp)); Text(m.content, fontSize = 14.sp, modifier = Modifier.weight(1f)) }
                        Text("From ${m.source ?: "a recent chat"}", fontSize = 11.5.sp, color = La.colors.text3, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.delete(m) }) { Text("Discard") }
                            FilledTonalButton(onClick = { vm.approve(m) }) { Text("Remember") }
                        }
                    }
                }
            }
            SectionHeader("Remembered", "Add") { adding = true }
            val active = items.filter { it.status == "ACTIVE" }
            if (active.isEmpty()) Text("Nothing remembered yet. In a chat with this agent, use “Suggest memories” in the conversation menu.", fontSize = 13.sp, color = La.colors.text3)
            active.forEach { m ->
                LaCard(padding = 12) {
                    Row(verticalAlignment = Alignment.Top) {
                        KindTag(m.kind); Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.content, fontSize = 14.sp)
                            Text("${if (m.pinned) "Pinned · " else ""}used ${m.useCount}× · saved ${fmtAgo(m.createdAt)}", fontSize = 11.5.sp, color = La.colors.text3)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { vm.update(m.copy(pinned = !m.pinned)) }) { Icon(Icons.Outlined.PushPin, if (m.pinned) "Unpin" else "Pin", tint = if (m.pinned) La.colors.accent else La.colors.text3) }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Outlined.Edit, "Edit", tint = La.colors.text3) }
                        IconButton(onClick = { vm.delete(m) }) { Icon(Icons.Outlined.Delete, "Delete", tint = La.colors.text3) }
                    }
                }
            }
            if (active.isNotEmpty()) OutlinedButton(onClick = { forget = true }, modifier = Modifier.fillMaxWidth()) { Text("Forget everything ${agent.name} remembers", color = La.colors.bad) }
            Text("Memory is separate from knowledge bases: documents you import are never rewritten as memories.", fontSize = 11.5.sp, color = La.colors.text3)
        }
    }
    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.content) }
        var kind by remember(m.id) { mutableStateOf(m.kind) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit memory") },
            text = { Column { OutlinedTextField(text, { text = it.take(280) }, minLines = 2); KindPicker(kind) { kind = it } } },
            confirmButton = { TextButton(onClick = { vm.update(m.copy(content = text.trim(), kind = kind)); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    if (adding) {
        var text by remember { mutableStateOf("") }; var kind by remember { mutableStateOf("FACT") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add a memory") },
            text = { Column { OutlinedTextField(text, { text = it.take(280) }, minLines = 2, placeholder = { Text("e.g. I prefer short answers with bullet points") }); KindPicker(kind) { kind = it } } },
            confirmButton = { TextButton(onClick = { vm.add(text, kind); adding = false }, enabled = text.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text("Forget everything?") },
        text = { Text("All memories of ${agent?.name} are permanently deleted from this phone.") },
        confirmButton = { TextButton(onClick = { vm.forgetAll(); forget = false }) { Text("Forget all", color = La.colors.bad) } },
        dismissButton = { TextButton(onClick = { forget = false }) { Text("Cancel") } })
    if (explain) AlertDialog(onDismissRequest = { explain = false }, title = { Text("How memory works") },
        text = { Text("• Conversation memory: the messages of one chat, deleted with the chat.\n\n• Agent memory: short facts and preferences per agent. Agents propose them when you tap “Suggest memories”; you approve them here.\n\n• Knowledge: documents in knowledge bases, cited as sources and never changed by agents.\n\nAt most 5 memories are added to a prompt, chosen by relevance, importance and recency. Pinned memories are always included.") },
        confirmButton = { TextButton(onClick = { explain = false }) { Text("OK") } })
}

@Composable
private fun KindTag(kind: String) {
    val c = when (kind) { "PREFERENCE" -> La.colors.agent; "EPISODE" -> La.colors.knowledge; else -> La.colors.model }
    Text(kind.take(4), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 3.dp))
}

@Composable
private fun KindPicker(kind: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("FACT" to "Fact", "PREFERENCE" to "Preference", "EPISODE" to "Episode").forEach { (k, l) -> FilterChip(kind == k, { onPick(k) }, { Text(l) }) }
    }
}

