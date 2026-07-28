package com.frameyu.app.sampling

import com.frameyu.app.vision.LabelScore

const val VIDEO_AGGREGATE_TIMESTAMP_MILLIS = -1L

data class TimedLabels(
    val timestampMillis: Long,
    val labels: List<LabelScore>,
) {
    init {
        require(timestampMillis >= 0L) { "逐帧分类时间戳不能为负数" }
    }
}

data class AggregatedVideoLabel(
    val label: String,
    val score: Float,
    val coverage: Float,
    val averageConfidence: Float,
    val maxConfidence: Float,
    val frameCount: Int,
)

/** 使用覆盖率和置信度把逐帧分类结果聚合为视频级标签。 */
class VideoClassificationAggregator {
    fun aggregate(frames: List<TimedLabels>): List<AggregatedVideoLabel> {
        if (frames.isEmpty()) return emptyList()
        require(frames.map(TimedLabels::timestampMillis).distinct().size == frames.size) {
            "同一采样时间戳只能出现一次"
        }

        val confidencesByLabel = linkedMapOf<String, MutableList<Float>>()
        frames.forEach { frame ->
            frame.labels
                .groupBy(LabelScore::label)
                .forEach { (label, duplicates) ->
                    confidencesByLabel.getOrPut(label, ::mutableListOf) +=
                        duplicates.maxOf(LabelScore::score)
                }
        }

        val totalFrameCount = frames.size
        return confidencesByLabel.mapNotNull { (label, confidences) ->
            val coverage = confidences.size.toFloat() / totalFrameCount.toFloat()
            if (totalFrameCount >= MIN_MULTI_FRAME_SAMPLE_COUNT &&
                confidences.size < MIN_LABEL_FRAME_COUNT &&
                coverage < MIN_COVERAGE
            ) {
                return@mapNotNull null
            }
            val averageConfidence = confidences.average().toFloat()
            val maxConfidence = confidences.max()
            AggregatedVideoLabel(
                label = label,
                score = COVERAGE_WEIGHT * coverage +
                    AVERAGE_CONFIDENCE_WEIGHT * averageConfidence +
                    MAX_CONFIDENCE_WEIGHT * maxConfidence,
                coverage = coverage,
                averageConfidence = averageConfidence,
                maxConfidence = maxConfidence,
                frameCount = confidences.size,
            )
        }.sortedWith(
            compareByDescending<AggregatedVideoLabel>(AggregatedVideoLabel::score)
                .thenBy(AggregatedVideoLabel::label),
        )
    }

    private companion object {
        const val COVERAGE_WEIGHT = 0.5f
        const val AVERAGE_CONFIDENCE_WEIGHT = 0.3f
        const val MAX_CONFIDENCE_WEIGHT = 0.2f
        const val MIN_COVERAGE = 0.2f
        const val MIN_LABEL_FRAME_COUNT = 2
        const val MIN_MULTI_FRAME_SAMPLE_COUNT = 2
    }
}

interface VideoClassificationAggregationStore {
    suspend fun loadFrameLabels(
        mediaId: Long,
        modelSha256: String,
    ): List<TimedLabels>

    suspend fun replaceAggregateLabels(
        mediaId: Long,
        modelSha256: String,
        timestampMillis: Long,
        labels: List<AggregatedVideoLabel>,
    )
}

/** 按当前计划重建聚合输入，历史已落库帧和空标签帧都会参与计算。 */
class VideoClassificationAggregationCoordinator(
    private val store: VideoClassificationAggregationStore,
    private val aggregator: VideoClassificationAggregator = VideoClassificationAggregator(),
) {
    suspend fun rebuildAndSave(
        mediaId: Long,
        modelSha256: String,
        plannedTimestampsMillis: List<Long>,
    ): List<AggregatedVideoLabel> {
        require(mediaId > 0L) { "媒体 ID 必须大于 0" }
        require(plannedTimestampsMillis.all { it >= 0L }) { "计划时间戳不能为负数" }
        val plannedTimestamps = plannedTimestampsMillis.distinct()
        val plannedTimestampSet = plannedTimestamps.toHashSet()
        val labelsByTimestamp = store.loadFrameLabels(mediaId, modelSha256)
            .asSequence()
            .filter { it.timestampMillis in plannedTimestampSet }
            .groupBy(TimedLabels::timestampMillis)
            .mapValues { (_, frames) -> frames.flatMap(TimedLabels::labels) }
        val aggregated = aggregator.aggregate(
            plannedTimestamps.map { timestampMillis ->
                TimedLabels(timestampMillis, labelsByTimestamp[timestampMillis].orEmpty())
            },
        )
        store.replaceAggregateLabels(
            mediaId = mediaId,
            modelSha256 = modelSha256,
            timestampMillis = VIDEO_AGGREGATE_TIMESTAMP_MILLIS,
            labels = aggregated,
        )
        return aggregated
    }
}
