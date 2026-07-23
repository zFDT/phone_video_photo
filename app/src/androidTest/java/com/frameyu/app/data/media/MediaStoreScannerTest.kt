package com.frameyu.app.data.media

import android.content.Context
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import android.content.pm.PackageManager
import androidx.room.Room
import com.frameyu.app.data.db.FrameYuDatabase
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreScannerTest {
    @Test
    fun mediaStoreMetadataQueryEntryIsSafeWithoutMediaData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contentResolver = context.contentResolver

        val canRead = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        assumeTrue("测试设备未授予媒体读取权限", canRead)
        val expectedRows = queryMetadata(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) +
            queryMetadata(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            val scanner = MediaStoreScanner(contentResolver, database.mediaAssetDao())
            val indexed = scanner.scan()
            val stored = database.mediaAssetDao().count()
            assertTrue("扫描结果不能为负数", indexed >= 0)
            assertTrue("Room 写入数量应与扫描结果一致", indexed == stored)
            assertTrue("扫描结果不能超过 MediaStore 查询结果", indexed <= expectedRows)
            if (expectedRows > 0) {
                assertTrue("MediaStore 有媒体时扫描应写入 Room", stored > 0)
            } else {
                assertTrue("MediaStore 为空时 Room 不应产生媒体记录", stored == 0)
            }
        } finally {
            database.close()
        }
    }

    private fun queryMetadata(contentResolver: ContentResolver, collectionUri: Uri): Int {
        return contentResolver.query(
                collectionUri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                check(cursor.columnCount > 0)
                while (cursor.moveToNext()) {
                    check(cursor.getColumnIndex(MediaStore.MediaColumns._ID) >= 0)
                    check(cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED) >= 0)
                    check(cursor.getColumnIndex(MediaStore.MediaColumns.SIZE) >= 0)
                }
                cursor.count
            } ?: error("MediaStore 查询未返回 Cursor")
    }

}
