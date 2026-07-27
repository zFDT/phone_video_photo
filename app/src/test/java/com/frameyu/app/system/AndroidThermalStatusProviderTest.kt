package com.frameyu.app.system

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidThermalStatusProviderTest {
    @Test
    fun `平台热状态映射为应用温控等级`() {
        assertEquals(ThermalStatus.NOMINAL, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_NONE))
        assertEquals(ThermalStatus.LIGHT, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(ThermalStatus.MODERATE, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(ThermalStatus.SEVERE, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(ThermalStatus.CRITICAL, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals(ThermalStatus.CRITICAL, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals(ThermalStatus.CRITICAL, AndroidThermalStatusProvider.mapPlatformStatus(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }
}
