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
    private val batchSize = 50

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
        val allowed = rule.mediaTypes.split(',').map { it.trim() }.toSet()
        var cursor = dao.getCursor(rule.id)?.scanMessageId ?: 0L
        setStatus(rule.id, "SCANNING")
        log(rule.id, "INFO", if (cursor == 0L) "开始从历史消息扫描" else "从断点继续（message $cursor）")
        onUpdate(if (cursor == 0L) "从历史消息扫描…" else "断点续采（$cursor）…")
        try {
            var copiedTotal = 0
            // 阶段一：按页从新到旧扫描，边扫边复制，游标每页落库（内存有界、崩溃续采）
            while (!stopRequested.get()) {
                waitIfPaused()
                val messages = pageMessages(historySafe(rule.sourceChatId, cursor))
                if (messages.isEmpty()) {
                    if (copyFailures()) { setStatus(rule.id, "FAILED"); log(rule.id, "ERROR", "连续取页失败"); return }
                    break
                }
                resetCopyFailures()
                val newOnes = messages
                    .filter { it.optLong("id") > 0L && mediaType(it) in allowed &&
                        dao.findCopiedMessage(rule.id, rule.sourceChatId, it.optLong("id")) == null }
                    .sortedBy { it.optLong("id") }
                if (newOnes.isNotEmpty()) {
                    var copied = 0
                    for (chunk in newOnes.chunked(batchSize)) {
                        if (stopRequested.get()) break
                        waitIfPaused()
                        copied += copyChunk(rule, chunk)
                    }
                    copiedTotal += copied
                    if (copied > 0) onUpdate("已复制 $copied 条·游标 $cursor")
                }
                cursor = messages.last().optLong("id")
                dao.upsertCursor(SyncCursor(rule.id, cursor, "SCANNING", now()))
                if (messages.size < 100 || cursor <= 0L) break
            }
            if (!stopRequested.get()) {
                if (rule.continuous && cursor > 0L) {
                    startContinuous(rule, allowed, onUpdate)
                } else {
                    setStatus(rule.id, "COMPLETED")
                    log(rule.id, "INFO", "历史采集完成，共复制 $copiedTotal 条")
                    onUpdate("历史采集完成，共复制 $copiedTotal 条")
                }
            } else {
                setStatus(rule.id, "PAUSED")
                log(rule.id, "INFO", "任务已暂停（游标保留，可重启续采）")
            }
        } catch (e: Exception) {
            setStatus(rule.id, "FAILED")
            log(rule.id, "ERROR", "采集失败：${e.message}")
            onUpdate("采集失败：${e.message}")
        }
    }

    // 阶段二：常驻轮询，新帖 20 秒内自动落库
    private suspend fun startContinuous(rule: SyncRule, allowed: Set<String>, onUpdate: (String) -> Unit) {
        setStatus(rule.id, "CONTINUOUS")
        log(rule.id, "INFO", "历史完成，进入持续采集")
        onUpdate("历史完成，进入持续采集…")
        var lastId = dao.getCursor(rule.id)?.scanMessageId ?: 0L
        while (!stopRequested.get()) {
            waitIfPaused()
            val newest = newestMessageId(rule.sourceChatId)
            if (newest > lastId) {
                val copyable = collectNewer(rule.sourceChatId, lastId)
                    .filter { it.optLong("id") > lastId && mediaType(it) in allowed &&
                        dao.findCopiedMessage(rule.id, rule.sourceChatId, it.optLong("id")) == null }
                    .sortedBy { it.optLong("id") }
                if (copyable.isEmpty()) { lastId = newest; continue }
                var copied = 0
                for (chunk in copyable.chunked(batchSize)) {
                    if (stopRequested.get()) break
                    waitIfPaused()
                    copied += copyChunk(rule, chunk)
                }
                lastId = maxOf(lastId, copyable.last().optLong("id"))
                dao.upsertCursor(SyncCursor(rule.id, lastId, "CONTINUOUS", now()))
                if (copied > 0) onUpdate("新增复制 $copied 条·游标 $lastId")
            }
            sleepInterruptible()
        }
        setStatus(rule.id, "PAUSED")
        log(rule.id, "INFO", "持续采集已停止，可随时重启")
    }

    private suspend fun copyChunk(rule: SyncRule, chunk: List<JSONObject>): Int {
        val ids = chunk.map { it.optLong("id") }
        val types = chunk.associate { it.optLong("id") to mediaType(it) }
        val result = telegram
            .runCatching { copyMessages(rule.sourceChatId, rule.targetChatId, ids, !rule.keepCaption) }
            .getOrElse { JSONObject().put("messages", JSONArray()) }
        val copied = result.optJSONArray("messages") ?: JSONArray()
        for (i in ids.indices) {
            dao.insertCopiedMessage(
                CopiedMessage(rule.id, rule.sourceChatId, ids[i], copied.optJSONObject(i)?.optLong("id") ?: 0L, types[ids[i]] ?: "MEDIA", now()),
            )
        }
        return ids.size
    }

    private fun collectNewer(sourceChatId: Long, lastId: Long): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        var fromId = 0L
        while (true) {
            val messages = pageMessages(historySafe(sourceChatId, fromId))
            if (messages.isEmpty()) break
            val stillNewer = messages.filter { it.optLong("id") > lastId }
            found += stillNewer
            if (stillNewer.size < messages.size || messages.size < 100) break
            fromId = messages.last().optLong("id")
        }
        return found
    }

    private fun newestMessageId(sourceChatId: Long): Long {
        val messages = pageMessages(historySafe(sourceChatId, 0L))
        return if (messages.isEmpty()) 0L else messages.first().optLong("id")
    }

    private var recentlyFailed = 0

    private fun pageMessages(page: JSONObject): List<JSONObject> {
        val arr = page.optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun historySafe(sourceChatId: Long, fromMessageId: Long): JSONObject {
        val attempt = telegram.runCatching { historyPage(sourceChatId, fromMessageId) }
        if (attempt.isSuccess) { recentlyFailed = 0; return attempt.getOrThrow() }
        recentlyFailed++
        Thread.sleep(3000)
        return JSONObject()
    }

    private fun copyFailures() = recentlyFailed >= 3
    private fun resetCopyFailures() { recentlyFailed = 0 }

    private fun sleepInterruptible() {
        var waited = 0L
        while (!stopRequested.get() && waited < POLL_INTERVAL_MS) {
            if (paused.get()) Thread.sleep(250)
            else { Thread.sleep(1000); waited += 1000 }
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

    private suspend fun setStatus(ruleId: Long, status: String) {
        dao.updateCursorStatus(ruleId, status)
    }

    private suspend fun log(ruleId: Long, level: String, message: String) {
        dao.insertLog(SyncLog(ruleId = ruleId, level = level, message = message, createdAt = now()))
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val POLL_INTERVAL_MS = 20_000L
    }
}