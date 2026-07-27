package com.frameyu.app.vision.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelAssetCatalogTest {
    @Test
    fun `清单解析三个模型并保留固定字段`() {
        val catalog = ModelAssetCatalog.parse(VALID_MANIFEST.byteInputStream())

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
        val manifest = VALID_MANIFEST.replace(
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
            VALID_MANIFEST.replace(
                "image_classifier.file=efficientnet_lite2.tflite",
                "image_classifier.file=",
            ),
            VALID_MANIFEST.replace(
                "94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83",
                "1234",
            ),
            VALID_MANIFEST.replace(
                "https://storage.googleapis.com/mediapipe-models/image_classifier",
                "http://storage.googleapis.com/mediapipe-models/image_classifier",
            ),
            VALID_MANIFEST.replace(
                "https://storage.googleapis.com/mediapipe-models/image_classifier",
                "https://[invalid-host]/mediapipe-models/image_classifier",
            ),
            VALID_MANIFEST.lines()
                .filterNot { it.startsWith("image_embedder.url=") }
                .joinToString("\n"),
        )

        invalidManifests.forEach { manifest ->
            assertFailsWithIllegalArgument {
                ModelAssetCatalog.parse(manifest.byteInputStream())
            }
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

    private companion object {
        val VALID_MANIFEST = """
            image_classifier.file=efficientnet_lite2.tflite
            image_classifier.sha256=94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83
            image_classifier.url=https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite2/float32/latest/efficientnet_lite2.tflite
            object_detector.file=efficientdet_lite2.tflite
            object_detector.sha256=AD2ABBF2B4E10585E15176FD7B5EF03C28DDA959AE26FC142549FDD1814DB91D
            object_detector.url=https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/latest/efficientdet_lite2.tflite
            image_embedder.file=mobilenet_v3_small_075_224_embedder.tflite
            image_embedder.sha256=0F786F8EB8BCECD1F9C5BC50F00A5E6B9F62600EDB503AD3BFFF5F51035B0EA5
            image_embedder.url=https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite
        """.trimIndent()
    }
}
