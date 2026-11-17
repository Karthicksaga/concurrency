package com.localai.app.data.chat

import com.localai.app.data.agents.AgentProgress
import com.localai.app.data.agents.AgentRequest
import com.localai.app.data.agents.AgentRunner
import com.localai.app.data.agents.MemoryService
import com.localai.app.data.agents.ToolConfirmer
import com.localai.app.data.agents.ToolTrace
import com.localai.app.data.db.AgentDao
import com.localai.app.data.db.ChatDao
import com.localai.app.data.db.ConversationEntity
import com.localai.app.data.db.MessageEntity
import com.localai.app.data.knowledge.SourceRef
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.tools.ToolSpec
import com.localai.app.data.workflow.WorkflowService
import com.localai.app.runtime.inference.ChatTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A response being generated right now (kept app-wide so leaving the chat screen does not stop it). */
data class LiveGeneration(val conversationId: String, val messageId: String, val text: String, val phase: String)

/** A tool call waiting for the user's permission ("Ask every time"). */
data class ToolRequest(val conversationId: String, val tool: ToolSpec, val input: String, val answer: CompletableDeferred<Boolean>)

const val SUPERVISOR = "supervisor"

/** Hides reasoning blocks emitted by "thinking" models unless the user asked to see them. */
fun visibleText(raw: String, showReasoning: Boolean): String {
    if (showReasoning) return raw
    var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
    val open = s.indexOf("<think>")
    if (open >= 0) s = s.substring(0, open)
    return s.trimStart()
}

fun sourcesToJson(s: List<SourceRef>) = JSONArray(s.map { JSONObject().put("n", it.n).put("docId", it.docId).put("title", it.title).put("at", it.location ?: "").put("x", it.excerpt) }).toString()
fun sourcesFromJson(j: String?): List<SourceRef> = runCatching {
    val a = JSONArray(j ?: return emptyList()); (0 until a.length()).map { val o = a.getJSONObject(it); SourceRef(o.getInt("n"), o.getString("docId"), o.getString("title"), o.optString("at").ifBlank { null }, o.optString("x")) }
}.getOrDefault(emptyList())
fun toolsToJson(t: List<ToolTrace>) = JSONArray(t.map { JSONObject().put("tool", it.tool).put("in", it.input).put("out", it.output).put("st", it.status) }).toString()
fun toolsFromJson(j: String?): List<ToolTrace> = runCatching {
    val a = JSONArray(j ?: return emptyList()); (0 until a.length()).map { val o = a.getJSONObject(it); ToolTrace(o.getString("tool"), o.getString("in"), o.getString("out"), o.getString("st")) }
}.getOrDefault(emptyList())

@Singleton
class ChatService @Inject constructor(
    private val dao: ChatDao,
    private val agents: AgentDao,
    private val runner: AgentRunner,
    private val memory: MemoryService,
    private val workflows: WorkflowService,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _live = MutableStateFlow<LiveGeneration?>(null)
    val live: StateFlow<LiveGeneration?> = _live.asStateFlow()
    private val _toolRequest = MutableStateFlow<ToolRequest?>(null)
    val toolRequest: StateFlow<ToolRequest?> = _toolRequest.asStateFlow()
    private var job: Job? = null

    init { scope.launch { dao.markInterrupted() } }

    suspend fun newConversation(agentId: String? = null, kbIds: List<String> = emptyList(), title: String = "New chat"): String {
        val s = settings.current()
        val agent = agentId?.takeIf { it != SUPERVISOR }?.let { agents.get(it) }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val kbs = (kbIds + (agent?.kbList ?: emptyList())).distinct().joinToString(",")
        dao.upsertConversation(ConversationEntity(id, title, s.activeModelId, agent?.systemPrompt ?: s.systemPrompt, now, now, agentId, kbs))
        return id
    }

    suspend fun delete(id: String) { if (_live.value?.conversationId == id) stop(); dao.deleteMessages(id); dao.deleteConversation(id) }

    suspend fun updateConversation(id: String, block: (ConversationEntity) -> ConversationEntity) {
        val c = dao.conversation(id) ?: return
        dao.updateConversation(block(c))
    }

    fun answerTool(allow: Boolean) { _toolRequest.value?.answer?.complete(allow); _toolRequest.value = null }

    fun send(conversationId: String, text: String) {
        if (_live.value != null) return
        scope.launch {
            val c = dao.conversation(conversationId) ?: return@launch
            val now = System.currentTimeMillis()
            dao.upsertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, "user", text.trim(), "COMPLETE", null, null, null, null, now))
            val title = if (c.title == "New chat") text.trim().lineSequence().first().take(48) else c.title
            dao.updateConversation(c.copy(title = title, updatedAt = now))
            if (c.agentId == SUPERVISOR) workflows.start(text.trim(), emptyList(), conversationId) else respond(conversationId)
        }
    }

    fun regenerate(conversationId: String) {
        if (_live.value != null) return
        scope.launch {
            val c = dao.conversation(conversationId) ?: return@launch
            val last = dao.messages(conversationId).lastOrNull() ?: return@launch
            if (last.role == "assistant") dao.deleteMessage(last.id)
            if (c.agentId == SUPERVISOR) dao.messages(conversationId).lastOrNull { it.role == "user" }?.let { workflows.start(it.content, emptyList(), conversationId) }
            else respond(conversationId)
        }
    }

    fun stop() { job?.cancel(); _toolRequest.value?.answer?.complete(false); _toolRequest.value = null }

    /** Proposes memories from this conversation for its agent. Returns the number added. */
    suspend fun suggestMemories(conversationId: String): Int {
        val c = dao.conversation(conversationId) ?: return 0
        val a = c.agentId?.let { agents.get(it) } ?: return 0
        if (!a.memoryEnabled) return 0
        return memory.suggest(a.id, a.memoryAutoSave, dao.messages(conversationId).filter { it.status == "COMPLETE" }.map { ChatTurn(it.role, it.content) }, "Chat “${c.title}”")
    }

    private fun respond(conversationId: String) {
        val msgId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        _live.value = LiveGeneration(conversationId, msgId, "", "Loading model…")
        job = scope.launch {
            var text = ""; var status = "COMPLETE"; var show = false; var modelId: String? = null
            var sources = emptyList<SourceRef>(); var traces = emptyList<ToolTrace>(); var tps: Float? = null; var ptps: Float? = null; var toks: Int? = null
            var agentId: String? = null
            try {
                val c = dao.conversation(conversationId) ?: return@launch
                show = settings.current().showReasoning
                val agent = c.agentId?.let { agents.get(it) }
                agentId = agent?.id
                val history = dao.messages(conversationId).filter { it.status != "ERROR" }.map { ChatTurn(it.role, it.content) }
                val req = AgentRequest(agent, c.systemPrompt, history, c.kbIds.split(',').filter { it.isNotBlank() })
                val confirmer = ToolConfirmer { tool, input ->
                    val d = CompletableDeferred<Boolean>()
                    _toolRequest.value = ToolRequest(conversationId, tool, input, d)
                    _live.value = LiveGeneration(conversationId, msgId, "", "Waiting for your permission…")
                    d.await()
                }
                val r = runner.run(req, confirmer) { p ->
                    when (p) {
                        is AgentProgress.Phase -> _live.value = LiveGeneration(conversationId, msgId, "", p.label)
                        is AgentProgress.Text -> _live.value = LiveGeneration(conversationId, msgId, visibleText(p.text, show), "")
                    }
                }
                text = visibleText(r.text, show).ifBlank { if (r.status == "COMPLETE") "(The model returned an empty answer.)" else r.text }
                status = r.status; modelId = r.modelId; sources = r.sources; traces = r.tools
                tps = r.stats?.genTps; ptps = r.stats?.promptTps; toks = r.stats?.generatedTokens
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "STOPPED"; text = visibleText(_live.value?.text ?: "", show)
            } finally {
                withContext(NonCancellable) {
                    // keep only the sources the answer actually cited (fall back to all if none are cited)
                    val cited = sources.filter { text.contains("[${it.n}]") }.ifEmpty { sources }
                    dao.upsertMessage(MessageEntity(msgId, conversationId, "assistant", text, status, modelId, tps, ptps, toks,
                        maxOf(startedAt, System.currentTimeMillis() - 1), if (cited.isEmpty()) null else sourcesToJson(cited), if (traces.isEmpty()) null else toolsToJson(traces)))
                    dao.conversation(conversationId)?.let { dao.updateConversation(it.copy(updatedAt = System.currentTimeMillis(), modelId = modelId ?: it.modelId)) }
                    agentId?.let { agents.recordRun(it, if (status == "ERROR") "FAILED" else "COMPLETED", System.currentTimeMillis(), if (status == "ERROR") text.take(200) else null) }
                }
                _live.value = null
            }
        }
    }
}
