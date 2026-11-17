package com.localai.app

import com.localai.app.data.catalog.Catalog
import com.localai.app.data.catalog.MemoryModel
import com.localai.app.data.catalog.Verdict
import com.localai.app.data.chat.visibleText
import com.localai.app.data.models.GgufReader
import com.localai.app.data.settings.AppSettings
import com.localai.app.data.settings.LiteMode
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceProfile
import com.localai.app.runtime.device.DeviceTier
import com.localai.app.runtime.device.defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiteModeTest {
    private val gib = 1024L * 1024 * 1024
    /** Phones report less than advertised RAM: e.g. a "4 GB" phone shows ~3.6 GiB. */
    private fun phone(reportedGiB: Double, perfCores: Int = 2) = DeviceProfile(
        (reportedGiB * gib).toLong(), (reportedGiB * 0.4 * gib).toLong(), false, DeviceTier.fromTotalRam((reportedGiB * gib).toLong()),
        "test", "test", "test", "13", 33, 8, perfCores, listOf("NEON"), 20 * gib, true)
    private fun verdict(id: String, p: DeviceProfile): Verdict {
        val d = policyFor(AppSettings(), p).defaults
        val spec = Catalog.byId(id)!!
        return MemoryModel.verdict(spec, minOf(d.contextLength, spec.ctxTrain), true, p, d.batchSize)
    }

    @Test fun tiersFromReportedRam() {
        assertEquals(DeviceTier.LITE_4, DeviceTier.fromTotalRam((3.6 * gib).toLong()))
        assertEquals(DeviceTier.LITE_6, DeviceTier.fromTotalRam((5.5 * gib).toLong()))
        assertEquals(DeviceTier.STANDARD_8, DeviceTier.fromTotalRam((7.4 * gib).toLong()))
        assertEquals(DeviceTier.PLUS_12, DeviceTier.fromTotalRam((11.2 * gib).toLong()))
        assertEquals(DeviceTier.PRO_16, DeviceTier.fromTotalRam((15.0 * gib).toLong()))
    }

    @Test fun fourGbPhoneRunsOneBModelsOnly() {
        val p = phone(3.6)
        assertTrue(policyFor(AppSettings(), p).lite)
        assertEquals(Verdict.RUNS_WELL, verdict("llama-3.2-1b", p))
        assertEquals(Verdict.RUNS_WELL, verdict("qwen3-0.6b", p))
        assertEquals(Verdict.RUNS_WELL, verdict("gemma-3-1b", p))
        assertEquals(Verdict.NOT_RECOMMENDED, verdict("llama-3.2-3b", p))
        assertEquals("llama-3.2-1b", DeviceTier.LITE_4.defaults().recommendedModelId)
    }

    @Test fun sixGbPhoneRunsTwoBModels() {
        val p = phone(5.5)
        assertTrue(policyFor(AppSettings(), p).lite)
        assertEquals(Verdict.RUNS_WELL, verdict("qwen3-1.7b", p))
        assertEquals(Verdict.RUNS_WELL, verdict("smollm2-1.7b", p))
        assertEquals(Verdict.TIGHT_FIT, verdict("llama-3.2-3b", p))
        assertEquals(Verdict.NOT_RECOMMENDED, verdict("qwen3-4b", p))
    }

    @Test fun recommendedModelRunsWellOnEveryTier() {
        mapOf(3.6 to DeviceTier.LITE_4, 5.5 to DeviceTier.LITE_6, 7.4 to DeviceTier.STANDARD_8, 11.2 to DeviceTier.PLUS_12, 15.0 to DeviceTier.PRO_16).forEach { (ram, tier) ->
            val p = phone(ram, 4)
            assertEquals(tier, p.tier)
            assertEquals("tier $tier", Verdict.RUNS_WELL, verdict(tier.defaults().recommendedModelId, p))
        }
    }

    @Test fun liteModeOverrides() {
        val big = phone(11.2, 6)
        assertFalse(policyFor(AppSettings(), big).lite)
        val forced = policyFor(AppSettings(liteMode = LiteMode.ON), big)
        assertTrue(forced.lite); assertEquals(2048, forced.defaults.contextLength); assertEquals(4, forced.threads)
        val off = policyFor(AppSettings(liteMode = LiteMode.OFF), phone(3.6))
        assertFalse(off.lite)
        assertEquals(3, policyFor(AppSettings(threads = 3), big).threads)
    }

    @Test fun ggufReaderParsesHeaderAndSkipsArrays() {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { u64(s.length.toLong()); out.write(s.toByteArray()) }
        fun kvStr(k: String, v: String) { str(k); u32(8); str(v) }
        fun kvU32(k: String, v: Int) { str(k); u32(4); u32(v) }
        u32(0x46554747); u32(3); u64(0); u64(7)
        kvStr("general.architecture", "llama")
        str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(3); str("a"); str("bb"); str("ccc")   // string array is skipped
        kvU32("llama.block_count", 16)
        kvU32("llama.attention.head_count", 32)
        kvU32("llama.attention.head_count_kv", 8)
        kvU32("llama.embedding_length", 2048)
        kvU32("general.file_type", 15)
        val m = GgufReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("llama", m.arch); assertEquals(16, m.blockCount); assertEquals(8, m.headCountKv); assertEquals(2048, m.embedding)
        assertEquals("Q4_K_M", m.fileTypeLabel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ggufReaderRejectsNonGguf() { GgufReader.read(ByteArrayInputStream("PK\u0003\u0004 not a model".toByteArray())) }

    @Test fun reasoningIsHiddenUnlessRequested() {
        assertEquals("Answer", visibleText("<think>step 1\nstep 2</think>\nAnswer", false))
        assertEquals("", visibleText("<think>still thinking", false))
        assertTrue(visibleText("<think>x</think>Answer", true).contains("<think>"))
    }
}
