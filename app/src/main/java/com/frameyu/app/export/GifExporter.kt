package com.frameyu.app.export

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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt

internal data class PixelCropBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun calculateCropBounds(
    width: Int,
    height: Int,
    cropRect: CropRect?,
): PixelCropBounds {
    require(width > 0 && height > 0) { "帧尺寸必须大于 0" }
    if (cropRect == null) {
        return PixelCropBounds(left = 0, top = 0, right = width, bottom = height)
    }

    val bounds = PixelCropBounds(
        left = (cropRect.left * width).roundToInt(),
        top = (cropRect.top * height).roundToInt(),
        right = (cropRect.right * width).roundToInt(),
        bottom = (cropRect.bottom * height).roundToInt(),
    )
    require(bounds.right > bounds.left && bounds.bottom > bounds.top) {
        "裁切区域在当前帧分辨率下为空"
    }
    return bounds
}

internal fun createGifFrameTimestamps(window: ClipWindow, fps: Int = GifExporter.DEFAULT_FPS): List<Long> {
    require(fps > 0) { "GIF 帧率必须大于 0" }
    val step = 1_000L / fps
    return generateSequence(window.startMillis) { timestamp ->
        val next = timestamp + step
        if (next < window.endMillis) next else null
    }.toList().ifEmpty { listOf(window.startMillis) }
}

/** 从候选点附近的视频片段抽帧并编码为 SDR GIF。 */
class GifExporter(private val context: Context) {
    suspend fun exportCandidate(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        clipDurationMillis: Long = VideoClipExporter.DEFAULT_CLIP_DURATION_MILLIS,
        cropRect: CropRect? = null,
    ): Uri = withContext(Dispatchers.IO) {
        val window = createClipWindow(candidate.timestampMillis, media.durationMillis, clipDurationMillis)
        val temporaryFile = File.createTempFile("frameyu-gif-", ".gif", context.cacheDir)
        try {
            encodeGif(media.uri, window, temporaryFile, cropRect)
            publishGif(media, candidate, temporaryFile)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun encodeGif(uri: String, window: ClipWindow, destination: File, cropRect: CropRect?) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            FileOutputStream(destination).use { output ->
                var encoder: GifSequenceEncoder? = null
                try {
                    for (timestamp in createGifFrameTimestamps(window)) {
                        val source = retriever.getFrameAtTime(
                            timestamp * 1_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        ) ?: continue
                        val croppedFrame = cropFrame(source, cropRect)
                        if (croppedFrame !== source) source.recycle()
                        val frame = scaleFrame(croppedFrame)
                        if (frame !== croppedFrame) croppedFrame.recycle()
                        try {
                            if (encoder == null) {
                                encoder = GifSequenceEncoder(output, frame.width, frame.height)
                                encoder.start()
                            }
                            encoder.addFrame(frame, FRAME_DELAY_CENTISECONDS)
                        } finally {
                            frame.recycle()
                        }
                    }
                    check(encoder != null) { "无法从视频读取 GIF 帧" }
                    encoder.finish()
                } catch (error: Throwable) {
                    throw error
                }
            }
            check(destination.length() > 0L) { "无法从视频读取 GIF 帧" }
        } finally {
            retriever.release()
        }
    }

    private fun scaleFrame(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= MAX_EDGE) return source
        val scale = MAX_EDGE.toFloat() / maxEdge
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun cropFrame(source: Bitmap, cropRect: CropRect?): Bitmap {
        val bounds = calculateCropBounds(source.width, source.height, cropRect)
        if (bounds.left == 0 && bounds.top == 0 &&
            bounds.right == source.width && bounds.bottom == source.height
        ) {
            return source
        }
        return Bitmap.createBitmap(
            source,
            bounds.left,
            bounds.top,
            bounds.right - bounds.left,
            bounds.bottom - bounds.top,
        )
    }

    private fun publishGif(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        temporaryFile: File,
    ): Uri {
        val resolver = context.contentResolver
        val destination = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, buildGifName(media, candidate))
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE_GIF)
                MetadataCopier.copyTo(
                    this,
                    LocalMetadata(
                        capturedAtMillis = media.takenAtMillis,
                        width = media.width,
                        height = media.height,
                        rotationDegrees = media.rotationDegrees,
                    ),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/帧遇")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            },
        ) ?: error("无法创建 GIF 导出文件")
        try {
            resolver.openOutputStream(destination)?.use { output ->
                temporaryFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法打开 GIF 导出文件")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(destination, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            return destination
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    companion object {
        const val DEFAULT_FPS = 8
        const val MIME_TYPE_GIF = "image/gif"
        private const val MAX_EDGE = 480
        private const val FRAME_DELAY_CENTISECONDS = 100 / DEFAULT_FPS

        internal fun buildGifName(media: MediaAssetEntity, candidate: AnalysisCandidateEntity): String {
            val baseName = media.uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "视频" }
            return String.format(Locale.ROOT, "%s-帧-%06d.gif", baseName, candidate.timestampMillis.coerceAtLeast(0L))
        }
    }
}
