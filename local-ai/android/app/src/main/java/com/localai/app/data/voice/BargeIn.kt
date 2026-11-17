package com.localai.app.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** How easily speaking interrupts the assistant. */
enum class BargeInSensitivity(val label: String, val marginDb: Float, val holdMs: Int) {
    OFF("Off", Float.MAX_VALUE, Int.MAX_VALUE),
    NORMAL("Normal", 11f, 280),
    SENSITIVE("Sensitive", 7f, 200);
    companion object { fun of(s: String) = entries.firstOrNull { it.name == s } ?: NORMAL }
}

/**
 * Decides when the user starts talking over the assistant ("barge-in").
 * The first [calibrateMs] of each turn measure the background level, which includes the phone's own voice leaking
 * into the microphone (what echo cancellation didn't remove). After that, speech counts only when it stays at least
 * [BargeInSensitivity.marginDb] above that background for [BargeInSensitivity.holdMs], so coughs, taps and the
 * assistant's own voice don't trigger it. Pure logic, unit-tested.
 */
class BargeInLogic(private val sensitivity: BargeInSensitivity, private val calibrateMs: Int = 450, private val floorDb: Float = -62f) {
    private var startMs = -1L
    private var calSum = 0.0; private var calN = 0
    private var baseline = floorDb
    private var aboveSince = -1L
    private var lastMs = 0L

    /** Feed one audio frame's level (dBFS) at [nowMs]; returns true once when the user starts talking. */
    fun feed(levelDb: Float, nowMs: Long): Boolean {
        if (sensitivity == BargeInSensitivity.OFF) return false
        if (startMs < 0) startMs = nowMs
        lastMs = nowMs
        if (nowMs - startMs < calibrateMs) {
            calSum += levelDb; calN++
            baseline = max(floorDb, (calSum / calN).toFloat())
            return false
        }
        // Slowly follow the background (e.g. a fan, or the assistant's voice getting louder), but not speech peaks.
        if (levelDb < baseline + sensitivity.marginDb / 2) baseline = baseline * 0.995f + levelDb * 0.005f
        val threshold = max(baseline + sensitivity.marginDb, floorDb + sensitivity.marginDb + 6f)
        if (levelDb >= threshold) {
            if (aboveSince < 0) aboveSince = nowMs
            if (nowMs - aboveSince >= sensitivity.holdMs) { aboveSince = Long.MAX_VALUE / 2; return true }
        } else if (levelDb < threshold - 4f && aboveSince != Long.MAX_VALUE / 2) {
            aboveSince = -1          // gaps between syllables are short; only a clear drop resets
        }
        return false
    }

    val calibratedBaselineDb get() = baseline

    companion object {
        /** RMS level of 16-bit PCM in dBFS (silence ≈ -90). */
        fun levelDb(pcm: ShortArray, n: Int): Float {
            if (n <= 0) return -90f
            var sum = 0.0
            for (i in 0 until n) { val v = pcm[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / n)
            return if (rms <= 1e-5) -90f else (20 * log10(rms)).toFloat()
        }
    }
}

/**
 * Listens to the microphone while the assistant thinks or speaks. Uses the voice-call input path with Android's
 * echo canceller and noise suppressor (when the phone has them) so the assistant's own voice is mostly removed.
 * Releases the microphone before the speech recognizer needs it.
 */
class BargeInMonitor {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    val isRunning get() = running

    /** Starts listening; [onSpeech] runs once on the main thread when the user starts talking. Returns false if the mic is unavailable. */
    @SuppressLint("MissingPermission")   // callers check RECORD_AUDIO first
    fun start(sensitivity: BargeInSensitivity, onSpeech: () -> Unit): Boolean {
        stop()
        if (sensitivity == BargeInSensitivity.OFF) return false
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 4096)) }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); return false }
        if (AcousticEchoCanceler.isAvailable()) aec = runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (runCatching { r.startRecording() }.isFailure || r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { releaseAll(r); return false }
        record = r; running = true
        val logic = BargeInLogic(sensitivity)
        thread = Thread({
            val buf = ShortArray(512)                       // 32 ms frames
            val t0 = System.currentTimeMillis()
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (logic.feed(BargeInLogic.levelDb(buf, n), System.currentTimeMillis() - t0)) {
                    running = false
                    main.post { stop(); onSpeech() }
                    break
                }
            }
        }, "barge-in").apply { priority = Thread.NORM_PRIORITY + 1; start() }
        return true
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(150) } }; thread = null
        record?.let { releaseAll(it) }; record = null
    }

    private fun releaseAll(r: AudioRecord) {
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { r.release() }
    }
}
