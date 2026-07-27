package com.frameyu.app.storage

/** 分析缓存条目的元数据；清理时按最近访问时间判断优先级。 */
data class AnalysisCacheEntry(
    val key: String,
    val sizeBytes: Long,
    val lastAccessMillis: Long,
) {
    init {
        require(key.isNotBlank()) { "缓存条目标识不能为空" }
        require(sizeBytes >= 0L) { "缓存条目大小不能为负数" }
        require(lastAccessMillis >= 0L) { "缓存条目访问时间不能为负数" }
    }
}

/** 由缓存管理器计算出的删除建议，不执行实际删除。 */
data class CacheCleanupPlan(
    val entriesToDelete: List<AnalysisCacheEntry>,
    val bytesToFree: Long,
    val resultingSizeBytes: Long,
)

/** 纯 Kotlin 的分析缓存容量计算器。 */
class AnalysisCacheManager(
    private val settings: CacheSettings = CacheSettings(),
) {
    /** 计算缓存条目大小总和。 */
    fun calculateSize(entries: Iterable<AnalysisCacheEntry>): Long = entries.fold(0L) { total, entry ->
        Math.addExact(total, entry.sizeBytes)
    }

    /** 判断给定缓存大小是否严格超过配置上限。 */
    fun isOverLimit(cacheSizeBytes: Long): Boolean {
        require(cacheSizeBytes >= 0L) { "缓存大小不能为负数" }
        return cacheSizeBytes > settings.maxCacheBytes
    }

    /** 按最早访问优先生成清理计划，直到缓存回到配置上限以内。 */
    fun planCleanup(entries: List<AnalysisCacheEntry>): CacheCleanupPlan {
        val totalSizeBytes = calculateSize(entries)
        if (!isOverLimit(totalSizeBytes)) {
            return CacheCleanupPlan(emptyList(), 0L, totalSizeBytes)
        }

        val bytesToTarget = totalSizeBytes - settings.maxCacheBytes
        val entriesToDelete = mutableListOf<AnalysisCacheEntry>()
        var bytesToFree = 0L
        for (entry in entries.sortedWith(compareBy<AnalysisCacheEntry> { it.lastAccessMillis }.thenBy { it.key })) {
            entriesToDelete += entry
            bytesToFree = Math.addExact(bytesToFree, entry.sizeBytes)
            if (bytesToFree >= bytesToTarget) break
        }
        return CacheCleanupPlan(
            entriesToDelete = entriesToDelete,
            bytesToFree = bytesToFree,
            resultingSizeBytes = totalSizeBytes - bytesToFree,
        )
    }
}
