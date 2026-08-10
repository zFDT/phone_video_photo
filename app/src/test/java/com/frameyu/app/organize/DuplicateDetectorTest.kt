package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDetectorTest {
    @Test
    fun `按 duplicateKey 只输出包含多个媒体的分组`() {
        val first = media(uri = "content://media/one.jpg")
        val second = media(uri = "content://media/two.jpg")
        val unique = media(uri = "content://media/unique.jpg")
        val detector = DuplicateDetector { asset ->
            if (asset.uri.contains("unique")) "unique" else "duplicate"
        }

        val groups = detector.detect(listOf(first, second, unique))

        assertEquals(1, groups.size)
        assertEquals("duplicate", groups.single().duplicateKey)
        assertEquals(listOf(first, second), groups.single().assets)
    }

    @Test
    fun `默认重复检测可以合并不同 Uri 的相同媒体特征`() {
        val groups = DuplicateDetector().detect(
            listOf(
                media(uri = "content://media/path-a.jpg"),
                media(uri = "content://media/path-b.jpg"),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().assets.size)
    }

    @Test
    fun `建议保留拍摄时间最早的媒体且同时间选择文件最大的媒体`() {
        val later = media(uri = "content://media/later.jpg", takenAtMillis = 300L, sizeBytes = 999L)
        val earlierSmaller = media(uri = "content://media/earlier-small.jpg", takenAtMillis = 100L, sizeBytes = 10L)
        val earlierLarger = media(uri = "content://media/earlier-large.jpg", takenAtMillis = 100L, sizeBytes = 20L)
        val detector = DuplicateDetector { "duplicate" }

        val group = detector.detect(listOf(later, earlierSmaller, earlierLarger)).single()

        assertEquals(earlierLarger, group.suggestedKeep)
    }

    @Test
    fun `查询重复候选不会触发删除`() = runBlocking {
        val deletedUris = mutableListOf<List<String>>()
        val repository = OrganizerRepository(
            detector = DuplicateDetector { "duplicate" },
            deleter = { uris ->
                deletedUris.add(uris)
                MediaDeletionResult(uris, emptyList())
            },
        )

        repository.duplicateCandidates(listOf(media("content://media/one.jpg"), media("content://media/two.jpg")))

        assertTrue(deletedUris.isEmpty())
    }

    @Test
    fun `完全重复扫描通过仓库暴露且不会触发删除`() = runBlocking {
        val deletedUris = mutableListOf<List<String>>()
        val first = media("content://media/one.jpg")
        val second = media("content://media/two.jpg")
        val repository = OrganizerRepository(
            detector = DuplicateDetector(),
            contentHashDetector = ContentHashDuplicateDetector(
                ContentHashReader { sha256Hex(listOf("same".toByteArray())) },
            ),
            deleter = { uris ->
                deletedUris.add(uris)
                MediaDeletionResult(uris, emptyList())
            },
        )

        val result = repository.exactDuplicateCandidates(listOf(first, second))

        assertEquals(listOf(listOf(first, second)), result.groups.map { it.assets })
        assertTrue(deletedUris.isEmpty())
    }

    @Test
    fun `显式删除只把冻结计划内的 Uri 列表交给注入的删除器`() = runBlocking {
        val deletedUris = mutableListOf<List<String>>()
        val repository = OrganizerRepository(
            detector = DuplicateDetector(),
            deleter = { uris ->
                deletedUris.add(uris)
                MediaDeletionResult(uris, emptyList())
            },
            deletionSafetyValidator = DeletionSafetyValidator(
                metadataReader = LiveMediaMetadataReader { item ->
                    LiveMediaMetadata(item.mediaId, item.uri, item.sizeBytes, item.lastModifiedMillis)
                },
                sha256Reader = ContentSha256Reader { "hash" },
            ),
        )
        val item = DeletionPlanItem(1L, "content://media/1", 1L, 1L)
        val plan = DeletionPlan(
            visibleFilter = OrganizerFilter.LOW_QUALITY,
            items = listOf(item),
            affectedGroups = listOf(
                DeletionPlanGroup(
                    id = "low:1",
                    filter = OrganizerFilter.LOW_QUALITY,
                    suggestedKeepId = null,
                    items = listOf(item),
                    exactContentHash = null,
                ),
            ),
        )

        repository.delete(plan)

        assertEquals(listOf(listOf(item.uri)), deletedUris)
    }

    private fun media(
        uri: String,
        takenAtMillis: Long = 1_000L,
        sizeBytes: Long = 100L,
    ): MediaAssetEntity = MediaAssetEntity(
        id = uri.hashCode().toLong(),
        uri = uri,
        mimeType = "image/jpeg",
        takenAtMillis = takenAtMillis,
        durationMillis = 0L,
        width = 1_080,
        height = 1_920,
        rotationDegrees = 0,
        sizeBytes = sizeBytes,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 2_000L,
    )
}
