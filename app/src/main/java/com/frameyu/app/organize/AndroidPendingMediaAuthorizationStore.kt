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
            if (!isValidPersistedMediaAuthorizationUriCount(count)) {
                return@runCatching null
            }
            val uris = (0 until count).map { index ->
                requireNotNull(preferences.getString("$KEY_URI_PREFIX$index", null))
            }
            PendingMediaAuthorization(
                requestId = requireNotNull(preferences.getString(KEY_REQUEST_ID, null)),
                createdAtMillis = preferences.getLong(KEY_CREATED_AT_MILLIS, -1L),
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
                .putString(KEY_REQUEST_ID, pending.requestId)
                .putLong(KEY_CREATED_AT_MILLIS, pending.createdAtMillis)
                .putString(KEY_TYPE, pending.requestType.name)
                .putString(KEY_PHASE, pending.phase.name)
                .putInt(KEY_URI_COUNT, pending.uris.size)
            pending.uris.forEachIndexed { index, uri ->
                editor.putString("$KEY_URI_PREFIX$index", uri)
            }
            pending.currentUri?.let { editor.putString(KEY_CURRENT_URI, it) }
        }
        editor.apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "media_authorization_pending"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_CREATED_AT_MILLIS = "created_at_millis"
        private const val KEY_TYPE = "type"
        private const val KEY_PHASE = "phase"
        private const val KEY_URI_COUNT = "uri_count"
        private const val KEY_URI_PREFIX = "uri_"
        private const val KEY_CURRENT_URI = "current_uri"

        fun toBundle(requestId: String?): Bundle? = requestId
            ?.takeIf(::isValidMediaAuthorizationRequestId)
            ?.let {
                Bundle().apply {
                    putString(KEY_REQUEST_ID, it)
                }
            }

        fun requestIdFromBundle(bundle: Bundle?): String? = runCatching {
            bundle?.getString(KEY_REQUEST_ID)?.takeIf(::isValidMediaAuthorizationRequestId)
        }.getOrNull()
    }
}
