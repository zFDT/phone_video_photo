package com.frameyu.app.organize

enum class PendingMediaAuthorizationType {
    TRASH,
    RECOVERABLE_DELETE,
}

enum class PendingMediaAuthorizationPhase {
    AWAITING_RESULT,
    GRANTED_PENDING_RECONCILIATION,
}

data class PendingMediaAuthorization(
    val requestId: String,
    val createdAtMillis: Long,
    val requestType: PendingMediaAuthorizationType,
    val uris: List<String>,
    val currentUri: String?,
    val phase: PendingMediaAuthorizationPhase = PendingMediaAuthorizationPhase.AWAITING_RESULT,
) {
    init {
        require(REQUEST_ID_PATTERN.matches(requestId)) { "授权请求 ID 格式无效" }
        require(createdAtMillis >= 0L) { "授权请求时间无效" }
        require(uris.isNotEmpty()) { "授权请求至少需要一个 Uri" }
        require(uris.size <= MAX_MEDIA_AUTHORIZATION_URIS) { "单次授权 Uri 数量超过上限" }
        require(uris.none(String::isBlank)) { "授权请求 Uri 不能为空" }
        require(uris.distinct().size == uris.size) { "授权请求 Uri 不应重复" }
        when (requestType) {
            PendingMediaAuthorizationType.TRASH -> require(currentUri == null) {
                "回收站请求不应包含当前 Uri"
            }
            PendingMediaAuthorizationType.RECOVERABLE_DELETE -> require(
                currentUri != null && currentUri in uris,
            ) {
                "Android Q 删除请求必须包含当前 Uri"
            }
        }
    }
}

sealed interface MediaAuthorizationAction {
    data object None : MediaAuthorizationAction

    data object RejectNewRequest : MediaAuthorizationAction

    data class ResumeContinuation(val granted: Boolean) : MediaAuthorizationAction

    data class ReconcileTrash(val uris: List<String>) : MediaAuthorizationAction

    data class RetryRecoverableDelete(val uri: String) : MediaAuthorizationAction

    data class ReconcileExpiredTrash(val uris: List<String>) : MediaAuthorizationAction

    data class ReconcileExpiredRecoverableDelete(val uri: String) : MediaAuthorizationAction
}

data class MediaAuthorizationTransition(
    val pending: PendingMediaAuthorization?,
    val action: MediaAuthorizationAction,
)

class MediaAuthorizationStateReducer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val expiryMillis: Long = MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
) {
    init {
        require(expiryMillis > 0L) { "授权过期时间必须大于零" }
    }

    fun begin(
        current: PendingMediaAuthorization?,
        request: PendingMediaAuthorization,
    ): MediaAuthorizationTransition = if (current == null) {
        MediaAuthorizationTransition(request, MediaAuthorizationAction.None)
    } else {
        MediaAuthorizationTransition(current, MediaAuthorizationAction.RejectNewRequest)
    }

    fun onActivityResult(
        current: PendingMediaAuthorization?,
        callbackRequestId: String?,
        granted: Boolean,
        continuationPresent: Boolean,
    ): MediaAuthorizationTransition {
        if (current == null ||
            callbackRequestId != current.requestId ||
            current.phase != PendingMediaAuthorizationPhase.AWAITING_RESULT
        ) {
            return MediaAuthorizationTransition(current, MediaAuthorizationAction.None)
        }
        if (!granted) {
            return MediaAuthorizationTransition(
                pending = null,
                action = if (continuationPresent) {
                    MediaAuthorizationAction.ResumeContinuation(false)
                } else {
                    MediaAuthorizationAction.None
                },
            )
        }
        val grantedPending = current.copy(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
        )
        return MediaAuthorizationTransition(
            pending = grantedPending,
            action = if (continuationPresent) {
                MediaAuthorizationAction.ResumeContinuation(true)
            } else {
                reconciliationAction(grantedPending)
            },
        )
    }

    fun recover(current: PendingMediaAuthorization?): MediaAuthorizationAction = when {
        current == null -> MediaAuthorizationAction.None
        isExpired(current) -> expiredReconciliationAction(current)
        current.phase == PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION ->
            reconciliationAction(current)
        else -> MediaAuthorizationAction.None
    }

    fun isExpired(pending: PendingMediaAuthorization): Boolean {
        val ageMillis = clock() - pending.createdAtMillis
        return ageMillis < 0L || ageMillis >= expiryMillis
    }

    fun complete(
        current: PendingMediaAuthorization?,
        completed: PendingMediaAuthorization,
    ): PendingMediaAuthorization? = if (
        current == completed &&
        completed.phase == PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION
    ) {
        null
    } else {
        current
    }

    fun launchFailed(
        current: PendingMediaAuthorization?,
        request: PendingMediaAuthorization,
    ): PendingMediaAuthorization? = if (
        current == request &&
        current.phase == PendingMediaAuthorizationPhase.AWAITING_RESULT
    ) {
        null
    } else {
        current
    }

    fun clearExpired(
        current: PendingMediaAuthorization?,
        expired: PendingMediaAuthorization,
    ): PendingMediaAuthorization? = if (
        current == expired &&
        isExpired(expired)
    ) {
        null
    } else {
        current
    }

    private fun reconciliationAction(
        pending: PendingMediaAuthorization,
    ): MediaAuthorizationAction = when (pending.requestType) {
        PendingMediaAuthorizationType.TRASH -> MediaAuthorizationAction.ReconcileTrash(
            pending.uris,
        )
        PendingMediaAuthorizationType.RECOVERABLE_DELETE ->
            MediaAuthorizationAction.RetryRecoverableDelete(checkNotNull(pending.currentUri))
    }

    private fun expiredReconciliationAction(
        pending: PendingMediaAuthorization,
    ): MediaAuthorizationAction = when (pending.requestType) {
        PendingMediaAuthorizationType.TRASH -> MediaAuthorizationAction.ReconcileExpiredTrash(
            pending.uris,
        )
        PendingMediaAuthorizationType.RECOVERABLE_DELETE ->
            MediaAuthorizationAction.ReconcileExpiredRecoverableDelete(checkNotNull(pending.currentUri))
    }
}

fun chunkMediaAuthorizationUris(
    uris: List<String>,
): List<List<String>> = uris.distinct().chunked(MAX_MEDIA_AUTHORIZATION_URIS)

const val MAX_MEDIA_AUTHORIZATION_URIS = 100
const val MEDIA_AUTHORIZATION_EXPIRY_MILLIS = 10L * 60L * 1000L
const val MEDIA_AUTHORIZATION_REQUEST_ID_LENGTH = 32

private val REQUEST_ID_PATTERN = Regex("[0-9a-f]{$MEDIA_AUTHORIZATION_REQUEST_ID_LENGTH}")

fun isValidMediaAuthorizationRequestId(requestId: String?): Boolean =
    requestId != null && REQUEST_ID_PATTERN.matches(requestId)

fun isValidPersistedMediaAuthorizationUriCount(count: Int): Boolean =
    count in 1..MAX_MEDIA_AUTHORIZATION_URIS
