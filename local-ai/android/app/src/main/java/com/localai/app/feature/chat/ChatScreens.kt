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
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.width
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
    private val dao: ChatDao,
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
    /** Set when no recognition service works: the screen opens Android's voice-typing screen instead. */
    var needVoiceTyping by mutableStateOf(false)
    fun voiceTypingIntent(lang: String) = speechIn.voiceTypingIntent(lang)
    val summaries = dao.observeSummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    suspend fun newChat(): String = chat.newConversation()
    fun setPinned(cid: String, pinned: Boolean) = viewModelScope.launch { dao.setPinned(cid, pinned) }
    fun rename(cid: String, title: String) = viewModelScope.launch { if (title.isNotBlank()) dao.rename(cid, title.trim()) }
    fun deleteOther(cid: String) = viewModelScope.launch { chat.delete(cid) }
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
                is HeardEvent.Error -> { dictating = false; dictationLevel = 0f; if (e.offerVoiceTyping) needVoiceTyping = true else if (!e.noSpeech) notice = e.message }
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
    val voiceTyping = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = (input.trimEnd() + " " + it).trim() }
    }
    LaunchedEffect(vm.needVoiceTyping) {
        if (vm.needVoiceTyping) { vm.needVoiceTyping = false; runCatching { voiceTyping.launch(vm.voiceTypingIntent(settings?.voiceLanguage ?: "")) }.onFailure { vm.notice = "No voice input app is installed." } }
    }
    val drawer = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    var plusSheet by remember { mutableStateOf(false) }
    val openChat: (String) -> Unit = { id -> scope.launch { drawer.close() }; if (id != vm.id) nav.navigate("chat/$id") { popUpTo("chat/{id}?attach={attach}") { inclusive = true } } }
    val newChat: () -> Unit = { scope.launch { drawer.close(); val id = vm.newChat(); nav.navigate("chat/$id") { popUpTo("chat/{id}?attach={attach}") { inclusive = true } } } }
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

    LaunchedEffect(Unit) { if (vm.autoAttach) plusSheet = true }
    androidx.compose.material3.ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ChatDrawer(nav, summaries, currentId = vm.id, onOpen = openChat, onNew = newChat,
            onPin = { id, p -> vm.setPinned(id, p) }, onRename = { id, t -> vm.rename(id, t) },
            onDelete = { id -> if (id == vm.id) vm.delete { newChat() } else vm.deleteOther(id) },
            close = { scope.launch { drawer.close() } })
    }) {
    Column(Modifier.fillMaxSize().background(La.colors.bg).statusBarsPadding().imePadding()) {
        // Top bar: menu · model picker · new chat
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { drawer.open() } }, Modifier.size(48.dp).clip(CircleShape).background(La.colors.surface1)) {
                Icon(Icons.Outlined.Menu, "Chats and menu", tint = La.colors.text)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable { showModels = true }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (agent != null) { AgentAvatar(agent.icon, agent.color, 22.dp); Spacer(Modifier.width(6.dp)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(agent?.name ?: modelName.removeSuffix(" Instruct"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                    Text(engLabel, color = La.colors.text3, fontSize = 11.sp, maxLines = 1)
                }
                Icon(Icons.Outlined.ExpandMore, null, tint = La.colors.text3, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showOptions = true }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Tune, "Conversation options", tint = La.colors.text2) }
            IconButton(onClick = newChat, Modifier.size(48.dp).clip(CircleShape).background(La.colors.surface1)) { Icon(Icons.Outlined.EditNote, "New chat", tint = La.colors.text) }
        }
        if (activeId == null) Box(Modifier.padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Banner("Download or choose a model before chatting.", Tone.WARN)
                Button(onClick = { nav.navigate("models") }) { Text("Open models") }
            }
        }
        vm.notice?.let { n -> Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable { vm.notice = null }) { Banner(n, Tone.RUN) } }
        if (messages.isEmpty() && myLive == null) {
            // Empty chat: one question, a few starting points
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (agent != null) { AgentAvatar(agent.icon, agent.color, 56.dp); Spacer(Modifier.height(12.dp)) }
                Text(when { supervisor -> "What should your agents work on?"; agent != null -> "Ask ${agent.name}"; else -> "What can I help with?" },
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(if (selectedKbs.isNotEmpty()) "Answers will cite your documents." else "Private and offline · stays on this phone", color = La.colors.text3, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                val starters = listOf(
                    Triple(Icons.Outlined.PhotoCamera, "Explain a photo") { plusSheet = true },
                    Triple(Icons.Outlined.GppMaybe, "Is this message a scam?") { nav.navigate("scam") },
                    Triple(Icons.Outlined.Description, "Write a letter or report") { nav.navigate("studio") },
                    Triple(Icons.Outlined.RecordVoiceOver, "Read a document to me") { nav.navigate("read") },
                )
                starters.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (icon, label, go) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(1.dp, La.colors.line, RoundedCornerShape(16.dp)).clickable(onClick = go).padding(14.dp)) {
                                Icon(icon, null, tint = La.colors.accent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(label, fontSize = 13.5.sp, color = La.colors.text2, maxLines = 2)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        } else LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
        // Active context, only when something is on
        if (selectedKbs.isNotEmpty() || c?.agentId != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedKbs.isNotEmpty()) FilterChip(true, { showKbs = true }, { Text(if (selectedKbs.size == 1) kbs.firstOrNull { it.id == selectedKbs[0] }?.name ?: "1 knowledge base" else "${selectedKbs.size} knowledge bases", maxLines = 1) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(16.dp)) })
            if (c?.agentId != null) FilterChip(true, { showAgents = true }, { Text(if (supervisor) "Supervisor" else agent?.name ?: "Agent", maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.SmartToy, null, Modifier.size(16.dp)) })
        }
        vm.pendingImage?.let { path -> PendingPhoto(path, onRemove = { vm.removeImage() }) }
        if (vm.importingImage) LinearProgressIndicator(Modifier.fillMaxWidth())
        PillComposer(input, onInput = { input = it }, busy = busy, enabled = activeId != null, canSendEmpty = vm.pendingImage != null,
            onPlus = { plusSheet = true },
            onSend = { if (input.isNotBlank() || vm.pendingImage != null) { vm.send(input); input = "" } },
            onStop = { if (runningRun != null) nav.navigate("workflow/${runningRun.id}") else vm.stop() },
            onMic = if (vm.voiceAvailable) ({ onMic() }) else null, dictating = vm.dictating, level = vm.dictationLevel,
            onVoiceMode = { nav.navigate("voice/${vm.id}") },
            footer = if (vm.dictating) "Listening… tap the mic to finish" else stats?.let { "%.1f tok/s · on this phone".format(it.genTps) })
    }
    }
    if (plusSheet) PlusSheet(onDismiss = { plusSheet = false },
        onCamera = { plusSheet = false; takePhoto() }, onPhotos = { plusSheet = false; pickPhoto() },
        onKnowledge = { plusSheet = false; showKbs = true }, onAgent = { plusSheet = false; showAgents = true },
        onScam = { plusSheet = false; nav.navigate("scam") }, onRead = { plusSheet = false; nav.navigate("read") }, onDocs = { plusSheet = false; nav.navigate("studio") },
        knowledgeOn = selectedKbs.isNotEmpty(), agentLabel = when { supervisor -> "Supervisor"; agent != null -> agent.name; else -> null })

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

/** ChatGPT-style composer: + · text · mic · (voice mode | send | stop). */
@Composable
private fun PillComposer(
    value: String, onInput: (String) -> Unit, busy: Boolean, enabled: Boolean, canSendEmpty: Boolean,
    onPlus: () -> Unit, onSend: () -> Unit, onStop: () -> Unit, onMic: (() -> Unit)?, dictating: Boolean, level: Float,
    onVoiceMode: () -> Unit, footer: String?,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(La.colors.surface2).border(1.dp, La.colors.line, RoundedCornerShape(30.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlus, enabled = enabled && !busy, modifier = Modifier.size(42.dp)) { Icon(Icons.Outlined.Add, "Add photo, files and more", tint = La.colors.text) }
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (value.isEmpty()) Text(if (dictating) "Listening…" else "Ask LOCAL AI", color = La.colors.text3, fontSize = 16.sp)
                androidx.compose.foundation.text.BasicTextField(value, onInput, enabled = enabled, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = La.colors.text, fontSize = 16.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(La.colors.accent), modifier = Modifier.fillMaxWidth())
            }
            if (onMic != null && !busy) IconButton(onClick = onMic, enabled = enabled, modifier = Modifier.size(42.dp).clip(CircleShape)
                .background(if (dictating) La.colors.accent.copy(alpha = 0.25f + 0.6f * level) else androidx.compose.ui.graphics.Color.Transparent)) {
                Icon(Icons.Outlined.Mic, if (dictating) "Stop dictation" else "Dictate", tint = if (dictating) La.colors.onAccent else La.colors.text)
            }
            Spacer(Modifier.width(4.dp))
            val sendable = enabled && (value.isNotBlank() || canSendEmpty)
            when {
                busy -> IconButton(onClick = onStop, modifier = Modifier.size(44.dp).clip(CircleShape).background(La.colors.text)) { Icon(Icons.Outlined.Stop, "Stop generating", tint = La.colors.bg) }
                sendable -> IconButton(onClick = onSend, modifier = Modifier.size(44.dp).clip(CircleShape).background(La.colors.accent)) { Icon(Icons.Outlined.ArrowUpward, "Send", tint = La.colors.onAccent) }
                else -> IconButton(onClick = onVoiceMode, enabled = enabled, modifier = Modifier.size(44.dp).clip(CircleShape).background(if (enabled) La.colors.accent else La.colors.surface4)) {
                    Icon(Icons.Outlined.GraphicEq, "Voice mode", tint = La.colors.onAccent)
                }
            }
        }
        if (footer != null) Text(footer, fontSize = 11.sp, color = La.colors.text3, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
    }
}

/** "+" sheet: camera and photos up top, then tools for this chat. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlusSheet(
    onDismiss: () -> Unit, onCamera: () -> Unit, onPhotos: () -> Unit, onKnowledge: () -> Unit, onAgent: () -> Unit,
    onScam: () -> Unit, onRead: () -> Unit, onDocs: () -> Unit, knowledgeOn: Boolean, agentLabel: String?,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = La.colors.surface1) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple(Icons.Outlined.PhotoCamera, "Camera", onCamera), Triple(Icons.Outlined.Image, "Photos", onPhotos), Triple(Icons.AutoMirrored.Outlined.MenuBook, "Knowledge", onKnowledge)).forEach { (icon, label, go) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(La.colors.surface2).clickable(onClick = go).padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, tint = La.colors.text); Spacer(Modifier.height(6.dp)); Text(label, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SheetRow(Icons.Outlined.SmartToy, "Agent mode", agentLabel ?: "Off · plain chat with the model", onAgent)
            SheetRow(Icons.AutoMirrored.Outlined.MenuBook, "Knowledge bases", if (knowledgeOn) "On · answers cite your documents" else "Answer from your documents", onKnowledge)
            SheetRow(Icons.Outlined.GppMaybe, "Scam check", "Is a message or screenshot a fraud?", onScam)
            SheetRow(Icons.Outlined.RecordVoiceOver, "Read it to me", "Photo of a letter, bill or label, read aloud", onRead)
            SheetRow(Icons.Outlined.Description, "Documents", "Write or convert PDF and Word", onDocs)
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = La.colors.text2)
        Column(Modifier.padding(start = 14.dp)) { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.5.sp, color = La.colors.text3) }
    }
}

/** Side drawer: search, app sections, pinned chats, recent chats, and a New chat button. */
@Composable
private fun ChatDrawer(
    nav: NavController, list: List<ConversationSummary>, currentId: String, onOpen: (String) -> Unit, onNew: () -> Unit,
    onPin: (String, Boolean) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit, close: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ConversationSummary?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    val go: (String) -> Unit = { r -> close(); nav.navigate(r) }
    androidx.compose.material3.ModalDrawerSheet(drawerContainerColor = La.colors.bg, modifier = Modifier.widthIn(max = 330.dp)) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (searching) androidx.compose.material3.OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search chats") })
                        else Text("LOCAL AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }, Modifier.clip(CircleShape).background(La.colors.surface1)) {
                            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "Close search" else "Search chats")
                        }
                    }
                }
                if (!searching) {
                    val sections = listOf(
                        Triple(Icons.Outlined.Home, "Home", "home"), Triple(Icons.Outlined.PhotoCamera, "Ask a photo", "photo"),
                        Triple(Icons.Outlined.GppMaybe, "Scam check", "scam"), Triple(Icons.Outlined.RecordVoiceOver, "Read it to me", "read"),
                        Triple(Icons.Outlined.Description, "Documents", "studio"), Triple(Icons.Outlined.SmartToy, "Agents", "agents"),
                        Triple(Icons.AutoMirrored.Outlined.MenuBook, "Knowledge", "knowledge"), Triple(Icons.Outlined.Memory, "Models", "models"),
                    )
                    items(sections) { (icon, label, route) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            if (route == "photo") go("newchat?attach=1") else go(route)
                        }.padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = La.colors.text); Text(label, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
                val filtered = list.filter { query.isBlank() || it.title.contains(query, true) || (it.lastMessage ?: "").contains(query, true) }
                    .filter { it.lastMessage != null || it.id == currentId }
                val pinned = filtered.filter { it.pinned }
                if (pinned.isNotEmpty()) {
                    item { Text("Pinned", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp)) }
                    items(pinned, key = { "p" + it.id }) { c -> DrawerChat(c, c.id == currentId, { onOpen(c.id) }, { menuFor = c }) }
                }
                item { Text(if (query.isBlank()) "Chats" else "Results", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp)) }
                val rest = filtered.filter { !it.pinned }
                if (rest.isEmpty()) item { Text(if (query.isBlank()) "Your chats will appear here." else "No chats match.", color = La.colors.text3, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                items(rest, key = { it.id }) { c -> DrawerChat(c, c.id == currentId, { onOpen(c.id) }, { menuFor = c }) }
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(La.colors.bg).navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onNew, shape = RoundedCornerShape(26.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                    Icon(Icons.Outlined.EditNote, null); Spacer(Modifier.width(8.dp)); Text("New chat", fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { go("settings") }, Modifier.size(48.dp).clip(CircleShape).background(La.colors.surface1)) { Icon(Icons.Outlined.Settings, "Settings") }
            }
        }
    }
    menuFor?.let { c ->
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(c.title, maxLines = 2) },
            text = { Column {
                TextButton(onClick = { onPin(c.id, !c.pinned); menuFor = null }) { Icon(Icons.Outlined.PushPin, null); Text(if (c.pinned) "  Unpin" else "  Pin") }
                TextButton(onClick = { renaming = c; menuFor = null }) { Icon(Icons.Outlined.Edit, null); Text("  Rename") }
                TextButton(onClick = { onDelete(c.id); menuFor = null }) { Icon(Icons.Outlined.Delete, null, tint = La.colors.bad); Text("  Delete", color = La.colors.bad) }
            } },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } })
    }
    renaming?.let { c ->
        var t by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename chat") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(c.id, t); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DrawerChat(c: ConversationSummary, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) La.colors.surface2 else androidx.compose.ui.graphics.Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = La.colors.text2, modifier = Modifier.size(20.dp))
        Text(c.title, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp).weight(1f))
    }
}

/** Opening "newchat" makes a fresh conversation and shows it (empty ones are cleaned up when left). */
@Composable
fun NewChatEntry(nav: NavController, attach: Boolean, vm: ChatListViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) {
        val id = vm.create()
        nav.navigate("chat/$id" + if (attach) "?attach=1" else "") { popUpTo("newchat?attach={attach}") { inclusive = true } }
    }
    Box(Modifier.fillMaxSize().background(La.colors.bg))
}
