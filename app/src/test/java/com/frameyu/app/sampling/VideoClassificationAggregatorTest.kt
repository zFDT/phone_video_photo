package com.frameyu.app.sampling

import com.frameyu.app.vision.LabelScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoClassificationAggregatorTest {
    @Test
    fun `偶发单帧标签不会覆盖持续标签`() {
        val result = VideoClassificationAggregator().aggregate(
            listOf(
                TimedLabels(0L, listOf(LabelScore("concert", 0.8f))),
                TimedLabels(1_000L, listOf(LabelScore("concert", 0.9f))),
                TimedLabels(2_000L, listOf(LabelScore("bird", 0.99f))),
            ),
        )

        assertEquals("concert", result.first().label)
    }

    @Test
    fun `同一帧重复标签只使用最高置信度`() {
        val result = VideoClassificationAggregator().aggregate(
            listOf(
                TimedLabels(
                    0L,
                    listOf(
                        LabelScore("concert", 0.2f),
                        LabelScore("concert", 0.8f),
                    ),
                ),
                TimedLabels(1_000L, listOf(LabelScore("concert", 0.6f))),
            ),
        ).single()

        assertEquals(1f, result.coverage, 0.0001f)
        assertEquals(0.7f, result.averageConfidence, 0.0001f)
        assertEquals(0.8f, result.maxConfidence, 0.0001f)
        assertEquals(0.87f, result.score, 0.0001f)
    }

    @Test
    fun `空标签帧计入覆盖率分母且百分之二十边界可进入`() {
        val result = VideoClassificationAggregator().aggregate(
            listOf(
                TimedLabels(0L, listOf(LabelScore("stage", 0.9f))),
                TimedLabels(1_000L, emptyList()),
                TimedLabels(2_000L, emptyList()),
                TimedLabels(3_000L, emptyList()),
                TimedLabels(4_000L, emptyList()),
            ),
        ).single()

        assertEquals(0.2f, result.coverage, 0.0001f)
        assertTrue(
            VideoClassificationAggregator().aggregate(
                listOf(
                    TimedLabels(0L, listOf(LabelScore("stage", 0.9f))),
                    TimedLabels(1_000L, emptyList()),
                    TimedLabels(2_000L, emptyList()),
                    TimedLabels(3_000L, emptyList()),
                    TimedLabels(4_000L, emptyList()),
                    TimedLabels(5_000L, emptyList()),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `总采样帧不足两帧时允许单帧标签`() {
        val result = VideoClassificationAggregator().aggregate(
            listOf(TimedLabels(0L, listOf(LabelScore("solo", 0.75f)))),
        )

        assertEquals(listOf("solo"), result.map(AggregatedVideoLabel::label))
    }

    @Test
    fun `同分标签按名称稳定排序`() {
        val result = VideoClassificationAggregator().aggregate(
            listOf(
                TimedLabels(
                    0L,
                    listOf(
                        LabelScore("zebra", 0.8f),
                        LabelScore("apple", 0.8f),
                    ),
                ),
            ),
        )

        assertEquals(listOf("apple", "zebra"), result.map(AggregatedVideoLabel::label))
    }
}
