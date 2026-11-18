package com.localai.app.data.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Turns model output (markdown) into text worth speaking. */
object Speakable {
    fun clean(md: String): String = md
        .replace(Regex("(?s)<think>.*?</think>"), " ")
        .replace(Regex("(?s)```.*?```"), " (code shown on screen) ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")       // links / images → their text
        .replace(Regex("\\[(\\d+)]"), "")                          // citation markers
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")             // headings
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")                  // bullets
        .replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$"), "")          // table rules
        .replace(Regex("[*_~|>#]"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("([^.!?:;,。！？\\s])\\s*\\n+\\s*"), "$1. ")         // a line break is a pause
        .replace(Regex("\\s*\\n+\\s*"), " ")
        .replace(Regex("\\s+([.,!?;:])"), "$1")
        .replace(Regex("[ \\t]+"), " ")
        .trim()
}

/**
 * Splits streamed text into speakable sentences as soon as each one is complete, so the phone starts talking
 * while the model is still writing. Code blocks are held back until they close.
 */
class SentenceStream {
    private var consumed = 0

    /** Feed the full text so far; returns sentences completed since the last call. */
    fun feed(full: String): List<String> {
        val out = ArrayList<String>()
        var i = consumed
        var inCode = full.substring(0, consumed).split("```").size % 2 == 0
        var start = consumed
        while (i < full.length) {
            if (full.startsWith("```", i)) { inCode = !inCode; i += 3; continue }
            val ch = full[i]
            val end = !inCode && (
                ch == '\n' ||
                (ch in ".!?;:。！？।" && i + 1 < full.length && full[i + 1].isWhitespace() && !isAbbreviation(full, i)))
            if (end) {
                val s = Speakable.clean(full.substring(start, i + 1))
                if (s.any { it.isLetterOrDigit() }) out += s
                start = i + 1
            }
            i++
        }
        consumed = start
        return out
    }

    /** Whatever is left once generation has finished. */
    fun flush(full: String): List<String> {
        val rest = Speakable.clean(full.substring(consumed.coerceAtMost(full.length)))
        consumed = full.length
        return if (rest.any { it.isLetterOrDigit() }) listOf(rest) else emptyList()
    }

    private fun isAbbreviation(s: String, dot: Int): Boolean {
        if (s[dot] != '.') return false
        val word = s.substring(0, dot).takeLastWhile { it.isLetter() }
        if (word.length == 1) return true                                      // initials: "J. Smith"
        if (dot > 0 && s[dot - 1].isDigit() && dot + 2 < s.length && s[dot + 2].isDigit()) return true
        return word.lowercase() in setOf("mr", "mrs", "ms", "dr", "st", "vs", "etc", "eg", "ie", "no", "approx")
    }
}

sealed interface HeardEvent {
    data class Partial(val text: String) : HeardEvent
    data class Final(val text: String) : HeardEvent
    data class Level(val rms: Float) : HeardEvent
    data class Error(val message: String, val noSpeech: Boolean, val offerVoiceTyping: Boolean = false) : HeardEvent
    data object Ready : HeardEvent
}

/**
 * Speech-to-text through Android's SpeechRecognizer, trying in order:
 *  1. the on-device recognizer (Android 12+, when the phone has one),
 *  2. the phone's default recognition service (offline preferred),
 *  3. Google's recognition service directly (some phones, e.g. MIUI, set another default that fails).
 * A route that fails with a service error (e.g. code 11 "server disconnected") is remembered and skipped next time.
 * If every route fails, the error asks the UI to offer Android's own voice-typing screen (RecognizerIntent).
 * Must be used from the main thread.
 */
@Singleton
class SpeechInput @Inject constructor(@ApplicationContext private val context: Context) {
    private enum class Route { ON_DEVICE, DEFAULT, GOOGLE }
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
    private val googleService = android.content.ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")

    private fun broken(r: Route) = prefs.getBoolean("broken_" + r.name, false)
    private fun markBroken(r: Route) = prefs.edit().putBoolean("broken_" + r.name, true).apply()

    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    private fun googleInstalled() = runCatching { context.packageManager.getServiceInfo(googleService, 0); true }.getOrDefault(false)

    private fun routes(): List<Route> = buildList {
        if (onDeviceAvailable()) add(Route.ON_DEVICE)
        if (SpeechRecognizer.isRecognitionAvailable(context)) add(Route.DEFAULT)
        if (googleInstalled()) add(Route.GOOGLE)
    }.filterNot { broken(it) }.ifEmpty { listOf(Route.DEFAULT, Route.GOOGLE).filter { it != Route.GOOGLE || googleInstalled() } }

    /** Something can listen: a recognition service, or at least the voice-typing screen. */
    val available: Boolean get() = routes().isNotEmpty() || voiceTypingIntent().resolveActivity(context.packageManager) != null

    /** True when the last session used the on-device recognizer (shown in the UI as "on-device"). */
    var lastWasOnDevice = false; private set

    fun listen(language: String, onEvent: (HeardEvent) -> Unit) = main.post { start(language, onEvent, routes(), 0) }

    private fun start(language: String, onEvent: (HeardEvent) -> Unit, chain: List<Route>, i: Int) {
        release()
        val route = chain.getOrNull(i) ?: run {
            onEvent(HeardEvent.Error("Speech recognition isn't working on this phone. Use voice typing instead, or check Android settings › Voice input.", false, offerVoiceTyping = true)); return
        }
        val r = runCatching {
            when (route) {
                Route.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
                Route.DEFAULT -> SpeechRecognizer.createSpeechRecognizer(context)
                Route.GOOGLE -> SpeechRecognizer.createSpeechRecognizer(context, googleService)
            }
        }.getOrNull() ?: return start(language, onEvent, chain, i + 1)
        recognizer = r
        lastWasOnDevice = route == Route.ON_DEVICE
        var heardSomething = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(HeardEvent.Ready)
            override fun onBeginningOfSpeech() { heardSomething = true }
            override fun onRmsChanged(rmsdB: Float) = onEvent(HeardEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) { best(b)?.let { onEvent(HeardEvent.Partial(it)) } }
            override fun onResults(b: Bundle?) { onEvent(HeardEvent.Final(best(b).orEmpty())) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val permission = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (noSpeech || permission || (heardSomething && error == SpeechRecognizer.ERROR_NETWORK)) {
                    onEvent(HeardEvent.Error(describe(error), noSpeech)); return
                }
                // Service-level failure (client/server/disconnected/busy/language/network): this route doesn't work here.
                if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) markBroken(route)
                main.post { start(language, onEvent, chain, i + 1) }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, route != Route.GOOGLE)   // Google route may use its online model if no pack
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        runCatching { r.startListening(intent) }.onFailure { markBroken(route); main.post { start(language, onEvent, chain, i + 1) } }
    }

    /** Android's own voice-typing screen: the last resort that works on almost every phone. */
    fun voiceTypingIntent(language: String = ""): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }

    /** Forget remembered failures (e.g. after the user installs a speech pack). */
    fun resetRoutes() = prefs.edit().clear().apply()

    /** Stop listening and deliver what was heard so far. */
    fun stop() = main.post { recognizer?.stopListening() }
    fun cancel() = main.post { release() }

    private fun release() { recognizer?.run { runCatching { cancel(); destroy() } }; recognizer = null }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun describe(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition lost its connection. Try again."
        else -> "Speech recognition failed (code $e)."
    }
}

/** Text-to-speech with a sentence queue. Speaks with the phone's TTS engine (offline voices when installed). */
@Singleton
class SpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: com.localai.app.data.settings.SettingsRepository,
) {
    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    /** Id of the chat message being read aloud, for the speaker button state. */
    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()
    @Volatile private var idle: (() -> Unit)? = null

    // ---- natural (neural) voice pipeline: synthesis runs one sentence ahead of playback ----
    private val neural by lazy { NaturalTtsEngine(context) }
    private val synthExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tts-synth") }
    private val playExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tts-play").apply { priority = Thread.MAX_PRIORITY } }
    private val generation = AtomicInteger(0)
    @Volatile private var track: android.media.AudioTrack? = null
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    private suspend fun engine(): TextToSpeech? {
        if (tts == null) {
            ready = CompletableDeferred()
            tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) = done(utteranceId)
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = done(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = done(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = done(utteranceId)
            })
        }
        return if (ready.await()) tts else null
    }

    /** Utterances started by the neural pipeline only release their waiter; the pipeline counts them itself. */
    private fun done(id: String?) { val w = id?.let { waiters.remove(it) }; if (w != null) w.countDown() else finished() }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0); _speaking.value = false; _speakingId.value = null
            idle?.let { cb -> idle = null; cb() }
        }
    }

    /** Speaks [text] after whatever is already queued. Returns false if no TTS engine is available. */
    suspend fun enqueue(text: String, language: String, rate: Float, messageId: String? = null): Boolean {
        if (text.isBlank()) return true
        val s = settings.current()
        val voice = NaturalVoices.byId(s.ttsVoice)?.takeIf { NaturalVoices.isInstalled(context, it) }
        if (voice != null) {
            val t = engine()          // the phone's voice is still used, in order, for text the natural voice can't speak
            messageId?.let { _speakingId.value = it }
            enqueueNatural(text, voice, s.ttsSpeaker, rate, language, t)
            return true
        }
        val t = engine() ?: return false
        val loc = if (language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(language)
        if (t.voice?.locale != loc) runCatching { t.setLanguage(loc) }
        t.setSpeechRate(rate)
        messageId?.let { _speakingId.value = it }
        // Android limits one utterance to getMaxSpeechInputLength() characters (4000).
        text.chunked(TextToSpeech.getMaxSpeechInputLength() - 10).forEach { part ->
            pending.incrementAndGet(); _speaking.value = true
            t.speak(part, TextToSpeech.QUEUE_ADD, null, "u" + ids.incrementAndGet())
        }
        return true
    }

    private fun enqueueNatural(text: String, voice: NaturalVoice, speaker: Int, rate: Float, language: String, system: TextToSpeech?) {
        val gen = generation.get()
        // Split long text into sentences so the first one starts playing quickly.
        val parts = text.split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { it.chunked(400) }
        parts.forEach { part ->
            pending.incrementAndGet(); _speaking.value = true
            synthExec.execute {
                if (generation.get() != gen) { finished(); return@execute }
                val audio = if (NaturalVoices.canSpeak(voice, part) && neural.load(voice, 2)) neural.generate(part, speaker.coerceIn(0, voice.speakers - 1), rate) else null
                playExec.execute {
                    try {
                        if (generation.get() != gen) return@execute
                        if (audio != null) play(audio, gen) else if (system != null) speakSystemAndWait(system, part, language, rate)
                    } finally { finished() }
                }
            }
        }
    }

    private fun play(a: Speech, gen: Int) {
        val t = track?.takeIf { it.sampleRate == a.sampleRate } ?: run {
            track?.release()
            val min = android.media.AudioTrack.getMinBufferSize(a.sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_FLOAT)
            android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(a.sampleRate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, a.sampleRate * 4 / 2)).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
                .also { track = it }
        }
        if (t.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) t.play()
        val start = t.playbackHeadPosition
        var off = 0
        while (off < a.samples.size && generation.get() == gen) {
            val n = t.write(a.samples, off, minOf(2048, a.samples.size - off), android.media.AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        // write() returns when the audio is buffered; wait until it has actually been heard.
        val end = start + off
        var guard = 0
        while (generation.get() == gen && t.playbackHeadPosition < end && guard++ < 1500) Thread.sleep(10)
    }

    private fun speakSystemAndWait(t: TextToSpeech, text: String, language: String, rate: Float) {
        val loc = if (language.isBlank()) Locale.getDefault() else Locale.forLanguageTag(language)
        if (t.voice?.locale != loc) runCatching { t.setLanguage(loc) }
        t.setSpeechRate(rate)
        val id = "p" + ids.incrementAndGet()
        val latch = java.util.concurrent.CountDownLatch(1); waiters[id] = latch
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Plays a short sample in [voice] (Settings preview). */
    suspend fun preview(voice: NaturalVoice, speaker: Int, rate: Float) {
        stop()
        val sample = if (voice.language == "hi") "नमस्ते! मैं आपकी मदद के लिए यहाँ हूँ। आज मैं आपके लिए क्या कर सकती हूँ?"
            else "Hi! I'm your assistant, running right here on your phone. What would you like to talk about?"
        val t = engine()
        enqueueNatural(sample, voice, speaker, rate, if (voice.language == "hi") "hi-IN" else "en-IN", t)
    }

    /** Frees the neural voice (≈ 100–200 MB) when memory is low or the voice changes. */
    fun releaseVoice() { synthExec.execute { neural.release() } }

    /** Called once the queue empties (used by voice conversation to start listening again). */
    fun onIdle(cb: () -> Unit) { if (pending.get() == 0 && !_speaking.value) cb() else idle = cb }

    fun stop() {
        idle = null
        generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
        waiters.values.forEach { it.countDown() }; waiters.clear()
        tts?.stop(); pending.set(0); _speaking.value = false; _speakingId.value = null
    }
}
