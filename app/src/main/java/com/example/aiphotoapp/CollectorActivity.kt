package com.example.aiphotoapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.data.SyncRule
import com.example.aiphotoapp.sync.SyncEngine
import com.example.aiphotoapp.telegram.TelegramManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class CollectorActivity : AppCompatActivity() {
    private lateinit var tvTitle: TextView
    private lateinit var status: TextView
    private lateinit var flContent: FrameLayout
    private val channels = linkedMapOf<Long, String>()
    private var currentChannelList: LinearLayout? = null
    private var authPending = false
    private var selectedSourceChatId: Long? = null
    private var selectedTargetChatId: Long? = null
    private var runtimeExceptionHandler: ((Throwable) -> Unit)? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun installCrashHandler() {
        val crashFile = java.io.File(filesDir, "crash.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile.writeText("${t?.name}: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}") }
            prev?.uncaughtException(t, e) ?: kotlin.run { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
        runtimeExceptionHandler = { e ->
            runCatching { crashFile.writeText("runtime: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}") }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val db: CollectorDatabase
        get() = CollectorRuntime.db ?: Room.databaseBuilder(this, CollectorDatabase::class.java, "collector.db")
            .build().also { CollectorRuntime.db = it }

    private val client: TelegramManager?
        get() = CollectorRuntime.telegram

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        requestNotificationPermissionIfNeeded()
        setContentView(R.layout.activity_collector)
        tvTitle = findViewById(R.id.tv_title)
        status = findViewById(R.id.tv_status)
        flContent = findViewById(R.id.fl_content)
        val nav = findViewById<BottomNavigationView>(R.id.nav)
        nav.setOnItemSelectedListener { item ->
            showTab(
                when (item.itemId) {
                    R.id.nav_tab_overview -> 1
                    R.id.nav_tab_rules -> 2
                    R.id.nav_tab_settings -> 3
                    else -> 0
                }
            )
            true
        }
        nav.selectedItemId = R.id.nav_tab_channels
        status.text = "未登录"
        status.setTextColor(color(R.color.onSurfaceVariant))
        showTab(0)
    }

    private fun showTab(index: Int) {
        tvTitle.text = listOf("频道管理", "运行总览", "采集规则", "设置")[index]
        flContent.removeAllViews()
        val screenW = resources.displayMetrics.widthPixels
        val pad = max(16.dp, (screenW - 540.dp) / 2)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 2.dp, pad, 16.dp) }
        when (index) {
            0 -> channelManagement(body)
            1 -> overview(body)
            2 -> rules(body)
            else -> settings(body)
        }
        flContent.addView(ScrollView(this).apply { addView(body) })
    }

    // ---------- 页面构建 ----------

    private fun channelManagement(body: LinearLayout) {
        val loggedIn = client != null
        section(body) { sc ->
            if (loggedIn) {
                val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                header.addView(
                    TextView(this).apply { text = "已登录"; textSize = 14f; setTextColor(color(R.color.primary)) },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                val refresh = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
                    text = "刷新"
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(10.dp.toFloat()).build()
                    insetTop = 0; insetBottom = 0; minimumHeight = 0; minHeight = 0
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    setTextColor(color(R.color.primary))
                    setOnClickListener {
                        channels.clear()
                        currentChannelList?.let { renderChannels(it) }
                        client?.loadChannels()
                        status.text = "刷新频道列表"
                    }
                }
                header.addView(refresh, LinearLayout.LayoutParams(-2, 32.dp))
                sc.addView(header, LP)
            }
            label(sc, if (loggedIn) "点一个选来源，再点另一个选目标" else "请先到「设置」页登录 Telegram", 13f, R.color.onSurfaceVariant)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sc.addView(list, LP.apply { topMargin = 2.dp })
            renderChannels(list)
        }
        if (loggedIn) {
            client?.let { bindClient(it) }
            if (channels.isEmpty()) client?.loadChannels()
        }
    }

    private fun renderChannels(list: LinearLayout) {
        currentChannelList = list
        list.removeAllViews()
        channels.entries.forEach { (id, name) -> list.addView(channelRow(name, id), LP.apply { topMargin = 4.dp }) }
    }

    private fun channelRow(name: String, id: Long): MaterialCardView {
        val isTarget = selectedTargetChatId == id
        val isSource = selectedSourceChatId == id
        val bg = if (isTarget) color(R.color.primary) else if (isSource) color(R.color.primaryContainer) else color(R.color.surfaceVariant)
        val fg = if (isTarget) color(R.color.onPrimary) else if (isSource) color(R.color.onPrimaryContainer) else color(R.color.onSurface)
        return MaterialCardView(this).apply {
            radius = 10.dp.toFloat()
            cardElevation = 0f
            strokeWidth = if (isSource || isTarget) 0 else 1.dp
            setStrokeColor(ColorStateList.valueOf(color(R.color.outline)))
            setCardBackgroundColor(ColorStateList.valueOf(bg))
            val row = LinearLayout(this@CollectorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(10.dp, 8.dp, 10.dp, 8.dp)
                addView(
                    TextView(this@CollectorActivity).apply {
                        text = name; setTextColor(fg); textSize = 14f
                        typeface = if (isTarget || isSource) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                if (isSource) addView(badge("来源", R.color.onPrimaryContainer, R.color.primaryContainer))
                if (isTarget) addView(badge("目标", R.color.onPrimary, R.color.primary))
            }
            addView(row)
            setOnClickListener {
                if (selectedSourceChatId == null || selectedSourceChatId == id || id == selectedTargetChatId) {
                    selectedSourceChatId = id
                    selectedTargetChatId = null
                    status.text = "来源已选：$name，继续点击目标频道"
                } else {
                    selectedTargetChatId = id
                    status.text = "目标已选：$name"
                }
                currentChannelList?.let { renderChannels(it) }
            }
        }
    }

    private fun bindClient(client: TelegramManager) {
        client.bindUiListener { update ->
            onUi {
                try {
                    val type = update.optString("@type")
                    if (type == "updateAuthorizationState") handleAuth(client, update.optJSONObject("authorization_state"))
                    if (type == "chat") {
                        val chatType = update.optJSONObject("type")
                        if (chatType?.optString("@type") != "chatTypeSupergroup" || !chatType.optBoolean("is_channel")) return@onUi
                        channels[update.optLong("id")] = update.optString("title", update.optLong("id").toString())
                        currentChannelList?.let { renderChannels(it) }
                    }
                    if (type == "chats") {
                        update.optJSONArray("chat_ids")?.let { ids ->
                            thread {
                                for (i in 0 until ids.length()) runCatching { client.getChat(ids.getLong(i)) }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    runtimeExceptionHandler?.invoke(e)
                }
            }
        }
    }

    private fun handleAuth(client: TelegramManager, state: JSONObject?) {
        when (state?.optString("@type")) {
            "authorizationStateWaitPhoneNumber" -> client.setPhone(pendingPhone)
            "authorizationStateReady" -> {
                status.text = "已登录（会话已恢复，自动刷新频道）"
                client.loadChannels()
                navToChannels()
            }
            "authorizationStateWaitCode" -> {
                if (authPending) {
                    authPending = false
                    status.text = "验证码无效，已重新输入"
                }
                promptCode(client)
            }
            "authorizationStateWaitPassword" -> promptPassword(client)
            "authorizationStateWaitTdlibParameters" -> status.text = "TDLib 参数配置中"
            "authorizationStateWaitEncryptionKey" -> status.text = "正在解密本地 session"
        }
    }

    private fun promptCode(client: TelegramManager) {
        inputDialog("Telegram 登录验证码", "输入收到的验证码", "提交") { code ->
            if (code.isEmpty()) return@inputDialog
            authPending = true
            status.text = "正在校验，请稍候…"
            client.setCode(code)
        }
    }

    private fun promptPassword(client: TelegramManager) {
        inputDialog("Telegram 2FA 密码", "输入两步验证密码", "提交") { pw ->
            if (pw.isEmpty()) return@inputDialog
            status.text = "正在校验，请稍候…"
            client.setPassword(pw)
        }
    }

    private fun overview(body: LinearLayout) {
        section(body) { sc ->
            label(sc, "运行状态", 15f, R.color.onSurface)
            val info = TextView(this).apply {
                setTextColor(color(R.color.onSurfaceVariant)); textSize = 12f
                typeface = Typeface.MONOSPACE; includeFontPadding = false
                setPadding(0, 6.dp, 0, 6.dp)
            }
            sc.addView(info, LP)
            thread {
                val ruleId = CollectorRuntime.activeRuleId.get()
                val dao = db.collectorDao()
                val cursor = runBlocking { if (ruleId > 0) dao.getCursor(ruleId) else null }
                val copied = runBlocking { if (ruleId > 0) dao.countCopiedMessages(ruleId) else 0 }
                val errors = runBlocking { if (ruleId > 0) dao.countLogs(ruleId, "ERROR") else 0 }
                val logs = runBlocking { if (ruleId > 0) dao.getRecentLogs(ruleId, 20) else emptyList() }
                onUi {
                    info.text = buildString {
                        append("当前规则 ID：").append(if (ruleId > 0) ruleId else "无").append('\n')
                        append("状态：").append(cursor?.status ?: "IDLE").append('\n')
                        append("扫描位置：").append(cursor?.scanMessageId ?: 0).append('\n')
                        append("已复制条数：").append(copied).append('\n')
                        append("错误条数：").append(errors).append('\n')
                        append('\n').append("最近日志：").append('\n')
                        logs.forEach { log -> append(time(log.createdAt)).append(" [").append(log.level).append("] ").append(log.message).append('\n') }
                    }
                }
            }
        }
        section(body) { sc ->
            label(sc, "控制", 15f, R.color.onSurface)
            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            button(row1, "暂停", K_FILLED) { engine()?.pause(); status.text = "已暂停" }
            button(row1, "继续", K_FILLED) { engine()?.resume(); status.text = "继续采集" }
            sc.addView(row1, LP.apply { topMargin = 8.dp })
            button(sc, "停止并退出登录", K_OUTLINED) {
                confirmDialog("停止采集", "停止后当前规则暂停，账号将退出登录。确定？") { stopSync() }
            }
        }
    }

    private fun rules(body: LinearLayout) {
        section(body) { sc ->
            label(sc, "新建规则", 15f, R.color.onSurface)
            val source = input(sc, "来源频道 chat ID")
            val target = input(sc, "目标频道 chat ID")
            selectedSourceChatId?.let { source.setText(it.toString()) }
            selectedTargetChatId?.let { target.setText(it.toString()) }
            button(sc, "开始历史采集") {
                val s = source.text.toString().toLongOrNull()
                val t = target.text.toString().toLongOrNull()
                if (s == null || t == null || s == t) { status.text = "来源/目标 ID 无效，且不能相同"; return@button }
                val rule = SyncRule(sourceChatId = s, targetChatId = t, keepAlbum = true)
                confirmDialog("开始历史采集", "来源：$s\n目标：$t\n类型：${rule.mediaTypes}\n保留说明：${rule.keepCaption}\n\n确认开始？") { startSync(rule) }
            }
        }
        section(body) { sc ->
            label(sc, "已保存规则", 15f, R.color.onSurface)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sc.addView(list, LP.apply { topMargin = 4.dp })
            refreshRuleList(list)
        }
    }

    private fun refreshRuleList(list: LinearLayout) {
        thread {
            val dao = db.collectorDao()
            val rows = runBlocking {
                dao.getAllRules().map { rule ->
                    val cursor = dao.getCursor(rule.id)
                    val copied = dao.countCopiedMessages(rule.id)
                    val line = "规则 #${rule.id}：来源 ${rule.sourceChatId} → 目标 ${rule.targetChatId}\n" +
                        "类型：${rule.mediaTypes}  保留说明：${rule.keepCaption}\n" +
                        "状态：${cursor?.status ?: "IDLE"}  已复制：$copied  ${if (rule.enabled) "" else "(已禁用)"}"
                    Triple(rule, line, cursor?.status)
                }
            }
            onUi {
                list.removeAllViews()
                if (rows.isEmpty()) {
                    list.addView(textView("暂无规则，填好上面信息点开始采集", R.color.onSurfaceVariant, 13f))
                    return@onUi
                }
                rows.forEach { (rule, line, st) ->
                    list.addView(
                        textView(line, if (st == "RUNNING" || st == "SCANNING" || st == "COPYING") R.color.primary else R.color.onSurface, 13f),
                        LP.apply { topMargin = 10.dp }
                    )
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val continueLabel = if (st == "RUNNING" || st == "SCANNING" || st == "COPYING") "再次运行" else "继续"
                    button(row, continueLabel, K_FILLED) { startSync(rule) }
                    button(row, "清空游标", K_TEXT) {
                        confirmDialog("清空游标", "规则 #${rule.id} 将重新扫描历史（已复制的不重复复制）。确定？") {
                            clearCursor(rule.id); refreshRuleList(list)
                        }
                    }
                    button(row, "删除", K_TEXT) {
                        confirmDialog("删除规则", "将删除规则 #${rule.id} 及其复制记录与日志。确定？") {
                            deleteRule(rule); refreshRuleList(list)
                        }
                    }
                    list.addView(row, LP.apply { topMargin = 4.dp })
                }
                list.addView(textView("提示：清空游标会重新扫描历史，已复制的不重复复制。", R.color.onSurfaceVariant, 13f), LP)
            }
        }
    }

    private fun settings(body: LinearLayout) {
        val loggedIn = client != null
        section(body) { sc ->
            label(sc, "Telegram 账号", 15f, R.color.onSurface)
            if (loggedIn) {
                sc.addView(textView("已登录（账号数据已存本机）", R.color.onSurface, 14f), LP)
                button(sc, "退出登录", K_OUTLINED) {
                    confirmDialog("退出登录", "将停止采集并退出账号。确定？") { stopSync() }
                }
            } else {
                val apiId = input(sc, "Telegram API ID")
                val apiHash = input(sc, "Telegram API Hash")
                val phone = input(sc, "手机号，例如 +86138...")
                button(sc, "登录 / 初始化") {
                    val id = apiId.text.toString().trim().toIntOrNull()
                    val hash = apiHash.text.toString().trim()
                    if (id == null || hash.isEmpty()) { status.text = "请填写 API ID / API Hash"; return@button }
                    pendingPhone = phone.text.toString().trim()
                    try {
                        val newClient = TelegramManager(this, id, hash)
                        CollectorRuntime.telegram = newClient
                        bindClient(newClient)
                        newClient.start()
                        status.text = "正在连接 Telegram…（若之前登录过，将自动恢复会话）"
                    } catch (e: Throwable) {
                        CollectorRuntime.telegram = null
                        status.text = "连接失败：${e.message ?: e.javaClass.simpleName}"
                        runtimeExceptionHandler?.invoke(e)
                    }
                }
            }
        }
        section(body) { sc ->
            label(sc, "关于", 15f, R.color.onSurface)
            sc.addView(textView(
                "Session 数据保存在本机 files/tdlib-db。\n清理后台后采集会继续运行（前台服务）。\n通知权限已自动请求；拒绝后后台仍可采集，仅不显示常驻通知。",
                R.color.onSurfaceVariant, 13f
            ), LP)
        }
        section(body) { sc ->
            label(sc, "崩溃日志", 15f, R.color.onSurface)
            val crashFile = java.io.File(filesDir, "crash.log")
            val logView = textView(if (crashFile.exists()) crashFile.readText() else "（无）", R.color.error, 12f)
            sc.addView(logView, LP)
            button(sc, "清除崩溃日志", K_TEXT) {
                confirmDialog("清除", "确定删除 crash.log 内容？") {
                    crashFile.delete(); logView.text = "（无）"; status.text = "已清除"
                }
            }
        }
    }

    private fun startSync(rule: SyncRule) {
        val c = client ?: run { status.text = "先登录 Telegram"; return }
        ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction(CollectorService.ACTION_START))
        val engine = engine() ?: SyncEngine(c, db.collectorDao()).also { CollectorRuntime.engine = it }
        engine.start(rule, onStarted = { id -> CollectorRuntime.activeRuleId.set(id) }, onUpdate = { message -> onUi { status.text = message } })
        status.text = "已开始：从最早消息扫描（可在后台继续）"
        navToRules()
    }

    private fun navToRules() {
        findViewById<BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_tab_rules
    }

    private fun navToChannels() {
        findViewById<BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_tab_channels
    }

    private fun stopSync() {
        engine()?.stop()
        CollectorRuntime.activeRuleId.set(0L)
        client?.close()
        CollectorRuntime.telegram = null
        CollectorRuntime.engine = null
        ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction(CollectorService.ACTION_STOP))
        status.text = "已停止并退出登录"
        findViewById<BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_tab_channels
    }

    private fun engine() = CollectorRuntime.engine

    private fun clearCursor(ruleId: Long) {
        thread { runBlocking { db.collectorDao().resetCursor(ruleId) } }
        status.text = "已清空规则 #$ruleId 游标"
    }

    private fun deleteRule(rule: SyncRule) {
        thread {
            runBlocking {
                val dao = db.collectorDao()
                dao.deleteCopiedMessages(rule.id)
                dao.deleteLogs(rule.id)
                dao.deleteCursor(rule.id)
                dao.deleteRule(rule)
            }
        }
        status.text = "已删除规则 #${rule.id}"
    }

    // ---------- 通用组件 ----------

    companion object {
        private const val K_FILLED = 0
        private const val K_OUTLINED = 1
        private const val K_TEXT = 2
        var pendingPhone = ""
    }

    private fun section(body: LinearLayout, block: (LinearLayout) -> Unit): MaterialCardView {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block(inner)
        return MaterialCardView(this).apply {
            radius = 14.dp.toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp
            setStrokeColor(ColorStateList.valueOf(color(R.color.outline)))
            setCardBackgroundColor(ColorStateList.valueOf(color(R.color.surface)))
            setContentPadding(12.dp, 6.dp, 12.dp, 6.dp)
            addView(inner)
            body.addView(this, LP.apply { topMargin = 6.dp })
        }
    }

    private fun label(container: LinearLayout, text: String, size: Float, colorRes: Int) {
        container.addView(
            TextView(this).apply { this.text = text; textSize = size; setTextColor(color(colorRes)) },
            LP.apply { topMargin = 2.dp; bottomMargin = 2.dp }
        )
    }

    private fun textView(text: String, colorRes: Int, size: Float) = TextView(this).apply {
        this.text = text; setTextColor(color(colorRes)); textSize = size
        includeFontPadding = false
    }

    private fun input(container: LinearLayout, hint: String, value: String? = null): TextInputEditText {
        val edit = TextInputEditText(this).apply {
            if (value != null) setText(value)
            setSingleLine(true)
            textSize = 14f
        }
        val layout = TextInputLayout(this).apply {
            setHint(hint)
            isHintEnabled = true
            setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
            setBoxCornerRadii(12.dp.toFloat(), 12.dp.toFloat(), 12.dp.toFloat(), 12.dp.toFloat())
            addView(edit)
        }
        container.addView(layout, LP.apply { topMargin = 6.dp })
        return edit
    }

    private fun button(container: LinearLayout, text: String, kind: Int = K_FILLED, action: () -> Unit): MaterialButton {
        val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(12.dp.toFloat()).build()
            insetTop = 0; insetBottom = 0
            minimumHeight = 0; minHeight = 0
            when (kind) {
                K_FILLED -> { backgroundTintList = ColorStateList.valueOf(color(R.color.primary)); setTextColor(color(R.color.onPrimary)) }
                K_OUTLINED -> { backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); setTextColor(color(R.color.primary)); setStrokeColor(ColorStateList.valueOf(color(R.color.outline))); strokeWidth = 1.dp }
                else -> { backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); setTextColor(color(R.color.primary)) }
            }
            setOnClickListener { action() }
        }
        container.addView(b, LP.apply { topMargin = 4.dp; height = 40.dp })
        return b
    }

    private fun badge(text: String, textColorRes: Int, bgColorRes: Int) = TextView(this).apply {
        this.text = text; textSize = 11f
        setTextColor(color(textColorRes))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10.dp.toFloat()
            setColor(color(bgColorRes))
        }
        setPadding(10.dp, 3.dp, 10.dp, 3.dp)
        includeFontPadding = false
    }

    private fun confirmDialog(title: String, message: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .show()
    }

    private fun inputDialog(title: String, hint: String, okLabel: String, onOk: (String) -> Unit) {
        val edit = TextInputEditText(this).apply { setSingleLine(true) }
        val layout = TextInputLayout(this).apply {
            setHint(hint)
            isHintEnabled = true
            setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE)
            setBoxCornerRadii(12.dp.toFloat(), 12.dp.toFloat(), 12.dp.toFloat(), 12.dp.toFloat())
            addView(edit)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(layout, LP)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton(okLabel) { _, _ -> onOk(edit.text?.toString()?.trim() ?: "") }
            .show()
    }

    private fun onUi(block: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            block()
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private val LP = LinearLayout.LayoutParams(-1, -2)

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun time(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}