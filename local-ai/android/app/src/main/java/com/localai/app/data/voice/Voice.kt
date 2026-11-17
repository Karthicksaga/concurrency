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
    data class Error(val message: String, val noSpeech: Boolean) : HeardEvent
    data object Ready : HeardEvent
}

/**
 * Speech-to-text through Android's SpeechRecognizer. Prefers the on-device recognizer (Android 12+ when the phone
 * has one installed), otherwise asks the system recognizer to work offline (EXTRA_PREFER_OFFLINE).
 * Must be used from the main thread.
 */
@Singleton
class SpeechInput @Inject constructor(@ApplicationContext private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private val main = Handler(Looper.getMainLooper())

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()
    private fun onDeviceAvailable() = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    /** True when the last session used the on-device recognizer (shown in the UI as "on-device"). */
    var lastWasOnDevice = false; private set

    fun listen(language: String, onEvent: (HeardEvent) -> Unit) = main.post { start(language, onEvent, preferOnDevice = true) }

    private fun start(language: String, onEvent: (HeardEvent) -> Unit, preferOnDevice: Boolean) {
        release()
        onDevice = preferOnDevice && onDeviceAvailable()
        val r = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context)
        else { onEvent(HeardEvent.Error("This phone has no speech recognition service. Install or enable Google speech services.", false)); return }
        recognizer = r
        lastWasOnDevice = onDevice
        val usedOnDevice = onDevice
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(HeardEvent.Ready)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onEvent(HeardEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) { best(b)?.let { onEvent(HeardEvent.Partial(it)) } }
            override fun onResults(b: Bundle?) { onEvent(HeardEvent.Final(best(b).orEmpty())) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                // On-device model missing for this language: fall back to the system recognizer (still offline-preferred).
                if (usedOnDevice && error in setOf(12, 13, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER)) {
                    main.post { start(language, onEvent, preferOnDevice = false) }; return
                }
                onEvent(HeardEvent.Error(describe(error), error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        runCatching { r.startListening(intent) }.onFailure { onEvent(HeardEvent.Error("Could not start the microphone.", false)) }
    }

    /** Stop listening and deliver what was heard so far. */
    fun stop() = main.post { recognizer?.stopListening() }
    fun cancel() = main.post { release() }

    private fun release() { recognizer?.run { runCatching { cancel(); destroy() } }; recognizer = null }

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private fun describe(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs its offline language pack. Download it in Android settings › Speech recognition (or connect once)."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be opened."
        12, 13 -> "This language isn't available for offline speech recognition on this phone."
        else -> "Speech recognition failed (code $e)."
    }
}

/** Text-to-speech with a sentence queue. Speaks with the phone's TTS engine (offline voices when installed). */
@Singleton
class SpeechOutput @Inject constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val pending = AtomicInteger(0)
    private val ids = AtomicInteger(0)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    /** Id of the chat message being read aloud, for the speaker button state. */
    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()
    private var idle: (() -> Unit)? = null

    private suspend fun engine(): TextToSpeech? {
        if (tts == null) {
            ready = CompletableDeferred()
            tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) = finished()
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finished()
                override fun onError(utteranceId: String?, errorCode: Int) = finished()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
            })
        }
        return if (ready.await()) tts else null
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0); _speaking.value = false; _speakingId.value = null
            idle?.let { cb -> idle = null; cb() }
        }
    }

    /** Speaks [text] after whatever is already queued. Returns false if no TTS engine is available. */
    suspend fun enqueue(text: String, language: String, rate: Float, messageId: String? = null): Boolean {
        val t = engine() ?: return false
        if (text.isBlank()) return true
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

    /** Called once the queue empties (used by voice conversation to start listening again). */
    fun onIdle(cb: () -> Unit) { if (pending.get() == 0 && !_speaking.value) cb() else idle = cb }

    fun stop() {
        idle = null
        tts?.stop(); pending.set(0); _speaking.value = false; _speakingId.value = null
    }
}
