package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** 视觉哈希读取器；实现层应在后台线程按缩略图读取，不能保留原图。 */
fun interface VisualHashReader {
    suspend fun readHash(uri: String): Long
}

/** 相似媒体扫描结果；读取失败不会被误判为相似。 */
data class SimilarMediaDuplicateResult(
    val groups: List<DuplicateGroup>,
    val failedUris: List<String>,
)

/** 当前相似扫描只处理照片；视频留给后续代表帧管线。 */
internal fun similarPhotoCandidates(assets: Iterable<MediaAssetEntity>): List<MediaAssetEntity> =
    assets.filter { it.mimeType.startsWith("image/") }

/** 使用感知哈希生成相似媒体候选，不执行删除。 */
class SimilarMediaDuplicateDetector(
    private val reader: VisualHashReader,
    private val maxHammingDistance: Int = 8,
    private val timeWindowMillis: Long = 24 * 60 * 60 * 1_000L,
) {
    init {
        require(maxHammingDistance in 0..64) { "视觉哈希差异阈值必须在 0 到 64 之间" }
        require(timeWindowMillis >= 0L) { "时间窗口不能为负数" }
    }

    suspend fun detect(assets: Iterable<MediaAssetEntity>): SimilarMediaDuplicateResult {
        val coroutineContext = currentCoroutineContext()
        val assetList = mutableListOf<MediaAssetEntity>()
        assets.forEach { asset ->
            coroutineContext.ensureActive()
            assetList += asset
        }
        val sortedAssets = assetList.sortedWith { left, right ->
            coroutineContext.ensureActive()
            keepPreference.compare(left, right)
        }
        val hashedAssets = mutableListOf<HashedAsset>()
        val failedUris = mutableListOf<String>()
        sortedAssets.forEach { asset ->
            coroutineContext.ensureActive()
            val hash = try {
                reader.readHash(asset.uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                failedUris += asset.uri
                return@forEach
            }
            hashedAssets += HashedAsset(asset, hash)
        }

        val clusters = mutableListOf<MutableList<HashedAsset>>()
        var firstActiveCluster = 0
        hashedAssets.forEach { candidate ->
            coroutineContext.ensureActive()
            while (
                firstActiveCluster < clusters.size &&
                !isWithinTimeWindow(
                    clusters[firstActiveCluster].first().asset.takenAtMillis,
                    candidate.asset.takenAtMillis,
                )
            ) {
                coroutineContext.ensureActive()
                firstActiveCluster += 1
            }
            var cluster: MutableList<HashedAsset>? = null
            for (index in firstActiveCluster until clusters.size) {
                coroutineContext.ensureActive()
                val members = clusters[index]
                val representative = members.first()
                if (PerceptualHash.hammingDistance(representative.hash, candidate.hash) <= maxHammingDistance) {
                    cluster = members
                    break
                }
            }
            if (cluster == null) {
                clusters += mutableListOf(candidate)
            } else {
                cluster += candidate
            }
        }

        val groups = clusters
            .filter { it.size > 1 }
            .map { cluster ->
                val group = cluster.map(HashedAsset::asset)
                DuplicateGroup(
                    duplicateKey = "visual:" + group.map(MediaAssetEntity::id).sorted().joinToString(","),
                    assets = group,
                    suggestedKeep = group.first(),
                )
            }
        return SimilarMediaDuplicateResult(groups, failedUris)
    }

    private fun isWithinTimeWindow(earlierMillis: Long, laterMillis: Long): Boolean =
        earlierMillis > Long.MAX_VALUE - timeWindowMillis ||
            laterMillis <= earlierMillis + timeWindowMillis

    private data class HashedAsset(val asset: MediaAssetEntity, val hash: Long)

    private companion object {
        val keepPreference = compareBy<MediaAssetEntity> { it.takenAtMillis }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.id }
            .thenBy { it.uri }
    }
}
