package com.example.aiphotoapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.data.SyncRule
import com.example.aiphotoapp.sync.SyncEngine
import com.example.aiphotoapp.telegram.TelegramManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class CollectorActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "collector_login"
        private const val KEY_ID = "api_id"
        private const val KEY_HASH = "api_hash"
        private const val KEY_PHONE = "phone"
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var flContent: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val db: CollectorDatabase by lazy {
        CollectorRuntime.db
            ?: Room.databaseBuilder(this, CollectorDatabase::class.java, "collector.db")
                .build()
                .also { CollectorRuntime.db = it }
    }
    private val dao get() = db.collectorDao()
    private val client get() = CollectorRuntime.telegram

    private var currentTab = 0
    private val channels = LinkedHashMap<Long, String>()
    private var selSource = 0L
    private var selTarget = 0L
    private var currentList: LinearLayout? = null

    private val updateQ = ConcurrentLinkedQueue<JSONObject>()
    private val flushScheduled = AtomicBoolean(false)
    private var phoneSent = false
    private var lastChatRender = 0L
    private val chatDirty = AtomicBoolean(false)
    private val renderPending = AtomicBoolean(false)
    private val renderAll = Runnable {
        renderPending.set(false)
        if (chatDirty.compareAndSet(true, false)) renderList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
hapticCrashGuard()
        runCatching { File(filesDir, "start.log").writeText("launch ${System.currentTimeMillis()}\n") }
        setContentView(com.example.aiphotoapp.R.layout.activity_collector)
        tvTitle = findViewById(com.example.aiphotoapp.R.id.tv_title)
        tvStatus = findViewById(com.example.aiphotoapp.R.id.tv_status)
        flContent = findViewById(com.example.aiphotoapp.R.id.fl_content)
        flContent.removeAllViews()
        val nav = findViewById<BottomNavigationView>(com.example.aiphotoapp.R.id.nav)
        nav.setOnItemSelectedListener { item ->
            showTab(
                when (item.itemId) {
                    com.example.aiphotoapp.R.id.nav_tab_overview -> 1
                    com.example.aiphotoapp.R.id.nav_tab_rules -> 2
                    com.example.aiphotoapp.R.id.nav_tab_settings -> 3
                    else -> 0
                },
            )
            true
        }
        CollectorRuntime.telegram?.let { bindClient(it) }
        showTab(0)
        if (CollectorRuntime.telegram != null) {
            status("正在恢复会话…")
            CollectorRuntime.telegram?.loadChannels()
        } else {
            status("未登录，请到设置页登录", color(com.example.aiphotoapp.R.color.onSurfaceVariant))
        }
    }

    private fun hapticCrashGuard() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, "crash.log").writeText(
                    "${t?.name}: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}",
                )
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun status(text: String, textColor: Int = color(com.example.aiphotoapp.R.color.onSurface)) {
        if (::tvStatus.isInitialized) {
            tvStatus.text = text
            tvStatus.setTextColor(textColor)
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun showTab(index: Int) {
        currentTab = index
        val vn = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        tvTitle.text = "${listOf("频道管理", "运行总览", "采集规则", "设置")[index]} · $vn"
        flContent.removeAllViews()
        val sv = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 4.dp(), 16.dp(), 16.dp())
        }
        when (index) {
            0 -> channelsPage(body)
            1 -> overviewPage(body)
            2 -> rulesPage(body)
            3 -> settingsPage(body)
        }
        sv.addView(body)
        flContent.addView(sv)
        if (index == 0 && chatDirty.get() && renderPending.compareAndSet(false, true)) {
            ui.post(renderAll)
        }
    }

    private fun card(container: LinearLayout, block: (LinearLayout) -> Unit) {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(
            inner.apply {
                setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_card)
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                val lp0 = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp0.topMargin = 6.dp()
                container.addView(inner, lp0)
                block(this)
            },
        )
    }

    private fun titleView(container: LinearLayout, text: String) {
        container.addView(
            TextView(this).apply {
                this.text = text
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(color(com.example.aiphotoapp.R.color.onSurface))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 4.dp() },
        )
    }

    private fun line(container: LinearLayout, text: String, textColor: Int, size: Float = 14f, marginTop: Int = 4.dp()) {
        container.addView(
            TextView(this).apply {
                this.text = text
                this.textSize = size
                setTextColor(color(textColor))
                setLineSpacing(0f, 1.1f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = marginTop },
        )
    }

    private fun typedCheck(container: LinearLayout, label: String, checked: Boolean): CheckBox {
        val cb = CheckBox(this).apply {
            this.text = label
            textSize = 14f
            isChecked = checked
            setTextColor(color(com.example.aiphotoapp.R.color.onSurface))
        }
        container.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp() })
        return cb
    }

    private fun ruleOptionsDialog(rule: SyncRule, onSave: (SyncRule) -> Unit) {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val types = rule.mediaTypes.split(',').map { it.trim() }
        val ckImage = typedCheck(wrap, "图片", "IMAGE" in types)
        val ckVideo = typedCheck(wrap, "视频", "VIDEO" in types)
        val ckGif = typedCheck(wrap, "GIF", "GIF" in types)
        val ckCaption = typedCheck(wrap, "保留描述", rule.keepCaption)
        val ckContinuous = typedCheck(wrap, "持续采集（新帖自动入库）", rule.continuous)
        MaterialAlertDialogBuilder(this)
            .setTitle("编辑规则 #${rule.id}")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val mt = buildString {
                    if (ckImage.isChecked) append("IMAGE,")
                    if (ckVideo.isChecked) append("VIDEO,")
                    if (ckGif.isChecked) append("GIF,")
                }.trimEnd(',')
                if (mt.isEmpty()) { status("至少选择一种媒体类型", color(com.example.aiphotoapp.R.color.error)); return@setPositiveButton }
                onSave(rule.copy(mediaTypes = mt, keepCaption = ckCaption.isChecked, continuous = ckContinuous.isChecked))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun button(container: LinearLayout, text: String, filled: Boolean = true, block: () -> Unit) {
        val b = Button(this).apply {
            this.text = text
            textSize = 14f
            minHeight = 0
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            setBackgroundResource(
                if (filled) com.example.aiphotoapp.R.drawable.bg_button else com.example.aiphotoapp.R.drawable.bg_row,
            )
            setTextColor(color(if (filled) com.example.aiphotoapp.R.color.onPrimary else com.example.aiphotoapp.R.color.onPrimaryContainer))
            setOnClickListener { block() }
        }
        container.addView(
            b,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp() },
        )
    }

    private fun buttonRow(container: LinearLayout, block: (LinearLayout) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp() },
        )
        block(row)
    }

    private fun button2(container: LinearLayout, text: String, filled: Boolean, block: () -> Unit) {
        val b = Button(this).apply {
            this.text = text
            textSize = 13f
            minHeight = 0
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            setBackgroundResource(
                if (filled) com.example.aiphotoapp.R.drawable.bg_button else com.example.aiphotoapp.R.drawable.bg_row,
            )
            setTextColor(color(if (filled) com.example.aiphotoapp.R.color.onPrimary else com.example.aiphotoapp.R.color.onPrimaryContainer))
            setOnClickListener { block() }
        }
        container.addView(
            b,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = 4.dp() },
        )
    }

    // ---------- 频道页 ----------

    private fun channelsPage(body: LinearLayout) {
        val c = client
        if (c == null) {
            card(body) { sc ->
                line(sc, "未登录", com.example.aiphotoapp.R.color.onSurface, 15f)
                line(sc, "需要 Telegram 账号才能看到频道列表，先在设置页完成登录。", com.example.aiphotoapp.R.color.onSurfaceVariant, 13f)
                button(sc, "去设置页登录") { findViewById<BottomNavigationView>(com.example.aiphotoapp.R.id.nav).selectedItemId = com.example.aiphotoapp.R.id.nav_tab_settings }
            }
            return
        }
        card(body) { sc ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sc.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            currentList = list
            renderList()
            if (channels.isEmpty()) line(sc, "频道加载中…（点下方刷新）", com.example.aiphotoapp.R.color.onSurfaceVariant, 13f)
            button(sc, "刷新频道，重新拉取最近聊天") { c.loadChannels() }
        }
        card(body) { sc ->
            when {
                selSource == 0L && selTarget == 0L -> line(sc, "用法：点两个频道 → 先点的为来源，后点的为目标。", com.example.aiphotoapp.R.color.onSurface, 14f)
                selTarget == 0L -> line(sc, "已选来源（${sourceName()}），再点一个频道作为目标。", com.example.aiphotoapp.R.color.onPrimaryContainer, 14f)
                else -> {
                    line(sc, "来源：${sourceName()}", com.example.aiphotoapp.R.color.primary, 14f)
                    line(sc, "目标：${channels[selTarget]}", com.example.aiphotoapp.R.color.primary, 14f)
                    val ckImage = typedCheck(sc, "图片", true)
                    val ckVideo = typedCheck(sc, "视频", true)
                    val ckGif = typedCheck(sc, "GIF", false)
                    val ckCaption = typedCheck(sc, "保留描述", true)
                    val ckContinuous = typedCheck(sc, "持续采集（新帖自动入库）", true)
                    button(sc, "创建采集规则") {
                        val name = sourceName()
                        val types = buildString {
                            if (ckImage.isChecked) append("IMAGE,")
                            if (ckVideo.isChecked) append("VIDEO,")
                            if (ckGif.isChecked) append("GIF,")
                        }.trimEnd(',')
                        if (types.isEmpty()) { status("至少选择一种媒体类型", color(com.example.aiphotoapp.R.color.error)); return@button }
                        thread {
                            runBlocking {
                                dao.insertRule(
                                    SyncRule(
                                        sourceChatId = selSource,
                                        targetChatId = selTarget,
                                        mediaTypes = types,
                                        keepCaption = ckCaption.isChecked,
                                        continuous = ckContinuous.isChecked,
                                    ),
                                )
                            }
                            ui.post { status("已创建规则：$name → ${channels[selTarget]}"); navTo(com.example.aiphotoapp.R.id.nav_tab_rules) }
                        }
                    }
                }
            }
            if (selSource != 0L) button2(sc, "清空选择", false) {
                selSource = 0L; selTarget = 0L; renderList(); status("已清空选择")
            }
        }
    }

    private fun sourceName(): String = channels[selSource] ?: "?"

    private fun renderList() {
        val list = currentList ?: return
        val now = System.currentTimeMillis()
        if (now - lastChatRender < 150) return
        lastChatRender = now
        list.removeAllViews()
        channels.entries.forEach { (id, name) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                setBackgroundResource(
                    when (id) {
                        selTarget -> com.example.aiphotoapp.R.drawable.bg_target
                        selSource -> com.example.aiphotoapp.R.drawable.bg_source
                        else -> com.example.aiphotoapp.R.drawable.bg_row
                    },
                )
                setOnClickListener {
                    when {
                        selSource == 0L || selSource == id || id == selTarget -> {
                            selSource = id
                            selTarget = 0L
                            status("来源已选：$name（可改成点“按最新到目标”）")
                        }
                        else -> {
                            selTarget = id
                            status("目标已选：$name")
                        }
                    }
                    renderList()
                }
            }
            row.addView(
                TextView(this).apply {
                    text = name; textSize = 14f
                    setTextColor(color(com.example.aiphotoapp.R.color.onSurface))
                    maxLines = 1
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (id == selTarget) row.addView(badge("目标", com.example.aiphotoapp.R.color.onPrimary))
            else if (id == selSource) row.addView(badge("来源", com.example.aiphotoapp.R.color.onPrimaryContainer))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() })
        }
    }

    private fun badge(text: String, textColor: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(color(textColor))
        setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
        setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_button)
    }

    // ---------- 总览页 ----------

    private fun overviewPage(body: LinearLayout) {
        val engine = CollectorRuntime.engine
        if (engine == null) {
            card(body) { sc ->
                line(sc, "没有正在运行的采集任务", com.example.aiphotoapp.R.color.onSurfaceVariant, 14f)
                line(sc, "到「规则」页选中一条规则点开始即可。", com.example.aiphotoapp.R.color.onSurfaceVariant, 13f)
            }
            return
        }
        val rule = runCatching {
            runBlocking { dao.getRule(CollectorRuntime.activeRuleId.get()) }
        }.getOrNull()
        card(body) { sc ->
            titleView(sc, "任务 ${rule?.id ?: "?"}")
            line(sc, "来源：${rule?.sourceChatId ?: "?"}", com.example.aiphotoapp.R.color.onSurface, 13f)
            line(sc, "目标：${rule?.targetChatId ?: "?"}", com.example.aiphotoapp.R.color.onSurface, 13f)
            val statView = TextView(this).apply { text = "统计读取中…"; textSize = 14f; setTextColor(color(com.example.aiphotoapp.R.color.primary)) }
            sc.addView(statView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() })
            thread {
                val stat = runCatching {
                    runBlocking {
                        val rid = CollectorRuntime.activeRuleId.get().takeIf { it > 0 } ?: return@runBlocking null to null
                        dao.countCopiedMessages(rid) to dao.getCursor(rid)
                    }
                }.getOrNull() ?: (null to null)
                ui.post {
                    val (count, cur) = stat
                    statView.text = "已复制 $count 条" + (cur?.let { " · 游标 ${it.scanMessageId}" } ?: "")
                }
            }
            line(sc, "进度：${CollectorRuntime.engineMsg.ifEmpty { "运行中…" }}", com.example.aiphotoapp.R.color.primary, 14f)
            buttonRow(sc) { row ->
                button2(row, "暂停", false) { collector(engine, "暂停") }
                button2(row, "继续", false) { engine.resume(); status("已继续") }
                button2(row, "停止", true) { collector(engine, "停止") }
            }
        }
        val logs = runCatching { runBlocking { dao.getRecentLogs(CollectorRuntime.activeRuleId.get(), 20) } }.getOrNull()
        card(body) { sc ->
            titleView(sc, "运行日志")
            logs?.forEach { line(sc, "[${it.level}] ${it.message}", if (it.level == "ERROR") com.example.aiphotoapp.R.color.error else com.example.aiphotoapp.R.color.onSurfaceVariant, 12f) }
                ?: line(sc, "（暂无）", com.example.aiphotoapp.R.color.onSurfaceVariant, 13f)
        }
    }

    private fun collector(engine: SyncEngine, act: String) {
        when (act) {
            "暂停" -> engine.pause()
            "停止" -> engine.stop().also { status("已停止（游标保留，下次从断点继续）") }
        }
    }

    // ---------- 规则页 ----------

    private var ruleStatusViews = HashMap<Long, TextView>()

    private fun rulesPage(body: LinearLayout) {
        ruleStatusViews.clear()
        val rules = runCatching { runBlocking { dao.getAllRules() } }.getOrNull() ?: emptyList()
        if (rules.isEmpty()) {
            card(body) { sc ->
                line(sc, "还没有采集规则", com.example.aiphotoapp.R.color.onSurface, 15f)
                line(sc, "到「频道」页点两个频道（来源+目标）自动创建。", com.example.aiphotoapp.R.color.onSurfaceVariant, 13f)
            }
            return
        }
        rules.forEach { rule ->
            card(body) { sc ->
                titleView(sc, "规则 #${rule.id}")
                line(sc, "来源 ${rule.sourceChatId} → 目标 ${rule.targetChatId}", com.example.aiphotoapp.R.color.onSurface, 13f)
                line(sc, "媒体：${rule.mediaTypes} · 保留描述：${if (rule.keepCaption) "是" else "否"}", com.example.aiphotoapp.R.color.onSurfaceVariant, 12f)
                val st = TextView(this).apply { textSize = 13f; setTextColor(color(com.example.aiphotoapp.R.color.primary)) }
                ruleStatusViews[rule.id] = st
                sc.addView(st, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp() })
                buttonRow(sc) { row ->
                    button2(row, "开始", true) { startRule(rule) }
                    button2(row, "暂停", false) { CollectorRuntime.engine?.pause(); status("已暂停规则 #${rule.id}") }
                    button2(row, "停止", false) { CollectorRuntime.engine?.stop(); status("已停止规则 #${rule.id}（断点保留）") }
                    button2(row, "编辑", false) {
                        ruleOptionsDialog(rule) { updated ->
                            thread { runBlocking { dao.insertRule(updated) }; ui.post { status("规则 #${rule.id} 已更新"); showTab(2) } }
                        }
                    }
                }
                buttonRow(sc) { row ->
                    button2(row, "重置游标（重扫全部）", false) {
                        thread { runBlocking { dao.resetCursor(rule.id) } }
                        status("已重置规则 #${rule.id} 游标，下次从头扫描")
                    }
                    button2(row, "删除规则", true) {
                        confimDlg("删除规则", "删除规则 #${rule.id}（含游标/日志/去重记录）？") {
                            thread {
                                runBlocking { dao.deleteRule(rule); dao.deleteCursor(rule.id); dao.deleteLogs(rule.id); dao.deleteCopiedMessages(rule.id) }
                                ui.post { showTab(2) }
                            }
                        }
                    }
                }
            }
        }
        refreshRuleStatuses()
    }

    private fun refreshRuleStatuses() {
        thread {
            val map = runBlocking { ruleStatusViews.keys.associateWith { dao.getCursor(it) } }
            ui.post {
                map.forEach { (id, c) ->
                    ruleStatusViews[id]?.text = c?.let { "状态：${statusText(it.status)} · 游标 ${it.scanMessageId}" } ?: "状态：未运行"
                }
            }
        }
    }

    private fun statusText(s: String) = when (s) {
        "SCANNING" -> "扫描中"
        "COPYING" -> "复制中"
        "COMPLETED" -> "已完成"
        "CONTINUOUS" -> "持续采集中"
        "FAILED" -> "失败"
        "PAUSED" -> "已暂停"
        "IDLE" -> "未开始"
        else -> s
    }

    private fun startRule(rule: SyncRule) {
        val c = client
        if (c == null) {
            status("请先到设置页登录 Telegram"); navTo(com.example.aiphotoapp.R.id.nav_tab_settings); return
        }
        val e = SyncEngine(c, dao)
        CollectorRuntime.engine = e
        CollectorRuntime.activeRuleId.set(rule.id)
        status("规则 #${rule.id} 开始采集…")
        e.start(
            rule,
            onStarted = { id -> ui.post { status("规则 #$id 已启动") } },
            onUpdate = { msg ->
                CollectorRuntime.engineMsg = msg
                ui.post {
                    status(msg)
                    refreshRuleStatuses()
                    if (currentTab == 1) showTab(1)
                }
            },
        )
    }

    // ---------- 设置页 ----------

    private fun settingsPage(body: LinearLayout) {
        val c = client
        val nowLoggedIn = c != null
        card(body) { sc ->
            titleView(sc, if (nowLoggedIn) "Telegram 账号（已登录）" else "Telegram 账号")
            if (nowLoggedIn) {
                line(sc, "登录会话已保存到本机（files/tdlib-db），重启无需重复验证码。", com.example.aiphotoapp.R.color.onSurfaceVariant, 13f)
                button(sc, "退出登录") {
                    confimDlg("退出登录", "将停止采集并断开账号。确定？") {
                        CollectorRuntime.engine?.stop()
                        CollectorRuntime.telegram?.close()
                        CollectorRuntime.telegram = null
                        CollectorRuntime.engine = null
                        CollectorRuntime.activeRuleId.set(0)
                        status("已退出登录（本机会话数据保留）")
                        showTab(1)
                    }
                }
            } else {
                val apiId = EditText(this).apply { hint = "API ID（数字）"; textSize = 14f; setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_input); setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp()) }
                val apiHash = EditText(this).apply { hint = "API Hash"; textSize = 14f; setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_input); setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp()) }
                val phone = EditText(this).apply { hint = "手机号，例如 +86138..."; textSize = 14f; setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_input); setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp()) }
                apiId.setText(prefs.getString(KEY_ID, ""))
                apiHash.setText(prefs.getString(KEY_HASH, ""))
                phone.setText(prefs.getString(KEY_PHONE, ""))
                listOf(apiId, apiHash, phone).forEachIndexed { i, v ->
                    sc.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 8.dp() else 6.dp() })
                }
                line(sc, "从 my.telegram.org 获取 API ID / Hash。不采集个人隐私信息，仅用于登录。手机号可留空，本机有历史会话时自动恢复。", com.example.aiphotoapp.R.color.onSurfaceVariant, 12f, 8.dp())
                button(sc, "登录 / 恢复会话") { doLogin(apiId, apiHash, phone) }
            }
        }
        card(body) { sc ->
            titleView(sc, "关于")
            val vn = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            line(sc, "收集器 $vn", com.example.aiphotoapp.R.color.onSurface, 14f)
            line(sc, "数据：files/tdlib-db · 采集匹配记录 collector.db", com.example.aiphotoapp.R.color.onSurfaceVariant, 12f)
        }
        card(body) { sc ->
            titleView(sc, "崩溃日志")
            val logView = TextView(this).apply { textSize = 11f; setTextColor(color(com.example.aiphotoapp.R.color.error)); setLineSpacing(0f, 1.2f) }
            val content = runCatching { File(filesDir, "crash.log").readText() }.getOrNull() ?: ""
            logView.text = content.ifEmpty { "（无崩溃记录）" }
            sc.addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp() })
            button(sc, "清除日志", false) { File(filesDir, "crash.log").delete(); logView.text = "（已清除）" }
        }
    }

    private fun doLogin(apiId: EditText, apiHash: EditText, phone: EditText) {
        val id = apiId.text.toString().trim().toIntOrNull()
        val hash = apiHash.text.toString().trim()
        if (id == null || hash.isEmpty()) {
            status("请填写有效的 API ID（纯数字）和 Hash", color(com.example.aiphotoapp.R.color.error)); return
        }
        prefs.edit().putString(KEY_ID, id.toString()).putString(KEY_HASH, hash).putString(KEY_PHONE, phone.text.toString().trim()).apply()
        phoneSent = false
        runCatching {
            val c = TelegramManager(this, id, hash)
            CollectorRuntime.telegram = c
            bindClient(c)
            c.start()
        }.onFailure {
            CollectorRuntime.telegram = null
            status("连接失败：${it.message}", color(com.example.aiphotoapp.R.color.error))
            logCrash(it)
        }.onSuccess { status("正在连接 Telegram…（历史会话会自动恢复）") }
    }

    // ---------- TDLib 更新（合并节流，绝不逐条刷 UI） ----------

    private fun bindClient(c: TelegramManager) {
        c.bindUiListener { u ->
            updateQ += u
            if (flushScheduled.compareAndSet(false, true)) {
                ui.postDelayed(::flushUpdates, 80)
            }
        }
    }

    private fun flushUpdates() {
        flushScheduled.set(false)
        var auth: JSONObject? = null
        var chatChanged = false
        while (true) {
            val u = updateQ.poll() ?: break
            when (u.optString("@type")) {
                "updateAuthorizationState" -> auth = u.optJSONObject("authorization_state")
                "chat", "updateChat", "updateChatLastMessage", "updateChatReadInbox" -> {
                    val chat = if (u.optString("@type") == "chat") u else u.optJSONObject("chat")
                    val id = chat?.optLong("chat_id", 0) ?: 0L
                    val title = chat?.optString("title", "") ?: ""
                    if (id != 0L && title.isNotEmpty()) {
                        channels[id] = title
                        chatChanged = true
                    }
                }
                "updateUser", "updateUserStatus" -> Unit
            }
        }
        try {
            auth?.optString("@type")?.let { handleAuth(it, client ?: return) }
        } catch (e: Exception) {
            status("授权处理异常：${e.message}", color(com.example.aiphotoapp.R.color.error))
            logCrash(e)
        }
        if (chatChanged) {
            chatDirty.set(true)
            // 合并渲染：洪峰段时间内只重建一次（约 350ms），不再每 80ms 全量刷列表
            if (currentTab == 0 && renderPending.compareAndSet(false, true)) {
                ui.postDelayed(renderAll, 350)
            }
        }
    }

    private fun handleAuth(state: String, c: TelegramManager) {
        when (state) {
            "authorizationStateWaitTdlibParameters" -> status("TDLib 参数配置中…")
            "authorizationStateWaitEncryptionKey" -> status("正在解密本地会话…")
            "authorizationStateWaitPhoneNumber" -> {
                if (phoneSent) status("手机号无效，请重新填写", color(com.example.aiphotoapp.R.color.error))
                else {
                    val p = prefs.getString(KEY_PHONE, "").orEmpty()
                    if (p.isEmpty()) promptPhone(c)
                    else { phoneSent = true; status("已提交手机号…"); c.setPhone(p) }
                }
            }
            "authorizationStateWaitCode" -> promptCode(c)
            "authorizationStateWaitPassword" -> promptPassword(c)
            "authorizationStateReady" -> {
                status("已登录（会话就绪）")
                c.loadChannels()
                navTo(com.example.aiphotoapp.R.id.nav_tab_channels)
            }
        }
    }

    private fun promptPhone(c: TelegramManager) {
        val input = EditText(this).apply { hint = "手机号，例如 +86138..."; textSize = 14f; setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_input); setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp()) }
        MaterialAlertDialogBuilder(this)
            .setTitle("需要手机号")
            .setMessage("本机没有保存账号。输入手机号即可继续登录。")
            .setView(input)
            .setPositiveButton("提交") { _, _ ->
                val p = input.text.toString().trim()
                if (p.isEmpty()) { status("手机号不能为空", color(com.example.aiphotoapp.R.color.error)); return@setPositiveButton }
                prefs.edit().putString(KEY_PHONE, p).apply()
                phoneSent = true
                status("已提交手机号…")
                c.setPhone(p)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptCode(c: TelegramManager) {
        val input = EditText(this).apply { hint = "输入收到的验证码"; textSize = 14f; setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_input); setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp()) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Telegram 登录验证码")
            .setView(input)
            .setPositiveButton("提交") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) { status("验证码不能为空", color(com.example.aiphotoapp.R.color.error)); return@setPositiveButton }
                status("正在校验…")
                c.setCode(code)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptPassword(c: TelegramManager) {
        val input = EditText(this).apply {
            hint = "2FA 密码"; textSize = 14f; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundResource(com.example.aiphotoapp.R.drawable.bg_input); setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("两步验证密码")
            .setView(input)
            .setPositiveButton("提交") { _, _ ->
                status("正在校验…")
                c.setPassword(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun navTo(itemId: Int) {
        findViewById<BottomNavigationView>(com.example.aiphotoapp.R.id.nav).selectedItemId = itemId
    }

    private fun confimDlg(title: String, message: String, onOk: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> runCatching { onOk() }.onFailure { status("操作失败：${it.message}"); logCrash(it) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun logCrash(e: Throwable) {
        runCatching { File(filesDir, "crash.log").writeText("runtime: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}\n") }
    }
}