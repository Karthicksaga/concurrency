package com.localai.app.data.ocr

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Text recognition stopped because the OCR process crashed or hung on this input. */
class OcrCrashedException(message: String) : Exception(message)

/**
 * Runs Tesseract in its own process (android:process=":ocr"). Native code reading arbitrary photos and scans can
 * crash on unusual inputs; isolated here, such a crash ends only this helper process, and the app marks that one
 * file as failed instead of closing (docs/SECURITY_AND_PRIVACY.md threat model: parser isolation).
 */
class OcrService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ocr-service").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val reply = msg.replyTo ?: return
                val req = msg.data
                val out = Bundle()
                try {
                    val file = File(req.getString("path") ?: error("no file"))
                    val langs = req.getString("langs") ?: "eng"
                    when (msg.what) {
                        OP_FILE -> runBlocking { Ocr.recognizeFileLocal(this@OcrService, file, langs) }?.let { r ->
                            out.putString("text", r.text.take(MAX_CHARS)); out.putInt("conf", r.confidence)
                        }
                        OP_PDF -> {
                            val pages = runBlocking { Ocr.recognizePdfLocal(this@OcrService, file, langs, req.getInt("maxPages", 20)) }
                            var budget = MAX_CHARS
                            val kept = pages.takeWhile { (_, t) -> budget -= t.length; budget > 0 }
                            out.putIntArray("pages", kept.map { it.first }.toIntArray())
                            out.putStringArray("texts", kept.map { it.second }.toTypedArray())
                        }
                    }
                } catch (e: Throwable) {
                    out.putString("error", e.message ?: e.javaClass.simpleName)
                }
                runCatching { reply.send(Message.obtain(null, msg.what).apply { data = out }) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() { runCatching { Ocr.release() }; thread.quitSafely(); super.onDestroy() }

    companion object {
        const val OP_FILE = 1
        const val OP_PDF = 2
        const val MAX_CHARS = 400_000           // keep replies well under the binder transaction limit
    }
}

/** Main-process side: binds to OcrService for one request and waits for the answer. */
object OcrClient {
    private suspend fun call(context: Context, op: Int, args: Bundle, timeoutMs: Long): Bundle = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val app = context.applicationContext
            val done = AtomicBoolean(false)
            lateinit var conn: ServiceConnection
            fun finish(block: () -> Unit) { if (done.compareAndSet(false, true)) { runCatching { app.unbindService(conn) }; block() } }
            conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(msg: Message) { val data = Bundle(msg.data); finish { cont.resume(data) } }
                    })
                    val m = Message.obtain(null, op).apply { data = args; this.replyTo = replyTo }
                    runCatching { Messenger(binder).send(m) }.onFailure { finish { cont.resumeWithException(OcrCrashedException("Text recognition could not start.")) } }
                }
                // The OCR process died while working on this input (native crash or out of memory).
                override fun onServiceDisconnected(name: ComponentName?) = finish {
                    cont.resumeWithException(OcrCrashedException("Text recognition stopped on this file. It may be damaged or unusual; other files are not affected."))
                }
                override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName?) = finish { cont.resumeWithException(OcrCrashedException("Text recognition is unavailable.")) }
            }
            val bound = runCatching { app.bindService(Intent(app, OcrService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
            if (!bound) finish { cont.resumeWithException(OcrCrashedException("Text recognition is unavailable.")) }
            cont.invokeOnCancellation { if (done.compareAndSet(false, true)) runCatching { app.unbindService(conn) } }
        }
    }

    suspend fun recognizeFile(context: Context, file: File, langs: String): OcrResult? {
        val r = call(context, OcrService.OP_FILE, Bundle().apply { putString("path", file.absolutePath); putString("langs", langs) }, 120_000)
        r.getString("error")?.let { throw OcrCrashedException(it) }
        val text = r.getString("text") ?: return null
        return OcrResult(text, r.getInt("conf"))
    }

    suspend fun recognizePdf(context: Context, file: File, langs: String, maxPages: Int): List<Pair<Int, String>> {
        val r = call(context, OcrService.OP_PDF, Bundle().apply { putString("path", file.absolutePath); putString("langs", langs); putInt("maxPages", maxPages) },
            60_000L * maxPages.coerceAtLeast(1))
        r.getString("error")?.let { throw OcrCrashedException(it) }
        val pages = r.getIntArray("pages") ?: return emptyList()
        val texts = r.getStringArray("texts") ?: return emptyList()
        return pages.zip(texts)
    }
}
