package com.frameyu.app.organize

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max

/** 从 Android 媒体 Uri 读取缩略图并计算感知哈希。 */
class AndroidVisualHashReader(
    private val contentResolver: ContentResolver,
) : VisualHashReader {
    override suspend fun readHash(uri: String): Long {
        val mediaUri = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(mediaUri, uri, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("无法读取媒体尺寸：$uri")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = decode(mediaUri, uri, options) ?: error("无法解码媒体：$uri")
        var scaled: Bitmap? = null
        try {
            scaled = if (decoded.width == HASH_SIZE && decoded.height == HASH_SIZE) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, HASH_SIZE, HASH_SIZE, true)
            }
            val pixels = IntArray(HASH_SIZE * HASH_SIZE)
            scaled.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
            return PerceptualHash.fromPixels(HASH_SIZE, HASH_SIZE, pixels)
        } catch (cause: Exception) {
            throw IllegalStateException("无法读取媒体像素：$uri", cause)
        } finally {
            if (scaled != null && scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun decode(
        mediaUri: Uri,
        source: String,
        options: BitmapFactory.Options,
    ): Bitmap? {
        val input = try {
            contentResolver.openInputStream(mediaUri)
        } catch (cause: Exception) {
            throw IllegalStateException("无法打开媒体：$source", cause)
        } ?: error("无法打开媒体：$source")

        return try {
            input.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (cause: Exception) {
            throw IllegalStateException("无法读取媒体：$source", cause)
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        val maxDimension = max(width, height).toLong()
        var sampleSize = 1
        while ((maxDimension + sampleSize - 1) / sampleSize > MAX_DECODE_EDGE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val MAX_DECODE_EDGE = 512
        const val HASH_SIZE = 32
    }
}
