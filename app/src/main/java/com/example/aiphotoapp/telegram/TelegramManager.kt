package com.example.aiphotoapp.telegram

import android.content.Context
import io.xbot.tdlib.TdLib
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class TelegramManager(
    private val context: Context,
    private val apiId: Int,
    private val apiHash: String,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    @Volatile
    private var uiListener: ((JSONObject) -> Unit)? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private var clientId = -1

    fun addListener(listener: (JSONObject) -> Unit) {
        listeners += listener
    }

    fun bindUiListener(listener: (JSONObject) -> Unit) {
        uiListener = listener
    }

    fun start() {
        if (running.getAndSet(true)) return
        clientId = TdLib.createClientId()
        thread(name = "tdlib-receive") {
            while (running.get()) {
                val raw = TdLib.receive(1.0) ?: continue
                try {
                    val update = JSONObject(raw)
                    update.optString("@extra").takeIf { it.isNotEmpty() }?.let { pending.remove(it)?.complete(update) }
                    uiListener?.invoke(update)
                    listeners.forEach { it(update) }
                } catch (_: Exception) {
                }
            }
        }
        send(
            "setTdlibParameters",
            JSONObject()
                .put("use_test_dc", false)
                .put("database_directory", File(context.filesDir, "tdlib-db").absolutePath)
                .put("files_directory", File(context.filesDir, "tdlib-files").absolutePath)
                .put("use_file_database", true)
                .put("use_chat_info_database", true)
                .put("use_message_database", true)
                .put("use_secret_chats", false)
                .put("api_id", apiId)
                .put("api_hash", apiHash)
                .put("system_language_code", "zh-CN")
                .put("device_model", "Android")
                .put("application_version", "1.0")
                .put("enable_storage_optimizer", true),
        )
    }

    fun send(type: String, params: JSONObject = JSONObject()) {
        if (clientId < 0) error("TDLib client not started")
        params.put("@type", type)
        TdLib.send(clientId, params.toString())
    }

    fun request(type: String, params: JSONObject = JSONObject(), timeoutSeconds: Long = 120): JSONObject {
        val extra = "collector-${System.nanoTime()}"
        val future = CompletableFuture<JSONObject>()
        pending[extra] = future
        params.put("@extra", extra)
        send(type, params)
        return try {
            future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            pending.remove(extra)
        }
    }

    fun setPhone(phone: String) = send("setAuthenticationPhoneNumber", JSONObject().put("phone_number", phone))

    fun setCode(code: String) = send("checkAuthenticationCode", JSONObject().put("code", code))

    fun setPassword(password: String) = send("checkAuthenticationPassword", JSONObject().put("password", password))

    fun loadChannels(limit: Int = 100) = send(
        "getChats",
        JSONObject().put("chat_list", JSONObject().put("@type", "chatListMain")).put("limit", limit),
    )

    fun getChat(chatId: Long): JSONObject = request("getChat", JSONObject().put("chat_id", chatId))

    fun historyPage(chatId: Long, fromMessageId: Long, limit: Int = 100): JSONObject = request(
        "getChatHistory",
        JSONObject()
            .put("chat_id", chatId)
            .put("from_message_id", fromMessageId)
            .put("offset", 0)
            .put("limit", limit)
            .put("only_local", false),
    )

    fun copyMessages(sourceChatId: Long, targetChatId: Long, messageIds: List<Long>, removeCaption: Boolean = false): JSONObject = request(
        "copyMessages",
        JSONObject()
            .put("chat_id", targetChatId)
            .put("from_chat_id", sourceChatId)
            .put("message_ids", JSONArray(messageIds))
            .put("send_copy", true)
            .put("remove_caption", removeCaption),
    )

    override fun close() {
        if (!running.getAndSet(false)) return
        if (clientId >= 0) TdLib.send(clientId, JSONObject().put("@type", "close").toString())
        clientId = -1
    }
}
