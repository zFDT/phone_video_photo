package com.frameyu.app.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale

data class ClipWindow(val startMillis: Long, val endMillis: Long)

internal enum class VideoExportStrategy {
    NATIVE_MUX,
}

internal fun requireVideoExportStrategy(cropRect: CropRect?): VideoExportStrategy {
    if (cropRect != null) {
        throw UnsupportedOperationException("MP4 视频级裁切当前不支持")
    }
    return VideoExportStrategy.NATIVE_MUX
}

internal fun createClipWindow(
    centerMillis: Long,
    durationMillis: Long,
    clipDurationMillis: Long = 3_000L,
): ClipWindow {
    require(durationMillis >= 0L) { "视频时长不能为负数" }
    require(clipDurationMillis > 0L) { "片段时长必须大于 0" }
    val safeCenter = centerMillis.coerceIn(0L, durationMillis)
    val actualDuration = clipDurationMillis.coerceAtMost(durationMillis.coerceAtLeast(1L))
    val half = actualDuration / 2L
    val start = (safeCenter - half).coerceAtLeast(0L)
    val end = (start + actualDuration).coerceAtMost(durationMillis)
    return ClipWindow(start, end)
}

/** 使用原生复用器导出候选点附近的 MP4 片段，不重新编码视频帧。 */
class VideoClipExporter(private val context: Context) {
    suspend fun exportCandidate(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        clipDurationMillis: Long = DEFAULT_CLIP_DURATION_MILLIS,
        cropRect: CropRect? = null,
    ): Uri = withContext(Dispatchers.IO) {
        requireVideoExportStrategy(cropRect)
        val window = createClipWindow(candidate.timestampMillis, media.durationMillis, clipDurationMillis)
        val temporaryFile = File.createTempFile("frameyu-clip-", ".mp4", context.cacheDir)
        try {
            muxClip(media.uri, window, temporaryFile)
            publishClip(media, candidate, window, temporaryFile)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun muxClip(uri: String, window: ClipWindow, destination: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()
        var started = false
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[index] = muxer.addTrack(format)
                    extractor.selectTrack(index)
                }
            }
            check(trackMap.isNotEmpty()) { "视频没有可导出的音视频轨道" }
            muxer.start()
            started = true
            extractor.seekTo(window.startMillis * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L || sampleTimeUs > window.endMillis * 1_000L) break
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize > 0 && sampleTimeUs >= window.startMillis * 1_000L) {
                    info.set(0, sampleSize, sampleTimeUs - window.startMillis * 1_000L, extractor.sampleFlags)
                    muxer.writeSampleData(trackMap.getValue(trackIndex), buffer, info)
                }
                extractor.advance()
            }
        } finally {
            extractor.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }

    private fun publishClip(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        window: ClipWindow,
        temporaryFile: File,
    ): Uri {
        val resolver = context.contentResolver
        val destination = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, buildClipName(media, candidate))
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE_MP4)
                put(MediaStore.Video.Media.DURATION, window.endMillis - window.startMillis)
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
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/帧遇")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            },
        ) ?: error("无法创建 MP4 导出文件")
        try {
            resolver.openOutputStream(destination)?.use { output ->
                FileInputStream(temporaryFile).use { input -> input.copyTo(output) }
            } ?: error("无法打开 MP4 导出文件")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    destination,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return destination
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    companion object {
        const val DEFAULT_CLIP_DURATION_MILLIS = 3_000L
        const val MIME_TYPE_MP4 = "video/mp4"
        private const val BUFFER_SIZE_BYTES = 1 * 1024 * 1024

        internal fun buildClipName(media: MediaAssetEntity, candidate: AnalysisCandidateEntity): String {
            val baseName = media.uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "视频" }
            return String.format(Locale.ROOT, "%s-帧-%06d.mp4", baseName, candidate.timestampMillis.coerceAtLeast(0L))
        }
    }
}
