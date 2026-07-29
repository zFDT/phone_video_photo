package com.frameyu.app.sampling

import java.security.MessageDigest

/** 按时间覆盖完整视频的采样计划，区间使用半开范围且不会重叠。 */
class SamplingPlan private constructor(
    val videoDurationMillis: Long,
    val ranges: List<SampleRange>,
) {
    val planId: String = sha256(
        "$SAMPLING_ALGORITHM_VERSION|$videoDurationMillis|${ranges.joinToString()}",
    )

    fun sampleTimestamps(): List<Long> = ranges.indices.flatMap(::sampleTimestamps)

    internal fun sampleTimestamps(rangeIndex: Int): List<Long> {
        require(rangeIndex in ranges.indices) { "采样区间索引超出范围" }
        val range = ranges[rangeIndex]
        val stepMillis = 1_000.0 / range.framesPerSecond
        return generateSequence(0L) { it + 1L }
            .map { range.samplingOriginMillis + (it * stepMillis).toLong() }
            .dropWhile { it < range.startMillis }
            .takeWhile { it < range.endMillis }
            .toList()
    }

    internal fun previousCanonicalTimestamp(checkpoint: SamplingCheckpoint): Long? {
        require(checkpoint.planId == planId) { "断点不属于当前采样计划" }
        require(checkpoint.rangeIndex in ranges.indices) { "断点区间索引超出范围" }
        val range = ranges[checkpoint.rangeIndex]
        val timestamps = sampleTimestamps(checkpoint.rangeIndex)
        val nextIndex = if (checkpoint.nextTimestampMillis == range.endMillis) {
            timestamps.size
        } else {
            require(range.contains(checkpoint.nextTimestampMillis)) { "断点时间不属于当前采样区间" }
            timestamps.indexOf(checkpoint.nextTimestampMillis).also { index ->
                require(index >= 0) { "断点时间不是当前采样算法生成的时间戳" }
            }
        }
        timestamps.getOrNull(nextIndex - 1)?.let { return it }
        for (rangeIndex in checkpoint.rangeIndex - 1 downTo 0) {
            sampleTimestamps(rangeIndex).lastOrNull()?.let { return it }
        }
        return null
    }

    fun remainingRanges(checkpoint: SamplingCheckpoint): List<SampleRange> {
        require(checkpoint.planId == planId) { "断点不属于当前采样计划" }
        require(checkpoint.rangeIndex < ranges.size) { "断点区间索引超出范围" }
        val current = ranges[checkpoint.rangeIndex]
        if (checkpoint.nextTimestampMillis == current.endMillis) {
            return ranges.drop(checkpoint.rangeIndex + 1)
        }
        require(current.contains(checkpoint.nextTimestampMillis)) { "断点时间不属于当前采样区间" }
        require(checkpoint.nextTimestampMillis in sampleTimestamps(checkpoint.rangeIndex)) {
            "断点时间不是当前采样算法生成的时间戳"
        }
        return buildList {
            add(current.copy(startMillis = checkpoint.nextTimestampMillis))
            addAll(ranges.drop(checkpoint.rangeIndex + 1))
        }
    }

    companion object {
        const val MAX_VIDEO_DURATION_MILLIS = 6 * 60 * 60 * 1_000L
        internal const val SAMPLING_ALGORITHM_VERSION = "canonical-origin-index-floor-v3"

        fun create(
            videoDurationMillis: Long,
            requestedRanges: List<SampleRange> = emptyList(),
            coarseFramesPerSecond: Double = SamplingTier.COARSE.defaultFramesPerSecond,
        ): SamplingPlan {
            require(videoDurationMillis > 0L && videoDurationMillis <= MAX_VIDEO_DURATION_MILLIS) {
                "视频时长必须在 1 毫秒到 6 小时之间"
            }
            require(coarseFramesPerSecond in SamplingTier.COARSE.minFramesPerSecond..SamplingTier.COARSE.maxFramesPerSecond) {
                "粗采样帧率必须在 ${SamplingTier.COARSE.minFramesPerSecond}～${SamplingTier.COARSE.maxFramesPerSecond} 帧/秒之间"
            }
            val focused = requestedRanges.sortedBy(SampleRange::startMillis)
            focused.forEach { range ->
                require(range.endMillis <= videoDurationMillis) { "重点采样区间不能超出视频时长" }
            }
            focused.zipWithNext().forEach { (left, right) ->
                require(left.endMillis <= right.startMillis) { "重点采样区间不能重叠" }
            }
            val result = mutableListOf<SampleRange>()
            var cursor = 0L
            focused.forEach { range ->
                if (cursor < range.startMillis) {
                    result += SampleRange(cursor, range.startMillis, coarseFramesPerSecond, SamplingTier.COARSE)
                }
                result += range
                cursor = range.endMillis
            }
            if (cursor < videoDurationMillis) {
                result += SampleRange(cursor, videoDurationMillis, coarseFramesPerSecond, SamplingTier.COARSE)
            }
            return SamplingPlan(videoDurationMillis, result)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
