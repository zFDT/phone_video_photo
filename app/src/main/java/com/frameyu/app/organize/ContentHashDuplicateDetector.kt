package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/** 按内容 SHA-256 分组的完全重复检测结果；读取失败的媒体不会被误判为重复。 */
data class ContentHashDuplicateResult(
    val groups: List<DuplicateGroup>,
    val failedUris: List<String>,
)

/** 以分块读取接口避免一次性把长视频全部加载进内存。 */
fun interface ContentHashReader {
    suspend fun readChunks(uri: String): Iterable<ByteArray>
}

/** 完全重复检测器；不执行删除，只生成建议保留项。 */
class ContentHashDuplicateDetector(
    private val reader: ContentHashReader,
) {
    suspend fun detect(assets: Iterable<MediaAssetEntity>): ContentHashDuplicateResult {
        val buckets = linkedMapOf<String, MutableList<MediaAssetEntity>>()
        val failedUris = mutableListOf<String>()
        assets.forEach { asset ->
            currentCoroutineContext().ensureActive()
            val hash = try {
                sha256(reader.readChunks(asset.uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedUris += asset.uri
                return@forEach
            }
            buckets.getOrPut(hash) { mutableListOf() } += asset
        }
        return ContentHashDuplicateResult(
            groups = buckets.filterValues { it.size > 1 }.map { (hash, group) ->
                DuplicateGroup(
                    duplicateKey = hash,
                    assets = group,
                    suggestedKeep = group.minWithOrNull(keepPreference)!!,
                )
            },
            failedUris = failedUris,
        )
    }

    private suspend fun sha256(chunks: Iterable<ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        chunks.forEach { chunk ->
            currentCoroutineContext().ensureActive()
            digest.update(chunk)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val keepPreference = compareBy<MediaAssetEntity> { it.takenAtMillis }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.id }
            .thenBy { it.uri }
    }
}
