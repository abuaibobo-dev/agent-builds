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
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var etPrompt: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnSave: Button
    private lateinit var ivResult: ImageView
    private lateinit var tvStatus: TextView
    private var currentImageUrl: String = ""

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
                Toast.makeText(this, "请输入提示词", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateImage(prompt)
        }

        btnSave.setOnClickListener {
            saveImageToGallery()
        }
    }

    private fun generateImage(basePrompt: String) {
        tvStatus.text = "正在云端生成高画质图像，请稍候..."
        btnGenerate.isEnabled = false
        btnSave.isEnabled = false
        
        // 自动加入画质增强的提示词，确保出图是 4K/真实质感
        val enhancedPrompt = "$basePrompt, 8k resolution, highly detailed, realistic, masterpiece, best quality"
        val encodedPrompt = URLEncoder.encode(enhancedPrompt, "UTF-8")
        val seed = Random.nextInt(100000)
        
        // 核心：调用免费无限制的 Pollinations 接口
        currentImageUrl = "https://image.pollinations.ai/prompt/$encodedPrompt?width=1024&height=1024&nologo=true&seed=$seed"

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
