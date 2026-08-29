package com.example.aiphotoapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Persistence operations for collector state. */
@Dao
interface CollectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: SourceChannel)

    @Query("SELECT * FROM source_channels ORDER BY title")
    fun observeChannels(): Flow<List<SourceChannel>>

    @Delete
    suspend fun deleteChannel(channel: SourceChannel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: SyncRule): Long

    @Query("SELECT * FROM sync_rules ORDER BY id")
    fun observeRules(): Flow<List<SyncRule>>

    @Query("SELECT * FROM sync_rules WHERE id = :ruleId")
    suspend fun getRule(ruleId: Long): SyncRule?

    @Query("SELECT * FROM sync_rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<SyncRule>

    @Delete
    suspend fun deleteRule(rule: SyncRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SyncCursor)

    @Query("UPDATE sync_cursors SET status = :status, updatedAt = :updatedAt WHERE ruleId = :ruleId")
    suspend fun updateCursorStatus(ruleId: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sync_cursors WHERE ruleId = :ruleId")
    suspend fun getCursor(ruleId: Long): SyncCursor?

    @Query("SELECT * FROM sync_cursors WHERE ruleId = :ruleId")
    fun observeCursor(ruleId: Long): Flow<SyncCursor?>

    @Query("UPDATE sync_cursors SET scanMessageId = 0, status = 'IDLE', updatedAt = :updatedAt WHERE ruleId = :ruleId")
    suspend fun resetCursor(ruleId: Long, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCopiedMessage(message: CopiedMessage): Long

    @Query(
        "SELECT * FROM copied_messages " +
            "WHERE ruleId = :ruleId AND sourceChatId = :sourceChatId AND sourceMessageId = :sourceMessageId",
    )
    suspend fun findCopiedMessage(ruleId: Long, sourceChatId: Long, sourceMessageId: Long): CopiedMessage?

    @Query("SELECT COUNT(*) FROM copied_messages WHERE ruleId = :ruleId")
    suspend fun countCopiedMessages(ruleId: Long): Int

    @Insert
    suspend fun insertLog(log: SyncLog): Long

    @Query("SELECT * FROM sync_logs WHERE ruleId = :ruleId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentLogs(ruleId: Long, limit: Int = 50): Flow<List<SyncLog>>

    @Query("SELECT COUNT(*) FROM sync_logs WHERE ruleId = :ruleId AND level = :level")
    suspend fun countLogs(ruleId: Long, level: String): Int
}
