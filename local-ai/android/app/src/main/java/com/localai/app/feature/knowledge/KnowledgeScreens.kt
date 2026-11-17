package com.localai.app.feature.knowledge

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Note
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
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
import com.localai.app.core.designsystem.KeyValue
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.SectionHeader
import com.localai.app.core.designsystem.Stat
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.core.designsystem.Tag
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.core.designsystem.fmtBytes
import com.localai.app.data.agents.AgentRepository
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.chat.ChatService
import com.localai.app.data.db.DocCount
import com.localai.app.data.db.DocumentEntity
import com.localai.app.data.db.KnowledgeDao
import com.localai.app.data.db.NoteDao
import com.localai.app.data.db.NoteEntity
import com.localai.app.data.knowledge.KnowledgeService
import com.localai.app.data.knowledge.RetrievedChunk
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.feature.models.LabeledSlider
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.ui.goTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class KnowledgeViewModel @Inject constructor(
    val service: KnowledgeService, private val dao: KnowledgeDao, private val notes: NoteDao, models: ModelRepository,
    private val agents: AgentRepository, private val chat: ChatService, settings: SettingsRepository, device: DeviceCapabilityService,
) : ViewModel() {
    val kbs = dao.observeKbs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val counts = dao.observeCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val noteList = notes.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val installed = models.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val agentList = agents.agents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var results by mutableStateOf<List<RetrievedChunk>?>(null)
    var searching by mutableStateOf(false)
    val liteFlow = settings.settings.map { policyFor(it, device.profile).lite }.stateIn(viewModelScope, SharingStarted.Eagerly, device.profile.tier.isLite)
    val lite get() = liteFlow.value

    suspend fun attachToAgent(agentId: String, kbId: String) {
        agents.get(agentId)?.let { a -> if (kbId !in a.kbList) agents.save(a.copy(kbIds = (a.kbList + kbId).joinToString(","))) }
    }

    fun search(q: String, kbIds: List<String>) = viewModelScope.launch { searching = true; results = service.retrieve(q, kbIds, 6); searching = false }
    fun saveNote(n: NoteEntity) = viewModelScope.launch { notes.upsert(n) }
    fun deleteNote(id: String) = viewModelScope.launch { notes.delete(id) }
    suspend fun ask(kbIds: List<String>, title: String, agentId: String?) = chat.newConversation(agentId, kbIds, title)
}

private fun countFor(counts: List<DocCount>, kbId: String) = counts.firstOrNull { it.kbId == kbId } ?: DocCount(kbId, 0, 0, 0, 0)

@Composable
fun KnowledgeScreen(nav: NavController, vm: KnowledgeViewModel = hiltViewModel()) {
    val kbs by vm.kbs.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val notes by vm.noteList.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var editNote by remember { mutableStateOf<NoteEntity?>(null) }
    val hasEmbedder = installed.any { Catalog.isEmbedding(it.id) }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Knowledge", style = MaterialTheme.typography.headlineSmall)
                Text("Local search over your documents · nothing leaves this phone", color = La.colors.text3, fontSize = 12.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("Documents", "${counts.sumOf { it.docs }}", Modifier.weight(1f))
                    Stat("On disk", fmtBytes(counts.sumOf { it.bytes }), Modifier.weight(1f))
                    Stat("Indexing", "${counts.sumOf { it.indexing }}", Modifier.weight(1f))
                }
            }
            if (!hasEmbedder) item {
                LaCard(onClick = { nav.navigate("model/${if (vm.lite) "bge-small-en" else "e5-small"}") }) {
                    Text("Install an embedding model for meaning-based search", fontWeight = FontWeight.SemiBold)
                    Text("Without one, documents are searched by keywords only. multilingual-e5-small (126 MB) supports ~100 languages; bge-small (35 MB) is English-only and lightest.",
                        fontSize = 12.5.sp, color = La.colors.text2)
                }
            }
            if (kbs.isNotEmpty()) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Search all knowledge") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                    IconButton(onClick = { if (q.isNotBlank()) vm.search(q, kbs.map { it.id }) }) { Icon(Icons.Outlined.Search, "Search", tint = La.colors.accent) }
                }
            }
            if (vm.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.results?.let { r ->
                if (r.isEmpty()) item { Text("No matching passages.", color = La.colors.text3, fontSize = 13.sp) }
                items(r, key = { "r${it.chunkId}" }) { c -> ResultCard(c) { nav.navigate("doc/${c.docId}") } }
            }
            item { SectionHeader("Knowledge bases") }
            if (kbs.isEmpty()) item { Banner("Create a knowledge base, add PDFs, Word files, notes or code, then ask questions with cited answers.", Tone.PENDING) }
            items(kbs, key = { it.id }) { k ->
                val c = countFor(counts, k.id)
                LaCard(onClick = { nav.navigate("kb/${k.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = La.colors.knowledge)
                        Text(k.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        when { c.indexing > 0 -> StatusPill("Indexing ${c.indexing}", Tone.RUN); c.failed > 0 -> StatusPill("${c.failed} failed", Tone.BAD); else -> StatusPill("Indexed", Tone.OK) }
                    }
                    if (k.description.isNotBlank()) Text(k.description, fontSize = 12.5.sp, color = La.colors.text2, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    FlowRowSimple {
                        Tag("${c.docs} docs"); Tag(fmtBytes(c.bytes)); Tag(fmtAgo(k.updatedAt))
                        if (k.scope == "AGENT") Tag("One agent only", La.colors.agent) else Tag("Shared", La.colors.knowledge)
                    }
                }
            }
            item { SectionHeader("Notes", "New note") { editNote = NoteEntity(UUID.randomUUID().toString(), "", "", "USER", null, System.currentTimeMillis(), System.currentTimeMillis()) } }
            if (notes.isEmpty()) item { Text("Notes you or your agents write appear here. Agents use them through the Note Manager tool.", fontSize = 12.5.sp, color = La.colors.text3) }
            items(notes, key = { "n${it.id}" }) { n ->
                LaCard(onClick = { editNote = n }, padding = 12) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.Note, null, tint = La.colors.text3, modifier = Modifier.size(18.dp))
                        Text(n.title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                        if (n.createdBy == "AGENT") Tag("by agent", La.colors.agent)
                    }
                    if (n.body.isNotBlank()) Text(n.body.take(120), fontSize = 12.5.sp, color = La.colors.text2, maxLines = 2)
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { nav.navigate("kbNew") }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New knowledge base") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = La.colors.accent, contentColor = La.colors.onAccent)
    }
    editNote?.let { n ->
        var title by remember(n.id) { mutableStateOf(n.title) }; var body by remember(n.id) { mutableStateOf(n.body) }
        AlertDialog(onDismissRequest = { editNote = null }, title = { Text(if (n.title.isBlank()) "New note" else "Edit note") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("Text") }, minLines = 4, maxLines = 10)
                if (n.title.isNotBlank()) TextButton(onClick = { vm.deleteNote(n.id); editNote = null }) { Text("Delete note", color = La.colors.bad) }
            } },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { vm.saveNote(n.copy(title = title.trim(), body = body, updatedAt = System.currentTimeMillis())); editNote = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editNote = null }) { Text("Cancel") } })
    }
}

@Composable
fun ResultCard(c: RetrievedChunk, onClick: () -> Unit) {
    LaCard(onClick = onClick, padding = 12) {
        Row { Text(c.docTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1); c.location?.let { Text(it, fontSize = 11.sp, color = La.colors.text3) } }
        Text(c.text.replace('\n', ' ').take(240), fontSize = 12.5.sp, color = La.colors.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------- create ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KbFormScreen(nav: NavController, vm: KnowledgeViewModel = hiltViewModel()) {
    val agents by vm.agentList.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var shared by rememberSaveable { mutableStateOf(true) }
    var owner by rememberSaveable { mutableStateOf<String?>(null) }
    val embedders = Catalog.models.filter { it.purpose == "embed" }
    var embed by rememberSaveable { mutableStateOf(installed.firstOrNull { Catalog.isEmbedding(it.id) }?.id ?: if (vm.lite) "bge-small-en" else "e5-small") }
    var chunk by rememberSaveable { mutableStateOf(400f) }
    var overlap by rememberSaveable { mutableStateOf(60f) }
    val files = remember { mutableStateOf(listOf<android.net.Uri>()) }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files.value = (files.value + it).distinct() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("New knowledge base", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it.take(48) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("e.g. Lease and insurance") })
            OutlinedTextField(desc, { desc = it.take(160) }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Who can use it", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(shared, { shared = true }, { Text("All chats and agents") }); FilterChip(!shared, { shared = false }, { Text("One agent only") })
            }
            if (!shared) {
                if (agents.isEmpty()) Text("Create an agent first to make agent-only knowledge.", fontSize = 12.5.sp, color = La.colors.warn)
                FlowRowSimple { agents.forEach { a -> FilterChip(owner == a.id, { owner = a.id }, { Text(a.name) }) } }
            }
            Text("Sources", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*", "application/json", "*/*")) }) { Text("Add files") }
            }
            files.value.forEach { u -> Text("• " + (u.lastPathSegment?.substringAfterLast('/') ?: u.toString()), fontSize = 13.sp, color = La.colors.text2) }
            OutlinedTextField(note, { note = it }, label = { Text("Or write a note") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Text("PDF (with text), Word .docx, TXT, Markdown and source code. Scanned PDFs need OCR, which is planned for a later release.", fontSize = 11.5.sp, color = La.colors.text3)
            LaCard {
                Text("Indexing", fontWeight = FontWeight.SemiBold)
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(embedders.first { it.id == embed }.name + if (installed.any { it.id == embed }) "" else " (not installed: keyword search until installed)", {}, readOnly = true,
                        label = { Text("Embedding model") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(open, { open = false }) { embedders.forEach { e -> DropdownMenuItem({ Text(e.name) }, { embed = e.id; open = false }) } }
                }
                LabeledSlider("Chunk size", "${chunk.toInt()} tokens", chunk, 200f..800f, 11) { chunk = it }
                LabeledSlider("Overlap", "${overlap.toInt()} tokens", overlap, 0f..150f, 14) { overlap = it }
            }
            error?.let { Banner(it, Tone.BAD) }
        }
        Button(enabled = !busy, onClick = {
            error = when {
                name.isBlank() -> "Name the knowledge base."
                vm.kbs.value.any { it.name.equals(name.trim(), true) } -> "A knowledge base with this name already exists."
                !shared && owner == null -> "Choose the agent that owns it."
                files.value.isEmpty() && note.isBlank() -> "Add at least one file or note."
                else -> null
            }
            if (error == null) scope.launch {
                busy = true
                val id = vm.service.createKb(name, desc, if (shared) "SHARED" else "AGENT", owner, embed, chunk.toInt(), overlap.toInt())
                if (!shared) owner?.let { vm.attachToAgent(it, id) }
                val errs = files.value.mapNotNull { u -> vm.service.addDocument(id, u).exceptionOrNull()?.message }
                if (note.isNotBlank()) vm.service.addNote(id, note.lineSequence().first().take(40), note)
                busy = false
                if (errs.isNotEmpty()) error = errs.joinToString("\n")
                nav.navigate("kb/$id") { popUpTo("knowledge") }
            }
        }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text(if (busy) "Creating…" else "Create and start indexing") }
    }
}

// ---------------- KB detail ----------------

@HiltViewModel
class KbDetailViewModel @Inject constructor(savedState: SavedStateHandle, val service: KnowledgeService, dao: KnowledgeDao, private val chat: ChatService,
    private val agents: AgentRepository) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    val kb = dao.observeKb(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val docs = dao.observeDocs(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var results by mutableStateOf<List<RetrievedChunk>?>(null)
    var message by mutableStateOf<String?>(null)
    fun search(q: String) = viewModelScope.launch { results = service.retrieve(q, listOf(id), 6) }
    fun add(uris: List<android.net.Uri>) = viewModelScope.launch {
        val errs = uris.mapNotNull { service.addDocument(id, it).exceptionOrNull()?.message }
        message = if (errs.isEmpty()) "Added ${uris.size} file${if (uris.size == 1) "" else "s"}. Indexing on this phone…" else errs.joinToString("\n")
    }
    fun reindexAll() = viewModelScope.launch { service.reindexKb(id) }
    fun delete(done: () -> Unit) = viewModelScope.launch {
        agents.all().filter { id in it.kbList }.forEach { agents.save(it.copy(kbIds = (it.kbList - id).joinToString(","))) }
        service.deleteKb(id); done()
    }
    suspend fun ask(): String { val k = kb.value; return chat.newConversation(if (k?.scope == "AGENT") k.ownerAgentId else null, listOf(id), "Ask ${k?.name ?: "documents"}") }
}

@Composable
fun docStatus(d: DocumentEntity) = when (d.status) {
    "INDEXED" -> StatusPill(if (d.semantic) "Indexed" else "Keyword only", if (d.semantic) Tone.OK else Tone.WARN)
    "FAILED" -> StatusPill("Failed", Tone.BAD); "QUEUED" -> StatusPill("Queued", Tone.PENDING)
    "EXTRACTING" -> StatusPill("Extracting", Tone.RUN); else -> StatusPill("Embedding ${(d.progress * 100).toInt()}%", Tone.RUN)
}

@Composable
fun KbDetailScreen(nav: NavController, vm: KbDetailViewModel = hiltViewModel()) {
    val kb by vm.kb.collectAsStateWithLifecycle()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.add(it) }
    val k = kb ?: return
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) { Text(k.name, style = MaterialTheme.typography.titleLarge, maxLines = 1); Text("${docs.size} documents · ${fmtBytes(docs.sumOf { it.sizeBytes })}", fontSize = 12.sp, color = La.colors.text3) }
            IconButton(onClick = { vm.reindexAll() }) { Icon(Icons.Outlined.Refresh, "Re-index all", tint = La.colors.text2) }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete knowledge base", tint = La.colors.text2) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FlowRowSimple {
                    Tag(if (k.scope == "AGENT") "One agent only" else "Shared", La.colors.knowledge)
                    Tag(Catalog.byId(k.embedModelId)?.name ?: k.embedModelId); Tag("${k.chunkSize}/${k.chunkOverlap} tokens")
                }
            }
            vm.message?.let { item { Banner(it, Tone.RUN) } }
            if (docs.any { it.status in listOf("QUEUED", "EXTRACTING", "EMBEDDING") }) item { Banner("Indexing on this phone. It pauses while a model is answering and continues in the background.", Tone.RUN) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(q, { q = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Search in ${k.name}") })
                    IconButton(onClick = { if (q.isNotBlank()) vm.search(q) }) { Icon(Icons.Outlined.Search, "Search", tint = La.colors.accent) }
                }
            }
            vm.results?.let { r -> if (r.isEmpty()) item { Text("No matching passages.", color = La.colors.text3) }; items(r, key = { "r${it.chunkId}" }) { ResultCard(it) { nav.navigate("doc/${it.docId}") } } }
            item { SectionHeader("Documents", "Add") { picker.launch(arrayOf("*/*")) } }
            if (docs.isEmpty()) item { Text("No documents yet.", color = La.colors.text3, fontSize = 13.sp) }
            items(docs, key = { it.id }) { d ->
                LaCard(onClick = { nav.navigate("doc/${d.id}") }, padding = 12) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text("${d.kind} · ${fmtBytes(d.sizeBytes)}${d.pages?.let { " · $it pages" } ?: ""}${if (d.chunkCount > 0) " · ${d.chunkCount} chunks" else ""}", fontSize = 12.sp, color = La.colors.text3)
                        }
                        docStatus(d)
                    }
                    if (d.status == "EMBEDDING" || d.status == "EXTRACTING") LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth().padding(top = 8.dp), color = La.colors.knowledge)
                    d.error?.let { Text(it, fontSize = 12.sp, color = La.colors.bad, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        Button(onClick = { scope.launch { nav.navigate("chat/${vm.ask()}") } }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("Ask this knowledge base") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${k.name}?") },
        text = { Text("All documents, chunks and search vectors are permanently removed. Chats keep their text but lose source links.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete { nav.goTab("knowledge") } }) { Text("Delete", color = La.colors.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

// ---------------- document detail ----------------

@HiltViewModel
class DocumentViewModel @Inject constructor(savedState: SavedStateHandle, val service: KnowledgeService, dao: KnowledgeDao, private val chat: ChatService) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    val doc = dao.observeDoc(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val chunks = dao.observeChunks(id, 30).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val kdao = dao
    fun reindex() = viewModelScope.launch { service.reindex(id) }
    fun delete(done: () -> Unit) = viewModelScope.launch { service.deleteDoc(id); done() }
    suspend fun ask(d: DocumentEntity): String { val kb = kdao.kb(d.kbId); return chat.newConversation(if (kb?.scope == "AGENT") kb.ownerAgentId else null, listOf(d.kbId), "About ${d.title.take(30)}") }
}

@Composable
fun DocumentScreen(nav: NavController, vm: DocumentViewModel = hiltViewModel()) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val chunks by vm.chunks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val d = doc ?: return
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(d.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Remove document", tint = La.colors.text2) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { docStatus(d); Spacer(Modifier.size(8.dp)); Tag(d.kind) } }
            d.error?.let { item { Banner(it, Tone.BAD) } }
            item {
                val stages = listOf("Extract", "Chunk", "Embed", "Indexed")
                val at = when (d.status) { "QUEUED" -> -1; "EXTRACTING" -> 0; "EMBEDDING" -> 2; "INDEXED" -> 4; else -> -1 }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stages.forEachIndexed { i, s ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            LinearProgressIndicator(progress = { if (i < at) 1f else if (i == at) d.progress else 0f }, Modifier.fillMaxWidth(), color = La.colors.knowledge)
                            Text(s, fontSize = 11.sp, color = if (i <= at) La.colors.text2 else La.colors.text3)
                        }
                    }
                }
            }
            item {
                LaCard {
                    KeyValue("Size", fmtBytes(d.sizeBytes)); d.pages?.let { KeyValue("Pages", "$it") }
                    KeyValue("Chunks", if (d.chunkCount > 0) "${d.chunkCount}" else "—")
                    KeyValue("Search", if (d.semantic) "Meaning + keywords" else "Keywords only")
                    KeyValue("Added", fmtAgo(d.createdAt)); d.indexedAt?.let { KeyValue("Indexed", fmtAgo(it)) }
                    KeyValue("SHA-256", d.sha256.take(12) + "…")
                }
            }
            if (chunks.isNotEmpty()) item { SectionHeader("Chunks (first ${chunks.size})") }
            items(chunks, key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                    Text("#${c.ordinal + 1}${c.location?.let { " · $it" } ?: ""} · ~${c.tokenCount} tokens", fontSize = 11.sp, color = La.colors.text3, fontFamily = La.mono)
                    Text(c.text.take(500), fontSize = 13.sp, color = La.colors.text2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.reindex() }, modifier = Modifier.weight(1f)) { Text("Re-index") }
            Button(enabled = d.status == "INDEXED", onClick = { scope.launch { nav.navigate("chat/${vm.ask(d)}") } }, modifier = Modifier.weight(1f)) { Text("Ask about it") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Remove document?") },
        text = { Text("${d.title} and its ${d.chunkCount} chunks are deleted from this phone.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.delete { nav.popBackStack() } }) { Text("Remove", color = La.colors.bad) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

