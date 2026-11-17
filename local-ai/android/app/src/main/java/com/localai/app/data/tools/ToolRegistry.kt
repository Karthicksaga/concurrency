package com.localai.app.data.tools

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import com.localai.app.data.db.KnowledgeDao
import com.localai.app.data.db.NoteDao
import com.localai.app.data.db.NoteEntity
import com.localai.app.data.db.ToolCallDao
import com.localai.app.data.db.ToolCallEntity
import com.localai.app.data.knowledge.KnowledgeService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class Permission(val label: String) { ALLOWED("Allow"), ASK("Ask"), OFF("Off") }
enum class Risk(val label: String) { READ("Read only"), WRITE_LOCAL("Changes local data"), DELEGATE("Delegates to agents") }

/** Static description of a built-in tool. Inputs are one string whose format is described to the model. */
data class ToolSpec(
    val id: String, val name: String, val description: String, val inputFormat: String, val risk: Risk,
    val defaultPermission: Permission, val timeoutMs: Long = 5_000, val maxInput: Int = 400,
)

data class ToolResult(val ok: Boolean, val output: String, val status: String)

/** Execution context: what the calling agent may see. */
data class ToolScope(val agentId: String?, val kbIds: List<String>)

private val Context.toolStore by preferencesDataStore("tools")

/**
 * Typed, permissioned tools (docs/SECURITY_AND_PRIVACY.md §3, ADR-0007). No tool runs code, opens the network
 * or changes permissions. Every call is validated, time-limited and written to the audit log.
 */
@Singleton
class ToolRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knowledge: KnowledgeService,
    private val kdao: KnowledgeDao,
    private val notes: NoteDao,
    private val log: ToolCallDao,
) {
    val tools = listOf(
        ToolSpec("calculator", "Calculator", "Evaluates arithmetic with a safe parser. No variables, no code.",
            "an arithmetic expression, e.g. (1250 * 12) * 0.18", Risk.READ, Permission.ALLOWED, maxInput = 200),
        ToolSpec("knowledge_search", "Knowledge Search", "Searches the agent's knowledge bases by meaning and keywords, with sources.",
            "a search query in plain words", Risk.READ, Permission.ALLOWED, timeoutMs = 15_000, maxInput = 300),
        ToolSpec("document_reader", "Document Reader", "Reads the beginning of a document from the agent's knowledge bases.",
            "the document title (or part of it)", Risk.READ, Permission.ALLOWED, maxInput = 200),
        ToolSpec("code_search", "Code Search", "Searches indexed source code files in the agent's knowledge bases.",
            "what the code does or a symbol name", Risk.READ, Permission.ALLOWED, timeoutMs = 15_000, maxInput = 200),
        ToolSpec("note_manager", "Note Manager", "Lists, reads and creates your notes. Creating a note asks first by default.",
            "'list', 'read: <title>' or 'create: <title> | <text>'", Risk.WRITE_LOCAL, Permission.ASK, maxInput = 4000),
        ToolSpec("sqlite_query", "SQLite Query", "Runs one read-only SELECT on your notes and document list. Writes are blocked.",
            "one SELECT statement on tables note(title, body, createdAt) or document(title, kind, status, sizeBytes)", Risk.READ, Permission.ASK, maxInput = 1000),
        ToolSpec("file_search", "File Search", "Finds files by name in the folder you granted. Returns names, not contents.",
            "part of a file name, e.g. invoice", Risk.READ, Permission.ASK, timeoutMs = 15_000, maxInput = 120),
        ToolSpec("delegate_to_agent", "Agent Delegation", "Hands a sub-task to another agent. Used by the supervisor in workflows only.",
            "used internally by workflows", Risk.DELEGATE, Permission.ALLOWED),
    )
    fun spec(id: String) = tools.firstOrNull { it.id == id }

    // ---------- permissions ----------
    private fun key(id: String) = stringPreferencesKey("perm_$id")
    private val folderKey = stringPreferencesKey("file_search_tree")
    val permissions: Flow<Map<String, Permission>> = context.toolStore.data.map { p ->
        tools.associate { t -> t.id to (p[key(t.id)]?.let { runCatching { Permission.valueOf(it) }.getOrNull() } ?: t.defaultPermission) }
    }
    val folder: Flow<String?> = context.toolStore.data.map { it[folderKey] }
    suspend fun setPermission(id: String, p: Permission) { context.toolStore.edit { it[key(id)] = p.name } }
    suspend fun permission(id: String) = permissions.first()[id] ?: Permission.OFF
    suspend fun setFolder(uri: Uri?) {
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.toolStore.edit { if (uri == null) it.remove(folderKey) else it[folderKey] = uri.toString() }
    }
    val auditLog = log.observe()

    // ---------- execution ----------

    /** Validates and runs a tool. Permission (ASK) is resolved by the caller before this. */
    suspend fun execute(toolId: String, rawInput: String, scope: ToolScope): ToolResult {
        val spec = spec(toolId) ?: return record(scope, toolId, rawInput, ToolResult(false, "Unknown tool.", "INVALID"), 0)
        val input = rawInput.trim()
        if (input.length > spec.maxInput) return record(scope, toolId, input, ToolResult(false, "Input is longer than ${spec.maxInput} characters.", "INVALID"), 0)
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(spec.timeoutMs) {
            try { run(toolId, input, scope) } catch (e: ToolInputException) { ToolResult(false, e.message ?: "Invalid input.", "INVALID") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { ToolResult(false, "Tool error: ${e.message}", "ERROR") }
        } ?: ToolResult(false, "Timed out after ${spec.timeoutMs / 1000} s.", "ERROR")
        return record(scope, toolId, input, r, System.currentTimeMillis() - t0)
    }

    suspend fun recordDenied(toolId: String, input: String, scope: ToolScope) = record(scope, toolId, input, ToolResult(false, "The user declined this tool call.", "DENIED"), 0)

    private suspend fun record(scope: ToolScope, toolId: String, input: String, r: ToolResult, ms: Long): ToolResult {
        log.insert(ToolCallEntity(UUID.randomUUID().toString(), scope.agentId, toolId, input.take(500), r.output.take(500), r.status, ms, System.currentTimeMillis()))
        return r
    }

    private suspend fun run(id: String, input: String, scope: ToolScope): ToolResult = when (id) {
        "calculator" -> { val v = Calculator.eval(input); ToolResult(true, Calculator.format(v), "OK") }
        "knowledge_search", "code_search" -> {
            if (scope.kbIds.isEmpty()) ToolResult(false, "This agent has no knowledge bases.", "ERROR") else {
                var hits = knowledge.retrieve(input, scope.kbIds, 5)
                if (id == "code_search") { val code = kdao.docsIn(scope.kbIds).filter { it.kind == "CODE" }.map { it.id }.toSet(); hits = hits.filter { it.docId in code } }
                if (hits.isEmpty()) ToolResult(true, "No matching passages.", "OK")
                else ToolResult(true, hits.joinToString("\n\n") { "[${it.docTitle}${it.location?.let { l -> ", $l" } ?: ""}] ${it.text.take(600)}" }, "OK")
            }
        }
        "document_reader" -> {
            val docs = kdao.docsIn(scope.kbIds)
            val d = docs.firstOrNull { it.title.equals(input, true) } ?: docs.firstOrNull { it.title.contains(input, true) }
                ?: throw ToolInputException("No document called \"$input\" in this agent's knowledge bases.")
            val chunks = kdao.chunksOf(d.id, 6)
            ToolResult(true, "${d.title}:\n" + chunks.joinToString("\n") { it.text }.take(4000), "OK")
        }
        "note_manager" -> notes(input, scope)
        "sqlite_query" -> sql(input)
        "file_search" -> files(input)
        "delegate_to_agent" -> ToolResult(false, "Delegation is only available inside workflows.", "INVALID")
        else -> ToolResult(false, "Unknown tool.", "INVALID")
    }

    private suspend fun notes(input: String, scope: ToolScope): ToolResult {
        val lower = input.lowercase()
        return when {
            lower == "list" -> ToolResult(true, notes.all().take(30).joinToString("\n") { "- ${it.title}" }.ifBlank { "No notes yet." }, "OK")
            lower.startsWith("read:") -> {
                val t = input.substringAfter(':').trim()
                val n = notes.all().firstOrNull { it.title.equals(t, true) } ?: notes.all().firstOrNull { it.title.contains(t, true) }
                ToolResult(n != null, n?.let { "${it.title}\n${it.body}" } ?: "No note called \"$t\".", if (n != null) "OK" else "ERROR")
            }
            lower.startsWith("create:") -> {
                val rest = input.substringAfter(':')
                val title = rest.substringBefore('|').trim().take(120); val body = rest.substringAfter('|', "").trim()
                if (title.isBlank()) throw ToolInputException("A note needs a title: create: <title> | <text>")
                val now = System.currentTimeMillis()
                notes.upsert(NoteEntity(UUID.randomUUID().toString(), title, body, "AGENT", scope.agentId, now, now))
                ToolResult(true, "Created note \"$title\".", "OK")
            }
            else -> throw ToolInputException("Use 'list', 'read: <title>' or 'create: <title> | <text>'.")
        }
    }

    /** Read-only SQL on an allowlisted set of tables through a separate read-only connection. */
    private suspend fun sql(input: String): ToolResult = withContext(Dispatchers.IO) {
        val q = input.trim().trimEnd(';')
        val lower = q.lowercase()
        if (!lower.startsWith("select ")) throw ToolInputException("Only a single SELECT statement is allowed.")
        if (q.contains(';') || Regex("\\b(insert|update|delete|drop|alter|attach|detach|pragma|create|replace|vacuum)\\b").containsMatchIn(lower))
            throw ToolInputException("Only read-only SELECT statements are allowed.")
        val tables = Regex("\\b(?:from|join)\\s+([a-z_][a-z0-9_]*)").findAll(lower).map { it.groupValues[1] }.toSet()
        val allowed = setOf("note", "document")
        if (tables.isEmpty() || !allowed.containsAll(tables)) throw ToolInputException("Allowed tables: note, document.")
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath("app.db").path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            it.rawQuery(q, null).use { c ->
                val rows = ArrayList<String>(); rows += c.columnNames.joinToString(" | ")
                var n = 0
                while (c.moveToNext() && n < 50) { rows += (0 until c.columnCount).joinToString(" | ") { i -> (c.getString(i) ?: "null").take(120) }; n++ }
                ToolResult(true, rows.joinToString("\n") + if (n == 50) "\n(first 50 rows)" else "", "OK")
            }
        }
    }

    private suspend fun files(input: String): ToolResult = withContext(Dispatchers.IO) {
        val tree = folder.first() ?: return@withContext ToolResult(false, "No folder has been granted. Choose one in Tool registry › File Search.", "ERROR")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return@withContext ToolResult(false, "The granted folder is no longer available.", "ERROR")
        val out = ArrayList<String>(); var visited = 0
        fun walk(dir: DocumentFile, path: String, depth: Int) {
            if (depth > 3 || out.size >= 20 || visited > 2000) return
            dir.listFiles().forEach { f ->
                visited++
                val name = f.name ?: return@forEach
                if (f.isDirectory) walk(f, "$path$name/", depth + 1)
                else if (name.contains(input, ignoreCase = true) && out.size < 20) out += "$path$name (${(f.length() / 1024)} KB)"
            }
        }
        walk(root, "", 0)
        ToolResult(true, if (out.isEmpty()) "No files match \"$input\"." else out.joinToString("\n"), "OK")
    }
}

class ToolInputException(message: String) : Exception(message)

/** Recursive-descent arithmetic parser (never eval). Shared with the prototype's design. */
object Calculator {
    fun format(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) "%,.0f".format(v) else "%,.6f".format(v).trimEnd('0').trimEnd('.')

    fun eval(src: String): Double {
        val s = src.replace("×", "*").replace("÷", "/").replace(",", "").replace(" ", "").lowercase()
        if (s.isEmpty()) throw ToolInputException("Enter an expression.")
        var p = 0
        fun peek() = if (p < s.length) s[p] else '\u0000'
        lateinit var expr: () -> Double
        fun number(): Double {
            val m = Regex("^(\\d+(\\.\\d+)?|\\.\\d+)").find(s.substring(p)) ?: throw ToolInputException("Expected a number at position ${p + 1}.")
            p += m.value.length; return m.value.toDouble()
        }
        val fns = mapOf<String, (Double) -> Double>("sqrt" to Math::sqrt, "log" to Math::log10, "ln" to Math::log, "abs" to Math::abs,
            "sin" to Math::sin, "cos" to Math::cos, "tan" to Math::tan)
        fun atom(): Double {
            if (peek() == '(') { p++; val v = expr(); if (peek() != ')') throw ToolInputException("Missing closing parenthesis."); p++; return v }
            if (peek() == '-') { p++; return -atom() }
            val f = Regex("^[a-z]+").find(s.substring(p))
            if (f != null) {
                if (f.value == "pi") { p += 2; return Math.PI }
                val fn = fns[f.value] ?: throw ToolInputException("Unknown function \"${f.value}\".")
                p += f.value.length; if (peek() != '(') throw ToolInputException("Expected ( after ${f.value}.")
                return fn(atom())
            }
            return number()
        }
        fun power(): Double { var v = atom(); while (peek() == '^') { p++; v = Math.pow(v, atom()) }; if (peek() == '%') { p++; v /= 100 }; return v }
        fun term(): Double {
            var v = power()
            while (peek() == '*' || peek() == '/') { val o = s[p++]; val r = power(); if (o == '/' && r == 0.0) throw ToolInputException("Division by zero."); v = if (o == '*') v * r else v / r }
            return v
        }
        expr = { var v = term(); while (peek() == '+' || peek() == '-') { val o = s[p++]; val r = term(); v = if (o == '+') v + r else v - r }; v }
        val v = expr()
        if (p != s.length) throw ToolInputException("Unexpected \"${s[p]}\" at position ${p + 1}.")
        if (v.isNaN() || v.isInfinite()) throw ToolInputException("The result is not a finite number.")
        return v
    }
}
