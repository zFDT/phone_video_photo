package com.frameyu.app.system

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityTrackerTest {
    @Test
    fun `无已启动页面时视为后台且多个页面按计数保持前台`() {
        val state = AppVisibilityState()
        val firstActivity = Any()
        val secondActivity = Any()

        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        state.onActivityStarted(firstActivity)
        state.onActivityStarted(secondActivity)
        state.onActivityStopped(firstActivity)

        assertEquals(AppVisibility.FOREGROUND, state.visibility)
        assertEquals(1, state.startedActivityCount)

        state.onActivityStopped(secondActivity)

        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        assertEquals(0, state.startedActivityCount)
    }

    @Test
    fun `重复 stopped 不会产生负计数`() {
        val state = AppVisibilityState()
        val activity = Any()

        state.onActivityStopped(activity)
        state.onActivityStopped(activity)

        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        assertEquals(0, state.startedActivityCount)
    }

    @Test
    fun `重复 stopped 只移除对应页面`() {
        val state = AppVisibilityState()
        val firstActivity = Any()
        val secondActivity = Any()

        state.onActivityStarted(firstActivity)
        state.onActivityStarted(secondActivity)
        state.onActivityStopped(firstActivity)
        state.onActivityStopped(firstActivity)

        assertEquals(1, state.startedActivityCount)
        assertEquals(AppVisibility.FOREGROUND, state.visibility)

        state.onActivityStopped(secondActivity)

        assertEquals(0, state.startedActivityCount)
        assertEquals(AppVisibility.BACKGROUND, state.visibility)
    }

    @Test
    fun `同一页面重复 started 不会重复计数`() {
        val state = AppVisibilityState()
        val activity = Any()

        state.onActivityStarted(activity)
        state.onActivityStarted(activity)

        assertEquals(1, state.startedActivityCount)
        assertEquals(AppVisibility.FOREGROUND, state.visibility)

        state.onActivityStopped(activity)

        assertEquals(0, state.startedActivityCount)
        assertEquals(AppVisibility.BACKGROUND, state.visibility)
    }

    @Test
    fun `相等但非同一对象的页面分别计数`() {
        val state = AppVisibilityState()
        val firstActivity = VisibilityToken(1)
        val secondActivity = VisibilityToken(1)

        state.onActivityStarted(firstActivity)
        state.onActivityStarted(secondActivity)

        assertEquals(2, state.startedActivityCount)

        state.onActivityStopped(firstActivity)

        assertEquals(1, state.startedActivityCount)
        assertEquals(AppVisibility.FOREGROUND, state.visibility)

        state.onActivityStopped(secondActivity)

        assertEquals(0, state.startedActivityCount)
        assertEquals(AppVisibility.BACKGROUND, state.visibility)
    }
}

private data class VisibilityToken(val id: Int)
