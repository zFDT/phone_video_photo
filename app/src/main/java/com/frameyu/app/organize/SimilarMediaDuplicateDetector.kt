package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity

/** 视觉哈希读取器；实现层应在后台线程按缩略图读取，不能保留原图。 */
fun interface VisualHashReader {
    suspend fun readHash(uri: String): Long
}

/** 相似媒体扫描结果；读取失败不会被误判为相似。 */
data class SimilarMediaDuplicateResult(
    val groups: List<DuplicateGroup>,
    val failedUris: List<String>,
)

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
        val hashedAssets = mutableListOf<HashedAsset>()
        val failedUris = mutableListOf<String>()
        assets.forEach { asset ->
            runCatching { reader.readHash(asset.uri) }
                .onSuccess { hashedAssets += HashedAsset(asset, it) }
                .onFailure { failedUris += asset.uri }
        }

        val parent = hashedAssets.indices.toMutableList()
        for (left in hashedAssets.indices) {
            for (right in left + 1 until hashedAssets.size) {
                if (kotlin.math.abs(hashedAssets[left].asset.takenAtMillis - hashedAssets[right].asset.takenAtMillis) > timeWindowMillis) continue
                if (PerceptualHash.hammingDistance(hashedAssets[left].hash, hashedAssets[right].hash) <= maxHammingDistance) {
                    union(parent, left, right)
                }
            }
        }

        val groups = parent.indices.groupBy { find(parent, it) }
            .values
            .filter { it.size > 1 }
            .map { indexes ->
                val group = indexes.map { hashedAssets[it].asset }
                DuplicateGroup(
                    duplicateKey = "visual:" + group.map(MediaAssetEntity::id).sorted().joinToString(","),
                    assets = group,
                    suggestedKeep = group.minWithOrNull(keepPreference)!!,
                )
            }
        return SimilarMediaDuplicateResult(groups, failedUris)
    }

    private fun union(parent: MutableList<Int>, left: Int, right: Int) {
        val leftRoot = find(parent, left)
        val rightRoot = find(parent, right)
        if (leftRoot != rightRoot) parent[rightRoot] = leftRoot
    }

    private fun find(parent: MutableList<Int>, index: Int): Int {
        var root = index
        while (parent[root] != root) root = parent[root]
        var current = index
        while (parent[current] != current) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    private data class HashedAsset(val asset: MediaAssetEntity, val hash: Long)

    private companion object {
        val keepPreference = compareBy<MediaAssetEntity> { it.takenAtMillis }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.id }
            .thenBy { it.uri }
    }
}
