package com.frameyu.app.data.media

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.InputStream

/** 集中封装媒体 URI 的读取、持久化授权和原始媒体访问检查。 */
class MediaUriAccess private constructor(
    private val contentResolver: ContentResolver,
    private val context: Context?,
) {
    constructor(contentResolver: ContentResolver) : this(contentResolver, null)

    constructor(context: Context) : this(context.contentResolver, context)


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

    /** 检查 API 29+ 的媒体读取权限，不触发权限申请或 UI。 */
    fun hasMediaReadPermission(): Boolean {
        val currentContext = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val images = currentContext.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val videos = currentContext.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            // READ_MEDIA_VISUAL_USER_SELECTED 只代表用户选中的有限媒体，不足以执行全库扫描。
            images && videos
        } else {
            currentContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** 检查 ACCESS_MEDIA_LOCATION 权限，不触发权限申请或 UI。 */
    fun hasAccessMediaLocationPermission(): Boolean {
        val currentContext = context ?: return false
        return currentContext.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
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
