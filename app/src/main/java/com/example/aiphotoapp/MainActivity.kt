package com.example.aiphotoapp

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var etPrompt: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnPolish: Button
    private lateinit var btnSave: Button
    private lateinit var btnRandom: Button
    private lateinit var btnHistory: Button
    private lateinit var btnRef: Button
    private lateinit var btnVariation: Button
    private lateinit var ivResult: ImageView
    private lateinit var ivRefThumb: ImageView
    private lateinit var frameRefThumb: FrameLayout
    private lateinit var tvRefClear: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView
    private lateinit var pbLoading: ProgressBar

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val generating = AtomicBoolean(false)
    private val polishing = AtomicBoolean(false)

    private var selectedRatio = 0
    private var selectedStyle = 0
    private var currentBitmap: Bitmap? = null
    private var currentPromptText = ""
    private var lastIdea = ""
    private var refImageUrl: String? = null

    private val agnesBase = "https://apihub.agnes-ai.com/v1"
    private val agnesKey = BuildConfig.AGNES_API_KEY
    private val hfFluxBase = "https://black-forest-labs-flux-1-schnell.hf.space"

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

    private val pickRef = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) uploadRefImage(uri) else Toast.makeText(this, "未选择图片", Toast.LENGTH_SHORT).show()
    }

    private enum class Provider(val label: String, val loadingText: String) {
        AGNES("Agnes", "Agnes 高速引擎生成中..."),
        HFFLUX("FLUX 免费源", "FLUX 免费引擎接力生成中..."),
        POLLINATIONS("兜底源", "备用引擎兜底生成中...")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        worksDir = File(filesDir, "works").apply { mkdirs() }

        etPrompt = findViewById(R.id.etPrompt)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnPolish = findViewById(R.id.btnPolish)
        btnSave = findViewById(R.id.btnSave)
        btnRandom = findViewById(R.id.btnRandom)
        btnHistory = findViewById(R.id.btnHistory)
        btnRef = findViewById(R.id.btnRef)
        btnVariation = findViewById(R.id.btnVariation)
        btnRandom = findViewById(R.id.btnRandom)
        ivResult = findViewById(R.id.ivResult)
        ivRefThumb = findViewById(R.id.ivRefThumb)
        frameRefThumb = findViewById(R.id.frameRefThumb)
        tvRefClear = findViewById(R.id.tvRefClear)
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
            lastIdea = prompt
            generateImage(prompt)
        }

        btnPolish.setOnClickListener {
            val prompt = etPrompt.text.toString().trim()
            if (prompt.isEmpty()) {
                Toast.makeText(this, "先输入描述再优化", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            polishThenFill(prompt)
        }

        btnRandom.setOnClickListener {
            val idea = randomIdeas[Random.nextInt(randomIdeas.size)]
            etPrompt.setText(idea)
            lastIdea = idea
            generateImage(idea)
        }

        btnVariation.setOnClickListener {
            if (lastIdea.isEmpty()) {
                Toast.makeText(this, "先输入并生成一张图", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateImage(lastIdea)
        }

        btnRef.setOnClickListener {
            if (generating.get()) return@setOnClickListener
            pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        tvRefClear.setOnClickListener {
            refImageUrl = null
            frameRefThumb.visibility = View.GONE
            tvStatus.text = "已清除参考图"
            btnRef.text = "参考图"
        }

        ivRefThumb.setOnClickListener {
            if (refImageUrl != null && !generating.get()) {
                pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        btnSave.setOnClickListener {
            saveBitmapToGallery(currentBitmap)
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("gallery", MODE_PRIVATE)
        val pending = prefs.getString("pending_ref", null)
        if (pending != null) {
            prefs.edit().remove("pending_ref").apply()
            val file = File(pending)
            if (file.exists()) {
                uploadRefImage(Uri.fromFile(file))
            }
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
            chip.setBackgroundResource(if (i == selectedRatio) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chip.setTextColor(if (i == selectedRatio) 0xFF000000.toInt() else 0xFFB3B3B3.toInt())
        }
        val styleChips = findViewById<LinearLayout>(R.id.llStyle)
        for (i in 0 until styleChips.childCount) {
            val chip = styleChips.getChildAt(i) as Button
            chip.setBackgroundResource(if (i == selectedStyle) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chip.setTextColor(if (i == selectedStyle) 0xFF000000.toInt() else 0xFFB3B3B3.toInt())
        }
    }

    private fun polishThenFill(idea: String) {
        if (polishing.getAndSet(true)) return
        runOnUiThread { tvStatus.text = "AI 优化提示词中..." }
        thread {
            val result = polishPrompt(idea)
            runOnUiThread {
                etPrompt.setText(result)
                tvStatus.text = "提示词已优化，可再调整后生成"
                polishing.set(false)
            }
        }
    }

    private fun generateImage(idea: String) {
        if (generating.getAndSet(true)) return
        setBusy(true)

        thread {
            val (_, w, h) = ratios[selectedRatio]

            runOnUiThread { tvStatus.text = "智能优化提示词..." }
            val enhancedPrompt = polishPrompt(idea)
            currentPromptText = enhancedPrompt

            runOnUiThread {
                tvPreview.visibility = View.VISIBLE
                tvPreview.text = "出图提示词：$enhancedPrompt"
            }

            val providers = listOf(Provider.AGNES, Provider.HFFLUX, Provider.POLLINATIONS)

            var bitmap: Bitmap? = null
            var usedSource = ""
            for (p in providers) {
                runOnUiThread { tvStatus.text = p.loadingText }
                bitmap = when (p) {
                    Provider.AGNES -> generateWithAgnes(enhancedPrompt, w, h, refImageUrl)
                    Provider.HFFLUX -> generateWithHfFlux(enhancedPrompt, w, h)
                    Provider.POLLINATIONS -> generateWithPollinations(enhancedPrompt, w, h)
                }
                if (bitmap != null) {
                    usedSource = p.label
                    break
                }
            }

            val finalBitmap = bitmap
            val source = usedSource
            runOnUiThread {
                if (finalBitmap != null) {
                    currentBitmap = finalBitmap
                    ivResult.setImageBitmap(finalBitmap)
                    tvStatus.text = "生成成功（$source）！已存入“我的作品”"
                    btnSave.isEnabled = true
                    saveRecord(finalBitmap, idea, enhancedPrompt)
                } else {
                    tvStatus.text = "生成失败：所有引擎都忙或网络超时，请稍后重试"
                }
                setBusy(false)
                generating.set(false)
            }
        }
    }

    private fun polishPrompt(idea: String): String {
        if (agnesKey.isNotEmpty()) {
            try {
                val body = JSONObject()
                    .put("model", "agnes-2.0-flash")
                    .put("max_tokens", 500)
                    .put("messages", JSONArray().put(JSONObject()
                        .put("role", "system")
                        .put("content", "你是AI绘画提示词优化大师。把用户的中文描述或粗糙英文描述改写成一段高质量英文图像提示词：具体、有画面感、含灯光与质感描述。只输出优化后的英文提示词本身，不要解释不要引号。"))
                        .put(JSONObject().put("role", "user").put("content", idea)))
                val resp = postJson("$agnesBase/chat/completions", body)
                val content = resp?.getJSONArray("choices")
                    ?.getJSONObject(0)
                    ?.getJSONObject("message")
                    ?.getString("content")
                if (!content.isNullOrBlank()) return content.trim().take(400)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        var englishIdea = idea
        try {
            val translated = translateZhToEn(idea)
            if (translated.isNotEmpty()) englishIdea = translated
        } catch (e: Exception) {
        }
        return buildLocalPrompt(englishIdea)
    }

    private fun buildLocalPrompt(englishIdea: String): String {
        val style = if (selectedStyle > 0) styleSuffixes[selectedStyle] else ""
        val quality = "8k resolution, highly detailed, sharp focus, masterpiece, best quality"
        val parts = mutableListOf(englishIdea)
        if (style.isNotEmpty()) parts.add(style) else parts.add("realistic, cinematic lighting")
        parts.add(quality)
        return parts.joinToString(", ").take(400)
    }

    private fun generateWithAgnes(prompt: String, w: Int, h: Int, refUrl: String?): Bitmap? {
        if (agnesKey.isEmpty()) return null
        return try {
            val finalPrompt = if (refUrl != null) {
                "Use the reference image as the base. Keep the same composition, subject and overall look, only apply the described changes. $prompt"
            } else {
                prompt
            }
            val body = JSONObject()
                .put("model", "agnes-image-2.1-flash")
                .put("prompt", finalPrompt)
                .put("size", "${w}x${h}")
            refUrl?.let { body.put("image", it) }
            val resp = postJson("$agnesBase/images/generations", body)
            val url = resp?.getJSONArray("data")?.getJSONObject(0)?.getString("url")
            if (url.isNullOrEmpty()) return null
            downloadBitmap(url)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun generateWithHfFlux(prompt: String, w: Int, h: Int): Bitmap? {
        return try {
            val infoReq = Request.Builder().url("$hfFluxBase/gradio_api/info").build()
            val infoText = httpClient.newCall(infoReq).execute().use { it.body?.string() } ?: return null
            val epName = JSONObject(infoText).getJSONObject("named_endpoints").keys().next()
            val params = JSONObject(infoText).getJSONObject("named_endpoints").getJSONObject(epName).getJSONArray("parameters")

            val dataArr = JSONArray()
            for (i in 0 until params.length()) {
                val p = params.getJSONObject(i)
                val name = p.optString("parameter_name")
                when (name) {
                    "prompt" -> dataArr.put(prompt)
                    "width" -> dataArr.put(w)
                    "height" -> dataArr.put(h)
                    else -> {
                        if (p.has("parameter_default") && !p.isNull("parameter_default")) {
                            dataArr.put(p.get("parameter_default"))
                        } else if (p.has("parameter_has_default") && p.getBoolean("parameter_has_default")) {
                            dataArr.put(p.get("parameter_default"))
                        } else {
                            dataArr.put("")
                        }
                    }
                }
            }

            val submitBody = JSONObject().put("data", dataArr).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val submitReq = Request.Builder()
                .url("$hfFluxBase/gradio_api/call/$epName")
                .post(submitBody)
                .build()
            val eventId = httpClient.newCall(submitReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null).getString("event_id")
            }

            var resultUrl: String? = null
            for (attempt in 0 until 60) {
                Thread.sleep(2000)
                val pollReq = Request.Builder().url("$hfFluxBase/gradio_api/call/$epName/$eventId").build()
                val text = httpClient.newCall(pollReq).execute().use { it.body?.string() } ?: continue
                val idx = text.lastIndexOf("data: ")
                if (idx >= 0) {
                    val dataText = text.substring(idx + 6).trim()
                    try {
                        val arr = JSONArray(dataText)
                        if (arr.length() > 0) {
                            val first = arr.getJSONObject(0)
                            first.optString("url").takeIf { it.isNotEmpty() }?.let { resultUrl = it }
                            break
                        }
                    } catch (e: Exception) {
                    }
                }
            }
            val finalUrl = resultUrl ?: return null
            downloadBitmap(finalUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun generateWithPollinations(prompt: String, w: Int, h: Int): Bitmap? {
        return try {
            val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
            val seed = Random.nextInt(100000)
            val imageUrl = "https://image.pollinations.ai/prompt/$encoded?width=$w&height=$h&model=flux&enhance=true&nologo=true&seed=$seed"
            downloadBitmap(imageUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun postJson(url: String, json: JSONObject): JSONObject? {
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $agnesKey")
            .post(body)
            .build()
        return httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else JSONObject(resp.body?.string() ?: return null)
        }
    }

    private fun downloadBitmap(imageUrl: String): Bitmap? {
        var attempt = 0
        while (attempt < 4) {
            attempt++
            try {
                val req = Request.Builder().url(imageUrl).build()
                val resp = httpClient.newCall(req).execute()
                resp.use {
                    if (!it.isSuccessful) throw RuntimeException("HTTP ${it.code}")
                    it.body?.byteStream()?.use { stream ->
                        val bm = BitmapFactory.decodeStream(stream)
                        if (bm != null) return bm
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (attempt < 4) Thread.sleep(1500L * attempt)
        }
        return null
    }

    private fun translateZhToEn(text: String): String {
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://api.mymemory.translated.net/get?q=$q&langpair=zh-CN|en"
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return ""
            if (!response.isSuccessful) return ""
            val json = JSONObject(body)
            json.getJSONObject("responseData").optString("translatedText", "").trim()
        }
    }

    private fun uploadRefImage(uri: Uri) {
        thread {
            runOnUiThread { tvStatus.text = "解析参考图..." }
            try {
                val cacheRef = File(cacheDir, "ref_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheRef).use { output -> input.copyTo(output) }
                }
                val thumb = BitmapFactory.decodeFile(cacheRef.absolutePath)
                if (thumb != null) {
                    runOnUiThread {
                        ivRefThumb.setImageBitmap(thumb)
                        frameRefThumb.visibility = View.VISIBLE
                        btnRef.text = "参考图加载中..."
                    }
                }
                val mediaType = "image/jpeg".toMediaType()
                val fileBody = cacheRef.asRequestBody(mediaType)
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("reqtype", "fileupload")
                    .addFormDataPart("fileToUpload", cacheRef.name, fileBody)
                    .build()
                val req = Request.Builder().url("https://catbox.moe/user/api.php").post(multipart).build()
                val urlText = httpClient.newCall(req).execute().use { it.body?.string()?.trim() }
                if (urlText != null && urlText.startsWith("http")) {
                    refImageUrl = urlText
                    runOnUiThread {
                        btnRef.text = "已选参考图"
                        tvStatus.text = "参考图就绪，正在反推提示词..."
                    }
                    reversePrompt(cacheRef)
                } else {
                    runOnUiThread {
                        btnRef.text = "参考图"
                        tvStatus.text = "参考图上传失败，请重试"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    btnRef.text = "参考图"
                    tvStatus.text = "参考图处理失败：${e.message}"
                }
            }
        }
    }

    private fun reversePrompt(imageFile: File) {
        if (agnesKey.isEmpty()) return
        try {
            val base64 = java.util.Base64.getEncoder().encodeToString(imageFile.readBytes())
            val body = JSONObject()
                .put("model", "agnes-2.0-flash")
                .put("max_tokens", 300)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray()
                            .put(JSONObject().put("type", "text")
                                .put("text", "Describe this image as a detailed English image-generation prompt. Include subject, style, lighting, mood. Output only the English prompt, no quotes, under 80 words."))
                            .put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))))))
            val resp = postJson("$agnesBase/chat/completions", body)
            var desc = resp?.getJSONArray("choices")?.getJSONObject(0)
                ?.getJSONObject("message")?.optString("content", "")
            if (desc.isNullOrBlank()) {
                desc = resp?.getJSONArray("choices")?.getJSONObject(0)
                    ?.getJSONObject("message")?.optString("reasoning_content", "")
            }
            if (!desc.isNullOrBlank() && desc.length() > 12) {
                val finalDesc = desc.trim()
                runOnUiThread {
                    etPrompt.setText(finalDesc)
                    tvStatus.text = "已反推提示词，可修改后再生成"
                }
            } else {
                runOnUiThread { tvStatus.text = "反推失败，请手动输入描述" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { tvStatus.text = "反推失败，请手动输入描述" }
        }
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
        btnPolish.isEnabled = !busy
        btnRandom.isEnabled = !busy
        btnVariation.isEnabled = !busy
        btnRef.isEnabled = !busy
        pbLoading.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) btnSave.isEnabled = currentBitmap != null
    }
}