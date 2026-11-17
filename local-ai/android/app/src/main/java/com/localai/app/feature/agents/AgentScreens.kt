package com.localai.app.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.localai.app.core.designsystem.FlowRowSimple
import com.localai.app.core.designsystem.HUES
import com.localai.app.core.designsystem.KeyValue
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.SectionHeader
import com.localai.app.core.designsystem.Stat
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tag
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.core.designsystem.hue
import com.localai.app.feature.models.LabeledSlider
import com.localai.app.data.agents.AgentRepository
import com.localai.app.data.agents.Templates
import com.localai.app.data.chat.ChatService
import com.localai.app.data.db.AgentEntity
import com.localai.app.data.db.ChatDao
import com.localai.app.data.db.ConversationEntity
import com.localai.app.data.db.KnowledgeBaseEntity
import com.localai.app.data.db.KnowledgeDao
import com.localai.app.data.db.MemoryDao
import com.localai.app.data.db.MemoryEntity
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.tools.Permission
import com.localai.app.data.tools.ToolRegistry
import com.localai.app.ui.goTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: ViewModel, initial: T): StateFlow<T> = stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

@HiltViewModel
class AgentsViewModel @Inject constructor(
    val repo: AgentRepository, val tools: ToolRegistry, kdao: KnowledgeDao, val models: ModelRepository, private val chat: ChatService,
    memory: com.localai.app.data.agents.MemoryService,
) : ViewModel() {
    val agents = repo.agents.state(this, emptyList())
    val kbs = kdao.observeKbs().state(this, emptyList())
    val installed = models.installed.state(this, emptyList())
    val permissions = tools.permissions.state(this, emptyMap())
    val proposed = memory.proposedCount.state(this, 0)
    suspend fun chatWith(agentId: String, kbIds: List<String>, title: String) = chat.newConversation(agentId, kbIds, title)
}

@Composable
fun AgentsScreen(nav: NavController, vm: AgentsViewModel = hiltViewModel()) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val kbs by vm.kbs.collectAsStateWithLifecycle()
    val proposed by vm.proposed.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    val shown = agents.filter { when (filter) { "memory" -> it.memoryEnabled; "failed" -> it.lastStatus == "FAILED"; else -> true } }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Agent Studio", style = MaterialTheme.typography.headlineSmall)
                        Text("${agents.size} agents · share one loaded model", color = La.colors.text3, fontSize = 12.sp)
                    }
                    IconButton(onClick = { nav.navigate("tools") }) { Icon(Icons.Outlined.Build, "Tool registry", tint = La.colors.text2) }
                    Box {
                        IconButton(onClick = { nav.navigate("memory/${agents.firstOrNull { it.memoryEnabled }?.id ?: agents.firstOrNull()?.id ?: "none"}") }) {
                            Icon(Icons.Outlined.Psychology, "Agent memory", tint = La.colors.text2)
                        }
                        if (proposed > 0) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp).clip(CircleShape).background(La.colors.accent), contentAlignment = Alignment.Center) {
                            Text("$proposed", fontSize = 10.sp, color = La.colors.onAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                LaCard(onClick = { nav.navigate("workflow") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar("agent", "agent")
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("Supervisor workflow", style = MaterialTheme.typography.titleMedium)
                            Text("Plan → delegate → review → aggregate. Agents take turns on one model.", fontSize = 12.5.sp, color = La.colors.text2)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = La.colors.text3)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "memory" to "With memory", "failed" to "Needs attention").forEach { (k, l) -> FilterChip(filter == k, { filter = k }, { Text(l) }) }
                }
            }
            if (agents.isEmpty()) item {
                LaCard {
                    Text("No agents yet", style = MaterialTheme.typography.titleMedium)
                    Text("Agents are reusable assistants with their own instructions, tools, documents and memory. Start from a template.", color = La.colors.text2, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp)); Button(onClick = { nav.navigate("agentEdit/new") }) { Text("Create your first agent") }
                }
            }
            items(shown, key = { it.id }) { a -> AgentCard(a, kbs) { nav.navigate("agent/${a.id}") } }
        }
        ExtendedFloatingActionButton(onClick = { nav.navigate("agentEdit/new") }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New agent") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = La.colors.accent, contentColor = La.colors.onAccent)
    }
}

@Composable
fun statusPill(a: AgentEntity) = when (a.lastStatus) {
    "COMPLETED" -> StatusPill("Completed", Tone.OK); "FAILED" -> StatusPill("Failed", Tone.BAD); else -> StatusPill("Not run yet", Tone.PENDING)
}

@Composable
private fun AgentCard(a: AgentEntity, kbs: List<KnowledgeBaseEntity>, onClick: () -> Unit) {
    LaCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            AgentAvatar(a.icon, a.color)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); statusPill(a) }
                Text("${a.role} · ${a.runs} runs${a.lastRunAt?.let { " · " + fmtAgo(it) } ?: ""}", fontSize = 12.sp, color = La.colors.text2)
            }
        }
        if (a.description.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(a.description, fontSize = 13.sp, color = La.colors.text2) }
        Spacer(Modifier.height(10.dp))
        FlowRowSimple {
            Tag(a.modelId?.let { Catalog.byId(it)?.name ?: it } ?: "Active model", La.colors.model)
            a.kbList.mapNotNull { id -> kbs.firstOrNull { it.id == id } }.forEach { Tag(it.name, La.colors.knowledge) }
            a.toolList.forEach { Tag(it.replace('_', ' ')) }
            if (a.memoryEnabled) Tag("Memory", La.colors.agent)
        }
    }
}

// ---------------- create / edit ----------------

@HiltViewModel
class AgentFormViewModel @Inject constructor(savedState: SavedStateHandle, val repo: AgentRepository, val tools: ToolRegistry, kdao: KnowledgeDao, models: ModelRepository) : ViewModel() {
    val editId: String? = savedState.get<String>("id")?.takeIf { it != "new" }
    val kbs = kdao.observeKbs().state(this, emptyList())
    val installed = models.installed.state(this, emptyList())
    val permissions = tools.permissions.state(this, emptyMap())
    var draft by mutableStateOf<AgentEntity?>(null)
    var step by mutableStateOf(if (editId == null) 0 else 1)
    var error by mutableStateOf<String?>(null)
    init { viewModelScope.launch { draft = editId?.let { repo.get(it) } ?: repo.fromTemplate(Templates.all.first()) } }
    fun save(onSaved: (String) -> Unit) = viewModelScope.launch { draft?.let { repo.save(it).onSuccess(onSaved).onFailure { e -> error = e.message } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentFormScreen(nav: NavController, vm: AgentFormViewModel = hiltViewModel()) {
    val d = vm.draft ?: return
    val kbs by vm.kbs.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val editing = vm.editId != null
    fun upd(f: (AgentEntity) -> AgentEntity) { vm.draft = f(d); vm.error = null }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (vm.step == 1 && !editing) vm.step = 0 else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column { Text(if (editing) "Edit ${d.name}" else "New agent", style = MaterialTheme.typography.titleLarge)
                Text(if (editing) "Changes apply to the next run" else "Step ${vm.step + 1} of 2", fontSize = 12.sp, color = La.colors.text3) }
        }
        if (vm.step == 0) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose a starting point. Everything can be changed on the next step.", color = La.colors.text2, fontSize = 13.sp)
                Templates.all.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { t ->
                            val on = d.templateId == t.id
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) hue(t.color).copy(alpha = 0.12f) else La.colors.surface1)
                                .border(1.dp, if (on) hue(t.color) else La.colors.line, RoundedCornerShape(14.dp)).clickable { vm.draft = vm.repo.fromTemplate(t) }.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AgentAvatar(t.icon, t.color, 32.dp)
                                Text(t.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(t.description, fontSize = 11.5.sp, color = La.colors.text2, lineHeight = 15.sp)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Button(onClick = { vm.step = 1 }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Continue with ${Templates.byId(d.templateId)?.name ?: "template"}") }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(d.icon, d.color, 56.dp); Spacer(Modifier.width(12.dp))
                FlowRowSimple { HUES.forEach { h -> Box(Modifier.size(28.dp).clip(CircleShape).background(hue(h).copy(alpha = 0.2f))
                    .border(if (d.color == h) 2.dp else 0.dp, hue(h), CircleShape).clickable { upd { it.copy(color = h) } }) } }
            }
            OutlinedTextField(d.name, { v -> upd { it.copy(name = v.take(32)) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = vm.error?.contains("name") == true)
            OutlinedTextField(d.role, { v -> upd { it.copy(role = v.take(40)) } }, label = { Text("Role") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(d.description, { v -> upd { it.copy(description = v.take(160)) } }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(d.systemPrompt, { v -> upd { it.copy(systemPrompt = v) } }, label = { Text("System prompt") }, minLines = 4, maxLines = 10,
                modifier = Modifier.fillMaxWidth(), isError = vm.error?.contains("sentence") == true,
                supportingText = { Text("${d.systemPrompt.length} characters") })
            var open by remember { mutableStateOf(false) }
            val gens = installed.filter { !Catalog.isEmbedding(it.id) }
            ExposedDropdownMenuBox(open, { open = it }) {
                OutlinedTextField(d.modelId?.let { id -> gens.firstOrNull { it.id == id }?.name ?: Catalog.byId(id)?.name ?: id } ?: "Use the active model",
                    {}, readOnly = true, label = { Text("Model") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    supportingText = { Text("Agents share one loaded model. A different model means a reload (a few seconds) when this agent runs.") })
                ExposedDropdownMenu(open, { open = false }) {
                    DropdownMenuItem({ Text("Use the active model") }, { upd { it.copy(modelId = null) }; open = false })
                    gens.forEach { m -> DropdownMenuItem({ Text("${m.name} · ${m.quant}") }, { upd { it.copy(modelId = m.id) }; open = false }) }
                }
            }
            SectionHeader("Tools")
            vm.tools.tools.filter { it.id != "delegate_to_agent" }.forEach { t ->
                val on = t.id in d.toolList
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) La.colors.accentSoft else La.colors.surface1)
                    .clickable { upd { a -> a.copy(tools = (if (on) a.toolList - t.id else a.toolList + t.id).joinToString(",")) } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, null)
                    Column(Modifier.weight(1f)) { Text(t.name, fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(t.description, fontSize = 11.5.sp, color = La.colors.text3) }
                    when (perms[t.id]) { Permission.ASK -> Tag("Asks"); Permission.OFF -> Tag("Off", La.colors.bad); else -> Unit }
                }
            }
            SectionHeader("Knowledge bases")
            if (kbs.isEmpty()) Text("No knowledge bases yet. Create one in the Knowledge tab, then attach it here.", fontSize = 12.5.sp, color = La.colors.text3)
            kbs.filter { it.scope == "SHARED" || it.ownerAgentId == d.id }.forEach { k ->
                val on = k.id in d.kbList
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) La.colors.accentSoft else La.colors.surface1)
                    .clickable { upd { a -> a.copy(kbIds = (if (on) a.kbList - k.id else a.kbList + k.id).joinToString(",")) } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, null); Text(k.name, modifier = Modifier.weight(1f)); if (k.scope == "AGENT") Tag("Only this agent", La.colors.agent)
                }
            }
            LaCard {
                Text("Memory", fontWeight = FontWeight.SemiBold)
                SwitchRow("Remember across conversations", "Facts and preferences this agent learns", d.memoryEnabled) { v -> upd { it.copy(memoryEnabled = v, memoryAutoSave = v && it.memoryAutoSave) } }
                SwitchRow("Save without asking", "Off: new memories wait in the inbox for approval", d.memoryAutoSave, enabled = d.memoryEnabled) { v -> upd { it.copy(memoryAutoSave = v) } }
            }
            LaCard {
                Text("Execution limits", fontWeight = FontWeight.SemiBold)
                LabeledSlider("Max tool calls per answer", "${d.maxToolCalls}", d.maxToolCalls.toFloat(), 0f..6f, 5) { v -> upd { it.copy(maxToolCalls = v.toInt()) } }
                LabeledSlider("Max steps in workflows", "${d.maxSteps}", d.maxSteps.toFloat(), 1f..12f, 10) { v -> upd { it.copy(maxSteps = v.toInt()) } }
                LabeledSlider("Temperature", "%.2f".format(d.temperature), d.temperature, 0f..1.5f, 29) { v -> upd { it.copy(temperature = v) } }
                Text("Lite mode allows at most 2 tool calls per answer.", fontSize = 11.5.sp, color = La.colors.text3)
            }
            vm.error?.let { Banner(it, Tone.BAD) }
        }
        Button(onClick = { vm.save { id -> nav.navigate("agent/$id") { popUpTo("agents") } } }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Text(if (editing) "Save changes" else "Create agent")
        }
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.sp, color = La.colors.text3) }
        Switch(checked, onChange, enabled = enabled)
    }
}

// ---------------- details ----------------

@HiltViewModel
class AgentDetailViewModel @Inject constructor(
    savedState: SavedStateHandle, val repo: AgentRepository, kdao: KnowledgeDao, memDao: MemoryDao, private val chats: ChatDao,
    private val chat: ChatService, val tools: ToolRegistry,
) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    val agent = repo.agent(id).state(this, null)
    val kbs = kdao.observeKbs().state(this, emptyList())
    val memories = memDao.observe(id).state(this, emptyList<MemoryEntity>())
    val permissions = tools.permissions.state(this, emptyMap())
    var recent by mutableStateOf<List<ConversationEntity>>(emptyList())
    init { viewModelScope.launch { recent = chats.byAgent(id) } }
    suspend fun chatWith(a: AgentEntity) = chat.newConversation(a.id, a.kbList, "Chat with ${a.name}")
    fun delete(done: () -> Unit) = viewModelScope.launch { repo.delete(id); done() }
    suspend fun duplicate() = repo.duplicate(id)
}

@Composable
fun AgentDetailScreen(nav: NavController, vm: AgentDetailViewModel = hiltViewModel()) {
    val a by vm.agent.collectAsStateWithLifecycle()
    val kbs by vm.kbs.collectAsStateWithLifecycle()
    val mems by vm.memories.collectAsStateWithLifecycle()
    val perms by vm.permissions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val agent = a ?: return
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { scope.launch { vm.duplicate()?.let { nav.navigate("agent/$it") } } }) { Icon(Icons.Outlined.ContentCopy, "Duplicate", tint = La.colors.text2) }
            IconButton(onClick = { nav.navigate("agentEdit/${agent.id}") }) { Icon(Icons.Outlined.Edit, "Edit", tint = La.colors.text2) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete", tint = La.colors.text2) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(agent.icon, agent.color, 56.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(agent.name, style = MaterialTheme.typography.headlineSmall); Text(agent.role, color = La.colors.text2) }
                statusPill(agent)
            }
            if (agent.description.isNotBlank()) Text(agent.description, color = La.colors.text2)
            if (agent.lastStatus == "FAILED") Banner("Last run failed: ${agent.lastError ?: "unknown error"}", Tone.BAD)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Runs", "${agent.runs}", Modifier.weight(1f)); Stat("Memories", "${mems.count { it.status == "ACTIVE" }}", Modifier.weight(1f)); Stat("Tool calls", "≤ ${agent.maxToolCalls}", Modifier.weight(1f))
            }
            LaCard {
                KeyValue("Model", agent.modelId?.let { Catalog.byId(it)?.name ?: it } ?: "Active model")
                KeyValue("Temperature", "%.2f".format(agent.temperature))
                KeyValue("Memory", if (!agent.memoryEnabled) "Off" else if (agent.memoryAutoSave) "On · auto-save" else "On · needs approval")
                KeyValue("Last run", agent.lastRunAt?.let { fmtAgo(it) } ?: "never")
            }
            SectionHeader("Tools")
            LaCard(padding = 12) {
                if (agent.toolList.isEmpty()) Text("No tools · chat only", color = La.colors.text3, fontSize = 13.sp)
                agent.toolList.mapNotNull { vm.tools.spec(it) }.forEach { t ->
                    Row(Modifier.padding(vertical = 4.dp)) { Text(t.name, modifier = Modifier.weight(1f)); Text(perms[t.id]?.label ?: "", color = La.colors.text3, fontSize = 12.sp) }
                }
            }
            SectionHeader("Knowledge")
            LaCard(padding = 12) {
                val mine = agent.kbList.mapNotNull { id -> kbs.firstOrNull { it.id == id } }
                if (mine.isEmpty()) Text("No knowledge bases", color = La.colors.text3, fontSize = 13.sp)
                mine.forEach { k -> Text(k.name, modifier = Modifier.fillMaxWidth().clickable { nav.navigate("kb/${k.id}") }.padding(vertical = 6.dp)) }
            }
            SectionHeader("Memory", "Manage") { nav.navigate("memory/${agent.id}") }
            LaCard(padding = 12) {
                val act = mems.filter { it.status == "ACTIVE" }.take(3)
                if (act.isEmpty()) Text(if (agent.memoryEnabled) "Nothing remembered yet" else "Memory is off for this agent", color = La.colors.text3, fontSize = 13.sp)
                act.forEach { Text("• ${it.content}", fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                val inbox = mems.count { it.status == "PROPOSED" }
                if (inbox > 0) Text("$inbox waiting for approval", color = La.colors.accent, fontSize = 12.sp)
            }
            SectionHeader("System prompt")
            LaCard(padding = 12) { Text(agent.systemPrompt, fontSize = 13.sp, color = La.colors.text2) }
            SectionHeader("Recent chats")
            if (vm.recent.isEmpty()) Text("Not used in a chat yet.", color = La.colors.text3, fontSize = 13.sp)
            vm.recent.forEach { c -> Text("${c.title} · ${fmtAgo(c.updatedAt)}", modifier = Modifier.fillMaxWidth().clickable { nav.navigate("chat/${c.id}") }.padding(vertical = 6.dp), fontSize = 13.sp) }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { nav.navigate("workflow") }, Modifier.weight(1f)) { Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp)); Text("  Workflow") }
            Button(onClick = { scope.launch { nav.navigate("chat/${vm.chatWith(agent)}") } }, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Outlined.Chat, null, Modifier.size(16.dp)); Text("  Chat")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${agent.name}?") },
        text = { Text("The agent and its ${mems.size} memories are removed from this phone. Chats are kept.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete { nav.goTab("agents") } }) { Text("Delete", color = La.colors.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

