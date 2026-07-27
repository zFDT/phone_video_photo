package com.frameyu.app.storage

import com.frameyu.app.system.ThermalMonitor
import com.frameyu.app.system.ThermalState
import com.frameyu.app.system.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCacheManagerTest {
    private val cacheManager = AnalysisCacheManager()

    @Test
    fun `默认缓存上限为 50GB 且不能超过 200GB`() {
        assertEquals(CacheSettings.DEFAULT_CACHE_LIMIT_BYTES, CacheSettings().maxCacheBytes)
        assertEquals(50L * CacheSettings.BYTES_PER_GIGABYTE, CacheSettings.DEFAULT_CACHE_LIMIT_BYTES)
        assertEquals(200L * CacheSettings.BYTES_PER_GIGABYTE, CacheSettings.MAX_CACHE_LIMIT_BYTES)
    }

    @Test
    fun `缓存配置允许调整到 200GB`() {
        assertEquals(
            CacheSettings.MAX_CACHE_LIMIT_BYTES,
            CacheSettings(CacheSettings.MAX_CACHE_LIMIT_BYTES).maxCacheBytes,
        )
    }

    @Test
    fun `缓存配置不能超过 200GB`() {
        assertThrows<IllegalArgumentException> {
            CacheSettings(CacheSettings.MAX_CACHE_LIMIT_BYTES + 1L)
        }
    }

    @Test
    fun `缓存大小等于所有条目大小之和`() {
        val entries = listOf(
            AnalysisCacheEntry("a", 3L, 30L),
            AnalysisCacheEntry("b", 7L, 10L),
        )

        assertEquals(10L, cacheManager.calculateSize(entries))
    }

    @Test
    fun `缓存大小超过配置上限时判定为超限`() {
        val limit = CacheSettings.DEFAULT_CACHE_LIMIT_BYTES

        assertFalse(cacheManager.isOverLimit(limit))
        assertTrue(cacheManager.isOverLimit(limit + 1L))
    }

    @Test
    fun `清理计划按最早访问时间删除条目直到回到上限`() {
        val settings = CacheSettings(maxCacheBytes = 10L)
        val manager = AnalysisCacheManager(settings)
        val entries = listOf(
            AnalysisCacheEntry("recent", 4L, 30L),
            AnalysisCacheEntry("old", 6L, 10L),
            AnalysisCacheEntry("middle", 5L, 20L),
        )

        val plan = manager.planCleanup(entries)

        assertEquals(listOf("old"), plan.entriesToDelete.map { it.key })
        assertEquals(6L, plan.bytesToFree)
        assertEquals(9L, plan.resultingSizeBytes)
    }

    @Test
    fun `未超限时清理计划为空`() {
        val entries = listOf(AnalysisCacheEntry("only", 10L, 1L))
        val plan = AnalysisCacheManager(CacheSettings(10L)).planCleanup(entries)

        assertTrue(plan.entriesToDelete.isEmpty())
        assertEquals(0L, plan.bytesToFree)
        assertEquals(10L, plan.resultingSizeBytes)
    }

    @Test
    fun `温控状态按确认策略映射`() {
        val monitor = ThermalMonitor()

        assertEquals(ThermalState.RUNNING, monitor.stateFor(ThermalStatus.NOMINAL))
        assertEquals(ThermalState.RUNNING, monitor.stateFor(ThermalStatus.LIGHT))
        assertEquals(ThermalState.PAUSED, monitor.stateFor(ThermalStatus.MODERATE))
        assertEquals(ThermalState.PAUSED, monitor.stateFor(ThermalStatus.SEVERE))
        assertEquals(ThermalState.STOPPED, monitor.stateFor(ThermalStatus.CRITICAL))
    }

    @Test
    fun `演唱会长视频在温控暂停区间使用半速策略`() {
        val monitor = ThermalMonitor()

        val decision = monitor.decisionFor(
            status = ThermalStatus.MODERATE,
            isConcertLongVideo = true,
        )

        assertEquals(ThermalState.PAUSED, decision.state)
        assertEquals(0.5, decision.speedMultiplier, 0.0)
    }

    @Test
    fun `普通视频和正常温度保持正常速度`() {
        val monitor = ThermalMonitor()

        assertEquals(
            1.0,
            monitor.decisionFor(ThermalStatus.MODERATE, isConcertLongVideo = false).speedMultiplier,
            0.0,
        )
        assertEquals(
            1.0,
            monitor.decisionFor(ThermalStatus.NOMINAL, isConcertLongVideo = true).speedMultiplier,
            0.0,
        )
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue(error is T)
            return
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }
}
