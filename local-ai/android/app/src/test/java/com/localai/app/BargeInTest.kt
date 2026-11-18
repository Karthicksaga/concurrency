package com.localai.app

import com.localai.app.data.voice.BargeInLogic
import com.localai.app.data.voice.BargeInSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {
    /** Feeds 32 ms frames; returns the time (ms) of the trigger or -1. */
    private fun run(logic: BargeInLogic, levels: (Long) -> Float, untilMs: Long = 5000): Long {
        var t = 0L
        while (t <= untilMs) { if (logic.feed(levels(t), t)) return t; t += 32 }
        return -1
    }

    @Test fun assistantVoiceEchoAloneNeverTriggers() {
        // the phone's own voice leaking into the mic: -42 dB with ±4 dB syllable swings
        val echo = { t: Long -> -42f + if ((t / 96) % 2 == 0L) 4f else -4f }
        assertEquals(-1L, run(BargeInLogic(BargeInSensitivity.NORMAL), echo))
        assertEquals(-1L, run(BargeInLogic(BargeInSensitivity.SENSITIVE), echo))
    }

    @Test fun userSpeakingOverEchoTriggersQuickly() {
        // echo at -42 dB, then the user talks at -22 dB from 1.5 s
        val t = run(BargeInLogic(BargeInSensitivity.NORMAL), { ms -> if (ms < 1500) -42f else -22f })
        assertTrue("triggered at $t", t in 1500..1900)
    }

    @Test fun shortNoisesAndCoughsDoNotTrigger() {
        // a 120 ms tap/cough every second in a quiet room
        val cough = { ms: Long -> if (ms > 600 && ms % 1000 < 120) -15f else -60f }
        assertEquals(-1L, run(BargeInLogic(BargeInSensitivity.NORMAL), cough))
    }

    @Test fun quietRoomUserTriggersAndSyllableGapsDoNotReset() {
        // quiet room (-65 dB), user speech -30 dB with 64 ms gaps between syllables
        val speech = { ms: Long -> if (ms < 800) -65f else if ((ms / 64) % 4 == 3L) -40f else -30f }
        val t = run(BargeInLogic(BargeInSensitivity.NORMAL), speech)
        assertTrue("triggered at $t", t in 800..1300)
    }

    @Test fun offNeverTriggersAndLevelMath() {
        assertEquals(-1L, run(BargeInLogic(BargeInSensitivity.OFF), { -10f }))
        assertEquals(-90f, BargeInLogic.levelDb(ShortArray(512), 512), 0.01f)
        val full = ShortArray(512) { if (it % 2 == 0) 32767 else -32767 }
        assertTrue(BargeInLogic.levelDb(full, 512) > -0.1f)
        assertFalse(BargeInLogic(BargeInSensitivity.NORMAL).feed(-20f, 0))   // calibrating: never triggers immediately
    }
}

class NaturalVoicesTest {
    @Test fun catalogIsPinnedAndVerified() {
        val all = com.localai.app.data.voice.NaturalVoices.all
        assertTrue(all.size >= 6)
        all.forEach { v ->
            assertEquals(40, v.revision.length)                                   // pinned commit, not "main"
            assertTrue(v.files.all { it.sha256.length == 64 && it.size > 0 })
            assertTrue(v.files.any { it.name.endsWith(".onnx") } && v.files.any { it.name == "tokens.txt" })
            assertTrue(v.url(v.files[0]).startsWith("https://huggingface.co/csukuangfj/${v.repo}/resolve/${v.revision}/"))
        }
        assertTrue(all.filter { it.language == "hi" }.all { it.fast })
    }

    @Test fun scriptDecidesWhichVoiceSpeaks() {
        val nv = com.localai.app.data.voice.NaturalVoices
        val amy = nv.byId("amy")!!; val hindi = nv.byId("priyamvada")!!
        assertTrue(nv.canSpeak(amy, "Pack warm layers for Ooty."))
        assertFalse(nv.canSpeak(amy, "உங்கள் மின்சார கட்டணம்"))               // Tamil → phone's voice
        assertTrue(nv.canSpeak(hindi, "यह अक्टूबर का बिजली बिल है।"))
        assertFalse(nv.canSpeak(hindi, "This is in English."))
        assertTrue(nv.canSpeak(amy, "12:30 — 45%"))                            // no letters: either is fine
        assertEquals("hi", nv.scriptLanguage("बिल 2,184 रुपये, due 28 Nov"))     // mostly Devanagari
    }
}
