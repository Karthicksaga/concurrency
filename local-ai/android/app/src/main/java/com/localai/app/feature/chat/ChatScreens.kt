package com.localai.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.style.TextAlign
import com.localai.app.core.designsystem.AgentAvatar
import com.localai.app.core.designsystem.StatusPill
import com.localai.app.data.agents.AgentRepository
import com.localai.app.data.agents.ToolTrace
import com.localai.app.data.chat.SUPERVISOR
import com.localai.app.data.chat.sourcesFromJson
import com.localai.app.data.chat.toolsFromJson
import com.localai.app.data.db.AgentEntity
import com.localai.app.data.db.KnowledgeDao
import com.localai.app.data.knowledge.SourceRef
import com.localai.app.data.workflow.WorkflowService
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.localai.app.data.chat.DEFAULT_PHOTO_PROMPT
import com.localai.app.data.documents.DocFormat
import com.localai.app.data.documents.DocumentStudio
import com.localai.app.data.media.Images
import com.localai.app.data.voice.HeardEvent
import com.localai.app.data.voice.Speakable
import com.localai.app.data.voice.SpeechInput
import com.localai.app.data.voice.SpeechOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.localai.app.core.designsystem.La
import com.localai.app.core.designsystem.LaCard
import com.localai.app.core.designsystem.Tone
import com.localai.app.core.designsystem.fmtAgo
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.chat.ChatService
import com.localai.app.data.db.ChatDao
import com.localai.app.data.db.ConversationSummary
import com.localai.app.data.db.InstalledModelEntity
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.LlamaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(dao: ChatDao, private val chat: ChatService) : ViewModel() {
    val conversations: StateFlow<List<ConversationSummary>> = dao.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    suspend fun create() = chat.newConversation()
}

@Composable
fun ChatListScreen(nav: NavController, vm: ChatListViewModel = hiltViewModel()) {
    val list by vm.conversations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Chats", style = MaterialTheme.typography.headlineSmall)
                Text("${list.size} conversations · stored only on this phone", color = La.colors.text3, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            if (list.isEmpty()) item { Banner("No conversations yet. Start one with New chat.", Tone.PENDING) }
            items(list, key = { it.id }) { c -> ConversationRow(c) { nav.navigate("chat/${c.id}") } }
        }
        ExtendedFloatingActionButton(onClick = { scope.launch { nav.navigate("chat/${vm.create()}") } },
            icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New chat") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = La.colors.accent, contentColor = La.colors.onAccent)
    }
}

@Composable
fun ConversationRow(c: ConversationSummary, onClick: () -> Unit) {
    LaCard(onClick = onClick, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(La.colors.surface3), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = La.colors.text2, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row { Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f)); Text(fmtAgo(c.updatedAt), fontSize = 11.sp, color = La.colors.text3) }
                Text(c.lastMessage?.replace('\n', ' ')?.take(90) ?: "No messages yet", fontSize = 12.5.sp, color = La.colors.text2, maxLines = 1)
            }
        }
    }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    dao: ChatDao,
    private val chat: ChatService,
    private val models: ModelRepository,
    settings: SettingsRepository,
    engine: LlamaEngine,
    kdao: KnowledgeDao,
    agentRepo: AgentRepository,
    workflows: WorkflowService,
    @ApplicationContext private val context: Context,
    private val studio: DocumentStudio,
    private val speechIn: SpeechInput,
    private val speechOut: SpeechOutput,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    /** Opened from "Ask a photo": show the attach menu straight away. */
    val autoAttach: Boolean = savedState.get<String>("attach") == "1"
    var pendingImage by mutableStateOf<String?>(null); private set
    var importingImage by mutableStateOf(false); private set
    var dictating by mutableStateOf(false); private set
    var dictationLevel by mutableStateOf(0f); private set
    var exported by mutableStateOf<File?>(null)
    val speakingId = speechOut.speakingId
    val voiceAvailable get() = speechIn.available
    private var cameraFile: File? = null
    val messages = dao.observeMessages(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val conversation = dao.observeConversation(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val live = chat.live
    val toolRequest = chat.toolRequest
    val engineState = engine.state
    val lastStats = engine.lastStats
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val installed: StateFlow<List<InstalledModelEntity>> = models.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val kbs = kdao.observeKbs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val agents = agentRepo.agents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val workflowLive = workflows.live
    val activeRun = workflows.activeRunId
    val workflowHistory = workflows.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var notice by mutableStateOf<String?>(null)

    fun send(text: String) { chat.send(id, text, pendingImage); pendingImage = null }
    fun stop() = chat.stop()

    // ---- photos ----
    fun cameraUri(): Uri = Images.cameraTarget(context).let { (f, u) -> cameraFile = f; u }
    fun attach(uri: Uri) = viewModelScope.launch {
        importingImage = true
        runCatching { Images.import(context, uri) }
            .onSuccess { pendingImage?.let { old -> File(old).delete() }; pendingImage = it.path }
            .onFailure { notice = it.message ?: "Could not read that image." }
        cameraFile?.delete(); cameraFile = null
        importingImage = false
    }
    fun removeImage() { pendingImage?.let { File(it).delete() }; pendingImage = null }

    // ---- voice ----
    /** Dictation into the composer: partial results stream into [onText]. */
    fun dictate(onText: (String, Boolean) -> Unit) = viewModelScope.launch {
        if (dictating) { speechIn.stop(); return@launch }
        speechOut.stop()
        dictating = true
        speechIn.listen(settingsRepo.current().voiceLanguage) { e ->
            when (e) {
                is HeardEvent.Partial -> onText(e.text, false)
                is HeardEvent.Final -> { onText(e.text, true); dictating = false; dictationLevel = 0f }
                is HeardEvent.Level -> dictationLevel = e.rms
                is HeardEvent.Error -> { dictating = false; dictationLevel = 0f; if (!e.noSpeech) notice = e.message }
                HeardEvent.Ready -> Unit
            }
        }
    }
    fun speak(messageId: String, text: String) = viewModelScope.launch {
        if (speakingId.value == messageId) { speechOut.stop(); return@launch }
        speechOut.stop()
        val s = settingsRepo.current()
        if (!speechOut.enqueue(Speakable.clean(text), s.voiceLanguage, s.speechRate, messageId)) notice = "No text-to-speech engine is installed. Install one from Android settings › Text-to-speech."
    }

    // ---- documents ----
    fun saveAs(text: String, format: DocFormat) = viewModelScope.launch {
        runCatching { studio.exportMarkdown(text, format, conversation.value?.title ?: "Chat answer") }
            .onSuccess { exported = it }.onFailure { notice = "Could not create the ${format.label} file: ${it.message}" }
    }
    fun openExport(f: File) { if (!studio.open(f)) notice = "No app on this phone opens ${f.extension.uppercase()} files. Use Share instead." }
    fun shareExport(f: File) = context.startActivity(studio.shareIntent(f))
    fun regenerate() = chat.regenerate(id)
    fun answerTool(allow: Boolean) = chat.answerTool(allow)
    fun switchModel(modelId: String) = viewModelScope.launch { models.activate(modelId).onFailure { notice = it.message } }
    fun saveConversation(prompt: String, title: String) = viewModelScope.launch { chat.updateConversation(id) { it.copy(systemPrompt = prompt, title = title.ifBlank { it.title }) } }
    fun setKbs(ids: List<String>) = viewModelScope.launch { chat.updateConversation(id) { it.copy(kbIds = ids.joinToString(",")) } }
    fun setAgent(agent: AgentEntity?, supervisor: Boolean) = viewModelScope.launch {
        chat.updateConversation(id) { c ->
            when {
                supervisor -> c.copy(agentId = SUPERVISOR)
                agent != null -> c.copy(agentId = agent.id, systemPrompt = agent.systemPrompt, kbIds = (c.kbIds.split(',').filter { it.isNotBlank() } + agent.kbList).distinct().joinToString(","))
                else -> c.copy(agentId = null)
            }
        }
    }
    fun suggestMemories() = viewModelScope.launch {
        notice = "Looking for things worth remembering…"
        val n = chat.suggestMemories(id)
        notice = if (n == 0) "Nothing new to remember from this chat." else "$n memor${if (n == 1) "y" else "ies"} proposed. Review them in Agent memory."
    }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { chat.delete(id); onDone() }
    // Leaving a chat without sending anything should not leave an empty "New chat" behind.
    override fun onCleared() { chat.discardIfEmpty(id); speechIn.cancel(); speechOut.stop(); pendingImage?.let { File(it).delete() } }
}

@Composable
fun ChatScreen(nav: NavController, vm: ChatViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conv by vm.conversation.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val toolReq by vm.toolRequest.collectAsStateWithLifecycle()
    val eng by vm.engineState.collectAsStateWithLifecycle()
    val stats by vm.lastStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val kbs by vm.kbs.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val wfLive by vm.workflowLive.collectAsStateWithLifecycle()
    val wfHistory by vm.workflowHistory.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(vm.autoAttach) }
    val speakingId by vm.speakingId.collectAsStateWithLifecycle()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attach(it) } }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { vm.attach(it) } }
    val takePhoto = { runCatching { camera.launch(vm.cameraUri().also { cameraUri = it }) }.onFailure { vm.notice = "No camera app is available." }; Unit }
    val pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val dictationBase = remember { mutableStateOf("") }
    val startDictation = {
        dictationBase.value = input
        vm.dictate { text, _ -> input = (dictationBase.value.trimEnd() + " " + text).trim() }; Unit
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation() else vm.notice = "Allow the microphone to dictate messages."
    }
    val onMic = {
        if (vm.dictating || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startDictation()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    var showOptions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showKbs by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val c = conv
    val supervisor = c?.agentId == SUPERVISOR
    val agent = agents.firstOrNull { it.id == c?.agentId }
    val myLive = live?.takeIf { it.conversationId == vm.id }
    val runningRun = wfHistory.firstOrNull { it.conversationId == vm.id && it.status == "RUNNING" }
    val busy = live != null || runningRun != null
    val activeId = settings?.activeModelId
    val modelName = installed.firstOrNull { it.id == activeId }?.name ?: activeId?.let { Catalog.byId(it)?.name } ?: "No model selected"
    val selectedKbs = c?.kbIds?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    val engLabel = when (val e = eng) {
        is EngineState.Ready -> "ready · ${e.contextLength / 1024}K context"
        is EngineState.Loading -> "loading…"; is EngineState.Failed -> "failed to load"; EngineState.Idle -> "loads on first message"
    }
    // "Read replies aloud" (Settings › Voice): speak answers that arrive while this chat is open.
    val openedAt = remember { System.currentTimeMillis() }
    LaunchedEffect(messages.lastOrNull()?.id) {
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (settings?.readAloud == true && last.role == "assistant" && last.status == "COMPLETE" && last.createdAt >= openedAt) vm.speak(last.id, last.content)
    }
    LaunchedEffect(messages.size, myLive?.text?.length) {
        val n = messages.size + (if (myLive != null || runningRun != null) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1, scrollOffset = Int.MAX_VALUE / 4)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            if (agent != null) AgentAvatar(agent.icon, agent.color, 32.dp)
            Column(Modifier.weight(1f).padding(start = 6.dp).clickable { showModels = true }) {
                Text(c?.title ?: "Chat", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("$modelName · $engLabel", color = La.colors.text3, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { nav.navigate("voice/${vm.id}") }) { Icon(Icons.Outlined.GraphicEq, "Voice conversation", tint = La.colors.text2) }
            IconButton(onClick = { showModels = true }) { Icon(Icons.Outlined.Memory, "Choose model", tint = La.colors.text2) }
            IconButton(onClick = { showOptions = true }) { Icon(Icons.Outlined.Tune, "Conversation options", tint = La.colors.text2) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(La.colors.line))
        if (activeId == null) Box(Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Banner("Download or choose a model before chatting.", Tone.WARN)
                Button(onClick = { nav.navigate("models") }) { Text("Open models") }
            }
        }
        vm.notice?.let { n -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable { vm.notice = null }) { Banner(n, Tone.RUN) } }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (messages.isEmpty() && myLive == null) item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (agent != null) AgentAvatar(agent.icon, agent.color, 56.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(when { supervisor -> "Supervisor workflow"; agent != null -> "Chat with ${agent.name}"; else -> "Ask anything, offline" }, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(when {
                        supervisor -> "Your request is split into tasks for your agents, which take turns on one model."
                        agent != null -> agent.description
                        selectedKbs.isNotEmpty() -> "Answers will cite your documents."
                        else -> "This conversation stays on your phone."
                    }, color = La.colors.text2, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            items(messages, key = { it.id }) { m ->
                if (m.role == "user") UserBubble(m.content, m.imagePath)
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (m.runId != null) "Supervisor workflow" else agent?.name ?: installed.firstOrNull { it.id == m.modelId }?.name ?: m.modelId ?: "Assistant", fontSize = 12.sp, color = La.colors.text3)
                    toolsFromJson(m.toolsJson).forEach { t -> ToolTraceRow(t) }
                    if (m.status == "ERROR") Banner(m.content, Tone.BAD) else MarkdownText(m.content)
                    if (m.status == "STOPPED") Text("Generation stopped.", fontSize = 12.sp, color = La.colors.text3)
                    val sources = sourcesFromJson(m.sourcesJson)
                    if (sources.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sources.forEach { s -> SourceRow(s) { nav.navigate("doc/${s.docId}") } }
                    }
                    m.runId?.let { r -> TextButton(onClick = { nav.navigate("workflow/$r") }) { Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp)); Text("  Open workflow") } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(m.content)) }, Modifier.size(34.dp)) { Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(17.dp), tint = La.colors.text3) }
                        if (m.status != "ERROR") {
                            IconButton(onClick = { vm.speak(m.id, m.content) }, Modifier.size(34.dp)) {
                                Icon(if (speakingId == m.id) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp, if (speakingId == m.id) "Stop reading" else "Read aloud", Modifier.size(18.dp), tint = if (speakingId == m.id) La.colors.accent else La.colors.text3)
                            }
                            Box {
                                var saveMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { saveMenu = true }, Modifier.size(34.dp)) { Icon(Icons.Outlined.Description, "Save as document", Modifier.size(17.dp), tint = La.colors.text3) }
                                DropdownMenu(saveMenu, { saveMenu = false }) {
                                    DropdownMenuItem({ Text("Save as PDF") }, { saveMenu = false; vm.saveAs(m.content, DocFormat.PDF) })
                                    DropdownMenuItem({ Text("Save as Word (.docx)") }, { saveMenu = false; vm.saveAs(m.content, DocFormat.DOCX) })
                                }
                            }
                        }
                        if (m == messages.last() && !busy) IconButton(onClick = { vm.regenerate() }, Modifier.size(34.dp)) { Icon(Icons.Outlined.Refresh, "Regenerate", Modifier.size(17.dp), tint = La.colors.text3) }
                        m.genTps?.let { Text("%.1f tok/s".format(it) + (m.tokens?.let { t -> " · $t tokens" } ?: ""), fontSize = 11.sp, color = La.colors.text3, modifier = Modifier.padding(start = 6.dp)) }
                    }
                }
            }
            if (myLive != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(agent?.name ?: modelName, fontSize = 12.sp, color = La.colors.text3)
                    if (myLive.text.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        com.localai.app.core.designsystem.ModelLoadingMark(34.dp)
                        Text("  " + myLive.phase.ifBlank { "Thinking…" }, color = La.colors.text2, fontSize = 13.sp)
                    }
                    else MarkdownText(myLive.text + " ▍")
                }
            }
            if (runningRun != null) item {
                LaCard(onClick = { nav.navigate("workflow/${runningRun.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AccountTree, null, tint = La.colors.agent)
                        Text("  Supervisor workflow running", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        StatusPill("Running", Tone.RUN)
                    }
                    wfLive?.takeIf { it.runId == runningRun.id }?.let { l -> Text(l.phase.ifBlank { l.text.takeLast(120) }, fontSize = 12.5.sp, color = La.colors.text2, maxLines = 2) }
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Tap to watch the agents work", fontSize = 11.5.sp, color = La.colors.text3)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(La.colors.surface1).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selectedKbs.isNotEmpty(), { showKbs = true }, { Text(if (selectedKbs.isEmpty()) "Knowledge" else if (selectedKbs.size == 1) kbs.firstOrNull { it.id == selectedKbs[0] }?.name ?: "1 knowledge base" else "${selectedKbs.size} knowledge bases", maxLines = 1) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(16.dp)) })
            FilterChip(c?.agentId != null, { showAgents = true }, { Text(when { supervisor -> "Agent mode: Supervisor"; agent != null -> agent.name; else -> "Agent mode off" }, maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp)) })
        }
        vm.pendingImage?.let { path -> PendingPhoto(path, onRemove = { vm.removeImage() }) }
        if (vm.importingImage) LinearProgressIndicator(Modifier.fillMaxWidth())
        Composer(input, onInput = { input = it }, busy = busy, enabled = activeId != null,
            canSendEmpty = vm.pendingImage != null,
            onSend = { if (input.isNotBlank() || vm.pendingImage != null) { vm.send(input); input = "" } }, onStop = { if (runningRun != null) nav.navigate("workflow/${runningRun.id}") else vm.stop() },
            footer = if (vm.dictating) "Listening… tap the mic to finish" else stats?.let { "Last reply %.1f tok/s · prompt %.0f tok/s".format(it.genTps, it.promptTps) },
            attach = {
                Box {
                    IconButton(onClick = { attachMenu = true }, enabled = activeId != null && !busy, modifier = Modifier.padding(4.dp).size(44.dp)) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "Attach a photo", tint = La.colors.text2)
                    }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem({ Text("Take a photo") }, { attachMenu = false; takePhoto() }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) })
                        DropdownMenuItem({ Text("Choose from gallery") }, { attachMenu = false; pickPhoto() }, leadingIcon = { Icon(Icons.Outlined.Image, null) })
                    }
                }
            },
            mic = if (vm.voiceAvailable) ({ onMic() }) else null, dictating = vm.dictating, level = vm.dictationLevel)
    }

    vm.exported?.let { f ->
        AlertDialog(onDismissRequest = { vm.exported = null }, title = { Text("Saved ${f.extension.uppercase()}") },
            text = { Text("${f.name}\nFind it any time in Documents › Files.", fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { vm.openExport(f); vm.exported = null }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { vm.shareExport(f); vm.exported = null }) { Text("Share") } })
    }
    toolReq?.takeIf { it.conversationId == vm.id }?.let { r ->
        AlertDialog(onDismissRequest = { vm.answerTool(false) }, title = { Text("Allow ${r.tool.name}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${agent?.name ?: "The assistant"} wants to use ${r.tool.name}. ${r.tool.description}", fontSize = 13.sp)
                Text(r.input, fontFamily = La.mono, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(La.colors.surface2).padding(10.dp))
                Text("Change this in Agents › Tool registry.", fontSize = 11.5.sp, color = La.colors.text3)
            } },
            confirmButton = { TextButton(onClick = { vm.answerTool(true) }) { Text("Allow once") } },
            dismissButton = { TextButton(onClick = { vm.answerTool(false) }) { Text("Deny") } })
    }
    if (showKbs) {
        var sel by remember { mutableStateOf(selectedKbs.toSet()) }
        AlertDialog(onDismissRequest = { showKbs = false }, title = { Text("Knowledge bases") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Answers will cite passages from the selected knowledge bases.", fontSize = 12.5.sp, color = La.colors.text3)
                if (kbs.isEmpty()) Text("No knowledge bases yet. Create one in the Knowledge tab.", modifier = Modifier.padding(top = 8.dp))
                kbs.filter { it.scope == "SHARED" || it.ownerAgentId == c?.agentId }.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { sel = if (k.id in sel) sel - k.id else sel + k.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(k.id in sel, { sel = if (k.id in sel) sel - k.id else sel + k.id }); Text(k.name)
                    }
                }
                if (kbs.any { it.scope == "AGENT" && it.ownerAgentId != c?.agentId }) Text("Agent-only knowledge bases appear when chatting with their agent.", fontSize = 11.5.sp, color = La.colors.text3)
            } },
            confirmButton = { TextButton(onClick = { vm.setKbs(sel.toList()); showKbs = false }) { Text("Done") } })
    }
    if (showAgents) AlertDialog(onDismissRequest = { showAgents = false }, title = { Text("Agent mode") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AgentOption("Off", "Plain chat with the model", c?.agentId == null) { vm.setAgent(null, false); showAgents = false }
            AgentOption("Supervisor workflow", "Plans tasks and delegates to your agents", supervisor) { vm.setAgent(null, true); showAgents = false }
            agents.forEach { a -> AgentOption(a.name, a.role, a.id == c?.agentId) { vm.setAgent(a, false); showAgents = false } }
            if (agents.isEmpty()) TextButton(onClick = { showAgents = false; nav.navigate("agentEdit/new") }) { Text("Create an agent") }
        } },
        confirmButton = { TextButton(onClick = { showAgents = false }) { Text("Close") } })
    if (showModels) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Choose a model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val gens = installed.filter { !Catalog.isEmbedding(it.id) }
                if (gens.isEmpty()) Text("No chat models installed yet.")
                Text("One model is loaded at a time. Switching unloads the current one.", fontSize = 12.sp, color = La.colors.text3)
                gens.forEach { m ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.switchModel(m.id); showModels = false }.padding(10.dp)) {
                        Text(m.name, modifier = Modifier.weight(1f), fontWeight = if (m.id == activeId) FontWeight.Bold else FontWeight.Normal)
                        Text(m.quant, color = La.colors.text3, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showModels = false; nav.navigate("models") }) { Text("Get more models") } })
    if (showOptions) {
        var prompt by remember { mutableStateOf(c?.systemPrompt ?: "") }
        var title by remember { mutableStateOf(c?.title ?: "") }
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { showOptions = false }, title = { Text("Conversation") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, label = { Text(if (agent != null) "System prompt (from ${agent.name})" else "System prompt") }, minLines = 3, maxLines = 6, enabled = agent == null)
                    if (agent?.memoryEnabled == true) TextButton(onClick = { vm.suggestMemories(); showOptions = false }) { Icon(Icons.Outlined.Psychology, null); Text("  Suggest memories from this chat") }
                    if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, null, tint = La.colors.bad); Text(" Delete conversation", color = La.colors.bad) }
                    else TextButton(onClick = { vm.delete { nav.popBackStack() } }) { Text("Tap again to delete permanently", color = La.colors.bad) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveConversation(prompt, title); showOptions = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Cancel") } })
    }
}

@Composable
private fun AgentOption(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) La.colors.accentSoft else La.colors.surface1).clickable(onClick = onClick).padding(10.dp)) {
        Column { Text(title, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.sp, color = La.colors.text3) }
    }
}

@Composable
private fun SourceRow(s: SourceRef, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, La.colors.line, RoundedCornerShape(10.dp)).background(La.colors.surface1)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${s.n}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = La.colors.knowledge, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(La.colors.knowledge.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
        Text(s.title, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
        s.location?.let { Text(it, fontSize = 11.sp, color = La.colors.text3) }
    }
}

@Composable
private fun ToolTraceRow(t: ToolTrace) {
    val ok = t.status == "OK"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(La.colors.surface2).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("${t.tool.replace('_', ' ')} · ${if (ok) "used" else if (t.status == "DENIED") "declined" else "failed"}", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            color = if (ok) La.colors.knowledge else La.colors.warn)
        Text("${t.input} → ${t.output.replace('\n', ' ').take(140)}", fontSize = 11.5.sp, color = La.colors.text2, fontFamily = La.mono, maxLines = 3)
    }
}

@Composable
private fun UserBubble(text: String, imagePath: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (imagePath != null) {
            val bmp = rememberThumbnail(imagePath)
            Box(Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(16.dp)).background(La.colors.surface2)) {
                if (bmp != null) Image(bmp, "Attached photo", Modifier.widthIn(max = 240.dp), contentScale = ContentScale.Fit)
                else Text("Photo", Modifier.padding(24.dp), color = La.colors.text3)
            }
        }
        if (imagePath == null || text != DEFAULT_PHOTO_PROMPT)
            Text(text, modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).background(La.colors.accentSoft).padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 15.sp)
    }
}

@Composable
private fun rememberThumbnail(path: String, max: Int = 600): ImageBitmap? =
    produceState<ImageBitmap?>(null, path) { value = Images.thumbnail(File(path), max)?.asImageBitmap() }.value

@Composable
private fun PendingPhoto(path: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(La.colors.surface1).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val bmp = rememberThumbnail(path, 200)
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(La.colors.surface3)) {
            if (bmp != null) Image(bmp, "Photo to send", Modifier.size(56.dp), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("Photo attached", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("Ask about it, or just send. Text in the photo is read on the phone.", fontSize = 11.5.sp, color = La.colors.text3)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove photo", tint = La.colors.text2) }
    }
}

@Composable
private fun Composer(
    value: String, onInput: (String) -> Unit, busy: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit, footer: String?,
    canSendEmpty: Boolean = false, attach: (@Composable () -> Unit)? = null, mic: (() -> Unit)? = null, dictating: Boolean = false, level: Float = 0f,
) {
    Column(Modifier.fillMaxWidth().background(La.colors.surface1).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(La.colors.surface2).border(1.dp, La.colors.line, RoundedCornerShape(24.dp)).padding(4.dp),
            verticalAlignment = Alignment.Bottom) {
            attach?.invoke()
            TextField(value, onInput, modifier = Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 6, enabled = enabled,
                colors = TextFieldDefaults.colors(focusedContainerColor = La.colors.surface2, unfocusedContainerColor = La.colors.surface2, disabledContainerColor = La.colors.surface2,
                    focusedIndicatorColor = La.colors.surface2, unfocusedIndicatorColor = La.colors.surface2, disabledIndicatorColor = La.colors.surface2))
            if (mic != null && !busy && (value.isBlank() || dictating)) {
                IconButton(onClick = mic, enabled = enabled, modifier = Modifier.padding(4.dp).size(44.dp).clip(CircleShape)
                    .background(if (dictating) La.colors.accent.copy(alpha = 0.25f + 0.6f * level) else La.colors.surface3)) {
                    Icon(Icons.Outlined.Mic, if (dictating) "Stop dictation" else "Dictate", tint = if (dictating) La.colors.onAccent else La.colors.text2)
                }
            }
            val sendable = enabled && (value.isNotBlank() || canSendEmpty)
            val bg = if (busy) La.colors.text else La.colors.accent
            IconButton(onClick = if (busy) onStop else onSend, enabled = busy || sendable,
                modifier = Modifier.padding(4.dp).size(44.dp).clip(CircleShape).background(if (busy || sendable) bg else La.colors.surface4)) {
                Icon(if (busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, if (busy) "Stop generating" else "Send", tint = if (busy) La.colors.bg else La.colors.onAccent)
            }
        }
        if (footer != null) Text(footer, fontSize = 11.sp, color = La.colors.text3, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    }
}
