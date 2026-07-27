package com.frameyu.app.vision

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.vision.model.ModelAssetCatalog
import com.frameyu.app.vision.model.ModelAssetVerifier
import com.frameyu.app.vision.model.ModelId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledVisionModelsTest {
    @Test
    fun bundledModelsMatchManifestAndProduceValidStructures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = context.assets.open(MANIFEST_PATH).use(ModelAssetCatalog::parse)
        ModelId.entries.forEach { modelId ->
            val descriptor = catalog.require(modelId)
            context.assets.open("models/${descriptor.fileName}").use { input ->
                assertTrue("${descriptor.fileName} 的 SHA-256 与清单不一致", ModelAssetVerifier.matches(descriptor, input))
            }
        }

        val classifier = MediaPipeImageClassifierAnalyzer(
            context,
            catalog.require(ModelId.IMAGE_CLASSIFIER).assetPath(),
        )
        val detector = MediaPipeObjectDetectorAnalyzer(
            context,
            catalog.require(ModelId.OBJECT_DETECTOR).assetPath(),
        )
        val embedder = MediaPipeImageEmbeddingAnalyzer(
            context,
            catalog.require(ModelId.IMAGE_EMBEDDER).assetPath(),
        )
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF808080.toInt())
        }

        try {
            val labels = classifier.classify(bitmap)
            assertTrue(labels.size <= 5)
            assertTrue(labels.zipWithNext().all { (first, second) -> first.score >= second.score })

            val detections = detector.detect(bitmap)
            assertTrue(detections.size <= 10)

            val embedding = embedder.analyzeSynchronously(ThumbnailInput.fromBitmap(bitmap)).values
            assertTrue(embedding.isNotEmpty())
            assertTrue(embedding.all(Float::isFinite))
        } finally {
            val closeResults = listOf(
                runCatching { classifier.close() },
                runCatching { detector.close() },
                runCatching { embedder.close() },
            )
            bitmap.recycle()
            closeResults.firstOrNull(Result<Unit>::isFailure)?.getOrThrow()
        }
    }

    private fun com.frameyu.app.vision.model.ModelDescriptor.assetPath(): String =
        "models/$fileName"

    private companion object {
        const val MANIFEST_PATH = "models/model-manifest.properties"
    }
}
