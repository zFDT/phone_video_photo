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
    fun `剩余项为负数时不返回预计耗时`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(0, 29, AnalysisRunMode.FOREGROUND, 0L)

        assertNull(estimator.record(30, 29, AnalysisRunMode.FOREGROUND, 30_000L))
    }

    @Test
    fun `处理增量为零时不执行除法`() {
        val estimator = BatchEtaEstimator(minSamples = 1, minWindowMillis = 30_000L)
        estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 0L)

        assertNull(estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 30_000L))
    }

    @Test
    fun `极值预计耗时饱和为Long最大值`() {
        val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
        estimator.record(0, Int.MAX_VALUE, AnalysisRunMode.FOREGROUND, 0L)

        assertEquals(
            Long.MAX_VALUE,
            estimator.record(30, Int.MAX_VALUE, AnalysisRunMode.FOREGROUND, Long.MAX_VALUE),
        )
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

    @Test
    fun `初始通知不读取时钟或估算器且不显示预计耗时`() {
        val coordinator = BatchNotificationEmissionCoordinator(
            shouldEmit = { error("初始通知不应检查进度发布 gate") },
            visibilityProvider = { error("初始通知不应读取 visibility") },
            nowMillis = { error("初始通知不应读取单调时钟") },
            recordEta = { _, _, _, _ -> error("初始通知不应记录 ETA") },
        )

        val text = coordinator.initialNotification(
            progress(completed = 0, total = 0, stage = ""),
        )

        assertEquals("正常速度：0/0 · 失败 0", text.content)
        assertFalse(text.content.contains("约"))
    }

    @Test
    fun `未通过发布gate时不读取可见性时钟或估算器`() {
        val calls = mutableListOf<String>()
        val coordinator = BatchNotificationEmissionCoordinator(
            shouldEmit = {
                calls += "gate"
                false
            },
            visibilityProvider = { error("gate 未通过时不应读取 visibility") },
            nowMillis = { error("gate 未通过时不应读取单调时钟") },
            recordEta = { _, _, _, _ -> error("gate 未通过时不应记录 ETA") },
            presentation = { _, _, _ -> error("gate 未通过时不应生成通知") },
        )

        val text = coordinator.notificationFor(progress(completed = 1))

        assertNull(text)
        assertEquals(listOf("gate"), calls)
    }

    @Test
    fun `通过发布gate后按顺序采样并生成通知`() {
        val calls = mutableListOf<String>()
        val coordinator = BatchNotificationEmissionCoordinator(
            shouldEmit = {
                calls += "gate"
                true
            },
            visibilityProvider = {
                calls += "visibility"
                AppVisibility.BACKGROUND
            },
            nowMillis = {
                calls += "clock"
                30_000L
            },
            recordEta = { processed, total, mode, nowMillis ->
                calls += "estimator"
                assertEquals(31, processed)
                assertEquals(100, total)
                assertEquals(AnalysisRunMode.BACKGROUND_FRIENDLY, mode)
                assertEquals(30_000L, nowMillis)
                61L * 60_000L
            },
            presentation = { sampledProgress, mode, etaMillis ->
                calls += "presentation"
                presentBatchNotification(
                    stage = sampledProgress.currentStage,
                    mode = mode,
                    processed = sampledProgress.completed + sampledProgress.failed,
                    total = sampledProgress.total,
                    failed = sampledProgress.failed,
                    etaMillis = etaMillis,
                )
            },
        )

        val text = coordinator.notificationFor(
            progress(
                completed = 30,
                failed = 1,
                stage = AnalysisStage.CLASSIFICATION.name,
            ),
        )

        assertEquals(
            listOf("gate", "visibility", "clock", "estimator", "presentation"),
            calls,
        )
        assertEquals(
            "图片分类 · 后台友好：31/100 · 失败 1 · 约 2 小时",
            text?.content,
        )
    }

    private fun progress(
        completed: Int,
        failed: Int = 0,
        total: Int = 100,
        stage: String = AnalysisStage.EMBEDDING.name,
    ) = BatchAnalysisProgress(
        completed = completed,
        failed = failed,
        total = total,
        currentStage = stage,
        currentMediaId = 1L,
    )
}
