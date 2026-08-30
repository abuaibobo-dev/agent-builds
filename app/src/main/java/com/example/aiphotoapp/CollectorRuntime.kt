package com.example.aiphotoapp

import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.sync.SyncEngine
import com.example.aiphotoapp.telegram.TelegramManager
import java.util.concurrent.atomic.AtomicLong

object CollectorRuntime {
    @Volatile var db: CollectorDatabase? = null
    @Volatile var telegram: TelegramManager? = null
    @Volatile var engine: SyncEngine? = null
    val activeRuleId = AtomicLong(0L)
}