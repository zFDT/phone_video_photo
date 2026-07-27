package com.frameyu.app.vision

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImageDecoderTest {
    @Test
    fun `EXIF 非正常方向时优先于媒体旋转`() {
        assertEquals(
            ExifBitmapTransform(180f, 1f, 1f),
            imageTransformFor(
                exifOrientation = ExifInterface.ORIENTATION_ROTATE_180,
                mediaRotationDegrees = 90,
            ),
        )
    }

    @Test
    fun `EXIF 正常时使用归一化后的媒体旋转`() {
        assertEquals(
            ExifBitmapTransform(270f, 1f, 1f),
            imageTransformFor(
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                mediaRotationDegrees = -90,
            ),
        )
        assertEquals(
            ExifBitmapTransform(90f, 1f, 1f),
            imageTransformFor(
                exifOrientation = ExifInterface.ORIENTATION_UNDEFINED,
                mediaRotationDegrees = 450,
            ),
        )
    }

    @Test
    fun `媒体旋转只允许九十度的整数倍`() {
        assertThrows<IllegalArgumentException> {
            imageTransformFor(
                exifOrientation = ExifInterface.ORIENTATION_NORMAL,
                mediaRotationDegrees = 45,
            )
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("预期 ${T::class.java.simpleName}，实际 ${error::class.java.simpleName}", error is T)
            return error as T
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }
}
