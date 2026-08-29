package com.example.aiphotoapp

import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        worksDir = File(filesDir, "works").apply { mkdirs() }

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(scroll)

        title = "我的作品"
        loadRecords()
    }

    private fun loadRecords() {
        container.removeAllViews()
        val index = File(worksDir, "index.json")
        val arr = if (index.exists()) JSONArray(index.readText()) else JSONArray()

        if (arr.length() == 0) {
            container.addView(TextView(this).apply {
                text = "还没有作品，去生成第一张吧！"
                setPadding(48, 120, 48, 48)
                textSize = 16f
            })
            return
        }

        for (i in 0 until arr.length()) {
            buildCard(arr.getJSONObject(i))
        }
    }

    private fun buildCard(obj: JSONObject) {
        val ts = obj.getLong("ts")
        val idea = obj.optString("idea", "")
        val file = File(worksDir, obj.optString("file", ""))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
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
        })
        textCol.addView(TextView(this).apply {
            text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
            textSize = 12f
        })

        card.addView(textCol)

        card.setOnClickListener {
            showPreview(obj)
        }

        val sep = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        sep.setMargins(dp(8), 0, dp(8), dp(4))
        container.addView(card)
        container.addView(android.view.View(this), sep)
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
        })
        view.addView(TextView(this).apply {
            text = "提示词：$prompt"
            setPadding(0, dp(6), 0, 0)
            textSize = 11f
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val btnSave = Button(this)
        btnSave.text = "保存到相册"
        val btnDelete = Button(this)
        btnDelete.text = "删除"

        val saveLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val delLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        delLp.marginStart = dp(12)
        btnSave.layoutParams = saveLp
        btnDelete.layoutParams = delLp
        btnRow.addView(btnSave)
        btnRow.addView(btnDelete)
        view.addView(btnRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle("作品详情")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()

        btnSave.setOnClickListener {
            val bitmap = decodeSampled(file, 2048)
            if (bitmap != null) {
                saveBitmapToGallery(bitmap)
            }
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