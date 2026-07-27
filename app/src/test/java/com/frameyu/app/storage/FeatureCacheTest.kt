package com.frameyu.app.storage

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeatureCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `向量按固定路径和小端格式写入后可读取`() {
        val cache = FeatureCache(temporaryFolder.root)
        val modelSha = "A".repeat(64)
        val vector = floatArrayOf(0.1f, -0.2f, 3.5f)

        val file = cache.write(modelSha, 42L, vector)

        assertEquals(
            File(temporaryFolder.root, "features/${modelSha.lowercase()}/42.f32").absolutePath,
            file.absolutePath,
        )
        assertArrayEquals(vector, cache.read(modelSha.lowercase(), 42L), 0f)
        val bytes = file.readBytes()
        assertEquals(4 + vector.size * Float.SIZE_BYTES, bytes.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(vector.size, buffer.int)
        vector.forEach { expected -> assertEquals(expected, buffer.float, 0f) }
    }

    @Test
    fun `相同媒体的向量按模型隔离且覆盖写入以最后完成者为准`() {
        val cache = FeatureCache(temporaryFolder.root)
        val firstModel = "1".repeat(64)
        val secondModel = "2".repeat(64)

        cache.write(firstModel, 7L, floatArrayOf(1f))
        cache.write(secondModel, 7L, floatArrayOf(2f))
        cache.write(firstModel, 7L, floatArrayOf(3f, 4f))

        assertArrayEquals(floatArrayOf(3f, 4f), cache.read(firstModel, 7L), 0f)
        assertArrayEquals(floatArrayOf(2f), cache.read(secondModel, 7L), 0f)
        assertFalse(File(cache.fileFor(firstModel, 7L).parentFile, "7.f32.bak").exists())
    }

    @Test
    fun `覆盖提交失败时旧缓存仍可读且临时文件被清理`() {
        val modelSha = "3".repeat(64)
        val normalCache = FeatureCache(temporaryFolder.root)
        val finalFile = normalCache.write(modelSha, 8L, floatArrayOf(1f, 2f))
        val failingCache = FeatureCache(
            temporaryFolder.root,
            FeatureCacheMover { _, _ -> throw IOException("模拟提交 move 失败") },
        )

        assertThrows<IllegalStateException> {
            failingCache.write(modelSha, 8L, floatArrayOf(9f, 10f))
        }

        assertArrayEquals(floatArrayOf(1f, 2f), normalCache.read(modelSha, 8L), 0f)
        assertFalse(File(finalFile.parentFile, "${finalFile.name}.tmp").exists())
        assertFalse(File(finalFile.parentFile, "${finalFile.name}.bak").exists())
    }

    @Test
    fun `非法 SHA 媒体 ID 和向量被拒绝`() {
        val cache = FeatureCache(temporaryFolder.root)
        val validSha = "a".repeat(64)

        assertThrows<IllegalArgumentException> {
            cache.write("not-a-sha", 1L, floatArrayOf(1f))
        }
        assertThrows<IllegalArgumentException> {
            cache.read("f".repeat(63), 1L)
        }
        assertThrows<IllegalArgumentException> {
            cache.write(validSha, 0L, floatArrayOf(1f))
        }
        assertThrows<IllegalArgumentException> {
            cache.read(validSha, -1L)
        }
        assertThrows<IllegalArgumentException> {
            cache.write(validSha, 1L, floatArrayOf())
        }
        assertThrows<IllegalArgumentException> {
            cache.write(validSha, 1L, floatArrayOf(Float.NaN))
        }
        assertThrows<IllegalArgumentException> {
            cache.write(validSha, 1L, floatArrayOf(Float.POSITIVE_INFINITY))
        }
    }

    @Test
    fun `临时文件不可写时失败且不留下最终文件`() {
        val cache = FeatureCache(temporaryFolder.root)
        val modelSha = "b".repeat(64)
        val modelDirectory = File(temporaryFolder.root, "features/$modelSha")
        val tempPath = File(modelDirectory, "9.f32.tmp")
        assertTrue(requireNotNull(modelDirectory.parentFile).mkdirs())
        modelDirectory.writeText("阻止创建模型目录")

        assertThrows<IllegalStateException> {
            cache.write(modelSha, 9L, floatArrayOf(1f, 2f))
        }

        assertFalse(File(modelDirectory, "9.f32").exists())
        assertFalse(tempPath.exists())
    }

    @Test
    fun `损坏的维度长度和非有限数值不会被当作有效缓存`() {
        val cache = FeatureCache(temporaryFolder.root)
        val modelSha = "c".repeat(64)
        val file = cache.write(modelSha, 3L, floatArrayOf(1f, 2f))

        val corruptions = listOf(
            byteArrayOf(2, 0, 0),
            littleEndianBytes(0),
            littleEndianBytes(2, 1f),
            littleEndianBytes(1, Float.NaN),
            littleEndianBytes(1, 1f) + byteArrayOf(0),
        )

        corruptions.forEach { bytes ->
            file.writeBytes(bytes)
            assertNull(cache.read(modelSha, 3L))
        }
    }

    private fun littleEndianBytes(dimension: Int, vararg values: Float): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(dimension)
            .also { buffer -> values.forEach(buffer::putFloat) }
            .array()

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
