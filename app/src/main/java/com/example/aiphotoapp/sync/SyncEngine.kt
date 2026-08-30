package com.example.aiphotoapp.sync

import com.example.aiphotoapp.data.CopiedMessage
import com.example.aiphotoapp.data.CollectorDao
import com.example.aiphotoapp.data.SyncCursor
import com.example.aiphotoapp.data.SyncLog
import com.example.aiphotoapp.data.SyncRule
import com.example.aiphotoapp.telegram.TelegramManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

class SyncEngine(
    private val telegram: TelegramManager,
    private val dao: CollectorDao,
) {
    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    fun start(rule: SyncRule, onStarted: (Long) -> Unit = {}, onUpdate: (String) -> Unit = {}) {
        stopRequested.set(false)
        paused.set(false)
        thread(name = "sync-rule-${rule.id}") {
            runBlocking {
                val persistedRule = if (rule.id == 0L) rule.copy(id = dao.insertRule(rule)) else rule
                onStarted(persistedRule.id)
                run(persistedRule, onUpdate)
            }
        }
    }

    fun pause() { paused.set(true) }
    fun resume() { paused.set(false) }
    fun stop() { stopRequested.set(true) }

    private suspend fun run(rule: SyncRule, onUpdate: (String) -> Unit) {
        val existing = dao.getCursor(rule.id)
        if (existing != null) {
            dao.updateCursorStatus(rule.id, "SCANNING", now())
        } else {
            dao.upsertCursor(SyncCursor(rule.id, status = "SCANNING", updatedAt = now()))
        }
        var fromId = existing?.scanMessageId ?: 0L
        log(rule.id, "INFO", if (fromId == 0L) "开始从历史消息扫描" else "从断点继续（message $fromId）")
        try {
            val pages = ArrayList<List<JSONObject>>()
            while (!stopRequested.get()) {
                waitIfPaused()
                val page = telegram.historyPage(rule.sourceChatId, fromId).optJSONArray("messages") ?: JSONArray()
                if (page.length() == 0) break
                val messages = ArrayList<JSONObject>(page.length())
                for (i in 0 until page.length()) messages += page.getJSONObject(i)
                pages += messages.reversed()
                fromId = messages.last().optLong("id")
                if (fromId == 0L || page.length() < 100) break
            }
            dao.upsertCursor(SyncCursor(rule.id, fromId, "COPYING", now()))
            val allowed = rule.mediaTypes.split(',').map { it.trim() }.toSet()
            for (page in pages) {
                val candidates = page.filter { mediaType(it) in allowed }
                val types = candidates.associate { it.optLong("id") to mediaType(it) }
                val ids = candidates.map { it.optLong("id") }
                    .filter { it > 0 && dao.findCopiedMessage(rule.id, rule.sourceChatId, it) == null }
                if (ids.isEmpty()) continue
                waitIfPaused()
                if (stopRequested.get()) break
                val result = telegram.copyMessages(rule.sourceChatId, rule.targetChatId, ids, !rule.keepCaption)
                val copied = result.optJSONArray("messages") ?: JSONArray()
                for (i in ids.indices) {
                    val targetId = copied.optJSONObject(i)?.optLong("id") ?: 0L
                    dao.insertCopiedMessage(CopiedMessage(rule.id, rule.sourceChatId, ids[i], targetId, types[ids[i]] ?: "MEDIA", now()))
                }
                onUpdate("已复制 ${ids.size} 条")
            }
            dao.upsertCursor(SyncCursor(rule.id, fromId, if (stopRequested.get()) "PAUSED" else "COMPLETED", now()))
            log(rule.id, "INFO", if (stopRequested.get()) "任务已暂停" else "历史采集完成")
        } catch (e: Exception) {
            dao.updateCursorStatus(rule.id, "FAILED", now())
            log(rule.id, "ERROR", "采集失败：${e.message}")
        }
    }

    private fun waitIfPaused() {
        while (paused.get() && !stopRequested.get()) Thread.sleep(250)
    }

    private fun mediaType(message: JSONObject): String {
        return when (message.optJSONObject("content")?.optString("@type")) {
            "messagePhoto" -> "IMAGE"
            "messageVideo" -> "VIDEO"
            "messageAnimation" -> "GIF"
            else -> "OTHER"
        }
    }

    private fun log(ruleId: Long, level: String, message: String) {
        dao.insertLog(SyncLog(ruleId = ruleId, level = level, message = message, createdAt = now()))
    }

    private fun now() = System.currentTimeMillis()
}
