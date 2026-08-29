package com.example.aiphotoapp

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var etPrompt: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnSave: Button
    private lateinit var btnRandom: Button
    private lateinit var btnHistory: Button
    private lateinit var ivResult: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView
    private lateinit var pbLoading: ProgressBar

    private val httpClient = OkHttpClient()
    private val generating = AtomicBoolean(false)

    private var selectedRatio = 0
    private var selectedStyle = 0
    private var currentBitmap: Bitmap? = null
    private var currentPromptText = ""

    private val ratios = listOf(
        Triple("1:1", 1024, 1024),
        Triple("16:9", 1280, 720),
        Triple("9:16", 720, 1280),
        Triple("4:3", 1152, 864),
        Triple("3:4", 864, 1152)
    )

    private val styleLabels = listOf("无", "赛博朋克", "水彩", "油画", "动漫", "像素", "写实")
    private val styleSuffixes = listOf(
        "",
        "cyberpunk, neon lights, futuristic city",
        "watercolor painting, soft flowing colors",
        "oil painting, rich brushstrokes, impressionist",
        "anime style, vibrant colors, studio quality",
        "pixel art style, retro game",
        "photorealistic, shot on camera, sharp details"
    )

    private val randomIdeas = listOf(
        "夕阳下的海边小城，海鸥飞过",
        "赛博朋克夜市，霓虹灯牌",
        "一只戴着太空头盔的柴犬在月球上",
        "深山里云雾缭绕的古寺",
        "雨后的东京街头，倒影",
        "沙漠中的金字塔与骆驼商队",
        "毛茸茸的虎斑猫趴在一摞书上",
        "冰雪森林里发光的驯鹿",
        "漂浮在云海之上的天空之城",
        "夏夜稻田里的萤火虫",
        "老爷爷在街角煮奶茶，蒸汽缭绕",
        "宇宙飞船穿过绚烂星云"
    )

    private lateinit var worksDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        worksDir = File(filesDir, "works").apply { mkdirs() }

        etPrompt = findViewById(R.id.etPrompt)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnSave = findViewById(R.id.btnSave)
        btnRandom = findViewById(R.id.btnRandom)
        btnHistory = findViewById(R.id.btnHistory)
        ivResult = findViewById(R.id.ivResult)
        tvStatus = findViewById(R.id.tvStatus)
        tvPreview = findViewById(R.id.tvPreview)
        pbLoading = findViewById(R.id.pbLoading)

        buildChips(findViewById(R.id.llRatio), ratios.map { it.first }) { selectedRatio = it }
        buildChips(findViewById(R.id.llStyle), styleLabels) { selectedStyle = it }
        renderChipSelection()

        btnGenerate.setOnClickListener {
            val prompt = etPrompt.text.toString().trim()
            if (prompt.isEmpty()) {
                Toast.makeText(this, "请输入中文描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateImage(prompt)
        }

        btnRandom.setOnClickListener {
            etPrompt.setText(randomIdeas[Random.nextInt(randomIdeas.size)])
            val prompt = etPrompt.text.toString().trim()
            generateImage(prompt)
        }

        btnSave.setOnClickListener {
            saveBitmapToGallery(currentBitmap)
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun buildChips(container: LinearLayout, labels: List<String>, onSelect: (Int) -> Unit) {
        container.removeAllViews()
        labels.forEachIndexed { index, label ->
            val chip = Button(this)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = 6
            if (index == labels.lastIndex) lp.marginEnd = 0
            chip.layoutParams = lp
            chip.text = label
            chip.textSize = 12f
            chip.setPadding(0, 0, 0, 0)
            chip.setOnClickListener {
                if (container.id == R.id.llRatio) selectedRatio = index else selectedStyle = index
                renderChipSelection()
                onSelect(index)
            }
            container.addView(chip)
        }
    }

    private fun renderChipSelection() {
        val ratioChips = findViewById<LinearLayout>(R.id.llRatio)
        for (i in 0 until ratioChips.childCount) {
            val chip = ratioChips.getChildAt(i) as Button
            chip.setBackgroundColor(if (i == selectedRatio) 0xFFFF6200.toInt() else 0xFF333333.toInt())
            chip.setTextColor(if (i == selectedRatio) Color.WHITE else 0xFFBBBBBB.toInt())
        }
        val styleChips = findViewById<LinearLayout>(R.id.llStyle)
        for (i in 0 until styleChips.childCount) {
            val chip = styleChips.getChildAt(i) as Button
            chip.setBackgroundColor(if (i == selectedStyle) 0xFF03DAC5.toInt() else 0xFF333333.toInt())
            chip.setTextColor(if (i == selectedStyle) Color.BLACK else 0xFFBBBBBB.toInt())
        }
    }

    private fun generateImage(idea: String) {
        if (generating.getAndSet(true)) return
        setBusy(true)

        thread {
            var englishIdea = idea
            try {
                runOnUiThread { tvStatus.text = "正在翻译成英文..." }
                val translated = translateZhToEn(idea)
                if (translated.isNotEmpty()) englishIdea = translated
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "翻译失败，直接用原话生成" }
            }

            val enhancedPrompt = buildPrompt(englishIdea)
            currentPromptText = enhancedPrompt

            val (_, w, h) = ratios[selectedRatio]
            val encoded = URLEncoder.encode(enhancedPrompt, "UTF-8").replace("+", "%20")
            val seed = Random.nextInt(100000)
            val imageUrl = "https://image.pollinations.ai/prompt/$encoded?width=$w&height=$h&model=flux&enhance=true&nologo=true&seed=$seed"

            runOnUiThread {
                tvPreview.visibility = View.VISIBLE
                tvPreview.text = "出图提示词：$enhancedPrompt"
            }

            val bitmap = downloadImageWithRetry(imageUrl, maxAttempts = 4)
            runOnUiThread {
                if (bitmap != null) {
                    currentBitmap = bitmap
                    ivResult.setImageBitmap(bitmap)
                    tvStatus.text = "生成成功！已存入“我的作品”"
                    btnSave.isEnabled = true
                    saveRecord(bitmap, idea, enhancedPrompt)
                } else {
                    tvStatus.text = "生成失败：服务繁忙或网络超时，请稍后重试"
                }
                setBusy(false)
                generating.set(false)
            }
        }
    }

    private fun buildPrompt(englishIdea: String): String {
        val style = if (selectedStyle > 0) styleSuffixes[selectedStyle] else ""
        val quality = "8k resolution, highly detailed, sharp focus, masterpiece, best quality"
        val parts = mutableListOf(englishIdea)
        if (style.isNotEmpty()) parts.add(style) else parts.add("realistic, cinematic lighting")
        parts.add(quality)
        return parts.joinToString(", ").take(400)
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
                    if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
                    response.body?.byteStream()?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) return bitmap
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (attempt < maxAttempts) Thread.sleep(2000L * attempt)
        }
        return null
    }

    private fun saveRecord(bitmap: Bitmap, idea: String, englishPrompt: String) {
        try {
            val ts = System.currentTimeMillis()
            val file = File(worksDir, "$ts.jpg")
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }

            val indexFile = File(worksDir, "index.json")
            val arr = if (indexFile.exists()) JSONArray(indexFile.readText()) else JSONArray()
            val obj = JSONObject().put("ts", ts).put("idea", idea).put("prompt", englishPrompt).put("file", file.name)
            arr.put(0, obj)
            while (arr.length() > 50) arr.remove(arr.length() - 1)
            indexFile.writeText(arr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap?) {
        if (bitmap == null) {
            Toast.makeText(this, "没有可保存的图片", Toast.LENGTH_SHORT).show()
            return
        }
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
                    Toast.makeText(this, "已保存到相册：$filename", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            outputStream?.close()
        }
    }

    private fun setBusy(busy: Boolean) {
        btnGenerate.isEnabled = !busy
        btnRandom.isEnabled = !busy
        pbLoading.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) btnSave.isEnabled = currentBitmap != null
    }
}