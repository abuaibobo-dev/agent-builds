# Telegram Collector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace AI prototype with phone-only Telegram channel media collector.

**Architecture:** TDLib handles user authentication and Telegram updates. Room stores rules, durable cursors, deduplication and logs. A foreground sync service scans history oldest-first and uses `copyMessages` to copy media server-side.

**Tech Stack:** Kotlin, AndroidX, Material Components, TDLib, Room, WorkManager.

**Spec:** `docs/superpowers/specs/2026-08-29-telegram-collector-design.md`

## Global Constraints

- Only collect content the logged-in account can access.
- Never bypass protected-content restrictions.
- Keep Telegram session on device and protect it with Android Keystore.
- `sourceChatId + sourceMessageId` is deduplication key.
- Reset cursor preserves deduplication records.
- Default sync concurrency is 1.

### Task 1: Domain and Room persistence

**Files:** Create `app/src/main/java/com/example/aiphotoapp/data/CollectorEntities.kt`, `CollectorDao.kt`, `CollectorDatabase.kt`; Test `app/src/test/java/com/example/aiphotoapp/SyncStateTest.kt`.

- Add entities for channels, rules, cursors, copied messages and logs.
- Add DAO methods for rule CRUD, cursor reset, copied-message existence, counters and recent logs.
- Test cursor reset changes scan position but does not delete copied-message records.

### Task 2: TDLib client boundary

**Files:** Create `telegram/TelegramClient.kt`, `telegram/TelegramModels.kt`, `telegram/TelegramSessionStore.kt`; Modify Gradle dependencies and manifest.

- Wrap TDLib authorization states, channel listing, history pagination, message grouping and `copyMessages`.
- Store authorization key material through Android Keystore-backed storage.
- Expose suspend methods: `observeAuth()`, `listChannels()`, `historyPage()`, `copyMessages()`.
- Keep credentials out of source and logs.

### Task 3: Sync engine

**Files:** Create `sync/SyncEngine.kt`, `sync/SyncWorker.kt`; Test `sync/SyncEngineTest.kt`.

- Read history pages newest-to-oldest, reverse each page, group albums, filter media types, check dedupe, copy batches, persist results and cursor atomically.
- Handle pause/cancel, flood-wait delay, three retries, protected content skip and failure logging.
- Use foreground worker notification for active jobs.

### Task 4: Rebuild UI tabs

**Files:** Replace `MainActivity.kt` and `activity_main.xml`; Create focused tab layouts/adapters.

- Channel management: account state, source/target selection, task controls.
- Overview: counters, current rule and live logs.
- Rules: media toggles, caption/album options, reset cursor.
- Settings: session logout, concurrency, notifications and cache.
- Use high-contrast button colors and loading/error states.

### Task 5: Integration verification

- Run unit tests and `gradle assembleDebug` in CI.
- Verify login state transitions with a test account.
- Verify one image, one video, one album, duplicate skip, pause/resume and cursor reset.
- Verify protected content is skipped and logged.
