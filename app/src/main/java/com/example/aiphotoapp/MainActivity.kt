package com.example.aiphotoapp

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.random.Random
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var etPrompt: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnSave: Button
    private lateinit var ivResult: ImageView
    private lateinit var tvStatus: TextView
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPrompt = findViewById(R.id.etPrompt)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnSave = findViewById(R.id.btnSave)
        ivResult = findViewById(R.id.ivResult)
        tvStatus = findViewById(R.id.tvStatus)

        btnGenerate.setOnClickListener {
            val prompt = etPrompt.text.toString().trim()
            if (prompt.isEmpty()) {
                Toast.makeText(this, "请输入中文描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateImage(prompt)
        }

        btnSave.setOnClickListener {
            saveImageToGallery()
        }
    }

    private fun generateImage(basePrompt: String) {
        btnGenerate.isEnabled = false
        btnSave.isEnabled = false
        ivResult.setImageDrawable(null)

        thread {
            var english = basePrompt
            try {
                runOnUiThread { tvStatus.text = "正在翻译成英文..." }
                val translated = translateZhToEn(basePrompt)
                if (translated.isNotEmpty()) {
                    english = translated
                } else {
                    runOnUiThread { tvStatus.text = "翻译失败，直接用原话生成" }
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "翻译失败，直接用原话生成" }
            }

            val enhancedPrompt = "$english, 8k resolution, highly detailed, realistic, masterpiece, best quality"
            val encodedPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8")
            val seed = Random.nextInt(100000)
            val imageUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true&seed=$seed"

            runOnUiThread { tvStatus.text = "正在生成，请稍候..." }

            val bitmap = downloadImageWithRetry(imageUrl, maxAttempts = 4)
            runOnUiThread {
                if (bitmap != null) {
                    ivResult.setImageBitmap(bitmap)
                    tvStatus.text = "生成成功！"
                    btnGenerate.isEnabled = true
                    btnSave.isEnabled = true
                } else {
                    tvStatus.text = "生成失败：服务繁忙或网络超时，请稍后重试"
                    btnGenerate.isEnabled = true
                }
            }
        }
    }

    private fun translateZhToEn(text: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://api.mymemory.translated.net/get?q=$q&langpair=zh-CN|en"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return ""
            val json = JSONObject(body)
            return json.getJSONObject("responseData").getString("translatedText").trim()
        }
    }

    private fun downloadImageWithRetry(imageUrl: String, maxAttempts: Int): Bitmap? {
        var attempt = 0
        while (attempt < maxAttempts) {
            attempt++
            try {
                if (attempt > 1) {
                    runOnUiThread { tvStatus.text = "服务繁忙，自动重试中 ($attempt/$maxAttempts)..." }
                }
                val request = Request.Builder().url(imageUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw RuntimeException("HTTP ${response.code}")
                    }
                    response.body?.byteStream()?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) return bitmap
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (attempt < maxAttempts) {
                Thread.sleep(2000L * attempt)
            }
        }
        return null
    }

    private fun saveImageToGallery() {
        val drawable = ivResult.drawable
        if (drawable == null || drawable !is BitmapDrawable) {
            Toast.makeText(this, "没有可保存的图片", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = drawable.bitmap

        val filename = "AI_Photo_${System.currentTimeMillis()}.jpg"

        var outputStream: OutputStream? = null
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/InfiniteGallery")
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                outputStream = contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    Toast.makeText(this, "图片已保存到相册 (Pictures/InfiniteGallery)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            outputStream?.close()
        }
    }
}