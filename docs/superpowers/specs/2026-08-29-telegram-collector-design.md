# Telegram Collector Design

## Goal

重做 App：用户登录自己的 Telegram 账号，从已加入频道历史最早消息开始，把图片和视频复制到自己有发帖权限的目标频道。

## Scope

- TDLib 用户账号登录，session 仅保存在设备。
- 来源频道与目标频道管理。
- 历史同步按最早消息顺序复制。
- `copyMessages` 服务端复制，媒体不落手机相册。
- Room 持久化规则、游标、去重记录、日志。
- 前台任务支持暂停、继续、失败重试、清空游标重扫。
- 四个 Tab：频道管理、运行总览、采集规则、设置。

## Sync Semantics

每条消息以 `sourceChatId + sourceMessageId` 去重。清空记忆点只重置扫描游标，保留去重表；重扫时跳过已复制消息，只补漏采。媒体组按 `mediaAlbumId` 聚合后一次复制。受保护消息不绕过，记录 skipped。

## Architecture

Android Kotlin app uses TDLib for Telegram protocol, Room for durable state, and a foreground service started by WorkManager for long sync. Sync scans Telegram pages from newest toward oldest, reverses each page, then copies oldest-first. Cursor advances only after a batch result is persisted transactionally.

## Data

- `SourceChannel(chatId, title, username, enabled)`
- `SyncRule(id, sourceChatId, targetChatId, mediaTypes, keepCaption, keepAlbum, continuous, enabled)`
- `SyncCursor(ruleId, scanMessageId, status, updatedAt)`
- `CopiedMessage(ruleId, sourceChatId, sourceMessageId, targetMessageId, mediaType, copiedAt)`
- `SyncLog(id, ruleId, level, message, createdAt)`

## Safety and Limits

- Target must be a channel where account can post.
- Source and target cannot be identical; reject self-loop rules.
- No password or Telegram session leaves device; session protected by Android Keystore.
- Default concurrency 1; respect Telegram flood wait.
- Default media filters: images and videos enabled, text-only skipped.
- No comments, source edits/deletes, or multi-account in MVP.

## Acceptance

User can log in, see channels, create one valid source-to-target rule, start oldest-first collection, stop and resume without duplicate copies, reset cursor and rescan without duplicates, and inspect counters/logs.
