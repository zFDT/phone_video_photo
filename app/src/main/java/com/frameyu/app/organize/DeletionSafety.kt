package com.frameyu.app.organize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

data class LiveMediaMetadata(
    val mediaId: Long,
    val uri: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

fun interface LiveMediaMetadataReader {
    suspend fun read(expected: DeletionPlanItem): LiveMediaMetadata?
}

fun interface ContentSha256Reader {
    suspend fun sha256(uri: String): String
}

sealed interface DeletionValidationResult {
    data object Valid : DeletionValidationResult

    data class Blocked(val reason: String) : DeletionValidationResult
}

class DeletionSafetyValidator(
    private val metadataReader: LiveMediaMetadataReader,
    private val sha256Reader: ContentSha256Reader,
) {
    suspend fun validate(plan: DeletionPlan): DeletionValidationResult {
        val affectedItems = plan.affectedGroups.flatMap(DeletionPlanGroup::items)
        if (affectedItems.isEmpty() || !affectedItems.containsAll(plan.items)) {
            return DeletionValidationResult.Blocked("删除计划缺少完整组快照")
        }
        affectedItems.distinct().forEach { expected ->
            val current = try {
                metadataReader.read(expected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return DeletionValidationResult.Blocked("媒体读取失败，请重新扫描并确认")
            }
            if (current == null || current != expected.toLiveMetadata()) {
                return DeletionValidationResult.Blocked("媒体内容已变化，请重新扫描并确认")
            }
        }
        plan.affectedGroups
            .filter { group -> group.filter == OrganizerFilter.EXACT_DUPLICATE }
            .forEach { group ->
                val expectedHash = group.exactContentHash
                    ?: return DeletionValidationResult.Blocked("完全重复组缺少内容摘要")
                group.items.forEach { item ->
                    val currentHash = try {
                        sha256Reader.sha256(item.uri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return DeletionValidationResult.Blocked("媒体内容读取失败，请重新扫描并确认")
                    }
                    if (currentHash != expectedHash) {
                        return DeletionValidationResult.Blocked("完全重复组内容已变化，请重新扫描并确认")
                    }
                }
            }
        return DeletionValidationResult.Valid
    }

    private fun DeletionPlanItem.toLiveMetadata() = LiveMediaMetadata(
        mediaId = mediaId,
        uri = uri,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis,
    )

    companion object {
        fun unavailable() = DeletionSafetyValidator(
            metadataReader = LiveMediaMetadataReader { null },
            sha256Reader = ContentSha256Reader { error("未配置内容读取器") },
        )
    }
}

data class MediaDeletionFailure(
    val uri: String,
    val reason: String,
)

data class MediaDeletionResult(
    val succeededUris: List<String>,
    val failures: List<MediaDeletionFailure>,
)

sealed interface MediaDeleteAttempt {
    data object Deleted : MediaDeleteAttempt

    data object NotHandled : MediaDeleteAttempt

    data class RequiresAuthorization(
        val authorize: suspend () -> Boolean,
    ) : MediaDeleteAttempt
}

/** 确保同一 Activity 同时只挂起一个系统授权面板。 */
class MediaAuthorizationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

/** Android 11+ 的系统回收站请求：只有系统确认成功后才同步本地索引。 */
suspend fun executeTrashRequestDeletion(
    uris: List<String>,
    requestTrash: suspend (List<String>) -> Boolean,
    removeSucceededFromIndex: suspend (List<String>) -> Unit,
): MediaDeletionResult {
    if (uris.isEmpty()) return MediaDeletionResult(emptyList(), emptyList())
    val approved = try {
        requestTrash(uris)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        return MediaDeletionResult(
            succeededUris = emptyList(),
            failures = uris.map { uri ->
                MediaDeletionFailure(uri, error.message ?: "系统授权请求失败")
            },
        )
    }
    if (!approved) {
        return MediaDeletionResult(
            succeededUris = emptyList(),
            failures = uris.map { uri -> MediaDeletionFailure(uri, "用户取消系统删除授权") },
        )
    }
    withContext(NonCancellable) {
        removeSucceededFromIndex(uris)
    }
    return MediaDeletionResult(
        succeededUris = uris.toList(),
        failures = emptyList(),
    )
}

/** Android 10 的逐项删除：遇到可恢复授权时，仅授权并重试当前 Uri 一次。 */
suspend fun executeRecoverableMediaDeletion(
    uris: List<String>,
    deleteOne: suspend (String) -> MediaDeleteAttempt,
    removeSucceededFromIndex: suspend (List<String>) -> Unit,
    onAuthorizedRetrySettled: suspend (String) -> Unit = {},
): MediaDeletionResult {
    val succeeded = mutableListOf<String>()
    val pendingSync = mutableListOf<String>()
    val failures = mutableListOf<MediaDeletionFailure>()
    try {
        uris.forEach { uri ->
            var settleAuthorizationOnError = false
            try {
                when (val firstAttempt = deleteOne(uri)) {
                    MediaDeleteAttempt.Deleted -> {
                        succeeded += uri
                        pendingSync += uri
                    }
                    MediaDeleteAttempt.NotHandled -> {
                        failures += MediaDeletionFailure(uri, "系统未处理该媒体")
                    }
                    is MediaDeleteAttempt.RequiresAuthorization -> {
                        val approved = firstAttempt.authorize()
                        if (!approved) {
                            failures += MediaDeletionFailure(uri, "用户取消系统删除授权")
                        } else {
                            settleAuthorizationOnError = true
                            when (deleteOne(uri)) {
                                MediaDeleteAttempt.Deleted -> {
                                    try {
                                        withContext(NonCancellable) {
                                            removeSucceededFromIndex(listOf(uri))
                                        }
                                    } catch (error: Exception) {
                                        settleAuthorizationOnError = false
                                        throw error
                                    }
                                    settleAuthorizationOnError = false
                                    onAuthorizedRetrySettled(uri)
                                    succeeded += uri
                                }
                                MediaDeleteAttempt.NotHandled -> {
                                    settleAuthorizationOnError = false
                                    onAuthorizedRetrySettled(uri)
                                    failures += MediaDeletionFailure(uri, "系统未处理该媒体")
                                }
                                is MediaDeleteAttempt.RequiresAuthorization -> {
                                    settleAuthorizationOnError = false
                                    onAuthorizedRetrySettled(uri)
                                    failures += MediaDeletionFailure(uri, "系统仍需媒体删除授权")
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (settleAuthorizationOnError) {
                    onAuthorizedRetrySettled(uri)
                }
                failures += MediaDeletionFailure(uri, error.message ?: "未知错误")
            }
        }
    } catch (cancelled: CancellationException) {
        if (pendingSync.isNotEmpty()) {
            try {
                withContext(NonCancellable) {
                    removeSucceededFromIndex(pendingSync.toList())
                }
            } catch (syncError: Exception) {
                cancelled.addSuppressed(syncError)
            }
        }
        throw cancelled
    }
    if (pendingSync.isNotEmpty()) {
        withContext(NonCancellable) {
            removeSucceededFromIndex(pendingSync.toList())
        }
    }
    return MediaDeletionResult(
        succeededUris = succeeded.toList(),
        failures = failures.toList(),
    )
}

suspend fun executeMediaDeletion(
    uris: List<String>,
    deleteOne: suspend (String) -> Boolean,
    removeSucceededFromIndex: suspend (List<String>) -> Unit,
): MediaDeletionResult {
    val succeeded = mutableListOf<String>()
    val failures = mutableListOf<MediaDeletionFailure>()
    uris.forEach { uri ->
        try {
            if (deleteOne(uri)) {
                succeeded += uri
            } else {
                failures += MediaDeletionFailure(uri, "系统未处理该媒体")
            }
        } catch (cancelled: CancellationException) {
            if (succeeded.isNotEmpty()) {
                try {
                    withContext(NonCancellable) {
                        removeSucceededFromIndex(succeeded.toList())
                    }
                } catch (syncError: Exception) {
                    cancelled.addSuppressed(syncError)
                }
            }
            throw cancelled
        } catch (error: Exception) {
            failures += MediaDeletionFailure(uri, error.message ?: "未知错误")
        }
    }
    if (succeeded.isNotEmpty()) {
        withContext(NonCancellable) {
            removeSucceededFromIndex(succeeded.toList())
        }
    }
    return MediaDeletionResult(
        succeededUris = succeeded.toList(),
        failures = failures.toList(),
    )
}

fun sha256Hex(chunks: Iterable<ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    chunks.forEach(digest::update)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

suspend fun sha256HexCancellable(inputStream: InputStream): String = inputStream.use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(SHA_256_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = stream.read(buffer)
        currentCoroutineContext().ensureActive()
        if (count < 0) break
        if (count > 0) {
            digest.update(buffer, 0, count)
        }
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private const val SHA_256_BUFFER_SIZE = 1024 * 1024

sealed interface DeletionExecutionResult {
    data class Completed(val result: MediaDeletionResult) : DeletionExecutionResult

    data class Blocked(val reason: String) : DeletionExecutionResult
}

enum class OrganizerOperation {
    EXACT_SCAN,
    SIMILAR_SCAN,
    DELETION,
}

class OrganizerOperationGate {
    private var running: OrganizerOperation? = null

    @Synchronized
    fun tryStart(operation: OrganizerOperation): Boolean {
        if (running != null) return false
        running = operation
        return true
    }

    @Synchronized
    fun finish(operation: OrganizerOperation) {
        if (running == operation) running = null
    }

    @Synchronized
    fun isRunning(): Boolean = running != null
}
