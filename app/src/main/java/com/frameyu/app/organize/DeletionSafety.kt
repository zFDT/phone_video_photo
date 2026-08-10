package com.frameyu.app.organize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
