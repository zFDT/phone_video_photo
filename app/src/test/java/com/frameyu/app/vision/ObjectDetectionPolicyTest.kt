package com.frameyu.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectDetectionPolicyTest {
    private val policy = ObjectDetectionPolicy()

    @Test
    fun `最高置信度低于阈值时触发检测`() {
        assertTrue(policy.shouldDetect(listOf(LabelScore("lakeside", 0.44f)), highValue = false))
    }

    @Test
    fun `最高置信度等于阈值时不因置信度触发检测`() {
        assertFalse(policy.shouldDetect(listOf(LabelScore("lakeside", 0.45f)), highValue = false))
    }

    @Test
    fun `前两名差值低于阈值时触发检测且不依赖输入顺序`() {
        val labels = listOf(
            LabelScore("third", 0.20f),
            LabelScore("second", 0.50f),
            LabelScore("first", 0.59f),
        )

        assertTrue(policy.shouldDetect(labels, highValue = false))
    }

    @Test
    fun `可表示的最大一个小于差值阈值的 Float 应触发检测`() {
        val expectedGap = Math.nextDown(0.10f)
        val top1 = 0.45f
        val top2 = top1 - expectedGap

        assertEquals(expectedGap, top1 - top2, 0f)
        assertTrue(
            policy.shouldDetect(
                listOf(
                    LabelScore("first", top1),
                    LabelScore("second", top2),
                ),
                highValue = false,
            ),
        )
    }

    @Test
    fun `前两名差值等于阈值时不因差值触发检测`() {
        assertFalse(isUncertainConfidenceGap(0.10f))
    }

    @Test
    fun `主体标签忽略大小写和首尾空格并触发检测`() {
        assertTrue(policy.shouldDetect(listOf(LabelScore("  PeRsOn  ", 0.90f)), highValue = false))
    }

    @Test
    fun `其他主体标签也触发检测`() {
        val subjectLabels = listOf(
            "human",
            "performer",
            "singer",
            "musician",
            "dog",
            "cat",
            "pet",
            "vehicle",
        )

        subjectLabels.forEach { label ->
            assertTrue(policy.shouldDetect(listOf(LabelScore(label, 0.90f)), highValue = false))
        }
    }

    @Test
    fun `高价值候选触发检测`() {
        assertTrue(policy.shouldDetect(listOf(LabelScore("lakeside", 0.90f)), highValue = true))
    }

    @Test
    fun `空分类结果按不确定场景触发检测`() {
        assertTrue(policy.shouldDetect(emptyList(), highValue = false))
    }

    @Test
    fun `高置信普通场景不触发检测`() {
        assertFalse(policy.shouldDetect(listOf(LabelScore("lakeside", 0.90f)), highValue = false))
    }
}
