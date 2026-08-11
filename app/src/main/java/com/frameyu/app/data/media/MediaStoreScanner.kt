package com.frameyu.app.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import com.frameyu.app.data.db.MediaAssetDao
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun validateMediaStoreBatchSize(batchSize: Int) {
    require(batchSize > 0) { "扫描批次大小必须大于零" }
}

internal fun mediaStoreSecondsToMillis(seconds: Long): Long {
    return seconds.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND) * MILLIS_PER_SECOND
}

internal fun shouldRetryWithoutGainMapColumn(error: Throwable): Boolean =
    error is IllegalArgumentException && error.message?.contains("has_gain_map", ignoreCase = true) == true

internal fun filterChangedMediaAssets(
    candidates: List<MediaAssetEntity>,
    existingByUri: Map<String, MediaAssetEntity>,
): List<MediaAssetEntity> = candidates.filter { candidate ->
    val existing = existingByUri[candidate.uri]
    existing == null || existing.lastModifiedMillis != candidate.lastModifiedMillis ||
        existing.sizeBytes != candidate.sizeBytes
}

/** 从 MediaStore 分批读取本地图片和视频，并写入媒体索引。 */
class MediaStoreScanner(
    private val contentResolver: ContentResolver,
    private val mediaAssetDao: MediaAssetDao,
    @VisibleForTesting private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        validateMediaStoreBatchSize(batchSize)
    }

    /**
     * 扫描最近修改过的媒体；首次扫描时传入 null。
     * 返回本次新建或发生元数据变化并写入的媒体数量。
     */
    suspend fun scan(
        sinceLastModifiedMillis: Long? = null,
        scanBoundaryMillis: Long = System.currentTimeMillis(),
        onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        var indexedCount = 0
        var processed = 0
        val total = queryCount(sinceLastModifiedMillis, scanBoundaryMillis)
        onProgress?.invoke(0, total)
        queryCollection(
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            sinceLastModifiedMillis = sinceLastModifiedMillis,
            scanBoundaryMillis = scanBoundaryMillis,
        ) { batch ->
            indexedCount += upsertChangedBatch(batch)
            processed += batch.size
            onProgress?.invoke(processed, total)
        }
        queryCollection(
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
            sinceLastModifiedMillis = sinceLastModifiedMillis,
            scanBoundaryMillis = scanBoundaryMillis,
        ) { batch ->
            indexedCount += upsertChangedBatch(batch)
            processed += batch.size
            onProgress?.invoke(processed, total)
        }
        indexedCount
    }

    suspend fun mediaCount(): Int = withContext(Dispatchers.IO) {
        mediaAssetDao.count()
    }

    fun observeMediaCount(): Flow<Int> = mediaAssetDao.observeCount()

    fun observeVideos(): Flow<List<MediaAssetEntity>> = mediaAssetDao.observeVideos()

    private fun queryCount(sinceLastModifiedMillis: Long?, scanBoundaryMillis: Long): Int {
        val window = createMediaStoreScanWindow(sinceLastModifiedMillis, scanBoundaryMillis)
        return listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).sumOf { uri ->
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), window.selection, window.selectionArgs, null)
                ?.use { it.count } ?: error("MediaStore 查询未返回 Cursor: $uri")
        }
    }

    private suspend fun upsertChangedBatch(batch: List<MediaAssetEntity>): Int {
        val existingByUri = mediaAssetDao.getByUris(batch.map(MediaAssetEntity::uri)).associateBy(MediaAssetEntity::uri)
        val changedAssets = filterChangedMediaAssets(batch, existingByUri)
        if (changedAssets.isNotEmpty()) {
            mediaAssetDao.upsertAll(changedAssets)
        }
        return changedAssets.size
    }

    private suspend fun queryCollection(
        collectionUri: Uri,
        isVideo: Boolean,
        sinceLastModifiedMillis: Long?,
        scanBoundaryMillis: Long,
        onBatch: suspend (List<MediaAssetEntity>) -> Unit,
    ) {
        val projection = projectionFor(isVideo)
        val window = createMediaStoreScanWindow(sinceLastModifiedMillis, scanBoundaryMillis)
        val selection = window.selection
        val selectionArgs = window.selectionArgs
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC, ${MediaStore.MediaColumns._ID} ASC"

        val cursor = try {
            contentResolver.query(collectionUri, projection, selection, selectionArgs, sortOrder)
        } catch (error: IllegalArgumentException) {
            if (!isVideo && projection.contains(HAS_GAIN_MAP_COLUMN) && shouldRetryWithoutGainMapColumn(error)) {
                contentResolver.query(
                    collectionUri,
                    projection.filterNot { it == HAS_GAIN_MAP_COLUMN }.toTypedArray(),
                    selection,
                    selectionArgs,
                    sortOrder,
                )
            } else {
                throw error
            }
        }
        cursor?.use { cursor ->
            val batch = ArrayList<MediaAssetEntity>(batchSize)
            while (cursor.moveToNext()) {
                val asset = cursor.toMediaAssetEntity(collectionUri, isVideo)
                if (asset != null) {
                    batch += asset
                    if (batch.size == batchSize) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }
            if (batch.isNotEmpty()) {
                onBatch(batch.toList())
            }
        } ?: error("MediaStore 查询未返回 Cursor: $collectionUri")
    }

    private fun projectionFor(isVideo: Boolean): Array<String> {
        val columns = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
        )
        if (isVideo) {
            columns += MediaStore.Video.VideoColumns.DURATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            columns += MediaStore.MediaColumns.RELATIVE_PATH
        }
        if (!isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            columns += HAS_GAIN_MAP_COLUMN
        }
        return columns.toTypedArray()
    }

    private fun Cursor.toMediaAssetEntity(collectionUri: Uri, isVideo: Boolean): MediaAssetEntity? {
        val id = longOrNull(MediaStore.MediaColumns._ID) ?: return null
        if (id < 0L) return null

        val uri = ContentUris.withAppendedId(collectionUri, id)
        val lastModifiedMillis = mediaStoreSecondsToMillis(
            longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L,
        )
        val takenAtMillis = longOrNull(MediaStore.MediaColumns.DATE_TAKEN)
            ?.takeIf { it > 0L }
            ?: lastModifiedMillis
        val rotation = intOrNull(MediaStore.Images.Media.ORIENTATION)
            ?.let { ((it % DEGREES_IN_CIRCLE) + DEGREES_IN_CIRCLE) % DEGREES_IN_CIRCLE }
            ?: 0

        return MediaAssetEntity(
            id = id,
            uri = uri.toString(),
            mimeType = stringOrNull(MediaStore.MediaColumns.MIME_TYPE)
                ?: contentResolver.getType(uri)
                ?: if (isVideo) "video/*" else "image/*",
            takenAtMillis = takenAtMillis,
            durationMillis = if (isVideo) longOrNull(MediaStore.Video.VideoColumns.DURATION)?.coerceAtLeast(0L) ?: 0L else 0L,
            width = intOrNull(MediaStore.MediaColumns.WIDTH)?.coerceAtLeast(0) ?: 0,
            height = intOrNull(MediaStore.MediaColumns.HEIGHT)?.coerceAtLeast(0) ?: 0,
            rotationDegrees = rotation,
            sizeBytes = longOrNull(MediaStore.MediaColumns.SIZE)?.coerceAtLeast(0L) ?: 0L,
            isHdr = !isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                intOrNull(HAS_GAIN_MAP_COLUMN)?.let { it != 0 } == true,
            relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                null
            },
            lastModifiedMillis = lastModifiedMillis,
        )
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val DEGREES_IN_CIRCLE = 360
        const val HAS_GAIN_MAP_COLUMN = "has_gain_map"
    }
}

internal data class MediaStoreScanWindow(val selection: String?, val selectionArgs: Array<String>)

internal fun createMediaStoreScanWindow(sinceLastModifiedMillis: Long?, scanBoundaryMillis: Long): MediaStoreScanWindow {
    val upper = (scanBoundaryMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toString()
    if (sinceLastModifiedMillis == null) {
        return MediaStoreScanWindow(
            "${MediaStore.MediaColumns.DATE_MODIFIED} < ?",
            arrayOf(upper),
        )
    }
    return MediaStoreScanWindow(
        "${MediaStore.MediaColumns.DATE_MODIFIED} >= ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} < ?",
        arrayOf((sinceLastModifiedMillis.coerceAtLeast(0L) / MILLIS_PER_SECOND).toString(), upper),
    )
}

private const val MILLIS_PER_SECOND = 1_000L
