package com.example.aiphotoapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.aiphotoapp.data.CopiedMessage
import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.data.SourceChannel
import com.example.aiphotoapp.data.SyncCursor
import com.example.aiphotoapp.data.SyncRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class SyncStateTest {
    private lateinit var database: CollectorDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CollectorDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun resetCursorChangesScanPositionWithoutDeletingCopiedMessages() = runBlocking {
        val ruleId = database.collectorDao().insertRule(SyncRule(sourceChatId = 11L, targetChatId = 22L))
        val rule = SyncRule(id = ruleId, sourceChatId = 11L, targetChatId = 22L)
        database.collectorDao().upsertCursor(
            SyncCursor(ruleId = rule.id, scanMessageId = 900L, status = "RUNNING"),
        )
        database.collectorDao().insertCopiedMessage(
            CopiedMessage(
                ruleId = rule.id,
                sourceChatId = 11L,
                sourceMessageId = 901L,
                targetMessageId = 1001L,
                mediaType = "IMAGE",
            ),
        )

        database.collectorDao().resetCursor(rule.id)

        assertEquals(0L, database.collectorDao().getCursor(rule.id)?.scanMessageId)
        assertNotNull(database.collectorDao().findCopiedMessage(rule.id, 11L, 901L))
    }
}
