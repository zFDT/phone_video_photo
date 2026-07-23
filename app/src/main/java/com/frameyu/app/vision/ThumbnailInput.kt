package com.frameyu.app.vision

import android.graphics.Bitmap

/** 图像分析只接受已经解码好的缩略图，不负责读取原始媒体。 */
data class ThumbnailDimensions(
    val width: Int,
    val height: Int,
    val pixelCount: Long,
)

/** 缩略图输入约束，避免把演唱会长视频中的原始 4K 帧直接交给视觉模型。 */
object ThumbnailConstraints {
    const val MAX_EDGE = 1024
    const val MAX_PIXELS = 1_048_576L

    fun pixelCount(width: Int, height: Int): Long = width.toLong() * height.toLong()

    fun validate(width: Int, height: Int): ThumbnailDimensions {
        require(width > 0 && height > 0) { "缩略图宽高必须大于零" }
        require(width <= MAX_EDGE && height <= MAX_EDGE) {
            "缩略图单边不能超过 ${MAX_EDGE}px"
        }
        val pixels = pixelCount(width, height)
        require(pixels <= MAX_PIXELS) {
            "缩略图像素数不能超过 ${MAX_PIXELS}"
        }
        return ThumbnailDimensions(width, height, pixels)
    }
}

/** 视觉模型统一输入。调用方应在解码阶段使用采样参数生成缩略图。 */
class ThumbnailInput private constructor(
    val bitmap: Bitmap,
    val dimensions: ThumbnailDimensions,
) {
    init {
        require(!bitmap.isRecycled) { "缩略图 Bitmap 已被回收" }
    }

    companion object {
        fun fromBitmap(bitmap: Bitmap): ThumbnailInput {
            return ThumbnailInput(
                bitmap = bitmap,
                dimensions = ThumbnailConstraints.validate(bitmap.width, bitmap.height),
            )
        }
    }
}
