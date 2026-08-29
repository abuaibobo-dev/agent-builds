package com.example.aiphotoapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlin.concurrent.thread
import okhttp3.Request
import org.json.JSONArray

object Updater {

    private const val API = "https://api.github.com/repos/abuaibobo-dev/agent-builds/releases"

    private fun httpClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun check(context: Context, silent: Boolean = true) {
        thread {
            try {
                val req = Request.Builder()
                    .url("$API?per_page=1")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val resp = httpClient().newCall(req).execute()
                val body = resp.body?.string() ?: return@thread
                if (!resp.isSuccessful) return@thread
                val rel = JSONArray(body).optJSONObject(0) ?: return@thread
                val tag = rel.optString("tag_name", "")
                val version = tag.removePrefix("v").substringAfterLast(".")
                val latestCode = version.toIntOrNull() ?: return@thread
                val current = BuildConfig.VERSION_CODE
                if (latestCode > current) {
                    val assets = rel.optJSONArray("assets")
                    var downloadUrl = ""
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            downloadUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                    if (downloadUrl.isNotEmpty()) {
                        val url = downloadUrl
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "发现新版本 ${rel.optString("tag_name")}", Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                } else if (!silent) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "已是最新版（$current）", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}