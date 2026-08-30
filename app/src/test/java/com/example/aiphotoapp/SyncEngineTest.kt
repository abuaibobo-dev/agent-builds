package com.example.aiphotoapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.data.SyncRule
import com.example.aiphotoapp.sync.SyncBackend
import com.example.aiphotoapp.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    private lateinit var db: CollectorDatabase

    private class FakeBackend(idsDesc: List<Long>, private val batchFailures: Int = 0) : SyncBackend {
        private val ids = idsDesc.sortedDescending()
        var batchSizes = mutableListOf<Int>()
        private var batchCalls = 0

        override fun historyPage(chatId: Long, fromMessageId: Long, limit: Int): JSONObject {
            val start = if (fromMessageId == 0L) 0 else ids.indexOfFirst { it <= fromMessageId } + 1
            val arr = JSONArray()
            ids.drop(start).take(limit).forEach { arr.put(msg(it)) }
            return JSONObject().put("messages", arr)
        }

        override fun copyMessages(sourceChatId: Long, targetChatId: Long, messageIds: List<Long>, removeCaption: Boolean): JSONObject {
            batchCalls++
            batchSizes += messageIds.size
            if (batchCalls <= batchFailures) return JSONObject().put("messages", JSONArray())
            val arr = JSONArray()
            messageIds.forEach { arr.put(msg(it)) }
            return JSONObject().put("messages", arr)
        }

        private fun msg(id: Long) = JSONObject()
            .put("@type", "message")
            .put("id", id)
            .put("content", JSONObject().put("@type", "messagePhoto"))
    }

    private fun rule() = SyncRule(
        sourceChatId = 1L,
        targetChatId = 2L,
        mediaTypes = "IMAGE,VIDEO,GIF",
        keepCaption = true,
        continuous = false,
    )

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CollectorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `chunks large pages into batches of 50 and records all copies`() = runBlocking<Unit> {
        val backend = FakeBackend((1L..120L).toList())
        val dao = db.collectorDao()
        val id = dao.insertRule(rule())
        val engine = SyncEngine(backend, dao)
        engine.runForTest(rule().copy(id = id))

        assertEquals(listOf(50, 50, 20), backend.batchSizes)
        assertEquals(120, dao.countCopiedMessages(id))
    }

    @Test
    fun `resume with cursor skips already copied messages`() = runBlocking<Unit> {
        val dao = db.collectorDao()
        val firstId = dao.insertRule(rule())
        SyncEngine(FakeBackend((1L..50L).toList()), dao).runForTest(rule().copy(id = firstId))

        val backend2 = FakeBackend((1L..50L).toList())
        val engine2 = SyncEngine(backend2, dao)
        engine2.runForTest(rule().copy(id = firstId))

        assertEquals(0, backend2.batchSizes.size)
        assertEquals(50, dao.countCopiedMessages(firstId))
    }

    @Test
    fun `failed batch falls back per message and only successes are recorded`() = runBlocking<Unit> {
        val backend = FakeBackend((1L..10L).toList(), batchFailures = 1)
        val dao = db.collectorDao()
        val id = dao.insertRule(rule())
        SyncEngine(backend, dao).runForTest(rule().copy(id = id))

        assertEquals(10, dao.countCopiedMessages(id))
        assertEquals(12, backend.batchSizes.size)
        assertEquals(10, backend.batchSizes[0])
        assertEquals(10, backend.batchSizes[1])
        assertTrue(backend.batchSizes.drop(2).all { it == 1 })
        for (sourceId in 1L..10L) {
            val copied = dao.findCopiedMessage(id, 1L, sourceId)!!
            assertTrue("target must be > 0 for $sourceId", copied.targetMessageId > 0L)
        }
    }
}