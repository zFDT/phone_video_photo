package com.frameyu.app.organize

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** 纯 Kotlin 感知哈希，输入应为已缩放到较小尺寸的 ARGB 像素。 */
object PerceptualHash {
    private const val SAMPLE_SIZE = 32
    private const val DCT_SIZE = 8
    private val cosineTable = Array(DCT_SIZE) { frequency ->
        DoubleArray(SAMPLE_SIZE) { position ->
            cos((2 * position + 1) * frequency * PI / (2 * SAMPLE_SIZE))
        }
    }
    private val normalization = DoubleArray(DCT_SIZE) { frequency ->
        if (frequency == 0) 1.0 / sqrt(2.0) else 1.0
    }

    fun fromPixels(width: Int, height: Int, pixels: IntArray): Long {
        require(width > 0 && height > 0) { "像素尺寸必须大于零" }
        require(pixels.size == width * height) { "像素数组尺寸不匹配" }

        val samples = DoubleArray(SAMPLE_SIZE * SAMPLE_SIZE)
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val sourceX = ((x + 0.5) * width / SAMPLE_SIZE).toInt().coerceIn(0, width - 1)
                val sourceY = ((y + 0.5) * height / SAMPLE_SIZE).toInt().coerceIn(0, height - 1)
                val color = pixels[sourceY * width + sourceX]
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                samples[y * SAMPLE_SIZE + x] =
                    (299 * red + 587 * green + 114 * blue) / 1_000.0
            }
        }
        val average = samples.average()
        samples.indices.forEach { index -> samples[index] -= average }

        val coefficients = DoubleArray(DCT_SIZE * DCT_SIZE - 1)
        var coefficientIndex = 0
        for (v in 0 until DCT_SIZE) {
            for (u in 0 until DCT_SIZE) {
                if (u == 0 && v == 0) continue
                var coefficient = 0.0
                for (y in 0 until SAMPLE_SIZE) {
                    val verticalCosine = cosineTable[v][y]
                    for (x in 0 until SAMPLE_SIZE) {
                        coefficient += samples[y * SAMPLE_SIZE + x] *
                            cosineTable[u][x] *
                            verticalCosine
                    }
                }
                coefficients[coefficientIndex] =
                    coefficient * normalization[u] * normalization[v]
                coefficientIndex += 1
            }
        }
        val median = coefficients.sortedArray()[coefficients.size / 2]
        return coefficients.foldIndexed(0L) { index, hash, coefficient ->
            if (coefficient > median) hash or (1L shl index) else hash
        }
    }

    fun hammingDistance(left: Long, right: Long): Int = java.lang.Long.bitCount(left xor right)
}
