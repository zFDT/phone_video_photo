package com.frameyu.app.export

import android.net.Uri
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.MediaAssetEntity

/** 动态照片导出的结果；不兼容厂商容器时明确返回静态图和 MP4 兜底资源。 */
data class MotionPhotoExportResult(
    val stillImageUri: Uri,
    val videoUri: Uri,
    val isMotionPhotoContainer: Boolean,
    val fallbackReason: String? = null,
)

/**
 * 动态照片导出协调器。
 *
 * Redmi/HyperOS 的动态照片容器格式需要以真机样本确认，当前先提供不会丢失内容的
 * 静态图 + MP4 兜底导出，并通过结果字段避免把普通图片误标成动态照片。
 */
class MotionPhotoExporter(
    private val stillImageExporter: StillImageExporter,
    private val clipExporter: VideoClipExporter,
) {
    suspend fun exportCandidate(
        media: MediaAssetEntity,
        candidate: AnalysisCandidateEntity,
        clipDurationMillis: Long = VideoClipExporter.DEFAULT_CLIP_DURATION_MILLIS,
        cropRect: CropRect? = null,
    ): MotionPhotoExportResult {
        val stillImageUri = stillImageExporter.exportCandidate(
            media = media,
            candidate = candidate,
            cropRect = cropRect,
        )
        return try {
            val videoUri = clipExporter.exportCandidate(media, candidate, clipDurationMillis)
            MotionPhotoExportResult(
                stillImageUri = stillImageUri,
                videoUri = videoUri,
                isMotionPhotoContainer = false,
                fallbackReason = "尚未确认 Redmi 动态照片容器格式，已导出静态图和 MP4 兜底文件",
            )
        } catch (error: Throwable) {
            throw error
        }
    }
}
