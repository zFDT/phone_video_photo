package com.frameyu.app.organize

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.frameyu.app.data.media.mediaStoreSecondsToMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 通过 ContentResolver 读取删除前的实时媒体身份与内容版本。 */
class AndroidLiveMediaMetadataReader(
    private val contentResolver: ContentResolver,
) : LiveMediaMetadataReader {
    override suspend fun read(expected: DeletionPlanItem): LiveMediaMetadata? =
        withContext(Dispatchers.IO) {
            contentResolver.query(
                Uri.parse(expected.uri),
                PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                if (cursor.isNull(idIndex) || cursor.isNull(sizeIndex) || cursor.isNull(modifiedIndex)) {
                    return@use null
                }
                LiveMediaMetadata(
                    mediaId = cursor.getLong(idIndex),
                    uri = expected.uri,
                    sizeBytes = cursor.getLong(sizeIndex),
                    lastModifiedMillis = mediaStoreSecondsToMillis(cursor.getLong(modifiedIndex)),
                )
            }
        }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}
