package com.localai.app.data.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.localai.app.data.catalog.Catalog
import com.localai.app.data.catalog.MemoryModel
import com.localai.app.data.catalog.ModelSpec
import com.localai.app.data.catalog.Verdict
import com.localai.app.data.db.InstalledModelEntity
import com.localai.app.data.db.ModelDao
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.runtime.inference.LoadConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ModelDao,
    private val engine: LlamaEngine,
    private val settings: SettingsRepository,
    private val device: DeviceCapabilityService,
) {
    val modelsDir: File = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    val installed: Flow<List<InstalledModelEntity>> = dao.observe()

    fun fileFor(fileName: String) = File(modelsDir, fileName)
    fun partFileFor(fileName: String) = File(modelsDir, "$fileName.part")

    suspend fun specFor(id: String): ModelSpec? = dao.get(id)?.toSpec() ?: Catalog.byId(id)
    suspend fun isInstalled(id: String) = dao.get(id) != null

    suspend fun registerDownloaded(spec: ModelSpec) {
        dao.upsert(InstalledModelEntity(spec.id, spec.name, spec.family, spec.paramsB, spec.quant, spec.sizeBytes, spec.arch, spec.ctxTrain,
            spec.layers, spec.kvHeads, spec.headDim, spec.license, spec.fileName, spec.sha256, "CATALOG", System.currentTimeMillis()))
    }

    suspend fun delete(id: String) {
        val m = dao.get(id) ?: return
        if ((engine.state.value as? EngineState.Ready)?.modelId == id) engine.unload()
        fileFor(m.fileName).delete()
        partFileFor(m.fileName).delete()
        dao.delete(id)
        if (settings.current().activeModelId == id) settings.setActiveModel(null)
    }

    /** Loads [id] with the lite-mode policy and the model's saved profile. */
    suspend fun activate(id: String, allowNotRecommended: Boolean = false): Result<Unit> {
        val entity = dao.get(id) ?: return Result.failure(IllegalStateException("Model is not installed yet. Finish the download first."))
        if (Catalog.isEmbedding(id)) return Result.failure(IllegalStateException("${entity.name} is an embedding model for search; choose a chat model."))
        val spec = entity.toSpec()
        val file = fileFor(entity.fileName)
        if (!file.exists()) return Result.failure(IllegalStateException("The model file is missing. Download it again."))
        val s = settings.current()
        val dev = device.profile
        val policy = policyFor(s, dev)
        val profile = settings.profileNow(id, minOf(policy.defaults.contextLength, spec.ctxTrain))
        val ctx = profile.contextLength.coerceAtMost(minOf(spec.ctxTrain, policy.defaults.maxContext))
        val verdict = MemoryModel.verdict(spec, ctx, s.kvQ8, dev, policy.defaults.batchSize)
        if (verdict == Verdict.NOT_RECOMMENDED && !allowNotRecommended) {
            return Result.failure(IllegalStateException("${spec.name} needs more memory than this phone can safely give it at ${ctx / 1024}K context."))
        }
        settings.setActiveModel(id)
        return engine.load(id, file, LoadConfig(ctx, policy.threads, policy.defaults.batchSize, s.kvQ8))
    }

    /** Ensures the active model is loaded; returns its id or an error message. */
    suspend fun ensureLoaded(): Result<String> {
        val st = engine.state.value
        val active = settings.current().activeModelId ?: return Result.failure(IllegalStateException("Choose a model first."))
        if (st is EngineState.Ready && st.modelId == active) return Result.success(active)
        return activate(active).map { active }
    }

    // ---------- import ----------

    data class ImportProgress(val bytes: Long, val total: Long)

    suspend fun importGguf(uri: Uri, onProgress: (ImportProgress) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var displayName = "imported.gguf"; var total = -1L
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { total = c.getLong(it) }
                }
            }
            val meta = resolver.openInputStream(uri)?.use { GgufReader.read(it) } ?: error("Cannot open the file.")
            val need = (if (total > 0) total else 0L) + 512L * 1024 * 1024
            if (device.freeStorage() < need) error("Not enough free storage to copy this model.")
            val safeName = "import_" + displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf")) it else "$it.gguf" }
            val dest = fileFor(safeName)
            val tmp = File(modelsDir, "$safeName.importing")
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20); var copied = 0L; var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); copied += n
                        if (copied - last > 16 shl 20) { onProgress(ImportProgress(copied, total)); last = copied }
                    }
                }
            } ?: error("Cannot open the file.")
            if (!tmp.renameTo(dest)) error("Could not save the model.")
            val size = dest.length()
            val id = "import-" + safeName.removePrefix("import_").removeSuffix(".gguf").lowercase().take(40)
            val headDim = meta.headDim ?: (if (meta.headCount > 0) meta.embedding / meta.headCount else 128)
            dao.upsert(InstalledModelEntity(
                id = id, name = meta.name ?: displayName.removeSuffix(".gguf"), family = meta.arch, paramsB = size / 0.6e9,
                quant = meta.fileTypeLabel, sizeBytes = size, arch = meta.arch, ctxTrain = meta.contextLength.takeIf { it > 0 } ?: 4096,
                layers = meta.blockCount.takeIf { it > 0 } ?: 32, kvHeads = meta.headCountKv.takeIf { it > 0 } ?: meta.headCount.coerceAtLeast(8),
                headDim = headDim, license = meta.license ?: "Unknown · unverified", fileName = safeName, sha256 = null, source = "IMPORT",
                installedAt = System.currentTimeMillis(),
            ))
            id
        }
    }
}

/** Minimal GGUF metadata reader: validates the header and extracts what the memory estimate needs. */
object GgufReader {
    data class Meta(
        val arch: String, val name: String?, val license: String?, val blockCount: Int, val headCount: Int, val headCountKv: Int,
        val embedding: Int, val contextLength: Int, val headDim: Int?, val fileType: Int,
    ) {
        val fileTypeLabel get() = when (fileType) {
            0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"; 8 -> "Q5_0"; 9 -> "Q5_1"; 10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"
            13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"; 16 -> "Q5_K_S"; 17 -> "Q5_K_M"; 18 -> "Q6_K"; 32 -> "BF16"; else -> "GGUF"
        }
    }

    private class Le(input: InputStream) {
        private val s = DataInputStream(BufferedInputStream(input, 1 shl 16))
        private val b = ByteArray(8)
        private fun le(n: Int): ByteBuffer { s.readFully(b, 0, n); return ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN) }
        fun u32() = le(4).int.toLong() and 0xffffffffL
        fun i32() = le(4).int
        fun u64() = le(8).long
        fun f32() = le(4).float
        fun str(): String { val n = u64(); require(n in 0..65536) { "corrupt string" }; val a = ByteArray(n.toInt()); s.readFully(a); return String(a) }
        fun skip(n: Long) { var left = n; while (left > 0) { val k = s.skip(left); if (k <= 0) { s.readByte(); left-- } else left -= k } }
        fun u8() = s.readUnsignedByte()
    }

    private val sizes = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4, 6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)

    fun read(input: InputStream): Meta {
        val r = Le(input)
        require(r.u32() == 0x46554747L) { "Not a GGUF file." }           // "GGUF"
        val version = r.u32(); require(version in 2..3) { "Unsupported GGUF version $version." }
        r.u64()                                                           // tensor count
        val kvCount = r.u64(); require(kvCount in 1..10_000) { "Corrupt GGUF header." }
        val v = HashMap<String, Any>()
        repeat(kvCount.toInt()) {
            val key = r.str()
            val type = r.u32().toInt()
            v[key] = readValue(r, type) ?: return@repeat
        }
        val arch = v["general.architecture"] as? String ?: error("GGUF file has no architecture.")
        fun int(k: String) = (v["$arch.$k"] as? Number)?.toInt() ?: 0
        return Meta(arch, v["general.name"] as? String, v["general.license"] as? String, int("block_count"), int("attention.head_count"),
            int("attention.head_count_kv"), int("embedding_length"), int("context_length"), int("attention.key_length").takeIf { it > 0 },
            (v["general.file_type"] as? Number)?.toInt() ?: -1)
    }

    private fun readValue(r: Le, type: Int): Any? = when (type) {
        0 -> r.u8(); 1 -> r.u8().toByte().toInt(); 2, 3 -> { r.skip(2); 0 }
        4 -> r.u32(); 5 -> r.i32(); 6 -> r.f32(); 7 -> r.u8() != 0
        8 -> r.str(); 10, 11 -> r.u64(); 12 -> { r.skip(8); 0 }
        9 -> { // array: skip contents (tokenizer arrays are large)
            val et = r.u32().toInt(); val n = r.u64()
            if (et == 8) { for (i in 0 until n) r.skip(r.u64()) } else r.skip(n * (sizes[et] ?: error("Unsupported array type")))
            null
        }
        else -> error("Unknown GGUF value type $type")
    }
}
