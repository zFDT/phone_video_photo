package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 在不可取消上下文中保存终态；保存失败不能替换原始取消语义。 */
internal suspend fun persistCancellationAndRethrow(
    cancellation: CancellationException,
    persistCancelled: suspend () -> Unit,
): Nothing {
    withContext(NonCancellable) {
        try {
            persistCancelled()
        } catch (persistenceFailure: Throwable) {
            if (persistenceFailure !== cancellation) {
                cancellation.addSuppressed(persistenceFailure)
            }
        }
    }
    throw cancellation
}

/**
 * 从首次挂起点开始包围任务生命周期，并保证终态更新和资源关闭都不覆盖 primary throwable。
 */
internal suspend fun <Result> runTaskLifecycle(
    taskId: Long,
    markCancelled: suspend (Long, CancellationException) -> Unit,
    markFailed: suspend (Long, Throwable) -> Unit,
    closeResource: () -> Unit,
    run: suspend () -> Result,
): Result {
    var primary: Throwable? = null
    try {
        return run()
    } catch (cancelled: CancellationException) {
        primary = cancelled
        persistCancellationAndRethrow(cancelled) {
            markCancelled(taskId, cancelled)
        }
    } catch (error: Throwable) {
        primary = error
        try {
            markFailed(taskId, error)
        } catch (persistenceFailure: Throwable) {
            error.addSuppressedUnlessSame(persistenceFailure)
        }
        throw error
    } finally {
        closeWithPrimary(primary, closeResource)
    }
}

internal fun closeWithPrimary(
    primary: Throwable?,
    closeResource: () -> Unit,
) {
    try {
        closeResource()
    } catch (closeFailure: Throwable) {
        if (primary == null) throw closeFailure
        primary.addSuppressedUnlessSame(closeFailure)
    }
}

private fun Throwable.addSuppressedUnlessSame(suppressed: Throwable) {
    if (suppressed !== this) addSuppressed(suppressed)
}
