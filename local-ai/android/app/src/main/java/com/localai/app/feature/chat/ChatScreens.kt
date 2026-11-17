package com.localai.app.feature.chat

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
) : ViewModel() {
    val id: String = checkNotNull(savedState["id"])
    val messages = dao.observeMessages(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val conversation = dao.observeConversation(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val live = chat.live
    val engineState = engine.state
    val lastStats = engine.lastStats
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val installed: StateFlow<List<InstalledModelEntity>> = models.installed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun send(text: String) = chat.send(id, text)
    fun stop() = chat.stop()
    fun regenerate() = chat.regenerate(id)
    fun switchModel(modelId: String) = viewModelScope.launch { models.activate(modelId) }
    fun saveConversation(prompt: String, title: String) = viewModelScope.launch { chat.updateSystemPrompt(id, prompt, title) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { chat.delete(id); onDone() }
}

@Composable
fun ChatScreen(nav: NavController, vm: ChatViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conv by vm.conversation.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val eng by vm.engineState.collectAsStateWithLifecycle()
    val stats by vm.lastStats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var input by rememberSaveable { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val myLive = live?.takeIf { it.conversationId == vm.id }
    val busy = live != null
    val activeId = settings?.activeModelId
    val modelName = installed.firstOrNull { it.id == activeId }?.name ?: activeId?.let { Catalog.byId(it)?.name } ?: "No model selected"
    val engLabel = when (val e = eng) {
        is EngineState.Ready -> if (e.modelId == activeId) "ready · ${e.contextLength / 1024}K context" else "not loaded"
        is EngineState.Loading -> "loading…"
        is EngineState.Failed -> "failed to load"
        EngineState.Idle -> "loads on first message"
    }
    LaunchedEffect(messages.size, myLive?.text?.length) {
        val n = messages.size + (if (myLive != null) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n - 1, scrollOffset = Int.MAX_VALUE / 4)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f).clickable { showModels = true }) {
                Text(conv?.title ?: "Chat", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("$modelName · $engLabel", color = La.colors.text3, fontSize = 12.sp, maxLines = 1)
            }
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
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (messages.isEmpty() && myLive == null) item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ask anything, offline", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("This conversation stays on your phone.", color = La.colors.text2, fontSize = 13.sp)
                }
            }
            items(messages, key = { it.id }) { m ->
                if (m.role == "user") UserBubble(m.content)
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(installed.firstOrNull { it.id == m.modelId }?.name ?: m.modelId ?: "Assistant", fontSize = 12.sp, color = La.colors.text3)
                    if (m.status == "ERROR") Banner(m.content, Tone.BAD) else MarkdownText(m.content)
                    if (m.status == "STOPPED") Text("Generation stopped.", fontSize = 12.sp, color = La.colors.text3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(m.content)) }, Modifier.size(34.dp)) { Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(17.dp), tint = La.colors.text3) }
                        if (m == messages.last() && !busy) IconButton(onClick = { vm.regenerate() }, Modifier.size(34.dp)) { Icon(Icons.Outlined.Refresh, "Regenerate", Modifier.size(17.dp), tint = La.colors.text3) }
                        m.genTps?.let { Text("%.1f tok/s".format(it) + (m.tokens?.let { t -> " · $t tokens" } ?: ""), fontSize = 11.sp, color = La.colors.text3, modifier = Modifier.padding(start = 6.dp)) }
                    }
                }
            }
            if (myLive != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(modelName, fontSize = 12.sp, color = La.colors.text3)
                    if (myLive.text.isEmpty()) {
                        Text(myLive.phase.ifBlank { "Thinking…" }, color = La.colors.text2, fontSize = 13.sp)
                        LinearProgressIndicator(Modifier.fillMaxWidth(0.5f))
                    } else MarkdownText(myLive.text + " ▍")
                }
            }
        }
        Composer(input, onInput = { input = it }, busy = busy, enabled = activeId != null,
            onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } }, onStop = { vm.stop() },
            footer = stats?.let { "Last reply %.1f tok/s · prompt %.0f tok/s".format(it.genTps, it.promptTps) })
    }

    if (showModels) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Choose a model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installed.isEmpty()) Text("No models installed yet.")
                Text("One model is loaded at a time. Switching unloads the current one.", fontSize = 12.sp, color = La.colors.text3)
                installed.forEach { m ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.switchModel(m.id); showModels = false }.padding(10.dp)) {
                        Text(m.name, modifier = Modifier.weight(1f), fontWeight = if (m.id == activeId) FontWeight.Bold else FontWeight.Normal)
                        Text(m.quant, color = La.colors.text3, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showModels = false; nav.navigate("models") }) { Text("Get more models") } })

    if (showOptions) {
        var prompt by remember { mutableStateOf(conv?.systemPrompt ?: "") }
        var title by remember { mutableStateOf(conv?.title ?: "") }
        var confirmDelete by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { showOptions = false }, title = { Text("Conversation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt") }, minLines = 3, maxLines = 6)
                    if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, null, tint = La.colors.bad); Text(" Delete conversation", color = La.colors.bad) }
                    else TextButton(onClick = { vm.delete { nav.popBackStack() } }) { Text("Tap again to delete permanently", color = La.colors.bad) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.saveConversation(prompt, title); showOptions = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Cancel") } })
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        Text(text, modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)).background(La.colors.accentSoft).padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 15.sp)
    }
}

@Composable
private fun Composer(value: String, onInput: (String) -> Unit, busy: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit, footer: String?) {
    Column(Modifier.fillMaxWidth().background(La.colors.surface1).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(La.colors.surface2).border(1.dp, La.colors.line, RoundedCornerShape(24.dp)).padding(4.dp),
            verticalAlignment = Alignment.Bottom) {
            TextField(value, onInput, modifier = Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 6, enabled = enabled,
                colors = TextFieldDefaults.colors(focusedContainerColor = La.colors.surface2, unfocusedContainerColor = La.colors.surface2, disabledContainerColor = La.colors.surface2,
                    focusedIndicatorColor = La.colors.surface2, unfocusedIndicatorColor = La.colors.surface2, disabledIndicatorColor = La.colors.surface2))
            val bg = if (busy) La.colors.text else La.colors.accent
            IconButton(onClick = if (busy) onStop else onSend, enabled = busy || (enabled && value.isNotBlank()),
                modifier = Modifier.padding(4.dp).size(44.dp).clip(CircleShape).background(if (busy || (enabled && value.isNotBlank())) bg else La.colors.surface4)) {
                Icon(if (busy) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, if (busy) "Stop generating" else "Send", tint = if (busy) La.colors.bg else La.colors.onAccent)
            }
        }
        if (footer != null) Text(footer, fontSize = 11.sp, color = La.colors.text3, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
    }
}
