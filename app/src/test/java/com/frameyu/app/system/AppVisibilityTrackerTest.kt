package com.frameyu.app.system

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityTrackerTest {
    @Test
    fun `无已启动页面时视为后台且多个页面按计数保持前台`() {
        val state = AppVisibilityState()

        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        state.onActivityStarted()
        state.onActivityStarted()
        state.onActivityStopped()

        assertEquals(AppVisibility.FOREGROUND, state.visibility)
        assertEquals(1, state.startedActivityCount)

        state.onActivityStopped()

        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        assertEquals(0, state.startedActivityCount)
    }

    @Test
    fun `重复 stopped 不会产生负计数`() {
        val state = AppVisibilityState()

        state.onActivityStopped()
        state.onActivityStopped()

        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        assertEquals(0, state.startedActivityCount)
    }
}
