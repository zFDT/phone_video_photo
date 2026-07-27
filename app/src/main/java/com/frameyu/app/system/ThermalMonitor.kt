package com.frameyu.app.system

/** 由平台适配层转换后传入的温控等级；本模块不依赖 Android Context。 */
enum class ThermalStatus {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

/** 分析任务根据温控等级采取的运行状态。 */
enum class ThermalState {
    RUNNING,
    PAUSED,
    STOPPED,
}

/** 温控决策；倍率用于调节分析步进或采样频率。 */
data class ThermalDecision(
    val state: ThermalState,
    val speedMultiplier: Double,
)

/** 将温控输入映射为分析状态，并提供演唱会长视频降速策略。 */
class ThermalMonitor {
    /** 按约定将温控等级映射为运行、暂停或停止。 */
    fun stateFor(status: ThermalStatus): ThermalState = when (status) {
        ThermalStatus.NOMINAL,
        ThermalStatus.LIGHT,
        -> ThermalState.RUNNING

        ThermalStatus.MODERATE,
        ThermalStatus.SEVERE,
        -> ThermalState.PAUSED

        ThermalStatus.CRITICAL -> ThermalState.STOPPED
    }

    /** 长演唱会视频在温控暂停区间降低处理速度，临界温度则停止处理。 */
    fun decisionFor(status: ThermalStatus, isConcertLongVideo: Boolean): ThermalDecision {
        val state = stateFor(status)
        val speedMultiplier = when {
            state == ThermalState.STOPPED -> 0.0
            isConcertLongVideo && status in SLOW_CONCERT_STATUSES -> 0.5
            else -> 1.0
        }
        return ThermalDecision(state, speedMultiplier)
    }

    private companion object {
        val SLOW_CONCERT_STATUSES = setOf(ThermalStatus.MODERATE, ThermalStatus.SEVERE)
    }
}
