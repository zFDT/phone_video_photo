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
    val requestType: PendingMediaAuthorizationType,
    val uris: List<String>,
    val currentUri: String?,
    val phase: PendingMediaAuthorizationPhase = PendingMediaAuthorizationPhase.AWAITING_RESULT,
) {
    init {
        require(uris.isNotEmpty()) { "授权请求至少需要一个 Uri" }
        require(uris.none(String::isBlank)) { "授权请求 Uri 不能为空" }
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
}

data class MediaAuthorizationTransition(
    val pending: PendingMediaAuthorization?,
    val action: MediaAuthorizationAction,
)

class MediaAuthorizationStateReducer {
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
        granted: Boolean,
        continuationPresent: Boolean,
    ): MediaAuthorizationTransition {
        if (current == null ||
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

    fun recover(current: PendingMediaAuthorization?): MediaAuthorizationAction =
        if (current?.phase == PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION) {
            reconciliationAction(current)
        } else {
            MediaAuthorizationAction.None
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

    private fun reconciliationAction(
        pending: PendingMediaAuthorization,
    ): MediaAuthorizationAction = when (pending.requestType) {
        PendingMediaAuthorizationType.TRASH -> MediaAuthorizationAction.ReconcileTrash(
            pending.uris,
        )
        PendingMediaAuthorizationType.RECOVERABLE_DELETE ->
            MediaAuthorizationAction.RetryRecoverableDelete(checkNotNull(pending.currentUri))
    }
}
