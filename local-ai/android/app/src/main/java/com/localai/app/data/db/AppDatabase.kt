package com.localai.app.data.db

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.localai.app.data.catalog.ModelSpec
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "installed_model")
data class InstalledModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val family: String,
    val paramsB: Double,
    val quant: String,
    val sizeBytes: Long,
    val arch: String,
    val ctxTrain: Int,
    val layers: Int,
    val kvHeads: Int,
    val headDim: Int,
    val license: String,
    val fileName: String,
    val sha256: String?,
    val source: String,            // CATALOG | IMPORT
    val installedAt: Long,
) {
    fun toSpec() = ModelSpec(id, name, family, paramsB, quant, sizeBytes, arch, ctxTrain, layers, kvHeads, headDim, license,
        if (source == "IMPORT") "Imported from your device. Not checksum-verified against the catalog." else "",
        if (source == "IMPORT") listOf("Imported") else emptyList(), null, sha256, fileName)
}

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String?,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val agentId: String? = null,                       // agent id, "supervisor" for workflows, or null
    @ColumnInfo(defaultValue = "") val kbIds: String = "",  // comma-separated knowledge base ids
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

@Entity(tableName = "message")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,              // user | assistant
    val content: String,
    val status: String,            // COMPLETE | STREAMING | STOPPED | ERROR
    val modelId: String?,
    val genTps: Float?,
    val promptTps: Float?,
    val tokens: Int?,
    val createdAt: Long,
    val sourcesJson: String? = null,
    val toolsJson: String? = null,
    val runId: String? = null,
    val imagePath: String? = null,     // photo attached to a user message (app-private file)
)

@Dao
interface ModelDao {
    @Query("SELECT * FROM installed_model ORDER BY installedAt DESC") fun observe(): Flow<List<InstalledModelEntity>>
    @Query("SELECT * FROM installed_model WHERE id = :id") suspend fun get(id: String): InstalledModelEntity?
    @Query("SELECT * FROM installed_model") suspend fun all(): List<InstalledModelEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: InstalledModelEntity)
    @Query("DELETE FROM installed_model WHERE id = :id") suspend fun delete(id: String)
}

data class ConversationSummary(val id: String, val title: String, val modelId: String?, val updatedAt: Long, val lastMessage: String?, val pinned: Boolean = false)

@Dao
interface ChatDao {
    @Query("""SELECT c.id, c.title, c.modelId, c.updatedAt, c.pinned,
              (SELECT content FROM message m WHERE m.conversationId = c.id ORDER BY createdAt DESC LIMIT 1) AS lastMessage
              FROM conversation c ORDER BY c.updatedAt DESC""")
    fun observeSummaries(): Flow<List<ConversationSummary>>
    @Query("SELECT * FROM conversation WHERE id = :id") fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean)
    @Query("UPDATE conversation SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
    @Query("SELECT * FROM conversation WHERE id = :id") suspend fun conversation(id: String): ConversationEntity?
    @Query("SELECT * FROM message WHERE conversationId = :id ORDER BY createdAt ASC") fun observeMessages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM message WHERE conversationId = :id ORDER BY createdAt ASC") suspend fun messages(id: String): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertConversation(c: ConversationEntity)
    @Update suspend fun updateConversation(c: ConversationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMessage(m: MessageEntity)
    @Query("DELETE FROM message WHERE id = :id") suspend fun deleteMessage(id: String)
    @Query("DELETE FROM conversation WHERE id = :id") suspend fun deleteConversation(id: String)
    @Query("DELETE FROM message WHERE conversationId = :id") suspend fun deleteMessages(id: String)
    @Query("UPDATE message SET status = 'STOPPED' WHERE status = 'STREAMING'") suspend fun markInterrupted()
    @Query("SELECT * FROM conversation WHERE agentId = :agentId ORDER BY updatedAt DESC LIMIT 5") suspend fun byAgent(agentId: String): List<ConversationEntity>
    @Query("DELETE FROM conversation") suspend fun deleteAllConversations()
    @Query("DELETE FROM conversation WHERE id NOT IN (SELECT DISTINCT conversationId FROM message)") suspend fun deleteEmptyConversations()
    @Query("DELETE FROM message") suspend fun deleteAllMessages()
}

// ---------------- v2: agents, knowledge, memory, tools, workflows ----------------

@Entity(tableName = "agent")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String,
    val description: String,
    val systemPrompt: String,
    val templateId: String?,
    val modelId: String?,          // null = use the active model
    val color: String,
    val icon: String,
    val tools: String,             // comma-separated tool ids
    val kbIds: String,             // comma-separated knowledge base ids
    val memoryEnabled: Boolean,
    val memoryAutoSave: Boolean,
    val maxSteps: Int,
    val maxToolCalls: Int,
    val temperature: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val lastStatus: String = "NEVER",   // NEVER | COMPLETED | FAILED
    val lastRunAt: Long? = null,
    val lastError: String? = null,
    val runs: Int = 0,
) {
    val toolList get() = tools.split(',').filter { it.isNotBlank() }
    val kbList get() = kbIds.split(',').filter { it.isNotBlank() }
}

@Entity(tableName = "knowledge_base")
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val scope: String,             // SHARED | AGENT
    val ownerAgentId: String?,
    val embedModelId: String,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUri: String? = null,     // linked folder (SAF tree) kept in sync
    val lastSyncedAt: Long? = null,
)

@Entity(tableName = "document", indices = [Index("kbId")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val kbId: String,
    val title: String,
    val kind: String,              // PDF | DOCX | TEXT | MARKDOWN | CODE | NOTE
    val sizeBytes: Long,
    val pages: Int?,
    val status: String,            // QUEUED | EXTRACTING | EMBEDDING | INDEXED | FAILED
    val progress: Float,
    val chunkCount: Int,
    val error: String?,
    val storedPath: String,
    val sha256: String,
    val createdAt: Long,
    val indexedAt: Long?,
    val semantic: Boolean = false, // embeddings stored (otherwise keyword search only)
    val sourceUri: String? = null,       // file in a linked folder
    val sourceModified: Long? = null,    // its lastModified at import, to detect changes
)

@Entity(tableName = "chunk", indices = [Index("docId"), Index("kbId")])
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kbId: String,
    val docId: String,
    val ordinal: Int,
    val text: String,
    val location: String?,         // "p. 4", "§ Heading", "lines 10–42"
    val tokenCount: Int,
    val embedding: ByteArray?,     // float32 little-endian, L2-normalised
)

@Fts4(contentEntity = ChunkEntity::class)
@Entity(tableName = "chunk_fts")
data class ChunkFts(val text: String)

@Entity(tableName = "memory", indices = [Index("agentId")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val kind: String,              // FACT | PREFERENCE | EPISODE
    val content: String,
    val importance: Int,
    val pinned: Boolean,
    val status: String,            // PROPOSED | ACTIVE
    val source: String?,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int,
)

@Entity(tableName = "note")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val createdBy: String,         // USER | AGENT
    val agentId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "tool_call")
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val agentId: String?,
    val toolId: String,
    val input: String,
    val output: String,
    val status: String,            // OK | ERROR | DENIED | INVALID
    val durationMs: Long,
    val createdAt: Long,
)

@Entity(tableName = "run")
data class RunEntity(
    @PrimaryKey val id: String,
    val request: String,
    val status: String,            // RUNNING | COMPLETED | PARTIAL | FAILED | CANCELLED
    val finalAnswer: String?,
    val conversationId: String?,
    val agentIds: String,
    val tokens: Int,
    val startedAt: Long,
    val finishedAt: Long?,
    val error: String?,
)

@Entity(tableName = "run_step", indices = [Index("runId")])
data class RunStepEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val ordinal: Int,
    val kind: String,              // PLAN | TASK | REVIEW | AGGREGATE
    val agentId: String?,
    val title: String,
    val instruction: String,
    val dependsOn: String,         // comma-separated step ids
    val state: String,             // PENDING | RUNNING | COMPLETED | FAILED | SKIPPED | CANCELLED
    val output: String,
    val error: String?,
    val attempt: Int,
    val tokens: Int,
    val startedAt: Long?,
    val finishedAt: Long?,
)

data class ChunkVector(val id: Long, val docId: String, val embedding: ByteArray)
data class ChunkHit(val id: Long, val docId: String, val kbId: String, val text: String, val location: String?)
data class DocCount(val kbId: String, val docs: Int, val bytes: Long, val indexing: Int, val failed: Int)

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent ORDER BY COALESCE(lastRunAt, createdAt) DESC") fun observe(): Flow<List<AgentEntity>>
    @Query("SELECT * FROM agent WHERE id = :id") fun observeOne(id: String): Flow<AgentEntity?>
    @Query("SELECT * FROM agent WHERE id = :id") suspend fun get(id: String): AgentEntity?
    @Query("SELECT * FROM agent") suspend fun all(): List<AgentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(a: AgentEntity)
    @Query("DELETE FROM agent WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE agent SET lastStatus = :status, lastRunAt = :at, lastError = :error, runs = runs + 1 WHERE id = :id")
    suspend fun recordRun(id: String, status: String, at: Long, error: String?)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_base ORDER BY updatedAt DESC") fun observeKbs(): Flow<List<KnowledgeBaseEntity>>
    @Query("SELECT * FROM knowledge_base WHERE id = :id") fun observeKb(id: String): Flow<KnowledgeBaseEntity?>
    @Query("SELECT * FROM knowledge_base WHERE id = :id") suspend fun kb(id: String): KnowledgeBaseEntity?
    @Query("SELECT * FROM knowledge_base") suspend fun allKbs(): List<KnowledgeBaseEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertKb(k: KnowledgeBaseEntity)
    @Query("DELETE FROM knowledge_base WHERE id = :id") suspend fun deleteKb(id: String)
    @Query("UPDATE knowledge_base SET updatedAt = :at WHERE id = :id") suspend fun touchKb(id: String, at: Long)
    @Query("UPDATE knowledge_base SET sourceUri = :uri, lastSyncedAt = :at WHERE id = :id") suspend fun setSource(id: String, uri: String?, at: Long?)
    @Query("SELECT * FROM knowledge_base WHERE sourceUri IS NOT NULL") suspend fun linkedKbs(): List<KnowledgeBaseEntity>
    @Query("SELECT * FROM document WHERE createdAt >= :since ORDER BY createdAt DESC") suspend fun docsSince(since: Long): List<DocumentEntity>

    @Query("""SELECT kbId, COUNT(*) AS docs, SUM(sizeBytes) AS bytes,
              SUM(CASE WHEN status IN ('QUEUED','EXTRACTING','EMBEDDING') THEN 1 ELSE 0 END) AS indexing,
              SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed FROM document GROUP BY kbId""")
    fun observeCounts(): Flow<List<DocCount>>
    @Query("SELECT * FROM document WHERE kbId = :kbId ORDER BY createdAt DESC") fun observeDocs(kbId: String): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM document WHERE id = :id") fun observeDoc(id: String): Flow<DocumentEntity?>
    @Query("SELECT * FROM document WHERE id = :id") suspend fun doc(id: String): DocumentEntity?
    @Query("SELECT * FROM document WHERE kbId IN (:kbIds)") suspend fun docsIn(kbIds: List<String>): List<DocumentEntity>
    @Query("SELECT * FROM document WHERE status IN ('QUEUED','EXTRACTING','EMBEDDING') ORDER BY createdAt") suspend fun pending(): List<DocumentEntity>
    @Query("SELECT COUNT(*) FROM document WHERE kbId = :kbId AND sha256 = :sha") suspend fun countSha(kbId: String, sha: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDoc(d: DocumentEntity)
    @Query("UPDATE document SET status = :status, progress = :progress WHERE id = :id") suspend fun setStatus(id: String, status: String, progress: Float)
    @Query("DELETE FROM document WHERE id = :id") suspend fun deleteDoc(id: String)
    @Query("DELETE FROM document WHERE kbId = :kbId") suspend fun deleteDocsOfKb(kbId: String)

    @Insert suspend fun insertChunks(c: List<ChunkEntity>)
    @Query("DELETE FROM chunk WHERE docId = :docId") suspend fun deleteChunks(docId: String)
    @Query("DELETE FROM chunk WHERE kbId = :kbId") suspend fun deleteChunksOfKb(kbId: String)
    @Query("SELECT * FROM chunk WHERE docId = :docId ORDER BY ordinal LIMIT :limit") suspend fun chunksOf(docId: String, limit: Int): List<ChunkEntity>
    @Query("SELECT * FROM chunk WHERE docId = :docId ORDER BY ordinal LIMIT :limit") fun observeChunks(docId: String, limit: Int): Flow<List<ChunkEntity>>
    @Query("SELECT id, docId, embedding FROM chunk WHERE kbId = :kbId AND embedding IS NOT NULL") suspend fun vectors(kbId: String): List<ChunkVector>
    @Query("SELECT id, docId, kbId, text, location FROM chunk WHERE id IN (:ids)") suspend fun hits(ids: List<Long>): List<ChunkHit>
    @Query("""SELECT chunk.id, chunk.docId, chunk.kbId, chunk.text, chunk.location FROM chunk
              JOIN chunk_fts ON chunk.id = chunk_fts.rowid WHERE chunk_fts MATCH :q AND chunk.kbId IN (:kbIds) LIMIT :limit""")
    suspend fun keyword(q: String, kbIds: List<String>, limit: Int): List<ChunkHit>
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory WHERE agentId = :agentId ORDER BY pinned DESC, importance DESC, createdAt DESC") fun observe(agentId: String): Flow<List<MemoryEntity>>
    @Query("SELECT COUNT(*) FROM memory WHERE status = 'PROPOSED'") fun observeProposedCount(): Flow<Int>
    @Query("SELECT * FROM memory WHERE agentId = :agentId AND status = 'ACTIVE'") suspend fun active(agentId: String): List<MemoryEntity>
    @Query("SELECT * FROM memory WHERE agentId = :agentId") suspend fun all(agentId: String): List<MemoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: MemoryEntity)
    @Query("DELETE FROM memory WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM memory WHERE agentId = :agentId") suspend fun deleteAll(agentId: String)
    @Query("UPDATE memory SET useCount = useCount + 1, lastUsedAt = :at WHERE id IN (:ids)") suspend fun markUsed(ids: List<String>, at: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM note ORDER BY updatedAt DESC") fun observe(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM note ORDER BY updatedAt DESC") suspend fun all(): List<NoteEntity>
    @Query("SELECT * FROM note WHERE updatedAt >= :since ORDER BY updatedAt DESC") suspend fun since(since: Long): List<NoteEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(n: NoteEntity)
    @Query("DELETE FROM note WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_call ORDER BY createdAt DESC LIMIT 100") fun observe(): Flow<List<ToolCallEntity>>
    @Insert suspend fun insert(t: ToolCallEntity)
}

@Dao
interface RunDao {
    @Query("SELECT * FROM run ORDER BY startedAt DESC LIMIT 30") fun observeRuns(): Flow<List<RunEntity>>
    @Query("SELECT * FROM run WHERE startedAt >= :since ORDER BY startedAt DESC") suspend fun runsSince(since: Long): List<RunEntity>
    @Query("SELECT * FROM run WHERE id = :id") fun observeRun(id: String): Flow<RunEntity?>
    @Query("SELECT * FROM run WHERE id = :id") suspend fun run(id: String): RunEntity?
    @Query("SELECT * FROM run_step WHERE runId = :runId ORDER BY ordinal") fun observeSteps(runId: String): Flow<List<RunStepEntity>>
    @Query("SELECT * FROM run_step WHERE runId = :runId ORDER BY ordinal") suspend fun steps(runId: String): List<RunStepEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRun(r: RunEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStep(s: RunStepEntity)
    @Query("DELETE FROM run_step WHERE runId = :runId AND ordinal >= :fromOrdinal") suspend fun deleteStepsFrom(runId: String, fromOrdinal: Int)
    @Query("UPDATE run SET status = 'CANCELLED', finishedAt = :at WHERE status = 'RUNNING'") suspend fun markInterrupted(at: Long)
    @Query("UPDATE run_step SET state = 'CANCELLED' WHERE state IN ('RUNNING','PENDING') AND runId IN (SELECT id FROM run WHERE status = 'CANCELLED')") suspend fun markStepsInterrupted()
}

@Database(
    entities = [InstalledModelEntity::class, ConversationEntity::class, MessageEntity::class,
        AgentEntity::class, KnowledgeBaseEntity::class, DocumentEntity::class, ChunkEntity::class, ChunkFts::class,
        MemoryEntity::class, NoteEntity::class, ToolCallEntity::class, RunEntity::class, RunStepEntity::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun models(): ModelDao
    abstract fun chats(): ChatDao
    abstract fun agents(): AgentDao
    abstract fun knowledge(): KnowledgeDao
    abstract fun memory(): MemoryDao
    abstract fun notes(): NoteDao
    abstract fun toolCalls(): ToolCallDao
    abstract fun runs(): RunDao
}
