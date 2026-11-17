package com.localai.app.data.agents

import com.localai.app.data.db.AgentEntity
import com.localai.app.data.knowledge.KnowledgeService
import com.localai.app.data.knowledge.SourceRef
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.data.tools.Permission
import com.localai.app.data.tools.ToolRegistry
import com.localai.app.data.tools.ToolScope
import com.localai.app.data.tools.ToolSpec
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.ChatTurn
import com.localai.app.runtime.inference.EngineState
import com.localai.app.runtime.inference.GenEvent
import com.localai.app.runtime.inference.GenerationStats
import com.localai.app.runtime.inference.Grammars
import com.localai.app.runtime.inference.InferenceError
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.runtime.inference.SamplingParams
import com.localai.app.runtime.inference.StopReason
import com.localai.app.runtime.inference.complete
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ToolTrace(val tool: String, val input: String, val output: String, val status: String)

data class AgentRequest(
    val agent: AgentEntity?,
    val systemPrompt: String,
    val history: List<ChatTurn>,          // ends with the user's latest message
    val kbIds: List<String>,
    val maxTokens: Int? = null,
    val allowTools: Boolean = true,
)

data class AgentResult(
    val text: String, val status: String, val sources: List<SourceRef>, val tools: List<ToolTrace>, val stats: GenerationStats?, val modelId: String?,
)

/** Phases the UI can show while an agent works. */
sealed interface AgentProgress {
    data class Phase(val label: String) : AgentProgress
    data class Text(val text: String) : AgentProgress
}

fun interface ToolConfirmer { suspend fun confirm(tool: ToolSpec, input: String): Boolean }

/**
 * Single-agent loop (docs/AGENT_ARCHITECTURE.md §2): grammar-constrained tool decisions, then a streamed final
 * answer grounded in memory, tool results and retrieved sources.
 */
@Singleton
class AgentRunner @Inject constructor(
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val tools: ToolRegistry,
    private val knowledge: KnowledgeService,
    private val memory: MemoryService,
    private val settings: SettingsRepository,
    private val device: DeviceCapabilityService,
) {
    suspend fun run(req: AgentRequest, confirmer: ToolConfirmer, progress: suspend (AgentProgress) -> Unit): AgentResult {
        // 1. Model: the agent's own model if it has one, otherwise the active model.
        progress(AgentProgress.Phase("Loading model…"))
        val agentModel = req.agent?.modelId
        // An agent's own model is used only if it is installed; otherwise fall back to the active model.
        val loaded = if (agentModel != null && models.isInstalled(agentModel) && (engine.state.value as? EngineState.Ready)?.modelId != agentModel)
            models.activate(agentModel).map { agentModel } else models.ensureLoaded()
        val modelId = loaded.getOrElse { return AgentResult(it.message ?: "The model could not be loaded.", "ERROR", emptyList(), emptyList(), null, null) }

        val s = settings.current()
        val policy = policyFor(s, device.profile)
        val ctx = (engine.state.value as? EngineState.Ready)?.contextLength ?: policy.defaults.contextLength
        val profile = settings.profileNow(modelId, policy.defaults.contextLength)
        val query = req.history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val a = req.agent

        // 2. Memory recall
        val mem = if (a != null && a.memoryEnabled) memory.recall(a.id, query) else emptyList()

        // 3. Tools (grammar-constrained decisions)
        val traces = ArrayList<ToolTrace>()
        val perms = tools.permissions.first()
        val enabled = (a?.toolList ?: emptyList()).filter { it != "delegate_to_agent" && perms[it] != Permission.OFF }.mapNotNull { tools.spec(it) }
        val maxCalls = if (policy.lite) minOf(a?.maxToolCalls ?: 0, 2) else a?.maxToolCalls ?: 0
        if (req.allowTools && enabled.isNotEmpty() && maxCalls > 0) {
            val schema = JSONObject().put("type", "object").put("required", JSONArray(listOf("action", "tool", "input"))).put("properties", JSONObject()
                .put("action", JSONObject().put("enum", JSONArray(listOf("answer", "use_tool"))))
                .put("tool", JSONObject().put("enum", JSONArray(enabled.map { it.id })))
                .put("input", JSONObject().put("type", "string").put("maxLength", 300))).toString()
            val grammar = Grammars.fromSchema(schema)
            val toolList = enabled.joinToString("\n") { "- ${it.id}: ${it.description} Input: ${it.inputFormat}." }
            repeat(maxCalls) {
                progress(AgentProgress.Phase(if (traces.isEmpty()) "Deciding whether to use a tool…" else "Checking tool results…"))
                val results = if (traces.isEmpty()) "" else "\n\nTool results so far:\n" + traces.joinToString("\n") { "${it.tool}(${it.input}) → ${it.output.take(700)}" }
                val decision = engine.complete(listOf(
                    ChatTurn("system", "You are ${a?.name ?: "an assistant"} (${a?.role ?: "assistant"}). Decide whether ONE tool call would help answer the user's last message.\n" +
                        "Tools:\n$toolList\nIf you can answer directly, or the tool results are already enough, use action \"answer\" (tool and input are then ignored). Reply with JSON only."),
                    ChatTurn("user", query.take(1500) + results)),
                    SamplingParams(temperature = 0f, maxTokens = 120), grammar) ?: return@repeat
                val o = runCatching { JSONObject(decision) }.getOrNull() ?: return@repeat
                if (o.optString("action") != "use_tool") return@repeat
                val spec = tools.spec(o.optString("tool")) ?: return@repeat
                val input = o.optString("input")
                if (traces.any { it.tool == spec.id && it.input == input }) return@repeat     // no loops on the same call
                val scope = ToolScope(a?.id, req.kbIds)
                val allowed = perms[spec.id] == Permission.ALLOWED || confirmer.confirm(spec, input)
                val r = if (allowed) { progress(AgentProgress.Phase("Using ${spec.name}…")); tools.execute(spec.id, input, scope) } else tools.recordDenied(spec.id, input, scope)
                traces += ToolTrace(spec.id, input, r.output, r.status)
                if (!allowed) return@repeat
            }
        }

        // 4. Retrieval from selected knowledge bases
        var sourcesText = ""; var refs = emptyList<SourceRef>()
        if (req.kbIds.isNotEmpty()) {
            progress(AgentProgress.Phase("Searching your documents…"))
            val hits = knowledge.retrieve(query, req.kbIds, if (policy.lite) 4 else 6)
            val pair = KnowledgeService.sourcesBlock(hits, (ctx * 4 * 0.35).toInt()); sourcesText = pair.first; refs = pair.second
        }

        // 5. Final answer, streamed
        val system = buildString {
            append(a?.systemPrompt ?: req.systemPrompt)
            memory.block(mem).takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
            if (traces.isNotEmpty()) append("\n\nTool results (from tools you called):\n").append(traces.joinToString("\n") { "${it.tool}(${it.input}) → ${it.output.take(1200)}" })
            if (sourcesText.isNotEmpty()) append("\n\n").append(sourcesText)
        }
        progress(AgentProgress.Phase("Thinking…"))
        val params = SamplingParams(temperature = a?.temperature ?: profile.temperature, topP = profile.topP,
            maxTokens = req.maxTokens ?: policy.defaults.maxNewTokens, enableThinking = s.showReasoning)
        var history = req.history
        var text = ""; var status = "COMPLETE"; var stats: GenerationStats? = null
        while (true) {
            var overflow = false
            engine.generate(listOf(ChatTurn("system", system)) + history, params).collect { ev ->
                when (ev) {
                    is GenEvent.Delta -> { text += ev.text; progress(AgentProgress.Text(text)) }
                    is GenEvent.Done -> { stats = ev.stats; status = if (ev.reason == StopReason.ABORTED) "STOPPED" else "COMPLETE" }
                    is GenEvent.Failed -> if (ev.error == InferenceError.CONTEXT_OVERFLOW) overflow = true else { text = ev.error.message; status = "ERROR" }
                }
            }
            if (overflow && history.size > 1) { history = history.drop(if (history.size > 2) 2 else 1); text = ""; continue }
            if (overflow) { text = "This request is too long for the model's context window. Shorten it, select fewer knowledge bases, or raise the context length."; status = "ERROR" }
            break
        }
        return AgentResult(text, status, refs, traces, stats, modelId)
    }
}
