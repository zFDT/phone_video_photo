package com.frameyu.app.vision.model

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelAssetCatalogTest {
    @Test
    fun `仓库实际清单解析三个模型并保留固定字段`() {
        val catalog = Files.newInputStream(actualManifestPath()).use(ModelAssetCatalog::parse)

        assertEquals(
            ModelDescriptor(
                id = ModelId.IMAGE_CLASSIFIER,
                fileName = "efficientnet_lite2.tflite",
                sha256 = "94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83",
                url = "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite2/float32/latest/efficientnet_lite2.tflite",
            ),
            catalog.require(ModelId.IMAGE_CLASSIFIER),
        )
        assertEquals(
            ModelDescriptor(
                id = ModelId.OBJECT_DETECTOR,
                fileName = "efficientdet_lite2.tflite",
                sha256 = "AD2ABBF2B4E10585E15176FD7B5EF03C28DDA959AE26FC142549FDD1814DB91D",
                url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/latest/efficientdet_lite2.tflite",
            ),
            catalog.require(ModelId.OBJECT_DETECTOR),
        )
        assertEquals(
            ModelDescriptor(
                id = ModelId.IMAGE_EMBEDDER,
                fileName = "mobilenet_v3_small_075_224_embedder.tflite",
                sha256 = "0F786F8EB8BCECD1F9C5BC50F00A5E6B9F62600EDB503AD3BFFF5F51035B0EA5",
                url = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite",
            ),
            catalog.require(ModelId.IMAGE_EMBEDDER),
        )
    }

    @Test
    fun `SHA 统一为大写`() {
        val manifest = actualManifestText().replace(
            "94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83",
            "94b6c84a5d6de20f932558de213b50e0338dcc06ab83d8d64b12128a476b4b83",
        )

        val descriptor = ModelAssetCatalog.parse(manifest.byteInputStream())
            .require(ModelId.IMAGE_CLASSIFIER)

        assertEquals(
            "94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83",
            descriptor.sha256,
        )
    }

    @Test
    fun `校验器接受匹配内容并拒绝错误内容`() {
        val descriptor = ModelDescriptor(
            id = ModelId.IMAGE_CLASSIFIER,
            fileName = "model.tflite",
            sha256 = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            url = "https://example.com/model.tflite",
        )

        assertTrue(ModelAssetVerifier.matches(descriptor, "abc".byteInputStream()))
        assertFalse(ModelAssetVerifier.matches(descriptor, "wrong".byteInputStream()))
    }

    @Test
    fun `非法清单被拒绝`() {
        val invalidManifests = listOf(
            actualManifestText().replace(
                "image_classifier.file=efficientnet_lite2.tflite",
                "image_classifier.file=",
            ),
            actualManifestText().replace(
                "94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83",
                "1234",
            ),
            actualManifestText().replace(
                "https://storage.googleapis.com/mediapipe-models/image_classifier",
                "http://storage.googleapis.com/mediapipe-models/image_classifier",
            ),
            actualManifestText().replace(
                "https://storage.googleapis.com/mediapipe-models/image_classifier",
                "https://[invalid-host]/mediapipe-models/image_classifier",
            ),
            actualManifestText().lines()
                .filterNot { it.startsWith("image_embedder.url=") }
                .joinToString("\n"),
        )

        invalidManifests.forEach { manifest ->
            assertFailsWithIllegalArgument {
                ModelAssetCatalog.parse(manifest.byteInputStream())
            }
        }
    }

    @Test
    fun `文件名必须是小写扩展名的单一 tflite basename`() {
        val invalidFileNames = listOf(
            "../model.tflite",
            "subdir/model.tflite",
            "subdir\\model.tflite",
            "/absolute/model.tflite",
            "model.bin",
            "model.TFLITE",
            "model-manifest.properties",
            "model:stream.tflite",
            "model?.tflite",
            "CON.tflite",
            "cOm1.tflite",
            "LpT9.tflite",
        )

        invalidFileNames.forEach { fileName ->
            val manifest = actualManifestText().replace(
                "image_classifier.file=efficientnet_lite2.tflite",
                "image_classifier.file=$fileName",
            )
            assertFailsWithIllegalArgument {
                ModelAssetCatalog.parse(manifest.byteInputStream())
            }
        }
    }

    @Test
    fun `三个模型文件名必须忽略大小写后唯一`() {
        val manifest = actualManifestText().replace(
            "object_detector.file=efficientdet_lite2.tflite",
            "object_detector.file=EFFICIENTNET_LITE2.tflite",
        )

        assertFailsWithIllegalArgument {
            ModelAssetCatalog.parse(manifest.byteInputStream())
        }
    }

    @Test
    fun `未知属性被拒绝`() {
        val manifest = actualManifestText() + "\nunknown.key=value"

        assertFailsWithIllegalArgument {
            ModelAssetCatalog.parse(manifest.byteInputStream())
        }
    }

    @Test
    fun `重复属性被拒绝`() {
        val manifest = actualManifestText() + "\nimage_classifier.file=another.tflite"

        assertFailsWithIllegalArgument {
            ModelAssetCatalog.parse(manifest.byteInputStream())
        }
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("预期清单解析抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 符合预期
        }
    }

    private fun actualManifestText(): String = String(Files.readAllBytes(actualManifestPath()))

    private fun actualManifestPath(): Path {
        val candidates = listOf(
            Path.of("src", "main", "assets", "models", "model-manifest.properties"),
            Path.of("app", "src", "main", "assets", "models", "model-manifest.properties"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("找不到仓库实际模型清单，尝试路径：${candidates.joinToString()}")
    }
}
