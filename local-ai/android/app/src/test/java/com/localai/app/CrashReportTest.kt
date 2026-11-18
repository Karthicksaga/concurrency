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

    @Test fun backgroundMemoryKillsAreNotCrashes() {
        assertFalse(CrashReport.realProblem(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 400, "com.localai.app"))
        assertTrue(CrashReport.realProblem(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 400, "com.localai.app"))
        assertFalse(CrashReport.realProblem(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 100, "com.localai.app:ocr"))
    }
}
