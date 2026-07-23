package com.frameyu.app.analysis

/** 供本地基础分析使用的单帧像素样本。像素采用 ARGB 格式，时间单位为毫秒。 */
class FrameSample(
    val frameIndex: Int,
    val timestampMillis: Long,
    val width: Int,
    val height: Int,
    pixels: IntArray,
) {
    private val pixelData: IntArray = pixels.copyOf()
    val pixelCount: Int get() = pixelData.size

    init {
        require(frameIndex >= 0) { "帧序号不能为负数" }
        require(timestampMillis >= 0L) { "帧时间戳不能为负数" }
        require(width > 0) { "帧宽度必须大于零" }
        require(height > 0) { "帧高度必须大于零" }
        val expectedPixelCount = width.toLong() * height.toLong()
        require(expectedPixelCount <= Int.MAX_VALUE && pixelData.size.toLong() == expectedPixelCount) {
            "像素数量必须与帧尺寸匹配"
        }
    }

    /** 返回指定像素的感知亮度，范围为 0 到 255。 */
    fun luminanceAt(index: Int): Double {
        require(index in pixelData.indices) { "像素索引超出范围" }
        val pixel = pixelData[index]
        val red = (pixel ushr 16) and 0xFF
        val green = (pixel ushr 8) and 0xFF
        val blue = pixel and 0xFF
        return red * RED_WEIGHT + green * GREEN_WEIGHT + blue * BLUE_WEIGHT
    }

    /** 返回所有像素的感知亮度，避免分析器重复拆解 ARGB 通道。 */
    fun luminances(): DoubleArray = DoubleArray(pixelData.size) { luminanceAt(it) }

    companion object {
        private const val RED_WEIGHT = 0.299
        private const val GREEN_WEIGHT = 0.587
        private const val BLUE_WEIGHT = 0.114
    }
}
