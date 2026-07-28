package com.frameyu.app.sampling

import com.frameyu.app.vision.LabelScore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoClassificationAggregationCoordinatorTest {
    @Test
    fun `恢复后聚合使用当前计划全部时间戳和历史结果`() = runBlocking {
        val modelSha = "a".repeat(64)
        val store = RecordingAggregationStore(
            persisted = listOf(
                TimedLabels(0L, listOf(LabelScore("concert", 0.8f))),
                TimedLabels(1_000L, listOf(LabelScore("concert", 0.9f))),
                TimedLabels(9_000L, listOf(LabelScore("stale", 1f))),
            ),
        )
        val coordinator = VideoClassificationAggregationCoordinator(store)

        val result = coordinator.rebuildAndSave(
            mediaId = 42L,
            modelSha256 = modelSha,
            plannedTimestampsMillis = listOf(0L, 1_000L, 2_000L),
        )

        assertEquals(listOf("concert"), result.map(AggregatedVideoLabel::label))
        assertEquals(2f / 3f, result.single().coverage, 0.0001f)
        assertEquals(result, store.saved)
        assertEquals(VIDEO_AGGREGATE_TIMESTAMP_MILLIS, store.savedTimestampMillis)
    }

    private class RecordingAggregationStore(
        private val persisted: List<TimedLabels>,
    ) : VideoClassificationAggregationStore {
        var saved: List<AggregatedVideoLabel> = emptyList()
        var savedTimestampMillis: Long? = null

        override suspend fun loadFrameLabels(
            mediaId: Long,
            modelSha256: String,
        ): List<TimedLabels> = persisted

        override suspend fun replaceAggregateLabels(
            mediaId: Long,
            modelSha256: String,
            timestampMillis: Long,
            labels: List<AggregatedVideoLabel>,
        ) {
            saved = labels
            savedTimestampMillis = timestampMillis
        }
    }
}
