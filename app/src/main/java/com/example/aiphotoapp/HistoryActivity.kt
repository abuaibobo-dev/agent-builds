package com.example.aiphotoapp

import android.app.Dialog
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class HistoryActivity : AppCompatActivity() {

    private lateinit var worksDir: File
    private lateinit var container: LinearLayout
    private var favOnly = false
    private var searchText = ""
    private lateinit var etSearch: EditText
    private lateinit var btnFavToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        worksDir = File(filesDir, "works").apply { mkdirs() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F0F0F.toInt())
        }

        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(2))
        }
        etSearch = EditText(this).apply {
            hint = "搜描述 / 提示词"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF757575.toInt())
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchText = s.toString().trim()
                    loadRecords()
                }
            })
        }
        filterBar.addView(etSearch)

        btnFavToggle = Button(this).apply {
            text = "☆ 收藏"
            textSize = 13f
            setTextColor(0xFFB3B3B3.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            stateListAnimator = null
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                favOnly = !favOnly
                refreshFavButton()
                loadRecords()
            }
        }
        filterBar.addView(btnFavToggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })
        root.addView(filterBar)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        title = "我的作品"
        refreshFavButton()
        loadRecords()
    }

    private fun refreshFavButton() {
        btnFavToggle.text = if (favOnly) "★ 只看收藏" else "☆ 收藏"
        btnFavToggle.setTextColor(if (favOnly) 0xFF9E9EFF.toInt() else 0xFFB3B3B3.toInt())
    }

    private fun loadRecords() {
        container.removeAllViews()
        val index = File(worksDir, "index.json")
        val objArr = if (index.exists()) JSONArray(index.readText()) else JSONArray()

        val list = ArrayList<JSONObject>()
        for (i in 0 until objArr.length()) {
            val o = objArr.getJSONObject(i)
            if (favOnly && o.optInt("fav", 0) != 1) continue
            if (searchText.isNotEmpty()) {
                val hay = o.optString("idea", "") + " " + o.optString("prompt", "")
                if (!hay.contains(searchText, ignoreCase = true)) continue
            }
            list.add(o)
        }

        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "没有匹配的作品"
                setPadding(48, 120, 48, 48)
                textSize = 16f
                setTextColor(0xFFB3B3B3.toInt())
            })
            return
        }

        var lastDay = ""
        for (o in list) {
            val ts = o.getLong("ts")
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
            if (day != lastDay) {
                lastDay = day
                container.addView(TextView(this).apply {
                    text = day
                    textSize = 13f
                    setTextColor(0xFF9E9EFF.toInt())
                    setPadding(dp(4), dp(12), dp(4), dp(4))
                })
            }
            buildCard(o)
        }
    }

    private fun buildCard(obj: JSONObject) {
        val ts = obj.getLong("ts")
        val idea = obj.optString("idea", "")
        val file = File(worksDir, obj.optString("file", ""))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_card)
        }

        val thumb = ImageView(this)
        val thumbSize = dp(72)
        val lp = LinearLayout.LayoutParams(thumbSize, thumbSize)
        thumb.layoutParams = lp
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        if (file.exists()) {
            thumb.setImageBitmap(decodeSampled(file, 144))
        }
        card.addView(thumb)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val textLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textLp.marginStart = dp(10)
        textCol.layoutParams = textLp

        textCol.addView(TextView(this).apply {
            text = idea.ifEmpty { "(无描述)" }
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        })
        textCol.addView(TextView(this).apply {
            text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        })

        card.addView(textCol)

        val favBtn = Button(this).apply {
            text = if (obj.optInt("fav", 0) == 1) "★" else "☆"
            textSize = 18f
            setTextColor(if (obj.optInt("fav", 0) == 1) 0xFFE6C300.toInt() else 0xFF757575.toInt())
            setBackgroundResource(0)
            stateListAnimator = null
            setPadding(0, 0, 0, 0)
        }
        card.addView(favBtn)

        card.setOnClickListener {
            showPreview(obj)
        }

        favBtn.setOnClickListener { v ->
            v.isClickable = false
            Thread {
                try {
                    val index = File(worksDir, "index.json")
                    val arr = if (index.exists()) JSONArray(index.readText()) else JSONArray()
                    for (i in 0 until arr.length()) {
                        if (arr.getJSONObject(i).getLong("ts") == obj.getLong("ts")) {
                            val o = arr.getJSONObject(i)
                            if (o.optInt("fav", 0) == 1) o.remove("fav") else o.put("fav", 1)
                        }
                    }
                    index.writeText(arr.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                runOnUiThread { loadRecords() }
            }.start()
        }

        val sep = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        sep.setMargins(dp(8), 0, dp(8), dp(8))
        container.addView(card)
    }

    private fun showPreview(obj: JSONObject) {
        val idea = obj.optString("idea", "")
        val prompt = obj.optString("prompt", "")
        val file = File(worksDir, obj.optString("file", ""))

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val iv = ImageView(this)
        val ilp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360))
        iv.layoutParams = ilp
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        if (file.exists()) iv.setImageBitmap(decodeSampled(file, 1440))
        view.addView(iv)

        view.addView(TextView(this).apply {
            text = "想法：$idea"
            setPadding(0, dp(12), 0, 0)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        })
        view.addView(TextView(this).apply {
            text = "提示词：$prompt"
            setPadding(0, dp(6), 0, 0)
            textSize = 11f
            setTextColor(0xFFB3B3B3.toInt())
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val btnRedraw = Button(this)
        btnRedraw.text = "一键重绘"
        val btnSave = Button(this)
        btnSave.text = "保存到相册"
        val btnWall = Button(this)
        btnWall.text = "设壁纸"
        val btnFav = Button(this)
        btnFav.text = if (obj.optInt("fav", 0) == 1) "取消收藏" else "收藏"
        val btnDelete = Button(this)
        btnDelete.text = "删除"
        listOf(btnRedraw, btnSave, btnWall, btnFav, btnDelete).forEach { b ->
            b.setTextColor(0xFFFFFFFF.toInt())
            b.setBackgroundResource(R.drawable.bg_btn_outline)
            b.textSize = 13f
            b.stateListAnimator = null
        }
        btnFav.setTextColor(if (obj.optInt("fav", 0) == 1) 0xFFE6C300.toInt() else 0xFFFFFFFF.toInt())

        val redrawLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val saveLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val wallLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val favLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val delLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        saveLp.marginStart = dp(8)
        wallLp.marginStart = dp(8)
        favLp.marginStart = dp(8)
        delLp.marginStart = dp(8)
        btnRedraw.layoutParams = redrawLp
        btnSave.layoutParams = saveLp
        btnWall.layoutParams = wallLp
        btnFav.layoutParams = favLp
        btnDelete.layoutParams = delLp
        btnRow.addView(btnRedraw)
        btnRow.addView(btnSave)
        btnRow.addView(btnWall)
        btnRow.addView(btnFav)
        btnRow.addView(btnDelete)
        view.addView(btnRow)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        dialog.setContentView(view)
        @Suppress("DEPRECATION")
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.window?.setDimAmount(0.6f)
        view.setPadding(dp(18), dp(16), dp(18), dp(12))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 8)
        }
        titleRow.addView(TextView(this).apply {
            text = "作品详情"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(Button(this).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(0xFFB3B3B3.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            stateListAnimator = null
            setPadding(24, 8, 24, 8)
            setOnClickListener { dialog.dismiss() }
        })
        view.addView(titleRow, 0)

        btnRedraw.setOnClickListener {
            getSharedPreferences("gallery", MODE_PRIVATE)
                .edit()
                .putString("pending_ref", file.absolutePath)
                .apply()
            Toast.makeText(this, "已设为参考图，返回生成页", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            finish()
        }

        btnSave.setOnClickListener {
            val bitmap = decodeSampled(file, 2048)
            if (bitmap != null) {
                saveBitmapToGallery(bitmap)
            }
        }

        btnWall.setOnClickListener {
            val bitmap = decodeSampled(file, 4000)
            if (bitmap != null) {
                try {
                    android.app.WallpaperManager.getInstance(this).setBitmap(bitmap)
                    Toast.makeText(this, "壁纸已设置", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "设壁纸失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnFav.setOnClickListener {
            Thread {
                try {
                    val index = File(worksDir, "index.json")
                    val arr = JSONArray(index.readText())
                    for (i in 0 until arr.length()) {
                        if (arr.getJSONObject(i).getLong("ts") == obj.getLong("ts")) {
                            val o = arr.getJSONObject(i)
                            if (o.optInt("fav", 0) == 1) o.remove("fav") else o.put("fav", 1)
                        }
                    }
                    index.writeText(arr.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                runOnUiThread {
                    dialog.dismiss()
                    loadRecords()
                }
            }.start()
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
            loadRecords()
        }

        if (!file.exists()) btnSave.isEnabled = false
        dialog.show()
    }

    private fun saveBitmapToGallery(bitmap: android.graphics.Bitmap) {
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
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, outputStream)
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

    private fun decodeSampled(file: File, maxEdge: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}