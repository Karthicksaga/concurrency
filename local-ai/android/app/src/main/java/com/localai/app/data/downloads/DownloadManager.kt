package com.localai.app.data.downloads

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.localai.app.LocalAiApp
import com.localai.app.R
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.catalog.ModelSpec
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class DownloadPhase { QUEUED, RUNNING, VERIFYING, PAUSED, FAILED }

data class DownloadUi(val modelId: String, val phase: DownloadPhase, val bytes: Long, val total: Long, val speedBps: Long, val error: String?) {
    val progress get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

private const val TAG = "model-download"

/** Resumable, checksum-verified model downloads (docs/SYSTEM_ARCHITECTURE.md §5.2). The only network code in the app. */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
) {
    private val wm get() = WorkManager.getInstance(context)

    /** Bytes on disk so far for the model file plus its image encoder (if any). */
    private fun doneBytes(spec: ModelSpec): Long {
        fun one(name: String, size: Long) = if (models.fileFor(name).exists()) size else models.partFileFor(name).length()
        return one(spec.fileName, spec.sizeBytes) + (spec.projectorFile?.let { one(it, spec.projectorSize) } ?: 0)
    }

    val downloads: Flow<Map<String, DownloadUi>> = wm.getWorkInfosByTagFlow(TAG).map { infos ->
        val out = HashMap<String, DownloadUi>()
        // Paused = a partial file without running work.
        Catalog.models.forEach { spec ->
            val partial = models.partFileFor(spec.fileName).exists() || spec.projectorFile?.let { models.partFileFor(it).exists() } == true
            if (partial) out[spec.id] = DownloadUi(spec.id, DownloadPhase.PAUSED, doneBytes(spec), spec.totalBytes, 0, null)
        }
        infos.forEach { wi ->
            val id = wi.tags.firstOrNull { it.startsWith("model:") }?.removePrefix("model:") ?: return@forEach
            val spec = Catalog.byId(id) ?: return@forEach
            val p = wi.progress
            when (wi.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    out[id] = DownloadUi(id, DownloadPhase.QUEUED, doneBytes(spec), spec.totalBytes, 0, null)
                WorkInfo.State.RUNNING -> out[id] = DownloadUi(id,
                    if (p.getBoolean("verifying", false)) DownloadPhase.VERIFYING else DownloadPhase.RUNNING,
                    p.getLong("bytes", doneBytes(spec)), spec.totalBytes, p.getLong("speed", 0), null)
                WorkInfo.State.FAILED -> if (!models.fileFor(spec.fileName).exists() || spec.projectorFile?.let { !models.fileFor(it).exists() } == true)
                    out[id] = DownloadUi(id, DownloadPhase.FAILED, doneBytes(spec), spec.totalBytes, 0,
                        wi.outputData.getString("error") ?: "Download failed.")
                else -> Unit
            }
        }
        out
    }

    suspend fun start(spec: ModelSpec) {
        val wifiOnly = settings.current().wifiOnly
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("modelId" to spec.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag("model:${spec.id}")
            .build()
        wm.enqueueUniqueWork("dl_${spec.id}", ExistingWorkPolicy.KEEP, req)
    }

    fun pause(id: String) { wm.cancelUniqueWork("dl_$id") }

    fun cancel(id: String) {
        wm.cancelUniqueWork("dl_$id")
        Catalog.byId(id)?.let { s -> models.partFileFor(s.fileName).delete(); s.projectorFile?.let { models.partFileFor(it).delete() } }
        wm.pruneWork()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadEntryPoint { fun models(): ModelRepository }

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val models = EntryPointAccessors.fromApplication(context, DownloadEntryPoint::class.java).models()
    private val nm = context.getSystemService(NotificationManager::class.java)

    private fun notification(spec: ModelSpec, pct: Int, text: String) =
        NotificationCompat.Builder(applicationContext, LocalAiApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(spec.name)
            .setContentText(text)
            .setProgress(100, pct, pct < 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .build()

    private fun foreground(spec: ModelSpec, pct: Int, text: String) =
        if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(spec.id.hashCode(), notification(spec, pct, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(spec.id.hashCode(), notification(spec, pct, text))

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val spec = Catalog.byId(inputData.getString("modelId") ?: "") ?: Catalog.models.first()
        return foreground(spec, -1, "Preparing download")
    }

    private class Piece(val url: String, val sha256: String?, val size: Long, val fileName: String)

    private sealed interface Step { data object Ok : Step; class Stop(val result: Result) : Step }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val spec = Catalog.byId(inputData.getString("modelId") ?: "") ?: return@withContext Result.failure()
        val pieces = buildList {
            add(Piece(spec.url ?: return@withContext Result.failure(), spec.sha256, spec.sizeBytes, spec.fileName))
            if (spec.projectorUrl != null && spec.projectorFile != null)
                add(Piece(spec.projectorUrl, spec.projectorSha256, spec.projectorSize, spec.projectorFile))
        }
        runCatching { setForeground(foreground(spec, 0, "Starting")) }
        try {
            var offset = 0L
            for (piece in pieces) {
                when (val r = fetch(spec, piece, offset)) { is Step.Stop -> return@withContext r.result; Step.Ok -> Unit }
                offset += piece.size
            }
            models.registerDownloaded(spec)
            Result.success()
        } catch (e: java.io.IOException) {
            retryOrFail(e.message ?: "Network error")
        }
    }

    /** Downloads one file with resume and checksum. [offset] = bytes of earlier pieces, for progress. */
    private suspend fun fetch(spec: ModelSpec, piece: Piece, offset: Long): Step {
        val dest = models.fileFor(piece.fileName)
        if (dest.exists() && dest.length() == piece.size) return Step.Ok          // already done (e.g. adding image support later)
        val part = models.partFileFor(piece.fileName)
        val total = spec.totalBytes
        var existing = if (part.exists()) part.length() else 0L
        if (existing > piece.size) { part.delete(); existing = 0 }
        if (existing < piece.size) {
            val conn = (URL(piece.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LocalAI-Android/0.1")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            if (code == 200 && existing > 0) { existing = 0 }                   // server ignored Range: restart
            else if (code != 200 && code != 206) return Step.Stop(retryOrFail("Server returned HTTP $code"))
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(existing); raf.seek(existing)
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 20)
                    var done = existing; var lastT = System.currentTimeMillis(); var lastB = done
                    while (!isStopped) {
                        val n = input.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastT >= 700) {
                            val speed = (done - lastB) * 1000 / (now - lastT)
                            setProgress(workDataOf("bytes" to offset + done, "speed" to speed))
                            val pct = ((offset + done) * 100 / total).toInt()
                            val what = if (piece.fileName == spec.fileName) "" else "image encoder · "
                            nm.notify(spec.id.hashCode(), notification(spec, pct, "$what$pct% · ${speed / 1_000_000} MB/s"))
                            lastT = now; lastB = done
                        }
                    }
                }
            }
            conn.disconnect()
            if (isStopped) return Step.Stop(Result.failure())                       // paused: keep .part
        }
        if (part.length() != piece.size) return Step.Stop(retryOrFail("Connection closed early (${part.length()} of ${piece.size} bytes)."))

        setProgress(workDataOf("bytes" to offset + part.length(), "verifying" to true))
        nm.notify(spec.id.hashCode(), notification(spec, -1, "Verifying checksum"))
        val sha = sha256(part)
        if (piece.sha256 != null && !sha.equals(piece.sha256, ignoreCase = true)) {
            part.delete()
            return Step.Stop(Result.failure(workDataOf("error" to "Checksum mismatch. The file was deleted; please download again.")))
        }
        if (!part.renameTo(dest)) return Step.Stop(Result.failure(workDataOf("error" to "Could not save the model file.")))
        return Step.Ok
    }

    private fun retryOrFail(msg: String): Result =
        if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf("error" to "$msg Tap Resume to try again."))

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
