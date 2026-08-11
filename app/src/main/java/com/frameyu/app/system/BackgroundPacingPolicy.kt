package com.frameyu.app.system

class BackgroundPacingPolicy(
    private val backgroundDelayMillis: Long = 200L,
) {
    init {
        require(backgroundDelayMillis >= 0L) { "后台延迟不得为负" }
    }

    fun delayAfter(visibility: AppVisibility, isHeavyUnit: Boolean): Long =
        if (visibility == AppVisibility.BACKGROUND && isHeavyUnit) {
            backgroundDelayMillis
        } else {
            0L
        }
}
