package com.frameyu.app.vision.model

import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class ModelId {
    IMAGE_CLASSIFIER,
    OBJECT_DETECTOR,
    IMAGE_EMBEDDER,
}

data class ModelDescriptor(
    val id: ModelId,
    val fileName: String,
    val sha256: String,
    val url: String,
)

class ModelAssetCatalog private constructor(
    private val descriptors: Map<ModelId, ModelDescriptor>,
) {
    fun require(id: ModelId): ModelDescriptor =
        requireNotNull(descriptors[id]) { "清单缺少模型：$id" }

    companion object {
        private val sha256Pattern = Regex("^[0-9A-Fa-f]{64}$")
        private val windowsInvalidFileNameChars =
            setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*') + (0..31).map(Int::toChar)
        private val windowsReservedDeviceStem =
            Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOption.IGNORE_CASE)

        /**
         * 严格解析模型清单；模型文件名只接受 Windows 普通文件名且使用小写 `.tflite` 扩展名。
         *
         * [input] 由调用方持有，本方法不会关闭该流。
         */
        fun parse(input: InputStream): ModelAssetCatalog {
            val properties = input.readManifestProperties()
            val allowedKeys = ModelId.entries.flatMap { id ->
                val prefix = id.propertyPrefix()
                listOf("$prefix.file", "$prefix.sha256", "$prefix.url")
            }.toSet()
            val unknownKeys = properties.keys - allowedKeys
            require(unknownKeys.isEmpty()) { "模型清单包含未知属性：${unknownKeys.sorted().joinToString()}" }

            val descriptors = ModelId.entries.associateWith { id ->
                val prefix = id.propertyPrefix()
                val fileName = properties.requiredValue("$prefix.file")
                val sha256 = properties.requiredValue("$prefix.sha256")
                val url = properties.requiredValue("$prefix.url")

                require(fileName.isSafeModelFileName()) {
                    "$prefix.file 必须为使用小写 .tflite 扩展名的 Windows 普通文件名"
                }
                require(sha256Pattern.matches(sha256)) { "$prefix.sha256 必须为 64 位十六进制" }
                require(url.isHttpsUrl()) { "$prefix.url 必须为有效的 HTTPS URL" }

                ModelDescriptor(
                    id = id,
                    fileName = fileName,
                    sha256 = sha256.uppercase(),
                    url = url,
                )
            }
            val normalizedFileNames = descriptors.values.map { it.fileName.lowercase(Locale.ROOT) }
            require(normalizedFileNames.distinct().size == normalizedFileNames.size) {
                "三个模型文件名必须唯一"
            }
            return ModelAssetCatalog(descriptors)
        }

        private fun InputStream.readManifestProperties(): Map<String, String> {
            val properties = linkedMapOf<String, String>()
            val reader = bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) continue

                val separatorIndex = trimmedLine.indexOf('=')
                require(separatorIndex > 0) { "模型清单包含非法行：$line" }
                val key = trimmedLine.substring(0, separatorIndex).trim()
                val value = trimmedLine.substring(separatorIndex + 1).trim()
                require(key !in properties) { "模型清单包含重复属性：$key" }
                properties[key] = value
            }
            return properties
        }

        private fun Map<String, String>.requiredValue(key: String): String =
            requireNotNull(this[key]) { "清单缺少属性：$key" }

        private fun String.isSafeModelFileName(): Boolean =
            isNotBlank() &&
                endsWith(".tflite") &&
                none { it in windowsInvalidFileNameChars } &&
                !windowsReservedDeviceStem.matches(substringBefore('.')) &&
                !File(this).isAbsolute &&
                File(this).name == this

        private fun ModelId.propertyPrefix(): String = when (this) {
            ModelId.IMAGE_CLASSIFIER -> "image_classifier"
            ModelId.OBJECT_DETECTOR -> "object_detector"
            ModelId.IMAGE_EMBEDDER -> "image_embedder"
        }

        private fun String.isHttpsUrl(): Boolean = try {
            val uri = URI(this)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
    }
}
