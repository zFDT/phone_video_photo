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
