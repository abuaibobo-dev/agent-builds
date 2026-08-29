package com.example.aiphotoapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.example.aiphotoapp.data.CollectorDatabase
import com.example.aiphotoapp.data.SyncRule
import com.example.aiphotoapp.sync.SyncEngine
import com.example.aiphotoapp.telegram.TelegramManager
import org.json.JSONObject

class CollectorActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var db: CollectorDatabase
    private var telegram: TelegramManager? = null
    private var engine: SyncEngine? = null
    private var activeRule: SyncRule? = null
    private val channels = linkedMapOf<Long, String>()
    private var authFieldsAdded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(this, CollectorDatabase::class.java, "collector.db").build()
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
        content.addView(apiId); content.addView(apiHash); content.addView(phone); content.addView(login)
        val channelText = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(16, 16, 16, 8); text = "频道列表：\n暂无" }
        content.addView(channelText)
        login.setOnClickListener {
            val id = apiId.text.toString().trim().toIntOrNull()
            val hash = apiHash.text.toString().trim()
            if (id == null || hash.isEmpty()) { status.text = "请填写 API ID/API Hash"; return@setOnClickListener }
            val client = TelegramManager(this, id, hash)
            telegram = client
            client.addListener { update ->
                runOnUiThread {
                    val type = update.optString("@type")
                    if (type == "updateAuthorizationState") handleAuth(client, update.optJSONObject("authorization_state"), phone.text.toString())
                    if (type == "chat") {
                        val idValue = update.optLong("id")
                        val name = update.optString("title", idValue.toString())
                        channels[idValue] = name
                        channelText.text = "频道列表：\n" + channels.entries.joinToString("\n") { "${it.value} (${it.key})" }
                    }
                    if (type == "chats") update.optJSONArray("chat_ids")?.let { ids -> for (i in 0 until ids.length()) client.getChat(ids.getLong(i)) }
                }
            }
            client.start()
            status.text = "TDLib 已启动，等待授权"
        }
    }

    private fun handleAuth(client: TelegramManager, state: JSONObject?, phone: String) {
        when (state?.optString("@type")) {
            "authorizationStateWaitPhoneNumber" -> client.setPhone(phone)
            "authorizationStateReady" -> { status.text = "已登录"; client.loadChannels() }
            "authorizationStateWaitCode" -> addAuthField("Telegram 验证码", client) { client.setCode(it) }
            "authorizationStateWaitPassword" -> addAuthField("Telegram 2FA 密码", client) { client.setPassword(it) }
            "authorizationStateWaitTdlibParameters" -> status.text = "TDLib 参数配置中"
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
            submit(field.text.toString().trim())
        }
    }

    private fun overview() {
        title("运行总览")
        content.addView(TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); text = "当前任务：${activeRule?.id ?: "无"}\n状态和日志将在同步启动后显示。"; setPadding(16, 16, 16, 16) })
        content.addView(button("暂停") { engine?.pause(); status.text = "已暂停" })
        content.addView(button("继续") { engine?.resume(); status.text = "继续采集" })
        content.addView(button("停止") { engine?.stop(); status.text = "已停止" })
    }

    private fun rules() {
        title("采集规则")
        val source = input("来源频道 chat ID")
        val target = input("目标频道 chat ID")
        val start = button("开始历史采集")
        content.addView(source); content.addView(target); content.addView(start)
        start.setOnClickListener {
            val s = source.text.toString().toLongOrNull(); val t = target.text.toString().toLongOrNull()
            if (s == null || t == null || s == t) { status.text = "来源/目标 ID 无效，且不能相同"; return@setOnClickListener }
            val rule = SyncRule(sourceChatId = s, targetChatId = t)
            activeRule = rule
            val client = telegram ?: run { status.text = "先登录 Telegram"; return@setOnClickListener }
            engine = SyncEngine(client, db.collectorDao())
            engine?.start(rule) { message -> runOnUiThread { status.text = message } }
            status.text = "已开始：从最早消息扫描"
        }
    }

    private fun settings() { title("设置"); content.addView(TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); text = "Session 仅保存在本机。退出登录与并发设置将在下一步接入。"; setPadding(16, 16, 16, 16) }) }
    private fun title(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()); setPadding(16, 20, 16, 12) }) }
    private fun input(hint: String) = EditText(this).apply { this.hint = hint; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFB3B3B3.toInt()); setPadding(16, 12, 16, 12) }
    private fun button(text: String, action: (() -> Unit)? = null) = Button(this).apply { this.text = text; setTextColor(0xFFFFFFFF.toInt()); setBackgroundResource(R.drawable.bg_btn_outline); backgroundTintList = null; action?.let { setOnClickListener { it() } } }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() { engine?.stop(); telegram?.close(); db.close(); super.onDestroy() }
}
