package com.frameyu.app.settings

import android.content.SharedPreferences

/** 应用设置的不可变快照。 */
data class SettingsSnapshot(
    val cacheLimitGb: Int = DEFAULT_CACHE_LIMIT_GB,
    val concertModeEnabled: Boolean = DEFAULT_CONCERT_MODE_ENABLED,
    val copyLocationMetadata: Boolean = DEFAULT_COPY_LOCATION_METADATA,
    val backgroundAnalysisEnabled: Boolean = DEFAULT_BACKGROUND_ANALYSIS_ENABLED,
) {
    init {
        require(cacheLimitGb in MIN_CACHE_LIMIT_GB..MAX_CACHE_LIMIT_GB) {
            "缓存上限必须在 50 到 200GB 之间"
        }
    }

    companion object {
        const val MIN_CACHE_LIMIT_GB = 50
        const val MAX_CACHE_LIMIT_GB = 200
        const val DEFAULT_CACHE_LIMIT_GB = MIN_CACHE_LIMIT_GB
        const val DEFAULT_CONCERT_MODE_ENABLED = false
        const val DEFAULT_COPY_LOCATION_METADATA = true
        const val DEFAULT_BACKGROUND_ANALYSIS_ENABLED = false
    }
}

/** 使用 SharedPreferences 保存和读取应用设置。 */
class SettingsStore(
    private val preferences: SharedPreferences,
) {
    /** 读取设置；遇到超出范围的缓存值时回收到合法范围。 */
    fun read(): SettingsSnapshot = SettingsSnapshot(
        cacheLimitGb = normalizeCacheLimitGb(
            preferences.getInt(KEY_CACHE_LIMIT_GB, SettingsSnapshot.DEFAULT_CACHE_LIMIT_GB),
        ),
        concertModeEnabled = preferences.getBoolean(
            KEY_CONCERT_MODE_ENABLED,
            SettingsSnapshot.DEFAULT_CONCERT_MODE_ENABLED,
        ),
        copyLocationMetadata = preferences.getBoolean(
            KEY_COPY_LOCATION_METADATA,
            SettingsSnapshot.DEFAULT_COPY_LOCATION_METADATA,
        ),
        backgroundAnalysisEnabled = preferences.getBoolean(
            KEY_BACKGROUND_ANALYSIS_ENABLED,
            SettingsSnapshot.DEFAULT_BACKGROUND_ANALYSIS_ENABLED,
        ),
    )

    /** 保存完整设置快照。 */
    fun save(settings: SettingsSnapshot) {
        preferences.edit()
            .putInt(KEY_CACHE_LIMIT_GB, settings.cacheLimitGb)
            .putBoolean(KEY_CONCERT_MODE_ENABLED, settings.concertModeEnabled)
            .putBoolean(KEY_COPY_LOCATION_METADATA, settings.copyLocationMetadata)
            .putBoolean(KEY_BACKGROUND_ANALYSIS_ENABLED, settings.backgroundAnalysisEnabled)
            .apply()
    }

    companion object {
        private const val KEY_CACHE_LIMIT_GB = "cache_limit_gb"
        private const val KEY_CONCERT_MODE_ENABLED = "concert_mode_enabled"
        private const val KEY_COPY_LOCATION_METADATA = "copy_location_metadata"
        private const val KEY_BACKGROUND_ANALYSIS_ENABLED = "background_analysis_enabled"

        /** 将持久化或 UI 输入的缓存上限限制到支持范围。 */
        fun normalizeCacheLimitGb(cacheLimitGb: Int): Int =
            cacheLimitGb.coerceIn(
                SettingsSnapshot.MIN_CACHE_LIMIT_GB,
                SettingsSnapshot.MAX_CACHE_LIMIT_GB,
            )
    }
}
