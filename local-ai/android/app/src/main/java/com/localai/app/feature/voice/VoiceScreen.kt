package com.localai.app.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.localai.app.core.designsystem.La
import com.localai.app.data.chat.ChatService
import com.localai.app.data.db.ChatDao
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.voice.HeardEvent
import com.localai.app.data.voice.SentenceStream
import com.localai.app.data.voice.Speakable
import com.localai.app.data.voice.SpeechInput
import com.localai.app.data.voice.SpeechOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class VoicePhase(val label: String) { IDLE("Tap to talk"), LISTENING("Listening…"), THINKING("Thinking…"), SPEAKING("Speaking · tap to interrupt") }

/**
 * Hands-free conversation: listen → the on-device model answers → the phone speaks it sentence by sentence
 * while it is still being written → listen again. Tapping the orb interrupts at any point.
 * Uses the same conversation as the chat screen, so the transcript is kept.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val chat: ChatService,
    private val dao: ChatDao,
    private val input: SpeechInput,
    private val output: SpeechOutput,
    private val settings: SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val calls: com.localai.app.data.phone.CallManager,
) : ViewModel() {
    /** Talk over the assistant to interrupt it (barge-in), like a phone call. */
    private val monitor = com.localai.app.data.voice.BargeInMonitor()
    var bargeIn by mutableStateOf(com.localai.app.data.voice.BargeInSensitivity.NORMAL); private set
    /** True while the microphone is watching for you to interrupt. */
    var interruptible by mutableStateOf(false); private set
    init { viewModelScope.launch { bargeIn = com.localai.app.data.voice.BargeInSensitivity.of(settings.current().bargeIn) } }

    fun cycleBargeIn() {
        val all = com.localai.app.data.voice.BargeInSensitivity.entries
        bargeIn = all[(all.indexOf(bargeIn) + 1) % all.size]
        viewModelScope.launch { settings.setBargeIn(bargeIn.name) }
        if (bargeIn == com.localai.app.data.voice.BargeInSensitivity.OFF) stopMonitor()
        else if (phase == VoicePhase.THINKING || phase == VoicePhase.SPEAKING) startMonitor()
    }

    private fun micAllowed() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Watch the mic while the assistant thinks or speaks; talking interrupts it and starts listening. */
    private fun startMonitor() {
        if (!active || useVoiceTyping || !micAllowed()) return
        interruptible = monitor.start(bargeIn) { onBargeIn() }
    }

    private fun stopMonitor() { monitor.stop(); interruptible = false }

    private fun onBargeIn() {
        interruptible = false
        if (!active || (phase != VoicePhase.SPEAKING && phase != VoicePhase.THINKING)) return
        turn?.cancel(); output.stop(); chat.stop()
        silentTurns = 0
        phase = VoicePhase.LISTENING; heard = ""
        // Give Android a moment to release the microphone before the recognizer opens it.
        viewModelScope.launch { kotlinx.coroutines.delay(120); listen() }
    }
    val id: String = checkNotNull(savedState["id"])
    var phase by mutableStateOf(VoicePhase.IDLE); private set
    var heard by mutableStateOf(""); private set
    var reply by mutableStateOf(""); private set
    var level by mutableStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set
    var onDevice by mutableStateOf<Boolean?>(null); private set
    /** Keep the conversation going after each answer. */
    var handsFree by mutableStateOf(true)
    /** No recognition service works on this phone: each turn uses Android's voice-typing screen instead. */
    var useVoiceTyping by mutableStateOf(false); private set
    /** Incremented to ask the screen to open the voice-typing screen. */
    var voiceTypingRequest by mutableStateOf(0); private set
    fun voiceTypingIntent(lang: String) = input.voiceTypingIntent(lang)
    fun heardTyped(text: String?) { if (text.isNullOrBlank()) { phase = VoicePhase.IDLE; return }; silentTurns = 0; ask(text) }
    private var active = true
    private var turn: Job? = null
    private var silentTurns = 0

    fun tapOrb() {
        error = null
        when (phase) {
            VoicePhase.IDLE -> listen()
            VoicePhase.LISTENING -> input.stop()
            VoicePhase.THINKING -> { stopMonitor(); chat.stop(); turn?.cancel(); phase = VoicePhase.IDLE }
            VoicePhase.SPEAKING -> { stopMonitor(); turn?.cancel(); output.stop(); chat.stop(); listen() }
        }
    }

    fun listen() {
        if (!active) return
        stopMonitor()
        if (chat.live.value != null) {
            // An interrupted reply may take a moment to stop.
            viewModelScope.launch { repeat(20) { if (chat.live.value == null) { listen(); return@launch }; kotlinx.coroutines.delay(100) }
                error = "The model is busy with another reply."; phase = VoicePhase.IDLE }
            return
        }
        output.stop()
        if (useVoiceTyping) { phase = VoicePhase.LISTENING; heard = ""; voiceTypingRequest++; return }
        phase = VoicePhase.LISTENING; heard = ""; level = 0f
        viewModelScope.launch {
            input.listen(settings.current().voiceLanguage) { e ->
                when (e) {
                    HeardEvent.Ready -> onDevice = input.lastWasOnDevice
                    is HeardEvent.Partial -> heard = e.text
                    is HeardEvent.Level -> level = e.rms
                    is HeardEvent.Final -> { level = 0f; if (e.text.isBlank()) quiet() else { silentTurns = 0; ask(e.text) } }
                    is HeardEvent.Error -> {
                        level = 0f
                        when {
                            e.offerVoiceTyping -> { useVoiceTyping = true; voiceTypingRequest++ }   // fall back to the system voice-typing screen
                            e.noSpeech -> quiet()
                            else -> { error = e.message; phase = VoicePhase.IDLE }
                        }
                    }
                }
            }
        }
    }

    /** Nothing was said: try once more in hands-free mode, then wait for a tap. */
    private fun quiet() {
        silentTurns++
        if (handsFree && silentTurns < 2 && active) listen() else { phase = VoicePhase.IDLE; heard = "" }
    }

    private fun ask(text: String) {
        heard = text; reply = ""; phase = VoicePhase.THINKING
        startMonitor()
        turn = viewModelScope.launch {
            val s = settings.current()
            chat.send(id, text)
            // wait for the reply to start, then speak each finished sentence while the rest is generated
            withTimeoutOrNull(15_000) { chat.live.first { it?.conversationId == id } } ?: run { phase = VoicePhase.IDLE; error = "The model did not start. Check the chat for details."; return@launch }
            val stream = SentenceStream()
            chat.live.takeWhile { it?.conversationId == id }.collect { l ->
                if (l != null && l.text.isNotEmpty()) {
                    reply = l.text
                    stream.feed(l.text).forEach { sentence -> speak(sentence, s.voiceLanguage, s.speechRate) }
                }
            }
            val last = dao.messages(id).lastOrNull { it.role == "assistant" }
            if (last != null) {
                reply = last.content
                val rest = if (last.status == "ERROR") listOf(Speakable.clean(last.content)) else stream.flush(last.content)
                rest.forEach { speak(it, s.voiceLanguage, s.speechRate) }
            }
            if (phase == VoicePhase.THINKING && !output.speaking.value) { phase = VoicePhase.IDLE; stopMonitor() }
            // A call is starting (or we asked who someone is): stop the listening loop so the mic is free.
            val calling = { calls.pending.value != null || calls.askWho.value != null }
            if (calling()) stopMonitor()
            output.onIdle { if (active && phase == VoicePhase.SPEAKING) { stopMonitor(); if (handsFree && !calling()) listen() else phase = VoicePhase.IDLE } }
        }
    }

    private suspend fun speak(text: String, lang: String, rate: Float) {
        if (!output.enqueue(text, lang, rate)) { error = "No text-to-speech engine is installed (Android settings › Text-to-speech)."; return }
        phase = VoicePhase.SPEAKING
    }

    fun pause() { active = false; stopMonitor(); input.cancel(); output.stop() }
    fun resume() { active = true }

    override fun onCleared() { active = false; stopMonitor(); input.cancel(); output.stop(); chat.discardIfEmpty(id) }
}

@Composable
fun VoiceScreen(nav: NavController, vm: VoiceViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.listen() }
    val hasMic = { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    LaunchedEffect(Unit) {
        if (!asked) { asked = true; if (hasMic()) vm.listen() else permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    DisposableEffect(Unit) { vm.resume(); onDispose { vm.pause() } }
    val voiceTyping = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.heardTyped(res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }
    LaunchedEffect(vm.voiceTypingRequest) {
        if (vm.voiceTypingRequest > 0) runCatching { voiceTyping.launch(vm.voiceTypingIntent("")) }.onFailure { vm.heardTyped(null) }
    }

    val phase = vm.phase
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(if (phase == VoicePhase.THINKING) 600 else 1400, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val level by animateFloatAsState(if (phase == VoicePhase.LISTENING) vm.level else 0f, tween(120), label = "lvl")
    val scale = when (phase) { VoicePhase.LISTENING -> 0.95f + level * 0.35f; VoicePhase.IDLE -> 1f; else -> breathe }
    val c1 = when (phase) { VoicePhase.LISTENING -> La.colors.accent; VoicePhase.THINKING -> La.colors.agent; VoicePhase.SPEAKING -> La.colors.knowledge; VoicePhase.IDLE -> La.colors.surface4 }

    Column(Modifier.fillMaxSize().background(La.colors.bg).statusBarsPadding().navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice", style = MaterialTheme.typography.titleLarge)
                Text(when { vm.useVoiceTyping -> "Using Android voice typing (this phone's recognizer service didn't work)"; vm.onDevice == true -> "Speech recognised on this phone"; vm.onDevice == false -> "Speech via the system recognizer"; else -> "Answers by your on-device model" },
                    fontSize = 11.5.sp, color = La.colors.text3)
            }
            IconButton(onClick = { nav.popBackStack(); nav.navigate("chat/${vm.id}") }) { Icon(Icons.AutoMirrored.Outlined.Chat, "Show as chat", tint = La.colors.text2) }
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.Close, "Close", tint = La.colors.text2) }
        }
        Spacer(Modifier.weight(0.6f))
        Box(Modifier.size(230.dp).clickable(remember { MutableInteractionSource() }, null) { if (phase == VoicePhase.IDLE && !hasMic()) permission.launch(Manifest.permission.RECORD_AUDIO) else vm.tapOrb() },
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(230.dp)) {
                val r = size.minDimension / 2 * 0.78f * scale
                drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.35f), Color.Transparent), center, r * 1.35f), r * 1.35f)
                drawCircle(Brush.radialGradient(listOf(c1, c1.copy(alpha = 0.75f)), center + Offset(-r * 0.25f, -r * 0.25f), r * 1.4f), r)
            }
            Icon(Icons.Outlined.Mic, null,
                tint = Color.White.copy(alpha = if (phase == VoicePhase.LISTENING) 1f else 0.7f), modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(phase.label, style = MaterialTheme.typography.titleMedium, color = La.colors.text)
        if (vm.interruptible) Text("Just start talking to interrupt", fontSize = 12.5.sp, color = La.colors.text3, modifier = Modifier.padding(top = 4.dp))
        vm.error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = La.colors.bad, fontSize = 13.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.heard.isNotBlank()) Text("“${vm.heard}”", color = La.colors.text2, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            if (vm.reply.isNotBlank()) Text(Speakable.clean(vm.reply), color = La.colors.text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().heightIn(min = 0.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(CircleShape).background(La.colors.surface2).clickable { vm.handsFree = !vm.handsFree }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(if (vm.handsFree) "Hands-free: on" else "Hands-free: off", fontSize = 12.sp, color = La.colors.text2)
            }
            Row(Modifier.clip(CircleShape).background(La.colors.surface2).clickable { vm.cycleBargeIn() }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Interrupt by voice: ${vm.bargeIn.label}", fontSize = 12.sp, color = La.colors.text2)
            }
        }
        Text("Works best with earphones. On the speaker, the phone cancels its own voice; if it interrupts itself, choose Off or use earphones.",
            fontSize = 11.sp, color = La.colors.text3, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}
