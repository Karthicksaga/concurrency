package com.localai.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local crash report: nothing is uploaded. Java crashes are written to a file by an uncaught-exception handler;
 * native crashes and out-of-memory kills are read from Android's exit history (Android 11+). On the next start the
 * app offers to share the report (the user chooses where to send it).
 */
object CrashReport {
    private fun file(c: Context) = File(c.filesDir, "crash/last.txt")
    private fun prefs(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE)

    @Volatile private var stepFile: File? = null

    /**
     * Notes which native step is running ("generating with SmolVLM, photo", "loading voice Amy"), written straight to
     * disk so it survives a native crash, which kills the process before any Java code can run.
     */
    fun step(label: String) {
        val f = stepFile ?: return
        runCatching { java.io.RandomAccessFile(f, "rwd").use { it.setLength(0); it.write("${System.currentTimeMillis()}|$label".toByteArray()) } }
    }
    fun idle() = step("idle")

    private fun lastStep(): Pair<Long, String>? = runCatching {
        val t = stepFile?.takeIf { it.exists() }?.readText() ?: return null
        val (ts, label) = t.split('|', limit = 2)
        if (label == "idle") null else ts.toLong() to label
    }.getOrNull()

    /** Whether the previous run of the main process ended in a crash, ANR or a memory kill. */
    fun lastExitWasProblem(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return file(context).exists()
        return runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 4)
                .firstOrNull { !it.processName.orEmpty().endsWith(":ocr") }?.reason in setOf(ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)
        }.getOrDefault(false)
    }

    /**
     * The useful part of a native tombstone (Android's protobuf crash record): signal, abort message, the crashing
     * thread's stack, and the app's last warning/error log lines. Falls back to printable strings if parsing fails.
     */
    fun tombstoneLines(bytes: ByteArray): List<String> = runCatching { Tombstone.summarize(bytes) }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: printableStrings(bytes)

    private fun printableStrings(bytes: ByteArray): List<String> {
        val runs = ArrayList<String>(); val sb = StringBuilder()
        fun flush() { if (sb.length >= 5) runs += sb.toString(); sb.setLength(0) }
        for (b in bytes) { val c = b.toInt() and 0xff; if (c in 0x20..0x7e) sb.append(c.toChar()) else flush() }
        flush()
        val interesting = Regex("\\.so|abort|Abort|SIG|signal|fault|ggml|llama|mtmd|clip|sherpa|onnx|tesseract|lept|terminate|exception|Fatal|assert|GGML|std::|_Z")
        return runs.filter { interesting.containsMatchIn(it) && !it.startsWith("/system/framework") }.distinct().take(40)
    }

    fun install(context: Context) {
        stepFile = File(context.filesDir, "crash/step.txt").apply { parentFile?.mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file(context).apply { parentFile?.mkdirs() }.writeText("Java crash in thread ${t.name}: ${e.javaClass.name}: ${e.message?.take(300)}\n" +
                e.stackTrace.take(12).joinToString("\n") { "  at $it" }) }
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                file(context).apply { parentFile?.mkdirs() }.writeText("Java crash in thread ${t.name}\n" + sw.toString().take(30_000))
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The report from the last abnormal exit not shown yet, or null. */
    fun pending(context: Context): String? {
        val parts = ArrayList<String>()
        file(context).takeIf { it.exists() }?.let { parts += it.readText() }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            val seen = prefs(context).getLong("seen", 0)
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { x -> x.timestamp > seen && realProblem(x.reason, x.importance, x.processName.orEmpty()) }
                .take(3).forEachIndexed { i, x ->
                    val what = when (x.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"; ApplicationExitInfo.REASON_CRASH -> "Java crash"
                        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"; else -> "killed for low memory"
                    }
                    var text = "Exit: $what in ${x.processName} at ${SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(x.timestamp))}" +
                        " · ${x.description ?: ""} · memory ${x.pss / 1024} MB"
                    if (i == 0) lastStep()?.let { (ts, label) -> if (ts <= x.timestamp) text += "\nLast step: $label (${(x.timestamp - ts) / 1000}s before)" }
                    if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) runCatching {
                        x.traceInputStream?.use { s -> s.readNBytesCompat(4_000_000) }?.let { tombstoneLines(it) }
                            ?.takeIf { it.isNotEmpty() }?.let { text += "\n" + it.joinToString("\n") }
                    }
                    parts += text
                }
        }
        if (parts.isEmpty()) return null
        return "LOCAL AI ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" + parts.joinToString("\n\n")
    }

    /**
     * Only exits the user actually saw: crashes of the app itself. Android closing the app in the background to free
     * memory (normal on 6 GB phones with a model loaded) and failures in the ":ocr" helper (already handled, the photo
     * just isn't read) are not crashes and must not alarm the user on the next start.
     */
    fun realProblem(reason: Int, importance: Int, processName: String): Boolean {
        if (processName.endsWith(":ocr")) return false
        val foreground = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        return when (reason) {
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> true
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY -> foreground
            else -> false
        }
    }

    /** The first lines worth reading (exception and top frames), shown in the dialog so a screenshot is enough. */
    fun summary(report: String): String = report.lineSequence().filter { it.isNotBlank() }.take(40).joinToString("\n").take(4000)

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var total = 0
        while (total < max) { val n = read(buf, 0, minOf(buf.size, max - total)); if (n < 0) break; out.write(buf, 0, n); total += n }
        return out.toByteArray()
    }

    fun dismiss(context: Context) {
        idle()
        file(context).delete()
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    fun shareIntent(text: String): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "LOCAL AI crash report").putExtra(Intent.EXTRA_TEXT, text), "Share crash report")
}
