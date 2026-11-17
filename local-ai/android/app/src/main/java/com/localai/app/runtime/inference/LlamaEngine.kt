package com.localai.app.runtime.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** JNI surface implemented in src/main/cpp/la_jni.cpp. */
object LlamaNative {
    init { System.loadLibrary("localai") }

    interface Callback {
        fun onToken(utf8: ByteArray)
        fun onDone(promptTokens: Int, reusedTokens: Int, generatedTokens: Int, promptMs: Long, genMs: Long)
    }

    external fun init(nativeLibDir: String)
    external fun systemInfo(): String
    external fun loadModel(path: String, nCtx: Int, nThreads: Int, nBatch: Int, kvQ8: Boolean): Int
    external fun modelInfo(): String
    external fun unload()
    external fun abort()
    external fun generate(
        roles: Array<String>, contents: Array<String>, maxTokens: Int, temp: Float, topP: Float, topK: Int,
        minP: Float, repeatPenalty: Float, seed: Int, enableThinking: Boolean, grammar: String?,
        rgb: ByteArray?, imgW: Int, imgH: Int, callback: Callback,
    ): Int
    /** 0 ok, -1 no model, -2 projector failed to load, -3 projector has no image support. */
    external fun loadProjector(path: String, nThreads: Int): Int
    external fun schemaToGrammar(jsonSchema: String): String
    external fun loadEmbedder(path: String, nThreads: Int): Int
    external fun unloadEmbedder()
    external fun embed(texts: Array<String>): Array<FloatArray?>?
}

data class LoadConfig(val contextLength: Int, val threads: Int, val batch: Int, val kvQ8: Boolean)

data class ChatTurn(val role: String, val content: String)

/** An image as packed RGB bytes (width × height × 3), attached to the last user turn. */
class RgbImage(val rgb: ByteArray, val width: Int, val height: Int)

data class SamplingParams(
    val temperature: Float = 0.7f, val topP: Float = 0.9f, val topK: Int = 40, val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f, val maxTokens: Int = 1024, val seed: Int = -1, val enableThinking: Boolean = false,
)

data class GenerationStats(val promptTokens: Int, val reusedTokens: Int, val generatedTokens: Int, val promptMs: Long, val genMs: Long) {
    val promptTps get() = if (promptMs > 0) promptTokens * 1000f / promptMs else 0f
    val genTps get() = if (genMs > 0) generatedTokens * 1000f / genMs else 0f
}

enum class StopReason { END, LENGTH, ABORTED }

sealed interface GenEvent {
    data class Delta(val text: String) : GenEvent
    data class Done(val reason: StopReason, val stats: GenerationStats?) : GenEvent
    data class Failed(val error: InferenceError) : GenEvent
}

enum class InferenceError(val message: String) {
    NO_MODEL("No model is loaded."),
    CONTEXT_OVERFLOW("The conversation is longer than the model's context window."),
    TEMPLATE("This model's chat template could not be applied."),
    DECODE("The model failed while processing the prompt. Try a shorter context or another model."),
    NO_VISION("This model can't see images. In Models, install and use a vision model such as SmolVLM 500M (vision)."),
}

sealed interface EngineState {
    data object Idle : EngineState
    data class Loading(val modelId: String) : EngineState
    data class Ready(val modelId: String, val description: String, val contextLength: Int, val config: LoadConfig, val vision: Boolean = false) : EngineState
    data class Failed(val modelId: String, val message: String) : EngineState
}

/**
 * Single-slot engine: one model loaded, one generation at a time (docs/AGENT_ARCHITECTURE.md, ADR-0003).
 * All native calls run on one dedicated thread.
 */
@Singleton
class LlamaEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()
    private val _lastStats = MutableStateFlow<GenerationStats?>(null)
    val lastStats: StateFlow<GenerationStats?> = _lastStats.asStateFlow()
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    @Volatile private var initialised = false
    var systemInfo: String = ""; private set

    suspend fun ensureNativeInit() { if (!initialised) ensureInit() }

    private suspend fun ensureInit() = withContext(dispatcher) {
        if (!initialised) {
            LlamaNative.init(context.applicationInfo.nativeLibraryDir)
            systemInfo = LlamaNative.systemInfo()
            initialised = true
        }
    }

    suspend fun load(modelId: String, file: File, config: LoadConfig): Result<Unit> = mutex.withLock {
        ensureInit()
        _state.value = EngineState.Loading(modelId)
        withContext(dispatcher) {
            val rc = LlamaNative.loadModel(file.absolutePath, config.contextLength, config.threads, config.batch, config.kvQ8)
            if (rc == 0) {
                val info = LlamaNative.modelInfo().split("|")
                _state.value = EngineState.Ready(modelId, info.getOrElse(0) { "" }, info.getOrNull(3)?.toIntOrNull() ?: config.contextLength, config)
                Result.success(Unit)
            } else {
                val msg = if (rc == -1) "The model file could not be loaded. It may be corrupted or use an unsupported architecture."
                else "Not enough memory to create the context. Try a shorter context length."
                _state.value = EngineState.Failed(modelId, msg)
                Result.failure(IllegalStateException(msg))
            }
        }
    }

    /** Loads the image encoder for the model that is loaded now. */
    suspend fun loadProjector(file: File, threads: Int): Result<Unit> = mutex.withLock {
        val ready = _state.value as? EngineState.Ready ?: return@withLock Result.failure(IllegalStateException("No model is loaded."))
        withContext(dispatcher) {
            when (LlamaNative.loadProjector(file.absolutePath, threads)) {
                0 -> { _state.value = ready.copy(vision = true); Result.success(Unit) }
                -3 -> Result.failure(IllegalStateException("This image encoder has no image support."))
                else -> Result.failure(IllegalStateException("The image encoder could not be loaded."))
            }
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) { if (initialised) LlamaNative.unload() }
        _state.value = EngineState.Idle
    }

    fun abort() = LlamaNative.abort()

    /** Cold flow: collecting starts generation; cancelling the collector aborts native decoding. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun generate(turns: List<ChatTurn>, params: SamplingParams, grammar: String? = null, image: RgbImage? = null): Flow<GenEvent> = channelFlow {
        mutex.withLock {
            val st = _state.value
            if (st !is EngineState.Ready) { send(GenEvent.Failed(InferenceError.NO_MODEL)); return@withLock }
            if (image != null && !st.vision) { send(GenEvent.Failed(InferenceError.NO_VISION)); return@withLock }
            val finished = CompletableDeferred<Unit>()
            val watcher = launch {
                try { awaitCancellation() } finally { if (!finished.isCompleted) LlamaNative.abort() }
            }
            _generating.value = true
            var stats: GenerationStats? = null
            val rc = try {
                withContext(dispatcher) {
                    LlamaNative.generate(
                        turns.map { it.role }.toTypedArray(), turns.map { it.content }.toTypedArray(),
                        params.maxTokens, params.temperature, params.topP, params.topK, params.minP, params.repeatPenalty,
                        params.seed, params.enableThinking, grammar, image?.rgb, image?.width ?: 0, image?.height ?: 0,
                        object : LlamaNative.Callback {
                            override fun onToken(utf8: ByteArray) { trySendBlocking(GenEvent.Delta(String(utf8, Charsets.UTF_8))) }
                            override fun onDone(promptTokens: Int, reusedTokens: Int, generatedTokens: Int, promptMs: Long, genMs: Long) {
                                stats = GenerationStats(promptTokens, reusedTokens, generatedTokens, promptMs, genMs)
                            }
                        },
                    )
                }
            } finally {
                finished.complete(Unit)
                watcher.cancel()
                _generating.value = false
            }
            stats?.let { _lastStats.value = it }
            when (rc) {
                0 -> send(GenEvent.Done(StopReason.END, stats))
                1 -> send(GenEvent.Done(StopReason.LENGTH, stats))
                2 -> send(GenEvent.Done(StopReason.ABORTED, stats))
                -2 -> send(GenEvent.Failed(InferenceError.CONTEXT_OVERFLOW))
                -3 -> send(GenEvent.Failed(InferenceError.TEMPLATE))
                -1 -> send(GenEvent.Failed(InferenceError.NO_MODEL))
                else -> send(GenEvent.Failed(InferenceError.DECODE))
            }
        }
    }
}


/** Collects a whole (usually grammar-constrained) completion. Returns null on failure or abort. */
suspend fun LlamaEngine.complete(turns: List<ChatTurn>, params: SamplingParams, grammar: String? = null): String? {
    val sb = StringBuilder(); var ok = true
    generate(turns, params, grammar).collect { e ->
        when (e) { is GenEvent.Delta -> sb.append(e.text); is GenEvent.Failed -> ok = false; is GenEvent.Done -> if (e.reason == StopReason.ABORTED) ok = false }
    }
    return if (ok) sb.toString() else null
}

/** Grammars are cached: converting a schema is cheap but happens on every agent step. */
object Grammars {
    private val cache = HashMap<String, String>()
    @Synchronized fun fromSchema(schema: String): String = cache.getOrPut(schema) { LlamaNative.schemaToGrammar(schema) }
}

/** Small embedding model in its own context (docs/KNOWLEDGE_ENGINE_DESIGN.md §4). */
@Singleton
class EmbeddingEngine @Inject constructor(@ApplicationContext private val context: Context, private val engine: LlamaEngine) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "embedder") }.asCoroutineDispatcher()
    private val mutex = Mutex()
    @Volatile var loadedPath: String? = null; private set
    var dim: Int = 0; private set

    suspend fun ensure(file: File, threads: Int): Boolean = mutex.withLock {
        engine.ensureNativeInit()
        if (loadedPath == file.absolutePath) return@withLock true
        withContext(dispatcher) {
            val d = LlamaNative.loadEmbedder(file.absolutePath, threads)
            if (d > 0) { dim = d; loadedPath = file.absolutePath; true } else { loadedPath = null; false }
        }
    }

    suspend fun embed(texts: List<String>): List<FloatArray?> = mutex.withLock {
        if (loadedPath == null) return@withLock texts.map { null }
        withContext(dispatcher) { LlamaNative.embed(texts.toTypedArray())?.toList() ?: texts.map { null } }
    }

    suspend fun unload() = mutex.withLock { withContext(dispatcher) { LlamaNative.unloadEmbedder() }; loadedPath = null }
}
