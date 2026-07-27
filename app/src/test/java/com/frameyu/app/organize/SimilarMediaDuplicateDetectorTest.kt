package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class SimilarMediaDuplicateDetectorTest {
    @Test
    fun `视觉哈希接近且时间接近的媒体归为相似组`() = runBlocking {
        val first = media("content://first", 1_000L)
        val second = media("content://second", 2_000L)
        val third = media("content://third", 2_000L)
        val hashes = mapOf(first.uri to 0b1010L, second.uri to 0b1011L, third.uri to 0L)

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { uri -> hashes.getValue(uri) },
            maxHammingDistance = 1,
        ).detect(listOf(first, second, third))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(first, second), result.groups.single().assets)
        assertEquals(first, result.groups.single().suggestedKeep)
    }

    @Test
    fun `相似关系不会通过中间项传递合并`() = runBlocking {
        val first = media("content://first", 1_000L)
        val bridge = media("content://bridge", 2_000L)
        val distant = media("content://distant", 3_000L)
        val hashes = mapOf(
            first.uri to 0b000L,
            bridge.uri to 0b001L,
            distant.uri to 0b011L,
        )

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { uri -> hashes.getValue(uri) },
            maxHammingDistance = 1,
        ).detect(listOf(first, bridge, distant))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(first, bridge), result.groups.single().assets)
        assertEquals(first, result.groups.single().suggestedKeep)
    }

    @Test
    fun `时间接近关系不会通过中间项传递合并`() = runBlocking {
        val first = media("content://first", 0L)
        val bridge = media("content://bridge", 100L)
        val distant = media("content://distant", 200L)

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { 1L },
            maxHammingDistance = 0,
            timeWindowMillis = 150L,
        ).detect(listOf(first, bridge, distant))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(first, bridge), result.groups.single().assets)
        assertEquals(first, result.groups.single().suggestedKeep)
    }

    @Test
    fun `时间窗口外或读取失败的媒体不参与相似组`() = runBlocking {
        val old = media("content://old", 0L)
        val failed = media("content://failed", 1_000L)
        val current = media("content://current", 2_000L)

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { uri ->
                if (uri == failed.uri) error("读取失败") else 1L
            },
            maxHammingDistance = 0,
            timeWindowMillis = 500L,
        ).detect(listOf(old, failed, current))

        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(failed.uri), result.failedUris)
    }

    @Test
    fun `乱序输入排序后只在时间窗口内成组`() = runBlocking {
        val early = media("content://early", 0L)
        val nearby = media("content://nearby", 100L)
        val late = media("content://late", 10_000L)

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { 1L },
            maxHammingDistance = 0,
            timeWindowMillis = 200L,
        ).detect(listOf(late, nearby, early))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(early, nearby), result.groups.single().assets)
        assertEquals(early, result.groups.single().suggestedKeep)
    }

    @Test
    fun `扫描取消异常会继续向上传播`() {
        val cancellation = CancellationException("取消扫描")
        var thrown: CancellationException? = null

        try {
            runBlocking {
                SimilarMediaDuplicateDetector(
                    reader = VisualHashReader { throw cancellation },
                ).detect(listOf(media("content://photo", 1_000L)))
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertEquals(cancellation, thrown)
    }

    @Test
    fun `相似照片候选会排除视频`() {
        val jpeg = media("content://jpeg", 1_000L, mimeType = "image/jpeg")
        val heic = media("content://heic", 2_000L, mimeType = "image/heic")
        val video = media("content://video", 3_000L, mimeType = "video/mp4")

        val candidates = similarPhotoCandidates(listOf(jpeg, video, heic))

        assertEquals(listOf(jpeg, heic), candidates)
    }

    private fun media(
        uri: String,
        takenAtMillis: Long,
        mimeType: String = "image/jpeg",
    ) = MediaAssetEntity(
        id = uri.hashCode().toLong(),
        uri = uri,
        mimeType = mimeType,
        takenAtMillis = takenAtMillis,
        durationMillis = 0L,
        width = 1_080,
        height = 1_920,
        rotationDegrees = 0,
        sizeBytes = 10L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )
}
