package com.frameyu.app.export

import android.graphics.Bitmap
import java.io.OutputStream

/** 使用固定 RGB332 调色板编码 GIF，适合离线短片段导出。 */
internal class GifSequenceEncoder(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
) {
    private var started = false

    fun start() {
        check(!started) { "GIF 编码器已经启动" }
        started = true
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        output.write(0xF7)
        output.write(0)
        output.write(0)
        writePalette()
        output.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    fun addFrame(bitmap: Bitmap, delayCentiseconds: Int) {
        check(started) { "GIF 编码器尚未启动" }
        require(bitmap.width == width && bitmap.height == height) { "GIF 帧尺寸不一致" }
        output.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        writeShort(delayCentiseconds.coerceIn(1, 65535))
        output.write(byteArrayOf(0x00, 0x00))
        output.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        output.write(0)
        output.write(8)
        writeImageData(bitmap)
    }

    fun finish() {
        check(started) { "GIF 编码器尚未启动" }
        output.write(0x3B)
        output.flush()
        started = false
    }

    private fun writeImageData(bitmap: Bitmap) {
        val pixels = IntArray(width)
        output.write(8)
        val packedCodes = ByteArrayOutputStreamBuffer()
        var position = 0
        packedCodes.writeCode(CLEAR_CODE)
        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (pixel in pixels) {
                if (position == PIXELS_PER_DICTIONARY_RESET) {
                    packedCodes.writeCode(CLEAR_CODE)
                    position = 0
                }
                packedCodes.writeCode(rgb332(pixel))
                position++
            }
        }
        packedCodes.writeCode(END_CODE)
        val data = packedCodes.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val size = minOf(255, data.size - offset)
            output.write(size)
            output.write(data, offset, size)
            offset += size
        }
        output.write(0)
    }

    private fun writePalette() {
        for (index in 0 until 256) {
            output.write((index shr 5) * 255 / 7)
            output.write(((index shr 2) and 7) * 255 / 7)
            output.write((index and 3) * 255 / 3)
        }
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    private fun rgb332(pixel: Int): Int {
        val alpha = pixel ushr 24
        if (alpha < 16) return 0
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red shr 5 shl 5) or (green shr 5 shl 2) or (blue shr 6)
    }

    companion object {
        private const val CLEAR_CODE = 256
        private const val END_CODE = 257
        private const val PIXELS_PER_DICTIONARY_RESET = 200
    }
}

private class ByteArrayOutputStreamBuffer {
    private val bytes = ArrayList<Byte>()
    private var buffer = 0
    private var bitCount = 0

    fun writeCode(code: Int) {
        buffer = buffer or (code shl bitCount)
        bitCount += 9
        while (bitCount >= 8) {
            bytes += (buffer and 0xFF).toByte()
            buffer = buffer ushr 8
            bitCount -= 8
        }
    }

    fun toByteArray(): ByteArray {
        if (bitCount > 0) bytes += (buffer and 0xFF).toByte()
        return bytes.toByteArray()
    }
}
