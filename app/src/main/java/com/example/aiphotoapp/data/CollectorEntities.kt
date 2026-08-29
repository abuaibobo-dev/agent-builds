package com.example.aiphotoapp.data

import androidx.room.Entity
import androidx.room.Index

/** Telegram channel available to the authenticated account. */
@Entity(tableName = "source_channels")
data class SourceChannel(
    @androidx.room.PrimaryKey val chatId: Long,
    val title: String,
    val username: String? = null,
    val enabled: Boolean = true,
)

/** Source-to-target collection rule and media filtering options. */
@Entity(
    tableName = "sync_rules",
    indices = [Index(value = ["sourceChatId", "targetChatId"], unique = true)],
)
data class SyncRule(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceChatId: Long,
    val targetChatId: Long,
    val mediaTypes: String = "IMAGE,VIDEO",
    val keepCaption: Boolean = true,
    val keepAlbum: Boolean = true,
    val continuous: Boolean = false,
    val enabled: Boolean = true,
)

/** Durable scan position for one rule. */
@Entity(tableName = "sync_cursors")
data class SyncCursor(
    @androidx.room.PrimaryKey val ruleId: Long,
    val scanMessageId: Long = 0,
    val status: String = "IDLE",
    val updatedAt: Long = 0,
)

/** Deduplication record for a copied source message. */
@Entity(
    tableName = "copied_messages",
    primaryKeys = ["ruleId", "sourceChatId", "sourceMessageId"],
    indices = [Index(value = ["ruleId", "sourceChatId", "sourceMessageId"], unique = true)],
)
data class CopiedMessage(
    val ruleId: Long,
    val sourceChatId: Long,
    val sourceMessageId: Long,
    val targetMessageId: Long,
    val mediaType: String,
    val copiedAt: Long = 0,
)

/** Event emitted while a synchronization rule runs. */
@Entity(tableName = "sync_logs", indices = [Index(value = ["ruleId", "createdAt"])])
data class SyncLog(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val level: String,
    val message: String,
    val createdAt: Long = 0,
)
