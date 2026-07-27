package com.frameyu.app.organize

import kotlin.math.sqrt

/** 带时间信息的媒体向量，用于相似照片候选分组。 */
data class MediaEmbedding(
    val mediaId: Long,
    val takenAtMillis: Long,
    val values: FloatArray,
) {
    init {
        require(values.isNotEmpty()) { "媒体向量不能为空" }
        require(values.all(Float::isFinite)) { "媒体向量必须是有限数" }
    }
}

data class SimilarMediaGroup(val mediaIds: List<Long>, val similarity: Float)

/** 基于余弦相似度和时间窗口生成相似媒体候选，不执行删除。 */
class SimilarMediaGrouper(
    private val threshold: Float = 0.92f,
    private val timeWindowMillis: Long = 24 * 60 * 60 * 1_000L,
) {
    init {
        require(threshold in 0f..1f) { "相似度阈值必须在 0 到 1 之间" }
        require(timeWindowMillis >= 0L) { "时间窗口不能为负数" }
    }

    fun group(embeddings: List<MediaEmbedding>): List<SimilarMediaGroup> {
        val parent = embeddings.indices.toMutableList()
        val matchedEdges = mutableListOf<MatchedEdge>()
        for (left in embeddings.indices) {
            for (right in left + 1 until embeddings.size) {
                if (embeddings[left].values.size != embeddings[right].values.size) continue
                if (kotlin.math.abs(embeddings[left].takenAtMillis - embeddings[right].takenAtMillis) > timeWindowMillis) continue
                val similarity = cosineSimilarity(embeddings[left].values, embeddings[right].values)
                if (similarity >= threshold) {
                    union(parent, left, right)
                    matchedEdges += MatchedEdge(left, right, similarity)
                }
            }
        }
        return parent.indices.groupBy { find(parent, it) }
            .values
            .filter { it.size > 1 }
            .map { indexes ->
                SimilarMediaGroup(
                    mediaIds = indexes.map { embeddings[it].mediaId },
                    similarity = matchedEdges
                        .filter { edge -> edge.left in indexes && edge.right in indexes }
                        .maxOf { it.similarity },
                )
            }
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

    private data class MatchedEdge(val left: Int, val right: Int, val similarity: Float)
}

internal fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    require(left.size == right.size && left.isNotEmpty()) { "向量维度必须一致且非空" }
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    left.indices.forEach { index ->
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
    return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat().coerceIn(-1f, 1f)
}
