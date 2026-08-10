package com.frameyu.app.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAuthorizationStateTest {
    private var nowMillis = 1_000_000L
    private val reducer = MediaAuthorizationStateReducer(clock = { nowMillis })

    @Test
    fun `fresh granted 回收站请求继续普通 Room 对账`() {
        val pending = trashPending(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
        )

        val action = reducer.recover(pending)

        assertEquals(
            MediaAuthorizationAction.ReconcileTrash(listOf("content://media/1", "content://media/2")),
            action,
        )
    }

    @Test
    fun `expired granted 回收站请求走过期安全对账`() {
        val pending = trashPending(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
        )

        assertEquals(
            MediaAuthorizationAction.ReconcileExpiredTrash(pending.uris),
            reducer.recover(pending),
        )
    }

    @Test
    fun `expired granted Android Q 请求走当前 URI 过期对账`() {
        val pending = recoverablePending(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
        )

        assertEquals(
            MediaAuthorizationAction.ReconcileExpiredRecoverableDelete("content://media/3"),
            reducer.recover(pending),
        )
    }

    @Test
    fun `expired granted 在 UNKNOWN 对账后仍可清锁并接受新请求`() {
        val pending = trashPending(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
        )

        val cleared = reducer.clearExpired(pending, pending)
        val next = reducer.begin(cleared, recoverablePending())

        assertNull(cleared)
        assertSame(MediaAuthorizationAction.None, next.action)
    }

    @Test
    fun `系统授权成功且 continuation 存在时只恢复协程不重复对账`() {
        val transition = reducer.onActivityResult(
            current = trashPending(),
            callbackRequestId = REQUEST_ID_TRASH,
            granted = true,
            continuationPresent = true,
        )

        assertEquals(MediaAuthorizationAction.ResumeContinuation(true), transition.action)
        assertEquals(
            PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
            transition.pending?.phase,
        )
    }

    @Test
    fun `系统授权成功但 continuation 缺失时直接执行回收站对账`() {
        val transition = reducer.onActivityResult(
            current = trashPending(),
            callbackRequestId = REQUEST_ID_TRASH,
            granted = true,
            continuationPresent = false,
        )

        assertEquals(
            MediaAuthorizationAction.ReconcileTrash(listOf("content://media/1", "content://media/2")),
            transition.action,
        )
        assertEquals(
            PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
            transition.pending?.phase,
        )
    }

    @Test
    fun `用户拒绝授权会清理 pending 且不触发 Room 对账`() {
        val withContinuation = reducer.onActivityResult(
            current = trashPending(),
            callbackRequestId = REQUEST_ID_TRASH,
            granted = false,
            continuationPresent = true,
        )
        val withoutContinuation = reducer.onActivityResult(
            current = trashPending(),
            callbackRequestId = REQUEST_ID_TRASH,
            granted = false,
            continuationPresent = false,
        )

        assertNull(withContinuation.pending)
        assertEquals(MediaAuthorizationAction.ResumeContinuation(false), withContinuation.action)
        assertNull(withoutContinuation.pending)
        assertSame(MediaAuthorizationAction.None, withoutContinuation.action)
    }

    @Test
    fun `Android Q continuation 缺失时只重试 pending 当前 URI`() {
        val pending = PendingMediaAuthorization(
            requestId = REQUEST_ID_Q,
            createdAtMillis = nowMillis,
            requestType = PendingMediaAuthorizationType.RECOVERABLE_DELETE,
            uris = listOf("content://media/current"),
            currentUri = "content://media/current",
        )

        val transition = reducer.onActivityResult(
            current = pending,
            callbackRequestId = REQUEST_ID_Q,
            granted = true,
            continuationPresent = false,
        )

        assertEquals(
            MediaAuthorizationAction.RetryRecoverableDelete("content://media/current"),
            transition.action,
        )
    }

    @Test
    fun `重复回调不会再次恢复 continuation 或重复对账`() {
        val grantedPending = trashPending(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
        )

        val transition = reducer.onActivityResult(
            current = grantedPending,
            callbackRequestId = REQUEST_ID_TRASH,
            granted = true,
            continuationPresent = false,
        )

        assertEquals(grantedPending, transition.pending)
        assertSame(MediaAuthorizationAction.None, transition.action)
    }

    @Test
    fun `pending 未完成时拒绝新请求且只完成匹配请求`() {
        val current = trashPending()
        val replacement = PendingMediaAuthorization(
            requestId = REQUEST_ID_Q,
            createdAtMillis = nowMillis,
            requestType = PendingMediaAuthorizationType.RECOVERABLE_DELETE,
            uris = listOf("content://media/3"),
            currentUri = "content://media/3",
        )

        val rejected = reducer.begin(current, replacement)
        val unmatchedCompletion = reducer.complete(current, replacement)
        val granted = current.copy(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
        )

        assertEquals(current, rejected.pending)
        assertSame(MediaAuthorizationAction.RejectNewRequest, rejected.action)
        assertEquals(current, unmatchedCompletion)
        assertNull(reducer.complete(granted, granted))
    }

    @Test
    fun `等待系统结果的恢复状态不会提前对账`() {
        assertSame(MediaAuthorizationAction.None, reducer.recover(trashPending()))
        assertTrue(reducer.begin(null, trashPending()).pending != null)
    }

    @Test
    fun `系统面板启动失败只清理匹配的等待请求`() {
        val current = trashPending()
        val other = PendingMediaAuthorization(
            requestId = REQUEST_ID_Q,
            createdAtMillis = nowMillis,
            requestType = PendingMediaAuthorizationType.RECOVERABLE_DELETE,
            uris = listOf("content://media/other"),
            currentUri = "content://media/other",
        )
        val granted = current.copy(
            phase = PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION,
        )

        assertNull(reducer.launchFailed(current, current))
        assertEquals(current, reducer.launchFailed(current, other))
        assertEquals(granted, reducer.launchFailed(granted, current))
    }

    @Test
    fun `等待结果未到过期边界时保持互斥`() {
        val pending = trashPending(
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS + 1,
        )

        assertSame(MediaAuthorizationAction.None, reducer.recover(pending))
        assertSame(
            MediaAuthorizationAction.RejectNewRequest,
            reducer.begin(pending, recoverablePending()).action,
        )
    }

    @Test
    fun `等待结果到达过期边界时触发安全回收站对账`() {
        val pending = trashPending(
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
        )

        assertEquals(
            MediaAuthorizationAction.ReconcileExpiredTrash(pending.uris),
            reducer.recover(pending),
        )
    }

    @Test
    fun `过期 Android Q 请求只检查当前 URI 状态`() {
        val pending = recoverablePending(
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
        )

        assertEquals(
            MediaAuthorizationAction.ReconcileExpiredRecoverableDelete("content://media/3"),
            reducer.recover(pending),
        )
    }

    @Test
    fun `只有匹配且过期的请求可以释放持久锁`() {
        val stale = trashPending(
            createdAtMillis = nowMillis - MEDIA_AUTHORIZATION_EXPIRY_MILLIS,
        )
        val fresh = trashPending()

        assertNull(reducer.clearExpired(stale, stale))
        assertEquals(fresh, reducer.clearExpired(fresh, fresh))
        assertEquals(stale, reducer.clearExpired(stale, fresh))
    }

    @Test
    fun `不匹配 requestId 的系统回调不会改变当前请求`() {
        val pending = trashPending()

        val transition = reducer.onActivityResult(
            current = pending,
            callbackRequestId = REQUEST_ID_Q,
            granted = true,
            continuationPresent = false,
        )

        assertEquals(pending, transition.pending)
        assertSame(MediaAuthorizationAction.None, transition.action)
    }

    @Test
    fun `授权记录拒绝超过单块上限和非法 requestId`() {
        val tooMany = runCatching {
            trashPending(uris = (0..MAX_MEDIA_AUTHORIZATION_URIS).map { "content://media/$it" })
        }.exceptionOrNull()
        val invalidId = runCatching {
            trashPending(requestId = "short")
        }.exceptionOrNull()

        assertTrue(tooMany is IllegalArgumentException)
        assertTrue(invalidId is IllegalArgumentException)
    }

    @Test
    fun `持久化 URI 数量只接受一到单块上限`() {
        assertTrue(isValidPersistedMediaAuthorizationUriCount(1))
        assertTrue(isValidPersistedMediaAuthorizationUriCount(MAX_MEDIA_AUTHORIZATION_URIS))
        assertTrue(!isValidPersistedMediaAuthorizationUriCount(0))
        assertTrue(!isValidPersistedMediaAuthorizationUriCount(-1))
        assertTrue(!isValidPersistedMediaAuthorizationUriCount(MAX_MEDIA_AUTHORIZATION_URIS + 1))
    }

    @Test
    fun `大批 URI 严格拆为最多一百项的授权块`() {
        val uris = (0 until 205).map { "content://media/$it" }

        val chunks = chunkMediaAuthorizationUris(uris)

        assertEquals(listOf(100, 100, 5), chunks.map(List<String>::size))
        assertEquals(uris, chunks.flatten())
    }

    private fun trashPending(
        phase: PendingMediaAuthorizationPhase = PendingMediaAuthorizationPhase.AWAITING_RESULT,
        requestId: String = REQUEST_ID_TRASH,
        createdAtMillis: Long = nowMillis,
        uris: List<String> = listOf("content://media/1", "content://media/2"),
    ) = PendingMediaAuthorization(
        requestId = requestId,
        createdAtMillis = createdAtMillis,
        requestType = PendingMediaAuthorizationType.TRASH,
        uris = uris,
        currentUri = null,
        phase = phase,
    )

    private fun recoverablePending(
        phase: PendingMediaAuthorizationPhase = PendingMediaAuthorizationPhase.AWAITING_RESULT,
        createdAtMillis: Long = nowMillis,
    ) = PendingMediaAuthorization(
        requestId = REQUEST_ID_Q,
        createdAtMillis = createdAtMillis,
        requestType = PendingMediaAuthorizationType.RECOVERABLE_DELETE,
        uris = listOf("content://media/3"),
        currentUri = "content://media/3",
        phase = phase,
    )

    private companion object {
        const val REQUEST_ID_TRASH = "11111111111111111111111111111111"
        const val REQUEST_ID_Q = "22222222222222222222222222222222"
    }
}
