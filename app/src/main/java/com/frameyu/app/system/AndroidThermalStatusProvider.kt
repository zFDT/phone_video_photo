package com.frameyu.app.system

import android.content.Context
import android.os.Build
import android.os.PowerManager

/** 从 Android PowerManager 读取设备热状态，并转换为应用内稳定枚举。 */
class AndroidThermalStatusProvider(context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    fun current(): ThermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        mapPlatformStatus(powerManager.currentThermalStatus)
    } else {
        ThermalStatus.NOMINAL
    }

    companion object {
        fun mapPlatformStatus(status: Int): ThermalStatus = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalStatus.CRITICAL
            else -> ThermalStatus.CRITICAL
        }
    }
}
