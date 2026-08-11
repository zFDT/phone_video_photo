package com.frameyu.app.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundPacingPolicyTest {
    @Test
    fun `后台只节流重任务`() {
        val policy = BackgroundPacingPolicy()

        assertEquals(200L, policy.delayAfter(AppVisibility.BACKGROUND, isHeavyUnit = true))
        assertEquals(0L, policy.delayAfter(AppVisibility.BACKGROUND, isHeavyUnit = false))
        assertEquals(0L, policy.delayAfter(AppVisibility.FOREGROUND, isHeavyUnit = true))
    }

    @Test
    fun `可配置后台延迟`() {
        val policy = BackgroundPacingPolicy(backgroundDelayMillis = 350L)

        assertEquals(350L, policy.delayAfter(AppVisibility.BACKGROUND, isHeavyUnit = true))
    }

    @Test
    fun `后台延迟不得为负`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackgroundPacingPolicy(backgroundDelayMillis = -1L)
        }
    }
}
