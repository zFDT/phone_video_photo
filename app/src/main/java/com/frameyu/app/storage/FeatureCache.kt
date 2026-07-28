package com.frameyu.app.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 在应用私有目录中按模型和媒体保存 float32 特征向量。 */
class FeatureCache internal constructor(
    private val filesDir: File,
    private val mover: FeatureCacheMover,
    private val inputStreamOpener: FeatureCacheInputStreamOpener,
) {
    constructor(filesDir: File) : this(
        filesDir,
        AtomicReplacingFeatureCacheMover(),
        FeatureCacheInputStreamOpener(::FileInputStream),
    )

    internal constructor(filesDir: File, mover: FeatureCacheMover) : this(
        filesDir,
        mover,
        FeatureCacheInputStreamOpener(::FileInputStream),
    )

    /** 返回规范化后的固定缓存路径，不创建文件。 */
    fun fileFor(modelSha256: String, mediaId: Long): File {
        val normalizedSha = normalizeModelSha(modelSha256)
        require(mediaId > 0L) { "媒体 ID 必须大于 0" }
        return File(File(File(filesDir, FEATURES_DIRECTORY), normalizedSha), "$mediaId.f32")
    }

    /** 视频帧按媒体和时间戳隔离；时间戳 0 保持已有图片缓存路径兼容。 */
    fun fileFor(modelSha256: String, mediaId: Long, timestampMillis: Long): File {
        require(timestampMillis >= 0L) { "特征时间戳不能为负数" }
        if (timestampMillis == IMAGE_TIMESTAMP_MILLIS) return fileFor(modelSha256, mediaId)
        val normalizedSha = normalizeModelSha(modelSha256)
        require(mediaId > 0L) { "媒体 ID 必须大于 0" }
        return File(
            File(File(File(filesDir, FEATURES_DIRECTORY), normalizedSha), mediaId.toString()),
            "$timestampMillis.f32",
        )
    }

    /**
     * 损坏、截断或包含非有限数值的缓存统一视为未命中。
     * 参数非法仍抛出 IllegalArgumentException，避免把调用方错误伪装成缓存缺失。
     */
    fun read(modelSha256: String, mediaId: Long): FloatArray? {
        val file = fileFor(modelSha256, mediaId)
        return synchronized(lockFor(file)) {
            readValidated(file)
        }
    }

    fun read(modelSha256: String, mediaId: Long, timestampMillis: Long): FloatArray? {
        val file = fileFor(modelSha256, mediaId, timestampMillis)
        return synchronized(lockFor(file)) {
            readValidated(file)
        }
    }

    /**
     * 同一路径的进程内并发写入会串行化，最后完成的写入覆盖旧值。
     * 仅当临时文件完整落盘并成功重命名后才返回最终文件。
     */
    fun write(modelSha256: String, mediaId: Long, vector: FloatArray): File {
        return writeTo(fileFor(modelSha256, mediaId), vector)
    }

    fun write(
        modelSha256: String,
        mediaId: Long,
        timestampMillis: Long,
        vector: FloatArray,
    ): File {
        return writeTo(fileFor(modelSha256, mediaId, timestampMillis), vector)
    }

    private fun writeTo(finalFile: File, vector: FloatArray): File {
        require(vector.isNotEmpty()) { "特征向量不能为空" }
        require(vector.size <= MAX_VECTOR_DIMENSION) {
            "特征向量维度不能超过 $MAX_VECTOR_DIMENSION"
        }
        require(vector.all(Float::isFinite)) { "特征向量必须只包含有限数值" }

        return synchronized(lockFor(finalFile)) {
            writeAtomically(finalFile, vector)
        }
    }

    private fun readValidated(file: File): FloatArray? {
        if (!file.isFile) return null
        val length = file.length()
        if (length < MIN_FILE_SIZE_BYTES) return null

        return try {
            inputStreamOpener.open(file).buffered().use { input ->
                val header = ByteArray(Int.SIZE_BYTES)
                if (!input.readFully(header, header.size)) return null
                val dimension = ByteBuffer.wrap(header)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                if (dimension !in 1..MAX_VECTOR_DIMENSION) return null
                val expectedLength =
                    Int.SIZE_BYTES.toLong() + dimension.toLong() * Float.SIZE_BYTES
                if (expectedLength != length) return null

                val values = FloatArray(dimension)
                val chunk = ByteArray(minOf(READ_CHUNK_BYTES, dimension * Float.SIZE_BYTES))
                var valueOffset = 0
                while (valueOffset < dimension) {
                    val valuesInChunk = minOf(
                        chunk.size / Float.SIZE_BYTES,
                        dimension - valueOffset,
                    )
                    val bytesInChunk = valuesInChunk * Float.SIZE_BYTES
                    if (!input.readFully(chunk, bytesInChunk)) return null
                    val buffer = ByteBuffer.wrap(chunk, 0, bytesInChunk)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    repeat(valuesInChunk) {
                        val value = buffer.float
                        if (!value.isFinite()) return null
                        values[valueOffset++] = value
                    }
                }
                if (input.read() != -1) return null
                values
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun writeAtomically(finalFile: File, vector: FloatArray): File {
        val parent = requireNotNull(finalFile.parentFile) { "特征缓存路径必须包含父目录" }
        val tempFile = File(parent, "${finalFile.name}.tmp")

        try {
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                error("无法创建特征缓存目录：${parent.absolutePath}")
            }
            if (tempFile.exists() && !tempFile.delete()) {
                error("无法清理旧特征临时文件：${tempFile.absolutePath}")
            }

            val bytes = ByteBuffer.allocate(
                Int.SIZE_BYTES + Math.multiplyExact(vector.size, Float.SIZE_BYTES),
            ).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(vector.size)
                .also { buffer -> vector.forEach(buffer::putFloat) }
                .array()
            FileOutputStream(tempFile).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }

            mover.move(tempFile.toPath(), finalFile.toPath())
            return finalFile
        } catch (error: IOException) {
            throw IllegalStateException("写入特征缓存失败：${finalFile.absolutePath}", error)
        } finally {
            tempFile.delete()
        }
    }

    private fun normalizeModelSha(modelSha256: String): String {
        require(MODEL_SHA_PATTERN.matches(modelSha256)) {
            "模型 SHA-256 必须为 64 位十六进制字符串"
        }
        return modelSha256.lowercase()
    }

    companion object {
        internal const val MAX_VECTOR_DIMENSION = 65_536
        private const val STRIPED_LOCK_COUNT = 64
        private const val FEATURES_DIRECTORY = "features"
        private const val IMAGE_TIMESTAMP_MILLIS = 0L
        private const val MIN_FILE_SIZE_BYTES = Int.SIZE_BYTES + Float.SIZE_BYTES
        private const val READ_CHUNK_BYTES = 8 * 1_024
        private val MODEL_SHA_PATTERN = Regex("[0-9A-Fa-f]{64}")
        private val pathLocks = Array(STRIPED_LOCK_COUNT) { Any() }

        internal val lockCount: Int
            get() = pathLocks.size

        internal fun lockIndexFor(file: File): Int {
            val path = file.toPath().toAbsolutePath().normalize().toString()
            val hash = path.hashCode()
            return (hash xor (hash ushr 16)) and (STRIPED_LOCK_COUNT - 1)
        }

        private fun lockFor(file: File): Any =
            pathLocks[lockIndexFor(file)]
    }
}

private fun InputStream.readFully(buffer: ByteArray, length: Int): Boolean {
    var offset = 0
    while (offset < length) {
        val count = read(buffer, offset, length - offset)
        if (count < 0) return false
        if (count == 0) {
            val value = read()
            if (value < 0) return false
            buffer[offset++] = value.toByte()
        } else {
            offset += count
        }
    }
    return true
}

internal fun interface FeatureCacheInputStreamOpener {
    fun open(file: File): InputStream
}

internal fun interface FeatureCacheMover {
    fun move(source: Path, target: Path)
}

internal class AtomicReplacingFeatureCacheMover(
    private val atomicMove: (source: Path, target: Path) -> Unit = { source, target ->
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    },
) : FeatureCacheMover {
    override fun move(source: Path, target: Path) {
        atomicMove(source, target)
    }
}
