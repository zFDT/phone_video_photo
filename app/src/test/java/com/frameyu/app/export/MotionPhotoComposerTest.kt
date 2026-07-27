package com.frameyu.app.export

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoComposerTest {
    @Test
    fun `JPEG 中写入 Motion Photo XMP 并把 MP4 追加到末尾`() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x01, 0x11,
            0xFF.toByte(), 0xD9.toByte(),
        )
        val mp4 = "mp4-bytes".toByteArray(StandardCharsets.UTF_8)

        val result = MotionPhotoComposer.compose(jpeg, mp4)
        val text = result.toString(StandardCharsets.UTF_8)

        assertTrue(text.contains("Camera:MotionPhoto=\"1\""))
        assertTrue(text.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(text.contains("Item:Length=\"${mp4.size}\""))
        assertArrayEquals(mp4, result.copyOfRange(result.size - mp4.size, result.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝没有 SOS 的 JPEG`() {
        MotionPhotoComposer.compose(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()), byteArrayOf(1))
    }
}
