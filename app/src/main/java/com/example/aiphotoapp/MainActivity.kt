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
import android.view.Window
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
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
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

    private lateinit var bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var tabGenerate: View
    private lateinit var tabImg2img: View
    private lateinit var tabGallery: View
    private lateinit var tabSettings: View

    private lateinit var etPrompt: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnPolish: Button
    private lateinit var btnSave: Button
    private lateinit var btnUpscale: Button
    private lateinit var etBatchTheme: EditText
    private lateinit var btnBatchStart: Button
    private lateinit var btnBatchStop: Button
    private lateinit var tvBatchStatus: TextView
    private lateinit var llRate: LinearLayout
    private lateinit var llBatchPrompts: LinearLayout
    private lateinit var btnRandom: Button
    private lateinit var btnVariation: Button
    private lateinit var ivResult: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView
    private lateinit var pbLoading: ProgressBar

    private lateinit var btnRef: Button
    private lateinit var etImgPrompt: EditText
    private lateinit var btnImgGenerate: Button
    private lateinit var btnImgSave: Button
    private lateinit var btnImgUpscale: Button
    private lateinit var ivImgResult: ImageView
    private lateinit var ivImgRefThumb: ImageView
    private lateinit var frameImgRefThumb: FrameLayout
    private lateinit var tvImgRefClear: TextView
    private lateinit var tvImgStatus: TextView
    private lateinit var pbImgLoading: ProgressBar
    private lateinit var llImgEditMode: LinearLayout
    private lateinit var galleryRecycler: RecyclerView

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .build()
    private val localUpscaler by lazy { LocalUpscaler(this) }
    private val generating = AtomicBoolean(false)
    private val polishing = AtomicBoolean(false)
    private val upscaling = AtomicBoolean(false)
    private val batchRunning = AtomicBoolean(false)
    @Volatile
    private var batchStopRequested = false
    private var batchRateDelay = 5000L
    private var selectedEditMode = ImageEditPrompt.Mode.REMOVE_BACKGROUND
    private val batchPool = mutableListOf<String>()
    private var batchDoneCount = 0
    private var batchTheme = ""
    private val cooldowns = mutableMapOf<Provider, Long>()
    private lateinit var batchStateFile: File

    private var selectedRatio = 0
    private var selectedStyle = 0
    private var antiAi = false
    private var currentBitmap: Bitmap? = null
    private var imgCurrentBitmap: Bitmap? = null
    private var currentPromptText = ""
    private var tvPreviewText = ""
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
    private val antiAiSuffix = "candid documentary photo, natural skin texture, subtle film grain, muted natural colors, realistic imperfections, 35mm analog film look, honest natural lighting, no CGI, no airbrushing, no oversaturation"

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
        clearButtonTints(findViewById(android.R.id.content))

        worksDir = File(filesDir, "works").apply { mkdirs() }
        batchStateFile = File(filesDir, "batch_state.json")

        bottomNav = findViewById(R.id.bottomNav)
        tabGenerate = findViewById(R.id.tab_generate)
        tabImg2img = findViewById(R.id.tab_img2img)
        tabGallery = findViewById(R.id.tab_gallery)
        tabSettings = findViewById(R.id.tab_settings)

        etPrompt = findViewById(R.id.etPrompt)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnPolish = findViewById(R.id.btnPolish)
        btnSave = findViewById(R.id.btnSave)
        btnUpscale = findViewById(R.id.btnUpscale)
        etBatchTheme = findViewById(R.id.etBatchTheme)
        btnBatchStart = findViewById(R.id.btnBatchStart)
        btnBatchStop = findViewById(R.id.btnBatchStop)
        tvBatchStatus = findViewById(R.id.tvBatchStatus)
        llRate = findViewById(R.id.llRate)
        llBatchPrompts = findViewById(R.id.llBatchPrompts)
        btnRandom = findViewById(R.id.btnRandom)
        btnVariation = findViewById(R.id.btnVariation)
        ivResult = findViewById(R.id.ivResult)
        tvStatus = findViewById(R.id.tvStatus)
        tvPreview = findViewById(R.id.tvPreview)
        pbLoading = findViewById(R.id.pbLoading)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swAntiAi)
            .setOnCheckedChangeListener { _, checked -> antiAi = checked }

        btnRef = findViewById(R.id.btnRef)
        etImgPrompt = findViewById(R.id.etImgPrompt)
        btnImgGenerate = findViewById(R.id.btnImgGenerate)
        btnImgSave = findViewById(R.id.btnImgSave)
        btnImgUpscale = findViewById(R.id.btnImgUpscale)
        ivImgResult = findViewById(R.id.ivImgResult)
        ivResult.setOnClickListener { currentBitmap?.let { showBitmapDetail(it, false) } }
        ivImgResult.setOnClickListener { imgCurrentBitmap?.let { showBitmapDetail(it, true) } }
        ivImgRefThumb = findViewById(R.id.ivImgRefThumb)
        frameImgRefThumb = findViewById(R.id.frameImgRefThumb)
        tvImgRefClear = findViewById(R.id.tvImgRefClear)
        tvImgStatus = findViewById(R.id.tvImgStatus)
        pbImgLoading = findViewById(R.id.pbImgLoading)
        llImgEditMode = findViewById(R.id.llImgEditMode)
        galleryRecycler = findViewById(R.id.tab_gallery)
        galleryRecycler.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)

        buildChips(findViewById(R.id.llRatio), ratios.map { it.first }) { selectedRatio = it }
        buildChips(findViewById(R.id.llImgRatio), ratios.map { it.first }) { selectedRatio = it }
        buildChips(findViewById(R.id.llStyle), styleLabels) { selectedStyle = it }
        renderChipSelection()

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tab_generate -> showTab(tabGenerate)
                R.id.nav_tab_img2img -> showTab(tabImg2img)
                R.id.nav_tab_gallery -> {
                    showTab(tabGallery)
                    loadGallery()
                }
                R.id.nav_tab_settings -> showTab(tabSettings)
            }
            true
        }

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("清空我的作品")
                .setMessage("确定删除全部作品？此操作不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    worksDir.listFiles()?.forEach { it.delete() }
                    loadGallery()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

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

        tvImgRefClear.setOnClickListener {
            refImageUrl = null
            frameImgRefThumb.visibility = View.GONE
            tvImgStatus.text = "已清除参考图"
            btnRef.text = "选参考图（从相册）"
        }

        ivImgRefThumb.setOnClickListener {
            if (refImageUrl != null && !generating.get()) {
                pickRef.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        btnImgGenerate.setOnClickListener {
            val prompt = etImgPrompt.text.toString().trim()
            if (prompt.isEmpty()) {
                Toast.makeText(this, "请输入修改描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (refImageUrl == null) {
                Toast.makeText(this, "请先选择参考图", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateImageFrom(ImageEditPrompt.build(selectedEditMode, prompt))
        }

        btnImgSave.setOnClickListener {
            saveBitmapToGallery(imgCurrentBitmap)
        }

        btnUpscale.setOnClickListener {
            val bmp = currentBitmap
            if (bmp == null || upscaling.get() || generating.get()) return@setOnClickListener
            upscale4k(bmp, isImg = false)
        }

        btnImgUpscale.setOnClickListener {
            val bmp = imgCurrentBitmap
            if (bmp == null || upscaling.get() || generating.get()) return@setOnClickListener
            upscale4k(bmp, isImg = true)
        }

        buildChips(llRate, listOf("慢", "中", "快")) { idx ->
            batchRateDelay = when (idx) { 0 -> 15000L; 1 -> 5000L; 2 -> 1500L; else -> 5000L }
            setChipDefault(llRate, idx)
        }
        setChipDefault(llRate, 1)
        buildChips(llImgEditMode, listOf("抠背景", "换背景", "换衣服", "改衣服颜色")) { idx ->
            selectedEditMode = ImageEditPrompt.Mode.values()[idx]
        }

        btnBatchStart.setOnClickListener {
            if (batchRunning.get()) return@setOnClickListener
            startBatch()
        }

        btnBatchStop.setOnClickListener {
            if (batchRunning.get()) {
                batchStopRequested = true
                tvBatchStatus.text = "停止中：完成当前这张后保存…"
            }
        }

        btnSave.setOnClickListener {
            saveBitmapToGallery(currentBitmap)
        }
    }

    private fun showTab(target: View) {
        tabGenerate.visibility = if (target == tabGenerate) View.VISIBLE else View.GONE
        tabImg2img.visibility = if (target == tabImg2img) View.VISIBLE else View.GONE
        tabGallery.visibility = if (target == tabGallery) View.VISIBLE else View.GONE
        tabSettings.visibility = if (target == tabSettings) View.VISIBLE else View.GONE
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
                if (container.id == R.id.llRatio || container.id == R.id.llImgRatio) selectedRatio = index else selectedStyle = index
                renderChipSelection()
                onSelect(index)
            }
            container.addView(chip)
        }
    }

    private fun setChipDefault(container: LinearLayout, index: Int) {
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as Button
            chip.setBackgroundResource(if (i == index) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chip.setTextColor(if (i == index) 0xFF000000.toInt() else 0xFFB3B3B3.toInt())
        }
    }

    private fun renderChipSelection() {
        for (id in listOf(R.id.llRatio, R.id.llImgRatio)) {
            val ratioChips = findViewById<LinearLayout>(id)
            for (i in 0 until ratioChips.childCount) {
                val chip = ratioChips.getChildAt(i) as Button
                chip.setBackgroundResource(if (i == selectedRatio) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                chip.setTextColor(if (i == selectedRatio) 0xFF000000.toInt() else 0xFFB3B3B3.toInt())
            }
        }
        val styleChips = findViewById<LinearLayout>(R.id.llStyle)
        for (i in 0 until styleChips.childCount) {
            val chip = styleChips.getChildAt(i) as Button
            chip.setBackgroundResource(if (i == selectedStyle) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            chip.setTextColor(if (i == selectedStyle) 0xFF000000.toInt() else 0xFFB3B3B3.toInt())
        }
    }

    private fun startBatch() {
        val theme = etBatchTheme.text.toString().trim()
        if (theme.isEmpty()) {
            Toast.makeText(this, "先输入主题方向", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = loadBatchState()
        if (saved != null && saved.optInt("done", 0) > 0) {
            val msg = "上次任务「${saved.optString("theme")}」已完成 ${saved.optInt("done")} 张，未结束。"
            android.app.AlertDialog.Builder(this)
                .setTitle("发现未完成任务")
                .setMessage("$msg\n\n继续跑，还是开新批次？")
                .setPositiveButton("继续") { _, _ -> beginBatch(saved, theme) }
                .setNegativeButton("开新批次") { _, _ -> beginBatch(null, theme) }
                .setNeutralButton("取消", null)
                .show()
        } else {
            beginBatch(saved, theme)
        }
    }

    private fun beginBatch(previous: JSONObject?, theme: String) {
        if (previous != null) {
            batchTheme = previous.optString("theme")
            batchDoneCount = previous.optInt("done", 0)
            batchPool.clear()
            val arr = previous.optJSONArray("pool")
            if (arr != null) for (i in 0 until arr.length()) batchPool.add(arr.optString(i))
        } else {
            batchTheme = theme
            batchDoneCount = 0
            batchPool.clear()
        }
        batchStopRequested = false
        cooldowns.clear()
        batchRunning.set(true)
        btnBatchStart.isEnabled = false
        btnBatchStop.isEnabled = true
        btnGenerate.isEnabled = false
        btnPolish.isEnabled = false
        btnRandom.isEnabled = false
        btnVariation.isEnabled = false
        tvBatchStatus.text = "启动批量：$batchTheme"
        renderBatchPrompts(batchPool.toList())
        thread { batchWorker() }
    }

    private fun batchWorker() {
        while (!batchStopRequested) {
            try {
                if (batchPool.isEmpty()) {
                    runOnUiThread { tvBatchStatus.text = "裂变新创意中…" }
                    topUpPool()
                    persistBatch()
                    if (batchPool.isEmpty()) {
                        runOnUiThread { tvBatchStatus.text = "创意源不可用，已暂停；点“停止”结束" }
                        break
                    }
                }
                val idea = batchPool.removeAt(0)
                val remaining = batchPool.toList()
                runOnUiThread { renderBatchPrompts(remaining) }
                val bitmap = generateBatchImage(idea)
                if (bitmap != null) {
                    batchDoneCount++
                    runOnUiThread {
                        tvBatchStatus.text = "已完成 $batchDoneCount 张 · 池内 ${batchPool.size} · “$idea”"
                        ivResult.setImageBitmap(bitmap)
                    }
                    persistBatch()
                } else {
                    persistBatch()
                }
                if (batchStopRequested) break
                Thread.sleep(batchRateDelay)
                if (batchStopRequested) break
            } catch (e: Exception) {
                e.printStackTrace()
                Thread.sleep(2000)
            }
        }
        batchRunning.set(false)
        persistBatch(false)
        runOnUiThread {
            btnBatchStart.isEnabled = true
            btnBatchStop.isEnabled = false
            btnGenerate.isEnabled = true
            btnPolish.isEnabled = true
            btnRandom.isEnabled = true
            btnVariation.isEnabled = true
            tvBatchStatus.text = "已停止 · 本批共 $batchDoneCount 张"
            loadGallery()
        }
    }

    private fun topUpPool() {
        val fresh = batchCreateIdeas(batchTheme, 8)
        for (t in fresh) {
            if (t.isNotBlank()) batchPool.add(t)
        }
        if (batchPool.size > 24) {
            while (batchPool.size > 24) batchPool.removeAt(batchPool.size - 1)
        }
        val snapshot = batchPool.toList()
        runOnUiThread { renderBatchPrompts(snapshot) }
    }

    private fun renderBatchPrompts(items: List<String>) {
        llBatchPrompts.removeAllViews()
        if (items.isEmpty()) {
            llBatchPrompts.addView(TextView(this).apply {
                text = "等待 AI 扩写主题…"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
            return
        }
        items.forEachIndexed { index, prompt ->
            llBatchPrompts.addView(TextView(this).apply {
                text = "${index + 1}. $prompt"
                textSize = 13f
                setTextColor(0xFFE0E0E0.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(6)) }
            })
        }
    }

    private fun generateBatchImage(idea: String): Bitmap? {
        val (_, w, h) = ratios[selectedRatio]
        val providers = listOf(Provider.AGNES, Provider.HFFLUX, Provider.POLLINATIONS)
        for (p in providers) {
            val until = cooldowns[p]
            if (until != null && until > System.currentTimeMillis()) continue
            runOnUiThread { tvBatchStatus.text = "${p.loadingText} · 池内 ${batchPool.size + 1}" }
            val bmp = try {
                when (p) {
                    Provider.AGNES -> generateWithAgnes(idea, w, h, null)
                    Provider.HFFLUX -> generateWithHfFlux(idea, w, h)
                    Provider.POLLINATIONS -> generateWithPollinations(idea, w, h)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            if (bmp != null) {
                cooldowns.remove(p)
                currentBitmap = bmp
                runOnUiThread { btnSave.isEnabled = true }
                saveRecord(bmp, idea, idea)
                return bmp
            }
            cooldowns[p] = System.currentTimeMillis() + when (p) {
                Provider.AGNES -> 30_000L
                Provider.HFFLUX -> 60_000L
                Provider.POLLINATIONS -> 25_000L
            }
            runOnUiThread { tvBatchStatus.text = "${p.label} 暂不可用，换源…" }
        }
        return null
    }

    private fun batchCreateIdeas(theme: String, n: Int): List<String> {
        val inlineFallback = listOf(
            "$theme，主体位于画面中央，清晨薄雾与柔和逆光，近景构图，细节清晰",
            "$theme，深夜霓虹街道，雨水倒影，低机位广角，冷暖对比光影",
            "$theme，俯瞰辽阔环境，主体与远景形成比例关系，日落金色光线",
            "$theme，微距特写，突出纹理与材质，浅景深，背景柔和虚化",
            "$theme，雨后湿润环境，倒影与水滴细节，阴天漫射光，电影感构图",
            "$theme，冬季雪景，白雪覆盖环境，冷色调，远处薄雾与柔光",
            "$theme，正午强烈阳光，高对比硬光，清晰轮廓，丰富环境细节",
            "$theme，极简留白构图，单一主体，柔和侧光，干净高级画面"
        )
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
            val body = JSONObject()
                .put("model", "agnes-2.0-flash")
                .put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content",
                        "你是中文视觉创意总监。围绕主题「$theme」创作 $n 个具体、可直接用于绘图的中文提示词。每条必须包含主体、环境、构图、视角、光线、色调或材质中的多个细节；彼此明显不同，避免空泛词和重复。只输出中文描述，每行一条，不要编号，不要英文，不要解释。"))
                })
                .put("max_tokens", 1800)
            val req = Request.Builder()
                .url("https://api.agilestudio.cn/v1/chat/completions")
                .header("Authorization", "Bearer ${BuildConfig.AGNES_API_KEY}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val raw = resp.body?.string()
            resp.close()
            val lines = raw?.let { r ->
                JSONObject(r).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "") ?: ""
            }?.split("\n")?.map { it.trim() }?.filter { it.length > 12 }
            val list = lines?.take(n).orEmpty()
            if (list.size >= 4) list else inlineFallback
        } catch (e: Exception) {
            e.printStackTrace()
            inlineFallback
        }
    }

    private fun persistBatch(running: Boolean = batchRunning.get()) {
        try {
            val obj = JSONObject().apply {
                put("theme", batchTheme)
                put("done", batchDoneCount)
                put("running", running)
                val arr = JSONArray()
                for (t in batchPool) arr.put(t)
                put("pool", arr)
            }
            batchStateFile.writeText(obj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadBatchState(): JSONObject? {
        if (!batchStateFile.exists()) return null
        return try {
            JSONObject(batchStateFile.readText())
        } catch (e: Exception) {
            null
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

    private fun generateImageFrom(idea: String) {
        if (generating.getAndSet(true)) return
        setBusy(true, img = true)

        thread {
            val (_, w, h) = ratios[selectedRatio]

            runOnUiThread { tvImgStatus.text = "智能优化提示词..." }
            val enhancedPrompt = polishPrompt(idea)
            tvPreviewText = enhancedPrompt

            val providers = listOf(Provider.AGNES, Provider.HFFLUX, Provider.POLLINATIONS)

            var bitmap: Bitmap? = null
            var usedSource = ""
            for (p in providers) {
                runOnUiThread { tvImgStatus.text = p.loadingText }
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
                    imgCurrentBitmap = finalBitmap
                    ivImgResult.setImageBitmap(finalBitmap)
                    tvImgStatus.text = "生成成功（$source）！已存入“我的作品”"
                    btnImgSave.isEnabled = true
                    saveRecord(finalBitmap, idea, enhancedPrompt)
                } else {
                    tvImgStatus.text = "生成失败：所有引擎都忙或网络超时，请稍后重试"
                }
                setBusy(false, img = true)
                generating.set(false)
            }
        }
    }

    private fun upscale4k(source: Bitmap, isImg: Boolean, onDone: ((Bitmap) -> Unit)? = null) {
        if (upscaling.getAndSet(true)) return
        val status = if (isImg) tvImgStatus else tvStatus
        if (isImg) btnImgSave.isEnabled = false else btnSave.isEnabled = false

        thread {
            try {
                val maxDim = maxOf(source.width, source.height)
                if (maxDim >= 4500) {
                    runOnUiThread { status.text = "图片已够大，无需放大" }
                    return@thread
                }
                runOnUiThread { status.text = "本地 AI 超分放大中（x4）..." }
                val upBmp = localUpscaler.upscale(source)
                runOnUiThread {
                    if (isImg) { imgCurrentBitmap = upBmp; ivImgResult.setImageBitmap(upBmp) }
                    else { currentBitmap = upBmp; ivResult.setImageBitmap(upBmp) }
                    status.text = "已放大：${upBmp.width}×${upBmp.height}（本地 AI 超分）"
                    onDone?.invoke(upBmp)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { status.text = "超分失败：${e.message}" }
            } finally {
                upscaling.set(false)
                runOnUiThread { setBusy(false, img = isImg) }
            }
        }
    }

    private fun showBitmapDetail(source: Bitmap, isImg: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
            setBackgroundColor(0xFF0F0F0F.toInt())
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(source)
        }
        root.addView(image)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val upscale = Button(this).apply {
            text = "本地超分"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            stateListAnimator = null
        }
        val save = Button(this).apply {
            text = "保存"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            stateListAnimator = null
        }
        val close = Button(this).apply {
            text = "关闭"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            stateListAnimator = null
        }
        listOf(upscale, save, close).forEach { button ->
            button.backgroundTintList = null
            actions.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(actions)
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.setBackgroundDrawableResource(android.R.color.black)
        }
        upscale.setOnClickListener {
            if (!upscaling.get()) {
                upscale.isEnabled = false
                upscale4k(source, isImg) {
                    image.setImageBitmap(it)
                    save.isEnabled = true
                }
            }
        }
        save.setOnClickListener { saveBitmapToGallery(if (isImg) imgCurrentBitmap else currentBitmap) }
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                    Provider.AGNES -> generateWithAgnes(enhancedPrompt, w, h, null)
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
                val styleHint = if (antiAi) {
                    " (keep it anti-AI: candid, natural skin texture, film grain, muted colors, no CGI/airbrushing/oversaturation)"
                } else if (selectedStyle > 0) {
                    " (style: ${styleSuffixes[selectedStyle]})"
                } else {
                    ""
                }
                val body = JSONObject()
                    .put("model", "agnes-2.0-flash")
                    .put("max_tokens", 500)
                    .put("messages", JSONArray().put(JSONObject()
                        .put("role", "system")
                        .put("content", "你是AI绘画提示词优化大师。把用户的中文描述或粗糙英文描述改写成一段高质量英文图像提示词：具体、有画面感、含灯光与质感描述${if (styleHint.isEmpty()) "" else "，并严格保持用户指定风格" }。只输出优化后的英文提示词本身，不要解释不要引号。"))
                        .put(JSONObject().put("role", "user").put("content", idea + styleHint)))
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
        val style = if (antiAi) antiAiSuffix else if (selectedStyle > 0) styleSuffixes[selectedStyle] else ""
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
            runOnUiThread { tvImgStatus.text = "解析参考图..." }
            try {
                val cacheRef = File(cacheDir, "ref_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheRef).use { output -> input.copyTo(output) }
                }
                val thumb = BitmapFactory.decodeFile(cacheRef.absolutePath)
                if (thumb != null) {
                    runOnUiThread {
                        ivImgRefThumb.setImageBitmap(thumb)
                        frameImgRefThumb.visibility = View.VISIBLE
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
                        tvImgStatus.text = "参考图就绪，正在反推提示词..."
                    }
                    reversePrompt(cacheRef)
                } else {
                    runOnUiThread {
                        btnRef.text = "选参考图（从相册）"
                        tvImgStatus.text = "参考图上传失败，请重试"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    btnRef.text = "选参考图（从相册）"
                    tvImgStatus.text = "参考图处理失败：${e.message}"
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
            if (!desc.isNullOrBlank() && desc.length > 12) {
                val finalDesc = desc.trim()
                runOnUiThread {
                    etImgPrompt.setText(finalDesc)
                    tvImgStatus.text = "已反推提示词，可修改后再生成"
                }
            } else {
                runOnUiThread { tvImgStatus.text = "反推失败，请手动输入描述" }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread { tvImgStatus.text = "反推失败，请手动输入描述" }
        }
    }

    private fun loadGallery() {
        val index = File(worksDir, "index.json")
        val arr = if (index.exists() && index.length() > 0) JSONArray(index.readText()) else JSONArray()
        val items = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) items.add(arr.getJSONObject(i))
        galleryRecycler.adapter = GalleryAdapter(items)
    }

    private inner class GalleryAdapter(
        private val items: List<JSONObject>
    ) : RecyclerView.Adapter<GalleryAdapter.Holder>() {

        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(parent.context).apply {
                layoutParams = StaggeredGridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_card)
                clipToOutline = true
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val obj = items[position]
            val file = File(worksDir, obj.optString("file", ""))
            holder.image.setImageBitmap(if (file.exists()) decodeSampled(file, 900) else null)
            holder.image.contentDescription = obj.optString("idea", "作品")
            holder.image.setOnClickListener { showGalleryDetail(obj) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun showGalleryDetail(obj: JSONObject) {
        val idea = obj.optString("idea", "")
        val file = File(worksDir, obj.optString("file", ""))

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 8)
            setBackgroundColor(0xFF0F0F0F.toInt())
        }
        val iv = ImageView(this)
        iv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        if (file.exists()) iv.setImageBitmap(decodeSampled(file, 1400))
        view.addView(iv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14, 0, 0)
        }
        val btnRedraw = Button(this)
        btnRedraw.text = "设为参考图重绘"
        val btnLocalUpscale = Button(this)
        btnLocalUpscale.text = "本地超分"
        val btnSaveG = Button(this)
        btnSaveG.text = "保存到相册"
        val btnDelete = Button(this)
        btnDelete.text = "删除"
        listOf(btnRedraw, btnLocalUpscale, btnSaveG, btnDelete).forEach { b ->
            b.setTextColor(0xFFFFFFFF.toInt())
            b.setBackgroundResource(R.drawable.bg_btn_outline)
            b.backgroundTintList = null
            b.textSize = 13f
            b.stateListAnimator = null
        }
        val l1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l3 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val l4 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l2.marginStart = dp(8)
        l3.marginStart = dp(8)
        l4.marginStart = dp(8)
        btnRedraw.layoutParams = l1
        btnLocalUpscale.layoutParams = l2
        btnSaveG.layoutParams = l3
        btnDelete.layoutParams = l4
        btnRow.addView(btnRedraw)
        btnRow.addView(btnLocalUpscale)
        btnRow.addView(btnSaveG)
        btnRow.addView(btnDelete)
        view.addView(btnRow)

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.setContentView(view)

        btnRedraw.setOnClickListener {
            dialog.dismiss()
            if (file.exists()) {
                showTab(tabImg2img)
                uploadRefImage(Uri.fromFile(file))
                Toast.makeText(this, "已设为参考图，去“图生图”页生成", Toast.LENGTH_SHORT).show()
            }
        }
        btnLocalUpscale.setOnClickListener {
            if (!upscaling.get() && file.exists()) {
                btnLocalUpscale.isEnabled = false
                upscale4k(decodeSampled(file, 1400) ?: return@setOnClickListener, false) {
                    iv.setImageBitmap(it)
                    btnLocalUpscale.isEnabled = true
                }
            }
        }
        btnSaveG.setOnClickListener {
            val bmp = decodeSampled(file, 2048)
            if (bmp != null) saveBitmapToGallery(bmp)
        }
        btnDelete.setOnClickListener {
            file.delete()
            val index = File(worksDir, "index.json")
            val arr = JSONArray(index.readText())
            val next = JSONArray()
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).getLong("ts") != obj.getLong("ts")) next.put(arr.getJSONObject(i))
            }
            index.writeText(next.toString())
            dialog.dismiss()
            loadGallery()
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun decodeSampled(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun clearButtonTints(view: View) {
        when (view) {
            is Button -> view.backgroundTintList = null
            is ViewGroup -> for (i in 0 until view.childCount) clearButtonTints(view.getChildAt(i))
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

    override fun onDestroy() {
        localUpscaler.close()
        super.onDestroy()
    }

    private fun setBusy(busy: Boolean, img: Boolean = false) {
        if (img) {
            btnImgGenerate.isEnabled = !busy
            btnRef.isEnabled = !busy
            pbImgLoading.visibility = if (busy) View.VISIBLE else View.GONE
            if (!busy) btnImgSave.isEnabled = imgCurrentBitmap != null
            if (!busy) btnImgUpscale.isEnabled = imgCurrentBitmap != null && !upscaling.get()
        } else {
            btnGenerate.isEnabled = !busy
            btnPolish.isEnabled = !busy
            btnRandom.isEnabled = !busy
            btnVariation.isEnabled = !busy
            pbLoading.visibility = if (busy) View.VISIBLE else View.GONE
            if (!busy) btnSave.isEnabled = currentBitmap != null
            if (!busy) btnUpscale.isEnabled = currentBitmap != null && !upscaling.get()
        }
    }
}
