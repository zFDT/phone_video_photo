package com.frameyu.app.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAuthorizationStateTest {
    private val reducer = MediaAuthorizationStateReducer()

    @Test
    fun `恢复已授权回收站请求会继续 Room 对账`() {
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
    fun `系统授权成功且 continuation 存在时只恢复协程不重复对账`() {
        val transition = reducer.onActivityResult(
            current = trashPending(),
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
            granted = false,
            continuationPresent = true,
        )
        val withoutContinuation = reducer.onActivityResult(
            current = trashPending(),
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
            requestType = PendingMediaAuthorizationType.RECOVERABLE_DELETE,
            uris = listOf("content://media/current"),
            currentUri = "content://media/current",
        )

        val transition = reducer.onActivityResult(
            current = pending,
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

    private fun trashPending(
        phase: PendingMediaAuthorizationPhase = PendingMediaAuthorizationPhase.AWAITING_RESULT,
    ) = PendingMediaAuthorization(
        requestType = PendingMediaAuthorizationType.TRASH,
        uris = listOf("content://media/1", "content://media/2"),
        currentUri = null,
        phase = phase,
    )
}
