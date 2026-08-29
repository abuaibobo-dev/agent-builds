package com.example.aiphotoapp

import android.content.ContentValues
import android.graphics.Bitmap
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
import coil.load
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
    private var currentImageUrl: String = ""
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

            runOnUiThread { tvStatus.text = "已翻译，正在云端生成高画质图像，请稍候..." }

            val enhancedPrompt = "$english, 8k resolution, highly detailed, realistic, masterpiece, best quality"
            val encodedPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8")
            val seed = Random.nextInt(100000)

            currentImageUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true&seed=$seed"

            runOnUiThread {
                ivResult.load(currentImageUrl) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ ->
                            tvStatus.text = "生成成功！"
                            btnGenerate.isEnabled = true
                            btnSave.isEnabled = true
                        },
                        onError = { _, _ ->
                            tvStatus.text = "生成失败，请重试或检查网络"
                            btnGenerate.isEnabled = true
                        }
                    )
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