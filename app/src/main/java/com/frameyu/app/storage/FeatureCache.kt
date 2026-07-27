package com.frameyu.app.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** 在应用私有目录中按模型和媒体保存 float32 特征向量。 */
class FeatureCache internal constructor(
    private val filesDir: File,
    private val mover: FeatureCacheMover,
) {
    constructor(filesDir: File) : this(filesDir, AtomicReplacingFeatureCacheMover)

    /** 返回规范化后的固定缓存路径，不创建文件。 */
    fun fileFor(modelSha256: String, mediaId: Long): File {
        val normalizedSha = normalizeModelSha(modelSha256)
        require(mediaId > 0L) { "媒体 ID 必须大于 0" }
        return File(File(File(filesDir, FEATURES_DIRECTORY), normalizedSha), "$mediaId.f32")
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

    /**
     * 同一路径的进程内并发写入会串行化，最后完成的写入覆盖旧值。
     * 仅当临时文件完整落盘并成功重命名后才返回最终文件。
     */
    fun write(modelSha256: String, mediaId: Long, vector: FloatArray): File {
        require(vector.isNotEmpty()) { "特征向量不能为空" }
        require(vector.all(Float::isFinite)) { "特征向量必须只包含有限数值" }
        val finalFile = fileFor(modelSha256, mediaId)

        return synchronized(lockFor(finalFile)) {
            writeAtomically(finalFile, vector)
        }
    }

    private fun readValidated(file: File): FloatArray? {
        if (!file.isFile) return null
        val length = file.length()
        if (length < MIN_FILE_SIZE_BYTES || length > Int.MAX_VALUE) return null

        return runCatching {
            val bytes = ByteArray(length.toInt())
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) return null
                    offset += count
                }
                if (input.read() != -1) return null
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val dimension = buffer.int
            if (dimension <= 0) return null
            val expectedLength = Int.SIZE_BYTES.toLong() + dimension.toLong() * Float.SIZE_BYTES
            if (expectedLength != length) return null
            FloatArray(dimension) { buffer.float }.takeIf { values ->
                values.all(Float::isFinite)
            }
        }.getOrNull()
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
        } catch (error: Exception) {
            tempFile.delete()
            throw IllegalStateException("写入特征缓存失败：${finalFile.absolutePath}", error)
        }
    }

    private fun normalizeModelSha(modelSha256: String): String {
        require(MODEL_SHA_PATTERN.matches(modelSha256)) {
            "模型 SHA-256 必须为 64 位十六进制字符串"
        }
        return modelSha256.lowercase()
    }

    private companion object {
        const val FEATURES_DIRECTORY = "features"
        const val MIN_FILE_SIZE_BYTES = Int.SIZE_BYTES + Float.SIZE_BYTES
        val MODEL_SHA_PATTERN = Regex("[0-9A-Fa-f]{64}")
        val pathLocks = ConcurrentHashMap<String, Any>()

        fun lockFor(file: File): Any =
            pathLocks.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }
    }
}

internal fun interface FeatureCacheMover {
    fun move(source: Path, target: Path)
}

private object AtomicReplacingFeatureCacheMover : FeatureCacheMover {
    override fun move(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
