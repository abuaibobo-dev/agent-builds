package com.example.aiphotoapp.data

import androidx.room.Database
import androidx.room.RoomDatabase

/** Room database containing durable Telegram collector state. */
@Database(
    entities = [SourceChannel::class, SyncRule::class, SyncCursor::class, CopiedMessage::class, SyncLog::class],
    version = 1,
    exportSchema = false,
)
abstract class CollectorDatabase : RoomDatabase() {
    abstract fun collectorDao(): CollectorDao
}
