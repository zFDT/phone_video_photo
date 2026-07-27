package com.frameyu.app.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun `读取空偏好时返回默认设置`() {
        val store = SettingsStore(FakeSharedPreferences())

        assertEquals(
            SettingsSnapshot(
                cacheLimitGb = 50,
                concertModeEnabled = false,
                copyLocationMetadata = true,
                backgroundAnalysisEnabled = false,
            ),
            store.read(),
        )
    }

    @Test
    fun `读取缓存上限时限制在50到200GB`() {
        val preferences = FakeSharedPreferences()
        preferences.values["cache_limit_gb"] = 20
        val store = SettingsStore(preferences)

        assertEquals(50, store.read().cacheLimitGb)

        preferences.values["cache_limit_gb"] = 240

        assertEquals(200, store.read().cacheLimitGb)
    }

    @Test
    fun `保存设置后可以完整读取四项设置`() {
        val preferences = FakeSharedPreferences()
        val store = SettingsStore(preferences)
        val expected = SettingsSnapshot(
            cacheLimitGb = 150,
            concertModeEnabled = true,
            copyLocationMetadata = false,
            backgroundAnalysisEnabled = true,
        )

        store.save(expected)

        assertEquals(expected, store.read())
    }

    private class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                values[key] = value
                return this
            }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply {
                this@FakeSharedPreferences.values[key] = values
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                values.remove(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                values.clear()
            }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
