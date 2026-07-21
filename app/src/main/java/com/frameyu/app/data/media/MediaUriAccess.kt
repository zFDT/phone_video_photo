package com.frameyu.app.data.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.InputStream

/** 集中封装媒体 URI 的读取、持久化授权和原始媒体访问检查。 */
class MediaUriAccess(
    private val contentResolver: ContentResolver,
) {
    constructor(context: Context) : this(context.contentResolver)

    /** 打开媒体内容流；调用方负责关闭返回的流。 */
    fun openInputStream(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

    /** 为 Photo Picker 返回的 URI 保存可持久化的读取授权。 */
    fun persistPhotoPickerReadPermission(uri: Uri): Boolean {
        return runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
    }

    /** 检查当前进程是否能读取该媒体 URI。 */
    fun hasReadAccess(uri: Uri): Boolean {
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    /** 检查是否可以访问媒体原始内容，不触发权限申请或 UI。 */
    fun canReadOriginalMedia(uri: Uri): Boolean = hasReadAccess(uri)
}
