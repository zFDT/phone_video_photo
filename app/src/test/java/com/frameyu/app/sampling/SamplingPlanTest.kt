package com.frameyu.app.sampling

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SamplingPlanTest {
    @Test
    fun `有效采样区间使用半开时间范围`() {
        val range = SampleRange(
            startMillis = 1_000L,
            endMillis = 3_000L,
            framesPerSecond = 8.0,
            tier = SamplingTier.FOCUSED,
        )

        assertEquals(1_000L, range.startMillis)
        assertEquals(3_000L, range.endMillis)
        assertEquals(2_000L, range.durationMillis)
        assertTrue(range.contains(1_000L))
        assertTrue(!range.contains(3_000L))
    }

    @Test
    fun `采样区间拒绝非法时间和帧率`() {
        assertThrows(IllegalArgumentException::class.java) {
            SampleRange(3_000L, 1_000L, 1.0, SamplingTier.COARSE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SampleRange(0L, 1_000L, 4.0, SamplingTier.FOCUSED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SampleRange(0L, 1_000L, 31.0, SamplingTier.HIGH_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SampleRange(0L, 1_000L, Double.NaN, SamplingTier.COARSE)
        }
    }

    @Test
    fun `规划器填充空白并按时间生成不重叠完整区间`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 90_000L,
            requestedRanges = listOf(
                SampleRange(60_000L, 62_000L, 24.0, SamplingTier.HIGH_VALUE),
                SampleRange(30_000L, 35_000L, 8.0, SamplingTier.FOCUSED),
            ),
        )

        assertEquals(
            listOf(
                SampleRange(0L, 30_000L, 1.0, SamplingTier.COARSE),
                SampleRange(30_000L, 35_000L, 8.0, SamplingTier.FOCUSED),
                SampleRange(35_000L, 60_000L, 1.0, SamplingTier.COARSE),
                SampleRange(60_000L, 62_000L, 24.0, SamplingTier.HIGH_VALUE),
                SampleRange(62_000L, 90_000L, 1.0, SamplingTier.COARSE),
            ),
            plan.ranges,
        )

        assertEquals(0L, plan.ranges.first().startMillis)
        assertEquals(90_000L, plan.ranges.last().endMillis)
        plan.ranges.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endMillis, right.startMillis)
        }
    }

    @Test
    fun `长视频模式可以提高粗采样帧率`() {
        val plan = SamplingPlan.create(videoDurationMillis = 2_000L, coarseFramesPerSecond = 2.0)

        assertEquals(2.0, plan.ranges.single().framesPerSecond, 0.0)
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L), plan.sampleTimestamps())
    }

    @Test
    fun `粗采样帧率不能超过长视频模式上限`() {
        assertThrows(IllegalArgumentException::class.java) {
            SamplingPlan.create(videoDurationMillis = 2_000L, coarseFramesPerSecond = 2.1)
        }
    }

    @Test
    fun `规划器拒绝重叠重点区间`() {
        assertThrows(IllegalArgumentException::class.java) {
            SamplingPlan.create(
                videoDurationMillis = 10_000L,
                requestedRanges = listOf(
                    SampleRange(1_000L, 5_000L, 8.0, SamplingTier.FOCUSED),
                    SampleRange(4_000L, 6_000L, 24.0, SamplingTier.HIGH_VALUE),
                ),
            )
        }
    }

    @Test
    fun `规划器支持最长六小时且采样数量保持有界`() {
        val plan = SamplingPlan.create(videoDurationMillis = SamplingPlan.MAX_VIDEO_DURATION_MILLIS)

        assertEquals(6 * 60 * 60 * 1_000L, SamplingPlan.MAX_VIDEO_DURATION_MILLIS)
        assertEquals(SamplingPlan.MAX_VIDEO_DURATION_MILLIS, plan.ranges.single().endMillis)
        assertEquals(6 * 60 * 60, plan.sampleTimestamps().size)
        assertEquals(
            2 * 6 * 60 * 60,
            SamplingPlan.create(
                videoDurationMillis = SamplingPlan.MAX_VIDEO_DURATION_MILLIS,
                coarseFramesPerSecond = 2.0,
            ).sampleTimestamps().size,
        )

        assertThrows(IllegalArgumentException::class.java) {
            SamplingPlan.create(videoDurationMillis = SamplingPlan.MAX_VIDEO_DURATION_MILLIS + 1L)
        }
    }

    @Test
    fun `采样时间戳遵守每个区间的帧率且不跨越边界`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 2_000L,
            requestedRanges = listOf(
                SampleRange(1_000L, 2_000L, 10.0, SamplingTier.FOCUSED),
            ),
        )

        val timestamps = plan.sampleTimestamps()

        assertEquals(11, timestamps.size)
        assertEquals(0L, timestamps.first())
        assertEquals(1_900L, timestamps.last())
        assertEquals(timestamps.distinct().size, timestamps.size)
        assertTrue(timestamps.all { it in 0L until 2_000L })
    }

    @Test
    fun `24帧和15帧时间戳按区间起点与帧序号生成`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 400L,
            requestedRanges = listOf(
                SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE),
                SampleRange(200L, 400L, 15.0, SamplingTier.HIGH_VALUE),
            ),
        )

        assertEquals(
            listOf(0L, 41L, 83L, 125L, 166L, 200L, 266L, 333L),
            plan.sampleTimestamps(),
        )
    }

    @Test
    fun `采样算法版本会使旧计划断点失效`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE)),
        )
        val legacyPlanId = sha256("${plan.videoDurationMillis}|${plan.ranges.joinToString()}")

        assertNotEquals(legacyPlanId, plan.planId)
        assertThrows(IllegalArgumentException::class.java) {
            plan.remainingRanges(
                SamplingCheckpoint(
                    planId = legacyPlanId,
                    rangeIndex = 0,
                    nextTimestampMillis = 82L,
                    sampledFrameCount = 2L,
                ),
            )
        }
    }

    @Test
    fun `24帧恢复区间保持原canonical相位后缀`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(
                SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE),
            ),
        )
        val checkpoint = SamplingCheckpoint(
            planId = plan.planId,
            rangeIndex = 0,
            nextTimestampMillis = 83L,
            sampledFrameCount = 2L,
        )

        assertEquals(
            listOf(83L, 125L, 166L),
            rebuiltRemainingTimestamps(plan, checkpoint),
        )
    }

    @Test
    fun `15帧恢复区间保持原canonical相位后缀`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 300L,
            requestedRanges = listOf(
                SampleRange(0L, 300L, 15.0, SamplingTier.HIGH_VALUE),
            ),
        )
        val checkpoint = SamplingCheckpoint(
            planId = plan.planId,
            rangeIndex = 0,
            nextTimestampMillis = 133L,
            sampledFrameCount = 2L,
        )

        assertEquals(
            listOf(133L, 200L, 266L),
            rebuiltRemainingTimestamps(plan, checkpoint),
        )
    }

    @Test
    fun `采样相位算法升级会改变planId`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(
                SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE),
            ),
        )
        val versionTwoPlanId = sha256(
            "canonical-index-floor-v2|200|" +
                "SampleRange(startMillis=0, endMillis=200, " +
                "framesPerSecond=24.0, tier=HIGH_VALUE)",
        )

        assertNotEquals(versionTwoPlanId, plan.planId)
    }

    @Test
    fun `不同采样相位原点生成不同planId`() {
        val reanchored = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(
                SampleRange(83L, 200L, 24.0, SamplingTier.HIGH_VALUE),
            ),
        )
        val phasePreserved = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(
                SampleRange(
                    83L,
                    200L,
                    24.0,
                    SamplingTier.HIGH_VALUE,
                    samplingOriginMillis = 0L,
                ),
            ),
        )

        assertNotEquals(reanchored.planId, phasePreserved.planId)
    }

    @Test
    fun `断点可以从当前时间恢复并保持剩余区间的覆盖`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 10_000L,
            requestedRanges = listOf(
                SampleRange(4_000L, 6_000L, 8.0, SamplingTier.FOCUSED),
            ),
        )
        val checkpoint = SamplingCheckpoint(
            planId = plan.planId,
            rangeIndex = 1,
            nextTimestampMillis = 5_000L,
            sampledFrameCount = 42L,
        )

        val remaining = plan.remainingRanges(checkpoint)

        assertEquals(
            listOf(
                SampleRange(
                    5_000L,
                    6_000L,
                    8.0,
                    SamplingTier.FOCUSED,
                    samplingOriginMillis = 4_000L,
                ),
                SampleRange(6_000L, 10_000L, 1.0, SamplingTier.COARSE),
            ),
            remaining,
        )
    }

    @Test
    fun `断点拒绝不属于当前区间的时间`() {
        val plan = SamplingPlan.create(videoDurationMillis = 10_000L)

        assertThrows(IllegalArgumentException::class.java) {
            plan.remainingRanges(
                SamplingCheckpoint(
                    planId = plan.planId,
                    rangeIndex = 0,
                    nextTimestampMillis = 10_001L,
                    sampledFrameCount = 1L,
                ),
            )
        }
    }

    @Test
    fun `断点位于区间结束时自动恢复到下一区间`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 10_000L,
            requestedRanges = listOf(SampleRange(4_000L, 6_000L, 8.0, SamplingTier.FOCUSED)),
        )

        val remaining = plan.remainingRanges(
            SamplingCheckpoint(plan.planId, rangeIndex = 1, nextTimestampMillis = 6_000L, sampledFrameCount = 20L),
        )

        assertEquals(listOf(SampleRange(6_000L, 10_000L, 1.0, SamplingTier.COARSE)), remaining)
    }

    @Test
    fun `最后一个区间完成后没有剩余采样`() {
        val plan = SamplingPlan.create(videoDurationMillis = 10_000L)

        assertTrue(
            plan.remainingRanges(
                SamplingCheckpoint(plan.planId, rangeIndex = 0, nextTimestampMillis = 10_000L, sampledFrameCount = 10L),
            ).isEmpty(),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun rebuiltRemainingTimestamps(
        plan: SamplingPlan,
        checkpoint: SamplingCheckpoint,
    ): List<Long> = SamplingPlan.create(
        videoDurationMillis = plan.videoDurationMillis,
        requestedRanges = plan.remainingRanges(checkpoint),
    ).sampleTimestamps().filter { timestamp ->
        timestamp >= checkpoint.nextTimestampMillis
    }
}
