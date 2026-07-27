package com.frameyu.app.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 从视频候选时间点导出静态 JPEG 或 WebP 到系统相册。 */
class StillImageExporter(private val context: Context) {
    suspend fun exportCandidate(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        format: StillImageFormat = StillImageFormat.JPEG,
        cropRect: CropRect? = null,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val sourceBitmap = retrieveFrame(media.uri, candidate.timestampMillis)
            ?: error("无法读取候选帧画面")
        val canPreserveHdr = format == StillImageFormat.JPEG &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            media.isHdr && sourceBitmap.hasGainmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, buildStillImageName(media, candidate, format))
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            MetadataCopier.copyTo(
                this,
                LocalMetadata(
                    capturedAtMillis = media.takenAtMillis,
                    width = media.width,
                    height = media.height,
                    rotationDegrees = media.rotationDegrees,
                    isHdr = canPreserveHdr,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/帧遇")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                sourceBitmap.recycle()
                error("无法创建相册导出文件")
            }

        try {
            val bitmap = cropBitmap(sourceBitmap, cropRect)
            try {
                resolver.openOutputStream(destination)?.use { output ->
                    check(bitmap.compress(format.compressFormat, format.quality, output)) {
                        "候选帧压缩失败"
                    }
                } ?: error("无法打开相册导出文件")
            } finally {
                bitmap.recycle()
                if (bitmap !== sourceBitmap) sourceBitmap.recycle()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            destination
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun cropBitmap(source: Bitmap, cropRect: CropRect?): Bitmap {
        if (cropRect == null) return source
        val left = (source.width * cropRect.left).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * cropRect.top).toInt().coerceIn(0, source.height - 1)
        val right = (source.width * cropRect.right).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * cropRect.bottom).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun retrieveFrame(uri: String, timestampMillis: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.getFrameAtTime(
                timestampMillis.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            )
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val MIME_TYPE_JPEG = "image/jpeg"
        const val MIME_TYPE_WEBP = "image/webp"
        const val JPEG_QUALITY = 100

        internal fun buildStillImageName(
            media: MediaAssetEntity,
            candidate: AnalysisCandidateEntity,
            format: StillImageFormat = StillImageFormat.JPEG,
        ): String {
            val baseName = media.uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "视频" }
            return String.format(
                Locale.ROOT,
                "%s-帧-%06d.%s",
                baseName,
                candidate.timestampMillis.coerceAtLeast(0L),
                format.extension,
            )
        }
    }
}

enum class StillImageFormat(
    val mimeType: String,
    val extension: String,
    val compressFormat: Bitmap.CompressFormat,
    val quality: Int,
) {
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, 100),
    WEBP("image/webp", "webp", Bitmap.CompressFormat.WEBP, 100),
}
