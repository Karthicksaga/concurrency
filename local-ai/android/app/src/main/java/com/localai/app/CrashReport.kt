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

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
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
                .filter { it.timestamp > seen && it.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY) }
                .take(3).forEach { x ->
                    val what = when (x.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"; ApplicationExitInfo.REASON_CRASH -> "Java crash"
                        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"; else -> "killed for low memory"
                    }
                    parts += "Exit: $what in ${x.processName} at ${SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(x.timestamp))}" +
                        " · ${x.description ?: ""} · memory ${x.pss / 1024} MB"
                }
        }
        if (parts.isEmpty()) return null
        return "LOCAL AI ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n" + parts.joinToString("\n\n")
    }

    fun dismiss(context: Context) {
        file(context).delete()
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    fun shareIntent(text: String): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "LOCAL AI crash report").putExtra(Intent.EXTRA_TEXT, text), "Share crash report")
}
