package com.example.aiphotoapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class CollectorActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val channels = linkedMapOf<Long, String>()
    private var authFieldsAdded = false
    private var authPending = false
    private var selectedSourceChatId: Long? = null
    private var selectedTargetChatId: Long? = null

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

    private var runtimeExceptionHandler: ((Throwable) -> Unit)? = null

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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0F0F0F.toInt()) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 4) }
        listOf("频道管理", "运行总览", "采集规则", "设置").forEachIndexed { index, title ->
            tabs.addView(Button(this).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_btn_outline)
                backgroundTintList = null
                setOnClickListener { showTab(index) }
            }, LinearLayout.LayoutParams(0, 48.dp, 1f).apply { if (index > 0) marginStart = 6.dp })
        }
        root.addView(tabs)
        status = TextView(this).apply { setTextColor(0xFFB3B3B3.toInt()); text = "未登录"; setPadding(16, 8, 16, 8) }
        root.addView(status)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showTab(0)
    }

    private fun showTab(index: Int) {
        content.removeAllViews()
        when (index) {
            0 -> channelManagement()
            1 -> overview()
            2 -> rules()
            else -> settings()
        }
    }

    private fun channelManagement() {
        title("频道管理")
        val apiId = input("Telegram API ID")
        val apiHash = input("Telegram API Hash")
        val phone = input("手机号，例如 +86138...")
        authFieldsAdded = false
        val login = button("登录 / 初始化")
        val isLoggedIn = client != null
        if (isLoggedIn) {
            apiId.visibility = android.view.View.GONE
            apiHash.visibility = android.view.View.GONE
            phone.visibility = android.view.View.GONE
            login.text = "刷新频道列表"
        }
        content.addView(apiId); content.addView(apiHash); content.addView(phone); content.addView(login)
        val channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 16, 8, 8) }
        content.addView(channelList)
        login.setOnClickListener {
            val client = client
            if (!isLoggedIn) {
                val id = apiId.text.toString().trim().toIntOrNull()
                val hash = apiHash.text.toString().trim()
                if (id == null || hash.isEmpty()) { status.text = "请填写 API ID/API Hash"; return@setOnClickListener }
                val newClient = TelegramManager(this, id, hash)
                CollectorRuntime.telegram = newClient
                bindClient(newClient, channelList, phone)
                newClient.start()
                status.text = "TDLib 已启动，等待授权"
            } else if (client != null) {
                channels.clear()
                channelList.removeAllViews()
                client.loadChannels()
                status.text = "刷新频道列表"
            }
        }
        if (isLoggedIn) {
            client?.let { bindClient(it, channelList, phone) }
        }
    }

    private fun bindClient(client: TelegramManager, channelList: LinearLayout, phone: EditText) {
        client.addListener { update ->
            runOnUiThread {
                try {
                    val type = update.optString("@type")
                    if (type == "updateAuthorizationState") handleAuth(client, update.optJSONObject("authorization_state"), phone.text.toString())
                    if (type == "chat") {
                        val chatType = update.optJSONObject("type")
                        if (chatType?.optString("@type") != "chatTypeSupergroup" || !chatType.optBoolean("is_channel")) return@runOnUiThread
                        val idValue = update.optLong("id")
                        val name = update.optString("title", idValue.toString())
                        channels[idValue] = name
                        renderChannels(channelList)
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

    private fun renderChannels(channelList: LinearLayout) {
        channelList.removeAllViews()
        channelList.addView(TextView(this).apply {
            setTextColor(0xFFB3B3B3.toInt())
            text = "点击一次设为来源，再点击另一个设为目标"
        })
        channels.entries.forEach { entry ->
            channelList.addView(button("${entry.value} (${entry.key})") {
                if (selectedSourceChatId == null || selectedSourceChatId == entry.key || entry.key == selectedTargetChatId) {
                    selectedSourceChatId = entry.key
                    selectedTargetChatId = null
                    status.text = "来源已选：${entry.value}，继续点击目标频道"
                } else {
                    selectedTargetChatId = entry.key
                    status.text = "目标已选：${entry.value}"
                }
            })
        }
    }

    private fun handleAuth(client: TelegramManager, state: JSONObject?, phone: String) {
        when (state?.optString("@type")) {
            "authorizationStateWaitPhoneNumber" -> client.setPhone(phone)
            "authorizationStateReady" -> { status.text = "已登录（账号数据已存本机）"; client.loadChannels() }
            "authorizationStateWaitCode" -> {
                if (authPending) {
                    authPending = false
                    status.text = "验证码无效，已重新输入"
                } else {
                    addAuthField("Telegram 验证码（登录码）", client) { client.setCode(it) }
                }
            }
            "authorizationStateWaitPassword" -> addAuthField("Telegram 2FA 密码", client) { client.setPassword(it) }
            "authorizationStateWaitTdlibParameters" -> status.text = "TDLib 参数配置中"
            "authorizationStateWaitEncryptionKey" -> status.text = "正在解密本地 session"
        }
    }

    private fun addAuthField(hint: String, client: TelegramManager, submit: (String) -> Unit) {
        status.text = "请输入 $hint"
        if (authFieldsAdded) return
        authFieldsAdded = true
        val field = input(hint)
        val submitButton = button("提交")
        content.addView(field)
        content.addView(submitButton)
        submitButton.setOnClickListener {
            authFieldsAdded = false
            authPending = true
            status.text = "正在校验，请稍候…"
            submit(field.text.toString().trim())
        }
    }

    private fun overview() {
        title("运行总览")
        val info = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(16, 8, 16, 8) }
        content.addView(info)
        content.addView(button("刷新") { showTab(1) })
        val pause = button("暂停") { engine()?.pause(); status.text = "已暂停" }
        val resume = button("继续") { engine()?.resume(); status.text = "继续采集" }
        val stop = button("停止并退出登录") { stopSync() }
        content.addView(pause); content.addView(resume); content.addView(stop)
        thread {
            val ruleId = CollectorRuntime.activeRuleId.get()
            val dao = db.collectorDao()
            val cursor = runBlocking { if (ruleId > 0) dao.getCursor(ruleId) else null }
            val copied = runBlocking { if (ruleId > 0) dao.countCopiedMessages(ruleId) else 0 }
            val errors = runBlocking { if (ruleId > 0) dao.countLogs(ruleId, "ERROR") else 0 }
            val logs = runBlocking { if (ruleId > 0) dao.getRecentLogs(ruleId, 20) else emptyList() }
            runOnUiThread {
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

    private fun rules() {
        title("采集规则")
        val source = input("来源频道 chat ID")
        val target = input("目标频道 chat ID")
        selectedSourceChatId?.let { source.setText(it.toString()) }
        selectedTargetChatId?.let { target.setText(it.toString()) }
        val start = button("开始历史采集")
        content.addView(source); content.addView(target); content.addView(start)
        start.setOnClickListener {
            val s = source.text.toString().toLongOrNull(); val t = target.text.toString().toLongOrNull()
            if (s == null || t == null || s == t) { status.text = "来源/目标 ID 无效，且不能相同"; return@setOnClickListener }
            startSync(SyncRule(sourceChatId = s, targetChatId = t, keepAlbum = true))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 16, 0, 8) }
        content.addView(list)
        refreshRuleList(list)
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
            runOnUiThread {
                list.removeAllViews()
                if (rows.isEmpty()) {
                    list.addView(TextView(this).apply { setTextColor(0xFFB3B3B3.toInt()); text = "暂无规则，填好上面信息点开始采集" })
                    return@runOnUiThread
                }
                rows.forEach { (rule, line, status) ->
                    list.addView(TextView(this).apply {
                        setTextColor(0xFFFFFFFF.toInt())
                        text = line
                        setPadding(16, 12, 16, 4)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    val continueLabel = if (status == "RUNNING" || status == "SCANNING" || status == "COPYING") "再次运行" else "继续"
                    row.addView(button(continueLabel) { startSync(rule) }, lp)
                    row.addView(button("清空游标") { clearCursor(rule.id); refreshRuleList(list) }, lp)
                    row.addView(button("删除") { deleteRule(rule); refreshRuleList(list) }, lp)
                    list.addView(row)
                }
                list.addView(TextView(this).apply { setTextColor(0xFFB3B3B3.toInt()); text = "提示：清空游标会重新扫描历史，但已复制的不重复复制。"; setPadding(16, 12, 16, 4) })
            }
        }
    }

    private val lp by lazy { LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6.dp; marginEnd = 6.dp } }

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

    private fun startSync(rule: SyncRule) {
        val client = client ?: run { status.text = "先登录 Telegram"; return }
        ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction(CollectorService.ACTION_START))
        val engine = engine() ?: SyncEngine(client, db.collectorDao()).also { CollectorRuntime.engine = it }
        engine.start(rule, onStarted = { id -> CollectorRuntime.activeRuleId.set(id) }, onUpdate = { message -> runOnUiThread { status.text = message } })
        status.text = "已开始：从最早消息扫描（可在后台继续）"
        showTab(2)
    }

    private fun stopSync() {
        engine()?.stop()
        CollectorRuntime.activeRuleId.set(0L)
        client?.close()
        CollectorRuntime.telegram = null
        CollectorRuntime.engine = null
        ContextCompat.startForegroundService(this, Intent(this, CollectorService::class.java).setAction(CollectorService.ACTION_STOP))
        status.text = "已停止并退出登录"
        showTab(0)
    }

    private fun engine() = CollectorRuntime.engine

    private fun settings() {
        title("设置")
        content.addView(TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); text = "Session 数据保存在本机 files/tdlib-db。\n说明：清理后台后采集会继续运行（前台服务）。\n通知权限已自动请求；拒绝后后台仍可采集，仅不显示常驻通知。\n\n崩溃日志："; setPadding(16, 16, 16, 8) })
        val crashFile = java.io.File(filesDir, "crash.log")
        val logView = TextView(this).apply { setTextColor(0xFFFF6666.toInt()); text = if (crashFile.exists()) crashFile.readText() else "（无）"; setPadding(16, 4, 16, 8) }
        content.addView(logView)
        content.addView(button("清除崩溃日志") {
            crashFile.delete()
            logView.text = "（无）"
            status.text = "已清除"
        })
    }
    private fun title(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()); setPadding(16, 20, 16, 12) }) }
    private fun input(hint: String) = EditText(this).apply { this.hint = hint; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFB3B3B3.toInt()); setPadding(16, 12, 16, 12) }
    private fun button(text: String, action: (() -> Unit)? = null) = Button(this).apply { this.text = text; setTextColor(0xFFFFFFFF.toInt()); setBackgroundResource(R.drawable.bg_btn_outline); backgroundTintList = null; action?.let { setOnClickListener { it() } } }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun time(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}