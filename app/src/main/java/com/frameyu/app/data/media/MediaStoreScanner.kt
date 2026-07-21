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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从 MediaStore 分批读取本地图片和视频，并写入媒体索引。 */
class MediaStoreScanner(
    private val contentResolver: ContentResolver,
    private val mediaAssetDao: MediaAssetDao,
    @VisibleForTesting private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchSize > 0) { "扫描批次大小必须大于零" }
    }

    /**
     * 扫描最近修改过的媒体；首次扫描时传入 null。
     * 返回本次从 MediaStore 读取并写入的媒体数量。
     */
    suspend fun scan(sinceLastModifiedMillis: Long? = null): Int = withContext(Dispatchers.IO) {
        var indexedCount = 0
        queryCollection(
            collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            sinceLastModifiedMillis = sinceLastModifiedMillis,
        ) { batch ->
            mediaAssetDao.upsertAll(batch)
            indexedCount += batch.size
        }
        queryCollection(
            collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
            sinceLastModifiedMillis = sinceLastModifiedMillis,
        ) { batch ->
            mediaAssetDao.upsertAll(batch)
            indexedCount += batch.size
        }
        indexedCount
    }

    private suspend fun queryCollection(
        collectionUri: Uri,
        isVideo: Boolean,
        sinceLastModifiedMillis: Long?,
        onBatch: suspend (List<MediaAssetEntity>) -> Unit,
    ) {
        val projection = projectionFor(isVideo)
        val selection = sinceLastModifiedMillis?.let {
            "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"
        }
        val selectionArgs = sinceLastModifiedMillis?.let {
            arrayOf((it.coerceAtLeast(0L) / MILLIS_PER_SECOND).toString())
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC, ${MediaStore.MediaColumns._ID} ASC"

        contentResolver.query(
            collectionUri,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
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
        }
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
        val lastModifiedMillis = secondsToMillis(longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L)
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

    private fun secondsToMillis(seconds: Long): Long {
        return seconds.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND) * MILLIS_PER_SECOND
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val MILLIS_PER_SECOND = 1_000L
        const val DEGREES_IN_CIRCLE = 360
        const val HAS_GAIN_MAP_COLUMN = "has_gain_map"
    }
}
