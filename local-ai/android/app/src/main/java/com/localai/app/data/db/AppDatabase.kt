package com.localai.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
)

@Dao
interface ModelDao {
    @Query("SELECT * FROM installed_model ORDER BY installedAt DESC") fun observe(): Flow<List<InstalledModelEntity>>
    @Query("SELECT * FROM installed_model WHERE id = :id") suspend fun get(id: String): InstalledModelEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: InstalledModelEntity)
    @Query("DELETE FROM installed_model WHERE id = :id") suspend fun delete(id: String)
}

data class ConversationSummary(val id: String, val title: String, val modelId: String?, val updatedAt: Long, val lastMessage: String?)

@Dao
interface ChatDao {
    @Query("""SELECT c.id, c.title, c.modelId, c.updatedAt,
              (SELECT content FROM message m WHERE m.conversationId = c.id ORDER BY createdAt DESC LIMIT 1) AS lastMessage
              FROM conversation c ORDER BY c.updatedAt DESC""")
    fun observeSummaries(): Flow<List<ConversationSummary>>
    @Query("SELECT * FROM conversation WHERE id = :id") fun observeConversation(id: String): Flow<ConversationEntity?>
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
    @Query("DELETE FROM conversation") suspend fun deleteAllConversations()
    @Query("DELETE FROM message") suspend fun deleteAllMessages()
}

@Database(entities = [InstalledModelEntity::class, ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun models(): ModelDao
    abstract fun chats(): ChatDao
}
