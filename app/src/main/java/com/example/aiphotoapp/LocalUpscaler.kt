package com.example.aiphotoapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class LocalUpscaler(context: Context) : AutoCloseable {
    private val interpreter = Interpreter(loadModel(context), Interpreter.Options().setNumThreads(4))

    fun upscale(source: Bitmap): Bitmap {
        val maxInputEdge = 1024
        val ratio = minOf(1f, maxInputEdge.toFloat() / maxOf(source.width, source.height))
        val input = if (ratio < 1f) Bitmap.createScaledBitmap(
            source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true
        ) else source
        val output = Bitmap.createBitmap(input.width * 4, input.height * 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val inputTile = Array(1) { Array(50) { Array(50) { FloatArray(3) } } }
        val outputTile = Array(1) { Array(200) { Array(200) { FloatArray(3) } } }

        for (top in 0 until input.height step 50) {
            for (left in 0 until input.width step 50) {
                val tileWidth = minOf(50, input.width - left)
                val tileHeight = minOf(50, input.height - top)
                val tile = Bitmap.createBitmap(input, left, top, tileWidth, tileHeight)
                val resized = Bitmap.createScaledBitmap(tile, 50, 50, true)
                tile.recycle()
                for (y in 0 until 50) for (x in 0 until 50) {
                    val pixel = resized.getPixel(x, y)
                    inputTile[0][y][x][0] = Color.red(pixel) / 255f
                    inputTile[0][y][x][1] = Color.green(pixel) / 255f
                    inputTile[0][y][x][2] = Color.blue(pixel) / 255f
                }
                resized.recycle()
                interpreter.run(inputTile, outputTile)
                val pixels = IntArray(200 * 200)
                for (y in 0 until 200) for (x in 0 until 200) {
                    val value = outputTile[0][y][x]
                    pixels[y * 200 + x] = Color.rgb(channel(value[0]), channel(value[1]), channel(value[2]))
                }
                val upTile = Bitmap.createBitmap(pixels, 200, 200, Bitmap.Config.ARGB_8888)
                canvas.drawBitmap(upTile, left * 4f, top * 4f, null)
                upTile.recycle()
            }
        }
        if (input !== source) input.recycle()
        return output
    }

    override fun close() = interpreter.close()

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt()

    private fun loadModel(context: Context): MappedByteBuffer {
        val descriptor = context.assets.openFd("esrgan.tflite")
        return descriptor.createInputStream().channel.map(
            FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength
        )
    }
}
