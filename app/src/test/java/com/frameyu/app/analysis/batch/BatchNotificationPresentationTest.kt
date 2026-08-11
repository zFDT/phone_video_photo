package com.frameyu.app.analysis.batch

import com.frameyu.app.system.AppVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatchNotificationPresentationTest {
    @Test
    fun `运行模式提供中文标签`() {
        assertEquals("正常速度", AnalysisRunMode.FOREGROUND.label)
        assertEquals("后台友好", AnalysisRunMode.BACKGROUND_FRIENDLY.label)
    }

    @Test
    fun `不足三十项时不返回预计耗时`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)

        assertNull(estimator.record(0, 100, AnalysisRunMode.FOREGROUND, 0L))
        assertNull(estimator.record(29, 100, AnalysisRunMode.FOREGROUND, 30_000L))
    }

    @Test
    fun `不足三十秒时不返回预计耗时`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)

        estimator.record(0, 100, AnalysisRunMode.FOREGROUND, 0L)

        assertNull(estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 29_999L))
    }

    @Test
    fun `同时满足样本和时间阈值后返回预计耗时`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(0, 100, AnalysisRunMode.FOREGROUND, 0L)

        val etaMillis = estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 30_000L)

        assertNotNull(etaMillis)
        assertEquals(70_000L, etaMillis)
    }

    @Test
    fun `运行模式切换会重置估算窗口`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(0, 100, AnalysisRunMode.FOREGROUND, 0L)
        assertNotNull(estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 30_000L))

        assertNull(estimator.record(31, 100, AnalysisRunMode.BACKGROUND_FRIENDLY, 31_000L))
        assertEquals(
            39_000L,
            estimator.record(61, 100, AnalysisRunMode.BACKGROUND_FRIENDLY, 61_000L),
        )
    }

    @Test
    fun `处理数回退会重置估算窗口`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 0L)

        assertNull(estimator.record(29, 100, AnalysisRunMode.FOREGROUND, 30_000L))
        assertEquals(
            41_000L,
            estimator.record(59, 100, AnalysisRunMode.FOREGROUND, 60_000L),
        )
    }

    @Test
    fun `单调时钟回退会重置估算窗口`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(0, 100, AnalysisRunMode.FOREGROUND, 10_000L)

        assertNull(estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 9_999L))
        assertEquals(
            40_000L,
            estimator.record(60, 100, AnalysisRunMode.FOREGROUND, 39_999L),
        )
    }

    @Test
    fun `没有剩余项时不返回预计耗时`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(0, 30, AnalysisRunMode.FOREGROUND, 0L)

        assertNull(estimator.record(30, 30, AnalysisRunMode.FOREGROUND, 30_000L))
    }

    @Test
    fun `预计耗时仅显示向上取整的分钟或小时`() {
        val oneMinute = presentBatchNotification(
            stage = AnalysisStage.EMBEDDING.name,
            mode = AnalysisRunMode.FOREGROUND,
            processed = 1,
            total = 2,
            failed = 0,
            etaMillis = 1L,
        )
        val twoHours = presentBatchNotification(
            stage = AnalysisStage.CLASSIFICATION.name,
            mode = AnalysisRunMode.BACKGROUND_FRIENDLY,
            processed = 12_820,
            total = 37_012,
            failed = 4,
            etaMillis = 61L * 60_000L,
        )

        assertEquals("图片特征 · 正常速度：1/2 · 失败 0 · 约 1 分钟", oneMinute.content)
        assertEquals(
            "图片分类 · 后台友好：12820/37012 · 失败 4 · 约 2 小时",
            twoHours.content,
        )
        assertFalse(oneMinute.content.contains("秒"))
        assertFalse(twoHours.content.contains("秒"))
    }

    @Test
    fun `通知包含固定标题且保留已有阶段字符串`() {
        val text = presentBatchNotification(
            stage = "自定义阶段",
            mode = AnalysisRunMode.FOREGROUND,
            processed = 3,
            total = 10,
            failed = 1,
            etaMillis = null,
        )

        assertEquals("帧遇正在批量分析", text.title)
        assertEquals("自定义阶段 · 正常速度：3/10 · 失败 1", text.content)
    }

    @Test
    fun `前后台可见性映射为对应运行模式`() {
        assertEquals(AnalysisRunMode.FOREGROUND, AppVisibility.FOREGROUND.toAnalysisRunMode())
        assertEquals(
            AnalysisRunMode.BACKGROUND_FRIENDLY,
            AppVisibility.BACKGROUND.toAnalysisRunMode(),
        )
    }
}
