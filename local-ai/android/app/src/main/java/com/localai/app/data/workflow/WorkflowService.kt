package com.localai.app.data.workflow

import com.localai.app.data.agents.AgentProgress
import com.localai.app.data.agents.AgentRequest
import com.localai.app.data.agents.AgentRunner
import com.localai.app.data.agents.ToolConfirmer
import com.localai.app.data.db.AgentDao
import com.localai.app.data.db.AgentEntity
import com.localai.app.data.db.ChatDao
import com.localai.app.data.db.MessageEntity
import com.localai.app.data.db.RunDao
import com.localai.app.data.db.RunEntity
import com.localai.app.data.db.RunStepEntity
import com.localai.app.data.settings.SettingsRepository
import com.localai.app.data.settings.policyFor
import com.localai.app.runtime.device.DeviceCapabilityService
import com.localai.app.runtime.inference.ChatTurn
import com.localai.app.runtime.inference.Grammars
import com.localai.app.runtime.inference.LlamaEngine
import com.localai.app.runtime.inference.SamplingParams
import com.localai.app.runtime.inference.complete
import com.localai.app.data.models.ModelRepository
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

data class LiveStep(val runId: String, val stepId: String, val text: String, val phase: String)

/**
 * Supervisor workflow (docs/AGENT_ARCHITECTURE.md §3): plan (grammar-constrained DAG) → agent tasks in dependency
 * order on the single loaded model → review → aggregate. Every step is persisted so the UI and history read from the DB.
 */
@Singleton
class WorkflowService @Inject constructor(
    private val runs: RunDao,
    private val chats: ChatDao,
    private val agents: AgentDao,
    private val runner: AgentRunner,
    private val engine: LlamaEngine,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val device: DeviceCapabilityService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _active = MutableStateFlow<String?>(null)
    val activeRunId: StateFlow<String?> = _active.asStateFlow()
    private val _live = MutableStateFlow<LiveStep?>(null)
    val live: StateFlow<LiveStep?> = _live.asStateFlow()

    val history = runs.observeRuns()
    fun run(id: String) = runs.observeRun(id)
    fun steps(id: String) = runs.observeSteps(id)

    init { scope.launch { runs.markInterrupted(System.currentTimeMillis()); runs.markStepsInterrupted() } }

    /** Starts a run; returns its id, or null if another run is still going. */
    fun start(request: String, agentIds: List<String>, conversationId: String?): String? {
        if (job?.isActive == true) return null
        val id = UUID.randomUUID().toString()
        _active.value = id
        job = scope.launch {
            runs.upsertRun(RunEntity(id, request, "RUNNING", null, conversationId, agentIds.joinToString(","), 0, System.currentTimeMillis(), null, null))
            execute(id, plan = true)
        }
        return id
    }

    fun cancel() { job?.cancel() }

    /** Re-runs a failed task (and everything after it) without re-planning. */
    fun retry(runId: String, stepId: String) {
        if (job?.isActive == true) return
        _active.value = runId
        job = scope.launch {
            val steps = runs.steps(runId)
            val target = steps.firstOrNull { it.id == stepId } ?: return@launch
            steps.filter { it.ordinal >= target.ordinal }.forEach { runs.upsertStep(it.copy(state = "PENDING", output = "", error = null, startedAt = null, finishedAt = null)) }
            runs.run(runId)?.let { runs.upsertRun(it.copy(status = "RUNNING", finishedAt = null, finalAnswer = null)) }
            execute(runId, plan = false)
        }
    }

    private suspend fun step(s: RunStepEntity) = runs.upsertStep(s)

    private suspend fun execute(runId: String, plan: Boolean) {
        var tokens = 0
        try {
            val run = runs.run(runId) ?: return
            val policy = policyFor(settings.current(), device.profile)
            val lite = policy.lite
            val pool = (if (run.agentIds.isBlank()) agents.all() else run.agentIds.split(',').mapNotNull { agents.get(it) })
            val loaded = models.ensureLoaded()
            if (loaded.isFailure) { finish(runId, "FAILED", null, loaded.exceptionOrNull()?.message, tokens); return }

            if (plan) {
                val planStep = RunStepEntity(UUID.randomUUID().toString(), runId, 0, "PLAN", null, "Supervisor", "Split the request into tasks", "", "RUNNING", "", null, 1, 0, System.currentTimeMillis(), null)
                step(planStep)
                _live.value = LiveStep(runId, planStep.id, "", "Planning…")
                val tasks = plan(run.request, pool, if (lite) 3 else 4)
                step(planStep.copy(state = "COMPLETED", output = tasks.joinToString("\n") { t -> "${t.index + 1}. ${t.agent?.name ?: "Assistant"}: ${t.instruction}" + if (t.deps.isNotEmpty()) "  (after ${t.deps.joinToString { "${it + 1}" }})" else "" },
                    finishedAt = System.currentTimeMillis()))
                val ids = tasks.map { UUID.randomUUID().toString() }
                tasks.forEachIndexed { i, t ->
                    step(RunStepEntity(ids[i], runId, i + 1, "TASK", t.agent?.id, t.agent?.name ?: "Assistant", t.instruction,
                        t.deps.joinToString(",") { ids[it] }, "PENDING", "", null, 1, 0, null, null))
                }
                val last = tasks.size + 1
                if (!lite && tasks.size > 1) step(RunStepEntity(UUID.randomUUID().toString(), runId, last, "REVIEW", null, "Reviewer", "Check the drafts against the request", ids.joinToString(","), "PENDING", "", null, 1, 0, null, null))
                step(RunStepEntity(UUID.randomUUID().toString(), runId, last + 1, "AGGREGATE", null, "Aggregator", "Compose the final answer", "", "PENDING", "", null, 1, 0, null, null))
            }

            val maxTaskTokens = if (lite) 256 else 450
            for (s0 in runs.steps(runId).filter { it.kind == "TASK" }) {
                val s = runs.steps(runId).first { it.id == s0.id }
                if (s.state != "PENDING") continue
                val deps = runs.steps(runId).filter { it.id in s.dependsOn.split(',') }
                if (deps.any { it.state == "FAILED" || it.state == "SKIPPED" }) { step(s.copy(state = "SKIPPED", error = "An earlier step failed.")); continue }
                val agent = s.agentId?.let { agents.get(it) }
                val context = deps.joinToString("\n\n") { "Result from ${it.title}:\n${it.output.take(900)}" }
                var attempt = 1
                while (true) {
                    step(s.copy(state = "RUNNING", attempt = attempt, startedAt = System.currentTimeMillis(), error = null))
                    val prompt = "Overall request: ${run.request}\n\nYour task: ${s.instruction}" + (if (context.isNotEmpty()) "\n\n$context" else "") +
                        "\n\nDo only your task. Be concise."
                    val r = runner.run(AgentRequest(agent, agent?.systemPrompt ?: "You are a helpful assistant.", listOf(ChatTurn("user", prompt)),
                        agent?.kbList ?: emptyList(), maxTaskTokens, allowTools = !lite),
                        ToolConfirmer { _, _ -> false }) { p -> _live.value = LiveStep(runId, s.id, (p as? AgentProgress.Text)?.text ?: "", (p as? AgentProgress.Phase)?.label ?: "") }
                    tokens += r.stats?.generatedTokens ?: 0
                    val ok = r.status == "COMPLETE" && r.text.isNotBlank()
                    if (ok) {
                        step(s.copy(state = "COMPLETED", output = r.text.trim(), attempt = attempt, tokens = r.stats?.generatedTokens ?: 0, finishedAt = System.currentTimeMillis()))
                        agent?.let { agents.recordRun(it.id, "COMPLETED", System.currentTimeMillis(), null) }
                        break
                    }
                    if (attempt >= 2) {
                        step(s.copy(state = "FAILED", output = r.text, attempt = attempt, error = r.text.take(200).ifBlank { "Empty answer." }, finishedAt = System.currentTimeMillis()))
                        agent?.let { agents.recordRun(it.id, "FAILED", System.currentTimeMillis(), r.text.take(200)) }
                        break
                    }
                    attempt++
                }
            }

            val all = runs.steps(runId)
            val done = all.filter { it.kind == "TASK" && it.state == "COMPLETED" }
            all.firstOrNull { it.kind == "REVIEW" && it.state == "PENDING" }?.let { rv ->
                if (done.isEmpty()) step(rv.copy(state = "SKIPPED")) else {
                    step(rv.copy(state = "RUNNING", startedAt = System.currentTimeMillis()))
                    _live.value = LiveStep(runId, rv.id, "", "Reviewing…")
                    val out = engine.complete(listOf(ChatTurn("system", "You are a strict reviewer. Compare the drafts with the request. List at most 3 concrete problems, or reply exactly OK."),
                        ChatTurn("user", "Request: ${run.request}\n\n" + done.joinToString("\n\n") { "${it.title}:\n${it.output.take(1200)}" })),
                        SamplingParams(temperature = 0.1f, maxTokens = 160)) ?: ""
                    step(rv.copy(state = "COMPLETED", output = out.trim(), startedAt = rv.startedAt ?: System.currentTimeMillis(), finishedAt = System.currentTimeMillis()))
                }
            }

            val agg = runs.steps(runId).first { it.kind == "AGGREGATE" }
            if (done.isEmpty()) {
                step(agg.copy(state = "SKIPPED"))
                finish(runId, "FAILED", null, "No task completed.", tokens); return
            }
            step(agg.copy(state = "RUNNING", startedAt = System.currentTimeMillis()))
            val review = runs.steps(runId).firstOrNull { it.kind == "REVIEW" && it.state == "COMPLETED" }?.output?.takeIf { !it.trim().equals("OK", true) }
            val failed = runs.steps(runId).filter { it.kind == "TASK" && it.state != "COMPLETED" }
            var final = ""
            val r = runner.run(AgentRequest(null, "You combine the work of several assistants into one clear, well-structured answer for the user. " +
                "Keep code blocks intact. Do not mention the assistants unless something is missing.",
                listOf(ChatTurn("user", "Request: ${run.request}\n\n" + done.joinToString("\n\n") { "${it.title} (${it.instruction}):\n${it.output.take(1500)}" } +
                    (review?.let { "\n\nReviewer notes to address:\n$it" } ?: "") +
                    (if (failed.isNotEmpty()) "\n\nThese parts could not be completed: ${failed.joinToString { it.instruction }}. Say so briefly." else ""))),
                emptyList(), if (lite) 384 else 700, allowTools = false), ToolConfirmer { _, _ -> false }) { p ->
                if (p is AgentProgress.Text) { final = p.text; _live.value = LiveStep(runId, agg.id, p.text, "") }
            }
            tokens += r.stats?.generatedTokens ?: 0
            final = r.text.trim()
            step(agg.copy(state = if (r.status == "COMPLETE") "COMPLETED" else "FAILED", output = final, startedAt = agg.startedAt ?: System.currentTimeMillis(), finishedAt = System.currentTimeMillis()))
            finish(runId, if (failed.isEmpty() && r.status == "COMPLETE") "COMPLETED" else "PARTIAL", final, null, tokens)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                runs.steps(runId).filter { it.state == "RUNNING" || it.state == "PENDING" }.forEach { runs.upsertStep(it.copy(state = "CANCELLED")) }
                finish(runId, "CANCELLED", null, null, tokens)
            }
        } finally {
            _live.value = null
        }
    }

    private suspend fun finish(runId: String, status: String, answer: String?, error: String?, tokens: Int) = withContext(NonCancellable) {
        val run = runs.run(runId) ?: return@withContext
        runs.upsertRun(run.copy(status = status, finalAnswer = answer, error = error, tokens = run.tokens + tokens, finishedAt = System.currentTimeMillis()))
        run.conversationId?.let { cid ->
            val text = answer ?: when (status) { "CANCELLED" -> "Workflow cancelled."; else -> "The workflow could not complete: ${error ?: "unknown error"}." }
            chats.upsertMessage(MessageEntity(UUID.randomUUID().toString(), cid, "assistant", text, if (status == "FAILED") "ERROR" else "COMPLETE", null, null, null, null,
                System.currentTimeMillis(), runId = runId))
            chats.conversation(cid)?.let { chats.updateConversation(it.copy(updatedAt = System.currentTimeMillis())) }
        }
        if (_active.value == runId && status != "RUNNING") Unit
    }

    data class PlannedTask(val index: Int, val agent: AgentEntity?, val instruction: String, val deps: List<Int>)

    /** Planner: a DAG of at most [max] tasks, generated under a JSON grammar and validated. */
    private suspend fun plan(request: String, pool: List<AgentEntity>, max: Int): List<PlannedTask> {
        if (pool.isEmpty()) return listOf(PlannedTask(0, null, request, emptyList()))
        val handles = pool.mapIndexed { i, a -> "a${i + 1}" to a }.toMap()
        val schema = JSONObject().put("type", "object").put("required", JSONArray(listOf("tasks"))).put("properties", JSONObject().put("tasks",
            JSONObject().put("type", "array").put("minItems", 1).put("maxItems", max).put("items", JSONObject().put("type", "object")
                .put("required", JSONArray(listOf("agent", "instruction", "after")))
                .put("properties", JSONObject()
                    .put("agent", JSONObject().put("enum", JSONArray(handles.keys.toList())))
                    .put("instruction", JSONObject().put("type", "string").put("maxLength", 240))
                    .put("after", JSONObject().put("type", "array").put("maxItems", 3).put("items", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", max))))))).toString()
        val out = engine.complete(listOf(
            ChatTurn("system", "You are the supervisor. Split the user's request into at most $max small tasks and assign each to the best agent. " +
                "Use as few tasks as possible; one task is fine for simple requests. 'after' lists earlier task numbers (1-based) whose results the task needs.\nAgents:\n" +
                handles.entries.joinToString("\n") { (h, a) -> "$h: ${a.name} — ${a.role}. ${a.description}" } + "\nReply with JSON only."),
            ChatTurn("user", request)), SamplingParams(temperature = 0.2f, maxTokens = 320), Grammars.fromSchema(schema))
        val parsed = runCatching {
            val arr = JSONObject(out ?: "").getJSONArray("tasks")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val after = o.optJSONArray("after")
                PlannedTask(i, handles[o.getString("agent")], o.getString("instruction").trim().ifBlank { request },
                    (0 until (after?.length() ?: 0)).map { after!!.getInt(it) - 1 }.filter { it in 0 until i }.distinct())   // only earlier tasks: acyclic
            }
        }.getOrNull()
        return parsed?.takeIf { it.isNotEmpty() } ?: listOf(PlannedTask(0, pool.first(), request, emptyList()))
    }
}
