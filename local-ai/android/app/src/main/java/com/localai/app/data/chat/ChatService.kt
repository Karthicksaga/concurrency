package com.localai.app.data.chat

import com.localai.app.data.db.ChatDao
import com.localai.app.data.db.ConversationEntity
import com.localai.app.data.db.MessageEntity
import com.localai.app.data.models.ModelRepository
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.ChatTurn
import com.localai.app.runtime.inference.GenEvent
import com.localai.app.runtime.inference.InferenceError
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.runtime.inference.SamplingParams
import com.localai.app.runtime.inference.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A response being generated right now (kept app-wide so leaving the chat screen does not stop it). */
data class LiveGeneration(val conversationId: String, val messageId: String, val text: String, val phase: String)

/** Hides reasoning blocks emitted by "thinking" models unless the user asked to see them. */
fun visibleText(raw: String, showReasoning: Boolean): String {
    if (showReasoning) return raw
    var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
    val open = s.indexOf("<think>")
    if (open >= 0) s = s.substring(0, open)
    return s.trimStart()
}

@Singleton
class ChatService @Inject constructor(
    private val dao: ChatDao,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val device: DeviceCapabilityService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _live = MutableStateFlow<LiveGeneration?>(null)
    val live: StateFlow<LiveGeneration?> = _live.asStateFlow()
    private var job: Job? = null

    init { scope.launch { dao.markInterrupted() } }

    suspend fun newConversation(): String {
        val s = settings.current()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.upsertConversation(ConversationEntity(id, "New chat", s.activeModelId, s.systemPrompt, now, now))
        return id
    }

    suspend fun delete(id: String) { if (_live.value?.conversationId == id) stop(); dao.deleteMessages(id); dao.deleteConversation(id) }

    suspend fun updateSystemPrompt(id: String, prompt: String, title: String) {
        val c = dao.conversation(id) ?: return
        dao.updateConversation(c.copy(systemPrompt = prompt, title = title.ifBlank { c.title }))
    }

    fun send(conversationId: String, text: String) {
        if (_live.value != null) return
        scope.launch {
            val c = dao.conversation(conversationId) ?: return@launch
            val now = System.currentTimeMillis()
            dao.upsertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, "user", text.trim(), "COMPLETE", null, null, null, null, now))
            val title = if (c.title == "New chat") text.trim().lineSequence().first().take(48) else c.title
            dao.updateConversation(c.copy(title = title, updatedAt = now))
            respond(conversationId)
        }
    }

    fun regenerate(conversationId: String) {
        if (_live.value != null) return
        scope.launch {
            val last = dao.messages(conversationId).lastOrNull() ?: return@launch
            if (last.role == "assistant") dao.deleteMessage(last.id)
            respond(conversationId)
        }
    }

    fun stop() { job?.cancel() }

    private suspend fun respond(conversationId: String) {
        val msgId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        _live.value = LiveGeneration(conversationId, msgId, "", "Loading model…")
        job = scope.launch {
            var text = ""
            var status = "COMPLETE"
            var stats: com.localai.app.runtime.inference.GenerationStats? = null
            var show = false
            var modelId: String? = null
            try {
                val loaded = models.ensureLoaded()
                modelId = loaded.getOrNull()
                if (loaded.isFailure) {
                    text = loaded.exceptionOrNull()?.message ?: "The model could not be loaded."
                    status = "ERROR"
                    return@launch
                }
                val s = settings.current()
                show = s.showReasoning
                val policy = policyFor(s, device.profile)
                val conv = dao.conversation(conversationId) ?: return@launch
                val profile = settings.profileNow(modelId ?: return@launch, policy.defaults.contextLength)
                var history = dao.messages(conversationId).filter { it.status != "ERROR" }.map { ChatTurn(it.role, it.content) }
                val params = SamplingParams(temperature = profile.temperature, topP = profile.topP,
                    maxTokens = policy.defaults.maxNewTokens, enableThinking = s.showReasoning)
                _live.value = LiveGeneration(conversationId, msgId, "", "Thinking…")
                while (true) {
                    val turns = listOf(ChatTurn("system", conv.systemPrompt)) + history
                    var overflow = false
                    engine.generate(turns, params).collect { ev ->
                        when (ev) {
                            is GenEvent.Delta -> { text += ev.text; _live.value = LiveGeneration(conversationId, msgId, visibleText(text, s.showReasoning), "") }
                            is GenEvent.Done -> { stats = ev.stats; status = if (ev.reason == StopReason.ABORTED) "STOPPED" else "COMPLETE" }
                            is GenEvent.Failed -> if (ev.error == InferenceError.CONTEXT_OVERFLOW) overflow = true
                                else { text = ev.error.message; status = "ERROR" }
                        }
                    }
                    // Too long for the context window: drop the oldest exchange and try again.
                    if (overflow && history.size > 1) { history = history.drop(if (history.size > 2) 2 else 1); continue }
                    if (overflow) { text = "This message is too long for the model's context window. Shorten it or raise the context length in the model settings."; status = "ERROR" }
                    break
                }
                text = visibleText(text, s.showReasoning).ifBlank { if (status == "COMPLETE") "(The model returned an empty answer.)" else text }
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "STOPPED"
            } finally {
                val st = stats
                if (status == "STOPPED") text = visibleText(text, show)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    dao.upsertMessage(MessageEntity(msgId, conversationId, "assistant", text, status, modelId,
                        st?.genTps, st?.promptTps, st?.generatedTokens, maxOf(startedAt, System.currentTimeMillis() - 1)))
                    dao.conversation(conversationId)?.let { dao.updateConversation(it.copy(updatedAt = System.currentTimeMillis(), modelId = modelId ?: it.modelId)) }
                }
                _live.value = null
            }
        }
    }
}
