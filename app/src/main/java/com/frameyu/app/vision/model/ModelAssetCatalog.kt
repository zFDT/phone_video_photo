package com.frameyu.app.vision.model

import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Properties

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

        fun parse(input: InputStream): ModelAssetCatalog {
            val properties = Properties().apply { load(input) }
            val descriptors = ModelId.entries.associateWith { id ->
                val prefix = id.propertyPrefix()
                val fileName = properties.requiredValue("$prefix.file")
                val sha256 = properties.requiredValue("$prefix.sha256")
                val url = properties.requiredValue("$prefix.url")

                require(fileName.isNotBlank()) { "$prefix.file 不能为空" }
                require(sha256Pattern.matches(sha256)) { "$prefix.sha256 必须为 64 位十六进制" }
                require(url.isHttpsUrl()) { "$prefix.url 必须为有效的 HTTPS URL" }

                ModelDescriptor(
                    id = id,
                    fileName = fileName,
                    sha256 = sha256.uppercase(),
                    url = url,
                )
            }
            return ModelAssetCatalog(descriptors)
        }

        private fun Properties.requiredValue(key: String): String =
            requireNotNull(getProperty(key)) { "清单缺少属性：$key" }.trim()

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
