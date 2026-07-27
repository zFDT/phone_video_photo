package com.frameyu.app.vision

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** 受限解码后的 Bitmap 由此包装器独占，close 可重复调用。 */
class DecodedMediaImage internal constructor(
    val bitmap: Bitmap,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true) && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

/** 以两次流读取完成图片 bounds 探测和受限尺寸解码。 */
class MediaImageDecoder(
    private val contentResolver: ContentResolver,
) {
    fun decode(uri: Uri, maxEdge: Int = DEFAULT_MAX_EDGE): DecodedMediaImage {
        require(maxEdge > 0) { "图片解码最大边长必须大于 0" }
        var ownedBitmap: Bitmap? = null

        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).orThrow(uri, "无法打开图片 bounds 流").use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "无法读取图片尺寸：$uri"
            }
            val orientation = readExifOrientation(uri)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val decoded = contentResolver.openInputStream(uri).orThrow(uri, "无法打开图片解码流").use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: error("BitmapFactory 无法解码图片：$uri")
            ownedBitmap = decoded

            val oriented = applyExifOrientation(decoded, orientation)
            if (oriented !== decoded) {
                decoded.recycle()
                ownedBitmap = oriented
            }
            val result = DecodedMediaImage(requireNotNull(ownedBitmap))
            ownedBitmap = null
            return result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            throw IllegalStateException(
                "解码图片失败（URI：$uri）：${error.message ?: "未知错误"}",
                error,
            )
        } finally {
            ownedBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun readExifOrientation(uri: Uri): Int {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("无法打开图片 EXIF 文件描述符：$uri")
        return descriptor.use {
            ExifInterface(it.fileDescriptor).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val transform = exifTransformFor(orientation)
        if (transform == ExifBitmapTransform.IDENTITY) return source
        val matrix = Matrix().apply {
            setRotate(transform.rotationDegrees)
            postScale(transform.scaleX, transform.scaleY)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private companion object {
        const val DEFAULT_MAX_EDGE = 1_024
    }
}

internal fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    require(width > 0 && height > 0) { "原图宽高必须大于 0" }
    require(maxEdge > 0) { "图片解码最大边长必须大于 0" }
    val longestEdge = maxOf(width, height).toLong()
    var sampleSize = 1
    while ((longestEdge + sampleSize - 1L) / sampleSize > maxEdge) {
        sampleSize = Math.multiplyExact(sampleSize, 2)
    }
    return sampleSize
}

internal data class ExifBitmapTransform(
    val rotationDegrees: Float,
    val scaleX: Float,
    val scaleY: Float,
) {
    companion object {
        val IDENTITY = ExifBitmapTransform(0f, 1f, 1f)
    }
}

internal fun exifTransformFor(orientation: Int): ExifBitmapTransform = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifBitmapTransform(0f, -1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifBitmapTransform(180f, 1f, 1f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifBitmapTransform(180f, -1f, 1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifBitmapTransform(90f, -1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifBitmapTransform(90f, 1f, 1f)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifBitmapTransform(-90f, -1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifBitmapTransform(-90f, 1f, 1f)
    else -> ExifBitmapTransform.IDENTITY
}

private fun <Value : Any> Value?.orThrow(uri: Uri, message: String): Value =
    this ?: throw IllegalStateException("$message：$uri")
