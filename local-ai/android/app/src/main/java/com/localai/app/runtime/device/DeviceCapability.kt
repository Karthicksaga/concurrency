package com.localai.app.runtime.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val GIB = 1024.0 * 1024.0 * 1024.0

/**
 * Device classes. Phones report less RAM than advertised (a "4 GB" phone shows ~3.6 GiB),
 * so thresholds sit between advertised sizes.
 */
enum class DeviceTier(val label: String, val isLite: Boolean) {
    LITE_4("4 GB · Lite", true),
    LITE_6("6 GB · Lite", true),
    STANDARD_8("8 GB", false),
    PLUS_12("12 GB", false),
    PRO_16("16 GB+", false);

    companion object {
        fun fromTotalRam(totalBytes: Long): DeviceTier {
            val gib = totalBytes / GIB
            return when {
                gib < 4.6 -> LITE_4
                gib < 6.6 -> LITE_6
                gib < 9.0 -> STANDARD_8
                gib < 13.0 -> PLUS_12
                else -> PRO_16
            }
        }
    }
}

/** Runtime defaults per tier; see docs/MODEL_RUNTIME_DESIGN.md §6 and the lite-mode section. */
data class TierDefaults(
    val contextLength: Int,
    val maxContext: Int,
    val batchSize: Int,
    val maxThreads: Int,
    val maxNewTokens: Int,
    val unloadAfterBackgroundMs: Long,
    val recommendedModelId: String,
)

fun DeviceTier.defaults(): TierDefaults = when (this) {
    DeviceTier.LITE_4 -> TierDefaults(2048, 4096, 128, 4, 512, 30_000, "llama-3.2-1b")
    DeviceTier.LITE_6 -> TierDefaults(2048, 4096, 256, 4, 768, 120_000, "qwen3-1.7b")
    DeviceTier.STANDARD_8 -> TierDefaults(4096, 8192, 512, 6, 1024, 300_000, "llama-3.2-3b")
    DeviceTier.PLUS_12 -> TierDefaults(8192, 16384, 512, 8, 1024, 300_000, "qwen3-4b")
    DeviceTier.PRO_16 -> TierDefaults(8192, 32768, 512, 8, 1024, 300_000, "qwen3-4b")
}

data class DeviceProfile(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean,
    val tier: DeviceTier,
    val socModel: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    val cpuCores: Int,
    val performanceCores: Int,
    val cpuFeatures: List<String>,
    val freeStorageBytes: Long,
    val is64Bit: Boolean,
) {
    val totalRamGiB get() = totalRamBytes / GIB
    val availRamGiB get() = availRamBytes / GIB
    /** Memory a model may use: 0.5 × RAM − 0.4 GiB (initial calibration). */
    val modelBudgetGiB get() = (totalRamGiB * 0.5 - 0.4).coerceAtLeast(0.6)
}

data class LiveStats(
    val availRamBytes: Long,
    val appPssBytes: Long,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSave: Boolean,
)

@Singleton
class DeviceCapabilityService @Inject constructor(@ApplicationContext private val context: Context) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)

    val profile: DeviceProfile by lazy { detect() }

    private fun memInfo() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    private fun detect(): DeviceProfile {
        val mi = memInfo()
        val cores = Runtime.getRuntime().availableProcessors()
        return DeviceProfile(
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            lowMemory = mi.lowMemory || am.isLowRamDevice,
            tier = DeviceTier.fromTotalRam(mi.totalMem),
            socModel = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            cpuCores = cores,
            performanceCores = performanceCores(cores),
            cpuFeatures = cpuFeatures(),
            freeStorageBytes = freeStorage(),
            is64Bit = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"),
        )
    }

    fun freeStorage(): Long = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)

    /** Big/prime cores only: decoding on efficiency cores slows everything down. */
    private fun performanceCores(cores: Int): Int {
        val freqs = (0 until cores).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        if (freqs.size < cores || freqs.isEmpty()) return (cores / 2).coerceIn(2, 4)
        val max = freqs.max()
        // e.g. 1×3.2 + 4×2.8 + 3×2.0 GHz → 5 cores; 2×2.4 + 6×1.8 GHz → 2 cores
        return freqs.count { it >= max * 0.8 }.coerceIn(2, minOf(8, cores))
    }

    private fun cpuFeatures(): List<String> {
        val line = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") } }.getOrNull() ?: return emptyList()
        val all = line.substringAfter(":").trim().split(" ").toSet()
        return listOf("asimd" to "NEON", "asimddp" to "dotprod", "i8mm" to "i8mm", "sve" to "SVE", "sve2" to "SVE2", "sme" to "SME")
            .filter { it.first in all }.map { it.second }
    }

    fun live(): LiveStats {
        val mi = memInfo()
        val pss = runCatching { am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss * 1024L }.getOrDefault(Debug.getPss() * 1024L)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return LiveStats(
            availRamBytes = mi.availMem,
            appPssBytes = pss,
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0,
            thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10).takeIf { !it.isNaN() } else null,
            batteryPercent = if (level >= 0) level * 100 / scale else -1,
            charging = plugged,
            powerSave = pm.isPowerSaveMode,
        )
    }
}

fun thermalLabel(status: Int): String = when (status) {
    0 -> "Normal"; 1 -> "Light"; 2 -> "Moderate"; 3 -> "Severe"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> "Unknown"
}
