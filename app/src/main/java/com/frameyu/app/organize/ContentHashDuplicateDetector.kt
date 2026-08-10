package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 按内容 SHA-256 分组的完全重复检测结果；读取失败的媒体不会被误判为重复。 */
data class ContentHashDuplicateResult(
    val groups: List<DuplicateGroup>,
    val failedUris: List<String>,
)

/** Reader 在自身调用边界内完成流式读取和关闭，只向检测器返回内容摘要。 */
fun interface ContentHashReader {
    suspend fun sha256(uri: String): String
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
                reader.sha256(asset.uri)
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

    private companion object {
        val keepPreference = compareBy<MediaAssetEntity> { it.takenAtMillis }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.id }
            .thenBy { it.uri }
    }
}
