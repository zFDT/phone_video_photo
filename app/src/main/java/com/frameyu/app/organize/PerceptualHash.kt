package com.frameyu.app.organize

import kotlin.math.cos
import kotlin.math.PI

/** 纯 Kotlin 感知哈希，输入应为已缩放到较小尺寸的 ARGB 像素。 */
object PerceptualHash {
    private const val DCT_SIZE = 8

    fun fromPixels(width: Int, height: Int, pixels: IntArray): Long {
        require(width > 0 && height > 0) { "像素尺寸必须大于零" }
        require(pixels.size == width * height) { "像素数组尺寸不匹配" }
        val samples = Array(DCT_SIZE) { DoubleArray(DCT_SIZE) }
        for (y in 0 until DCT_SIZE) {
            for (x in 0 until DCT_SIZE) {
                val sourceX = ((x + 0.5) * width / DCT_SIZE).toInt().coerceIn(0, width - 1)
                val sourceY = ((y + 0.5) * height / DCT_SIZE).toInt().coerceIn(0, height - 1)
                val color = pixels[sourceY * width + sourceX]
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                samples[y][x] = 0.299 * red + 0.587 * green + 0.114 * blue
            }
        }
        val coefficients = mutableListOf<Double>()
        for (v in 0 until DCT_SIZE) {
            for (u in 0 until DCT_SIZE) {
                if (u == 0 && v == 0) continue
                var coefficient = 0.0
                for (y in 0 until DCT_SIZE) {
                    for (x in 0 until DCT_SIZE) {
                        coefficient += samples[y][x] *
                            cos((2 * x + 1) * u * PI / (2 * DCT_SIZE)) *
                            cos((2 * y + 1) * v * PI / (2 * DCT_SIZE))
                    }
                }
                coefficients += coefficient
            }
        }
        val median = coefficients.sorted()[coefficients.size / 2]
        return coefficients.foldIndexed(0L) { index, hash, coefficient ->
            if (coefficient >= median) hash or (1L shl index) else hash
        }
    }

    fun hammingDistance(left: Long, right: Long): Int = java.lang.Long.bitCount(left xor right)
}
