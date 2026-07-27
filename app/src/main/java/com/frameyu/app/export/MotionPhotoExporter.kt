package com.frameyu.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 动态照片导出的结果；不兼容厂商容器时明确返回静态图和 MP4 兜底资源。 */
data class MotionPhotoExportResult(
    val stillImageUri: Uri,
    val videoUri: Uri?,
    val isMotionPhotoContainer: Boolean,
    val motionPhotoUri: Uri? = null,
    val fallbackReason: String? = null,
)

/**
 * 动态照片导出协调器。
 *
 * 先生成应用自己的 JPEG/MP4，再按 Android Motion Photo 1.0 追加容器；最终仍需
 * 在 Redmi/HyperOS 真机相册验证显示和编辑兼容性，失败时保留两个独立资源兜底。
 */
class MotionPhotoExporter(
    private val context: Context,
    private val stillImageExporter: StillImageExporter,
    private val clipExporter: VideoClipExporter,
) {
    suspend fun exportCandidate(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        clipDurationMillis: Long = VideoClipExporter.DEFAULT_CLIP_DURATION_MILLIS,
        cropRect: CropRect? = null,
    ): MotionPhotoExportResult = withContext(Dispatchers.IO) {
        val stillImageUri = stillImageExporter.exportCandidate(
            media = media,
            candidate = candidate,
            cropRect = cropRect,
        )
        val videoUri = clipExporter.exportCandidate(media, candidate, clipDurationMillis)
        val composed = runCatching {
            val jpeg = context.contentResolver.openInputStream(stillImageUri)?.use { it.readBytes() }
                ?: error("无法读取动态照片主图")
            val mp4 = context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
                ?: error("无法读取动态照片视频")
            MotionPhotoComposer.compose(jpeg, mp4)
        }.getOrElse {
            return@withContext MotionPhotoExportResult(
                stillImageUri = stillImageUri,
                videoUri = videoUri,
                isMotionPhotoContainer = false,
                fallbackReason = "动态照片容器生成失败，已保留静态图和 MP4：${it.message ?: "未知错误"}",
            )
        }
        val motionPhotoUri = publishMotionPhoto(media, candidate, composed)
        context.contentResolver.delete(stillImageUri, null, null)
        context.contentResolver.delete(videoUri, null, null)
        MotionPhotoExportResult(
            stillImageUri = motionPhotoUri,
            videoUri = null,
            isMotionPhotoContainer = true,
            motionPhotoUri = motionPhotoUri,
        )
    }

    private fun publishMotionPhoto(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        bytes: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val destination = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, buildMotionPhotoName(media, candidate))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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
        ) ?: error("无法创建动态照片文件")
        try {
            resolver.openOutputStream(destination)?.use { it.write(bytes) }
                ?: error("无法写入动态照片文件")
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

    private fun buildMotionPhotoName(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
    ): String {
        val baseName = media.uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "视频" }
        return String.format(Locale.ROOT, "%s-MP-%06d.jpg", baseName, candidate.timestampMillis.coerceAtLeast(0L))
    }
}
