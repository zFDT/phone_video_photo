package com.frameyu.app.storage

/** 分析缓存的容量配置；只保存配置，不负责文件系统操作。 */
data class CacheSettings(
    val maxCacheBytes: Long = DEFAULT_CACHE_LIMIT_BYTES,
) {
    init {
        require(maxCacheBytes > 0L) { "缓存上限必须大于 0" }
        require(maxCacheBytes <= MAX_CACHE_LIMIT_BYTES) { "缓存上限不能超过 200GB" }
    }

    companion object {
        const val BYTES_PER_GIGABYTE: Long = 1024L * 1024L * 1024L
        const val DEFAULT_CACHE_LIMIT_BYTES: Long = 50L * BYTES_PER_GIGABYTE
        const val MAX_CACHE_LIMIT_BYTES: Long = 200L * BYTES_PER_GIGABYTE
    }
}
