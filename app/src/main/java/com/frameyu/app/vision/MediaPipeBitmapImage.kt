package com.frameyu.app.vision

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

/**
 * MediaPipe 的 MPImage 关闭时会释放其 Bitmap，因此使用内部副本隔离调用方持有的输入。
 */
internal inline fun <T> withMediaPipeBitmapImage(
    bitmap: Bitmap,
    operation: (MPImage) -> T,
): T {
    require(!bitmap.isRecycled) { "MediaPipe 输入 Bitmap 已被回收" }
    val ownedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        ?: error("无法复制 MediaPipe 输入 Bitmap")
    val image = try {
        BitmapImageBuilder(ownedBitmap).build()
    } catch (error: Exception) {
        ownedBitmap.recycle()
        throw error
    }
    return try {
        operation(image)
    } finally {
        try {
            image.close()
        } finally {
            if (!ownedBitmap.isRecycled) ownedBitmap.recycle()
        }
    }
}
