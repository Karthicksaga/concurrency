package com.localai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportTest {
    @Test fun tombstoneKeepsUsefulStrings() {
        val bytes = byteArrayOf(8, 1) + "Abort message: 'GGML_ASSERT(n > 0) failed'".toByteArray() + byteArrayOf(0, 18) +
            "/data/app/~~x/lib/arm64/libllama.so".toByteArray() + byteArrayOf(0) + "_ZN5llama7decodeEv".toByteArray() + byteArrayOf(0) + "hello world".toByteArray()
        val lines = CrashReport.tombstoneLines(bytes)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("GGML_ASSERT"))
        assertFalse(lines.any { it == "hello world" })
    }

    private fun varint(v: Long): ByteArray { val o = ArrayList<Byte>(); var x = v; while (x >= 0x80) { o += ((x and 0x7f) or 0x80).toByte(); x = x ushr 7 }; o += x.toByte(); return o.toByteArray() }
    private fun fld(n: Int, bytes: ByteArray) = varint((n.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun fld(n: Int, s: String) = fld(n, s.toByteArray())
    private fun num(n: Int, v: Long) = varint(n.toLong() shl 3) + varint(v)

    @Test fun tombstoneProtobufIsParsed() {
        val frame0 = fld(6, "/system/lib64/libc.so") + fld(4, "abort") + num(5, 164)
        val frame1 = fld(6, "/data/app/x/lib/arm64/libggml-base.so") + fld(4, "ggml_abort") + num(5, 32)
        val thread = num(1, 77) + fld(2, "embedder") + fld(4, frame0) + fld(4, frame1)
        val other = num(1, 5) + fld(2, "main") + fld(4, fld(4, "nativePollOnce"))
        val log = fld(2, num(4, 7) + fld(5, "LocalAI-native") + fld(6, "ggml abort: GGML_ASSERT(x) failed"))
        val bytes = num(5, 1) + num(6, 77) + fld(10, num(1, 6) + fld(2, "SIGABRT")) + fld(14, "GGML_ASSERT(x) failed") +
            fld(16, num(1, 5) + fld(2, other)) + fld(16, num(1, 77) + fld(2, thread)) + fld(18, fld(1, "main") + log)
        val lines = CrashReport.tombstoneLines(bytes)
        assertEquals("Signal: SIGABRT", lines[0])
        assertEquals("Abort: GGML_ASSERT(x) failed", lines[1])
        assertEquals("Crashed thread: embedder (77)", lines[2])
        assertTrue(lines[4].contains("libggml-base.so ggml_abort+32"))
        assertTrue(lines.last().contains("ggml abort"))
    }

    @Test fun backgroundMemoryKillsAreNotCrashes() {
        assertFalse(CrashReport.realProblem(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 400, "com.localai.app"))
        assertTrue(CrashReport.realProblem(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 400, "com.localai.app"))
        assertFalse(CrashReport.realProblem(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 100, "com.localai.app:ocr"))
    }
}
