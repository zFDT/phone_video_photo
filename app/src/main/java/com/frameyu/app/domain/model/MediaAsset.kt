package com.frameyu.app.domain.model

/** 本地媒体索引中的媒体资源。所有时间字段均使用毫秒。 */
data class MediaAsset(
    val id: Long,
    val uri: String,
    val mimeType: String,
    val takenAtMillis: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sizeBytes: Long,
    val isHdr: Boolean,
    val relativePath: String?,
    val lastModifiedMillis: Long,
) {
    init {
        require(id >= 0) { "媒体 ID 不能为负数" }
        require(uri.isNotBlank()) { "媒体 URI 不能为空" }
        require(mimeType.isNotBlank()) { "媒体 MIME 类型不能为空" }
        require(takenAtMillis >= 0) { "拍摄时间不能为负数" }
        require(durationMillis >= 0) { "媒体时长不能为负数" }
        require(width >= 0) { "媒体宽度不能为负数" }
        require(height >= 0) { "媒体高度不能为负数" }
        require(rotationDegrees in 0..359) { "旋转角度必须在 0 到 359 度之间" }
        require(sizeBytes >= 0) { "媒体大小不能为负数" }
        require(lastModifiedMillis >= 0) { "修改时间不能为负数" }
    }
}
