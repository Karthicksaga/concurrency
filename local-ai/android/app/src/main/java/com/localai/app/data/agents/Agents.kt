package com.localai.app.data.agents

import com.localai.app.data.db.AgentDao
import com.localai.app.data.db.AgentEntity
import com.localai.app.data.db.MemoryDao
import com.localai.app.data.db.MemoryEntity
import com.localai.app.runtime.inference.ChatTurn
import com.localai.app.runtime.inference.Grammars
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.runtime.inference.SamplingParams
import com.localai.app.runtime.inference.complete
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AgentTemplate(
    val id: String, val name: String, val role: String, val icon: String, val color: String, val description: String,
    val tools: List<String>, val memory: Boolean, val prompt: String, val temperature: Float,
)

object Templates {
    val all = listOf(
        AgentTemplate("research", "Research Agent", "Researcher", "search", "model", "Finds and synthesises information from your knowledge bases with citations.",
            listOf("knowledge_search", "document_reader", "note_manager"), true,
            "You are a careful research assistant. Answer only from the provided sources and cite every claim as [n]. If the sources do not contain the answer, say so and suggest what to import.", 0.3f),
        AgentTemplate("coding", "Coding Agent", "Engineer", "code", "agent", "Explains, reviews and drafts code using your indexed repositories.",
            listOf("code_search", "knowledge_search", "calculator"), true,
            "You are a senior software engineer. Prefer small, idiomatic, well-tested code. Reference the files you used. Never claim to have run code.", 0.2f),
        AgentTemplate("assistant", "Personal Assistant", "Assistant", "sparkle", "accent", "Everyday help with notes, plans and quick calculations. Remembers your preferences.",
            listOf("note_manager", "calculator", "knowledge_search", "phone_call"), true,
            "You are a concise, friendly personal assistant. Use what you remember about the user. Ask before creating or changing notes.", 0.7f),
        AgentTemplate("docanalyst", "Document Analyst", "Analyst", "file", "knowledge", "Summarises, compares and extracts facts from long documents.",
            listOf("document_reader", "knowledge_search"), false,
            "You analyse documents precisely. Quote exact clauses with page numbers. Use tables for comparisons. Cite sources as [n].", 0.1f),
        AgentTemplate("learning", "Learning Assistant", "Tutor", "school", "warn", "Turns course material into explanations, quizzes and flashcards.",
            listOf("knowledge_search", "note_manager"), true,
            "You are a patient tutor. Explain step by step, check understanding with one question at a time, and adapt to the learner's level.", 0.5f),
        AgentTemplate("data", "Data Analyst", "Data analyst", "table", "ok", "Answers questions about your notes and documents with read-only SQL and calculations.",
            listOf("sqlite_query", "calculator"), false,
            "You answer questions about the user's tables. Use one read-only SELECT at a time, explain the results, and never guess numbers.", 0.2f),
    )
    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

@Singleton
class AgentRepository @Inject constructor(private val dao: AgentDao, private val memory: MemoryDao) {
    val agents = dao.observe()
    fun agent(id: String) = dao.observeOne(id)
    suspend fun get(id: String) = dao.get(id)
    suspend fun all() = dao.all()

    suspend fun save(a: AgentEntity): Result<String> {
        val name = a.name.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("Give the agent a name."))
        if (dao.all().any { it.name.equals(name, true) && it.id != a.id }) return Result.failure(IllegalArgumentException("Another agent already uses this name."))
        if (a.systemPrompt.trim().length < 20) return Result.failure(IllegalArgumentException("Write at least a sentence so the agent knows its job."))
        dao.upsert(a.copy(name = name, updatedAt = System.currentTimeMillis()))
        return Result.success(a.id)
    }

    suspend fun delete(id: String) { memory.deleteAll(id); dao.delete(id) }

    suspend fun duplicate(id: String): String? {
        val a = dao.get(id) ?: return null
        val copy = a.copy(id = UUID.randomUUID().toString(), name = a.name + " copy", runs = 0, lastStatus = "NEVER", lastRunAt = null, lastError = null,
            createdAt = System.currentTimeMillis())
        dao.upsert(copy); return copy.id
    }

    fun fromTemplate(t: AgentTemplate) = AgentEntity(
        id = UUID.randomUUID().toString(), name = "", role = t.role, description = t.description, systemPrompt = t.prompt, templateId = t.id,
        modelId = null, color = t.color, icon = t.icon, tools = t.tools.joinToString(","), kbIds = "", memoryEnabled = t.memory, memoryAutoSave = false,
        maxSteps = 8, maxToolCalls = 3, temperature = t.temperature, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
    )
}

/** Agent memory: recall, proposals and user control (docs/KNOWLEDGE_ENGINE_DESIGN.md §9, ADR-0009). */
@Singleton
class MemoryService @Inject constructor(private val dao: MemoryDao, private val engine: LlamaEngine) {
    fun observe(agentId: String) = dao.observe(agentId)
    val proposedCount = dao.observeProposedCount()

    /** Up to 5 memories: pinned first, then keyword overlap, importance and recency. */
    suspend fun recall(agentId: String, query: String): List<MemoryEntity> {
        val all = dao.active(agentId); if (all.isEmpty()) return emptyList()
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val now = System.currentTimeMillis()
        val picked = all.sortedByDescending { m ->
            val overlap = terms.count { m.content.lowercase().contains(it) }
            (if (m.pinned) 100.0 else 0.0) + overlap * 3 + m.importance + 2.0 / (1 + (now - (m.lastUsedAt ?: m.createdAt)) / 86_400_000.0)
        }.take(5)
        dao.markUsed(picked.map { it.id }, now)
        return picked
    }

    fun block(memories: List<MemoryEntity>) = if (memories.isEmpty()) "" else
        "What you remember about the user (may be outdated; the user can edit these):\n" + memories.joinToString("\n") { "- ${it.content}" }

    suspend fun add(agentId: String, kind: String, content: String, status: String, source: String?) {
        val text = content.trim().take(280); if (text.length < 4) return
        if (dao.all(agentId).any { it.content.equals(text, true) }) return
        dao.upsert(MemoryEntity(UUID.randomUUID().toString(), agentId, kind, text, 3, false, status, source, System.currentTimeMillis(), null, 0))
    }
    suspend fun approve(m: MemoryEntity) = dao.upsert(m.copy(status = "ACTIVE"))
    suspend fun update(m: MemoryEntity) = dao.upsert(m)
    suspend fun delete(id: String) = dao.delete(id)
    suspend fun forgetAll(agentId: String) = dao.deleteAll(agentId)

    private val schema = """{"type":"object","properties":{"memories":{"type":"array","maxItems":3,"items":{"type":"object",
        "properties":{"kind":{"enum":["FACT","PREFERENCE","EPISODE"]},"content":{"type":"string","maxLength":200}},"required":["kind","content"]}}},"required":["memories"]}"""

    /** Asks the model for at most 3 durable facts/preferences from a conversation. Returns how many were added. */
    suspend fun suggest(agentId: String, autoSave: Boolean, transcript: List<ChatTurn>, source: String): Int {
        val text = transcript.takeLast(8).joinToString("\n") { "${it.role}: ${it.content.take(600)}" }
        val out = engine.complete(listOf(
            ChatTurn("system", "Extract at most 3 short facts or preferences about the USER that will still be useful in future conversations. " +
                "Ignore small talk and anything about the assistant. If there is nothing worth remembering, return an empty list. Reply with JSON only."),
            ChatTurn("user", text)), SamplingParams(temperature = 0f, maxTokens = 220), Grammars.fromSchema(schema)) ?: return 0
        val arr = runCatching { JSONObject(out).getJSONArray("memories") }.getOrNull() ?: return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val before = dao.all(agentId).size
            add(agentId, o.optString("kind", "FACT"), o.optString("content"), if (autoSave) "ACTIVE" else "PROPOSED", source)
            if (dao.all(agentId).size > before) n++
        }
        return n
    }
}
