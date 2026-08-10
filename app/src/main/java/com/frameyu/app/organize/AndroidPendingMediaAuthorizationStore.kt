package com.frameyu.app.organize

import android.content.Context
import android.os.Bundle

data class StoredPendingMediaAuthorization(
    val initialized: Boolean,
    val pending: PendingMediaAuthorization?,
)

class AndroidPendingMediaAuthorizationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): StoredPendingMediaAuthorization {
        if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
            return StoredPendingMediaAuthorization(initialized = false, pending = null)
        }
        val pending = runCatching {
            val type = preferences.getString(KEY_TYPE, null)
                ?.let(PendingMediaAuthorizationType::valueOf)
                ?: return@runCatching null
            val phase = preferences.getString(KEY_PHASE, null)
                ?.let(PendingMediaAuthorizationPhase::valueOf)
                ?: return@runCatching null
            val count = preferences.getInt(KEY_URI_COUNT, 0)
            val uris = (0 until count).map { index ->
                requireNotNull(preferences.getString("$KEY_URI_PREFIX$index", null))
            }
            PendingMediaAuthorization(
                requestType = type,
                uris = uris,
                currentUri = preferences.getString(KEY_CURRENT_URI, null),
                phase = phase,
            )
        }.getOrNull()
        return StoredPendingMediaAuthorization(initialized = true, pending = pending)
    }

    fun write(pending: PendingMediaAuthorization?) {
        val editor = preferences.edit()
            .clear()
            .putBoolean(KEY_INITIALIZED, true)
        if (pending != null) {
            editor
                .putString(KEY_TYPE, pending.requestType.name)
                .putString(KEY_PHASE, pending.phase.name)
                .putInt(KEY_URI_COUNT, pending.uris.size)
            pending.uris.forEachIndexed { index, uri ->
                editor.putString("$KEY_URI_PREFIX$index", uri)
            }
            pending.currentUri?.let { editor.putString(KEY_CURRENT_URI, it) }
        }
        check(editor.commit()) { "无法持久化媒体授权状态" }
    }

    companion object {
        private const val PREFERENCES_NAME = "media_authorization_pending"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_TYPE = "type"
        private const val KEY_PHASE = "phase"
        private const val KEY_URI_COUNT = "uri_count"
        private const val KEY_URI_PREFIX = "uri_"
        private const val KEY_CURRENT_URI = "current_uri"

        fun toBundle(pending: PendingMediaAuthorization?): Bundle? = pending?.let {
            Bundle().apply {
                putString(KEY_TYPE, it.requestType.name)
                putString(KEY_PHASE, it.phase.name)
                putStringArrayList(KEY_URI_PREFIX, ArrayList(it.uris))
                putString(KEY_CURRENT_URI, it.currentUri)
            }
        }

        fun fromBundle(bundle: Bundle?): PendingMediaAuthorization? = runCatching {
            bundle ?: return@runCatching null
            PendingMediaAuthorization(
                requestType = PendingMediaAuthorizationType.valueOf(
                    requireNotNull(bundle.getString(KEY_TYPE)),
                ),
                uris = requireNotNull(bundle.getStringArrayList(KEY_URI_PREFIX)),
                currentUri = bundle.getString(KEY_CURRENT_URI),
                phase = PendingMediaAuthorizationPhase.valueOf(
                    requireNotNull(bundle.getString(KEY_PHASE)),
                ),
            )
        }.getOrNull()
    }
}
