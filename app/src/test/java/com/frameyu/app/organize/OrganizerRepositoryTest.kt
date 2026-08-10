package com.frameyu.app.organize

import com.frameyu.app.data.db.AnalysisCandidateSummary
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.MediaQualityResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerRepositoryTest {
    private val repository = OrganizerRepository(
        detector = DuplicateDetector(),
        deleter = { MediaDeletionResult(emptyList(), emptyList()) },
    )

    @Test
    fun `持久化结果生成低质量截图录屏和视频精彩候选`() {
        val screenshot = media(1L, "image/jpeg", "Pictures/Screenshots/Screenshot_1.jpg")
        val recording = media(2L, "video/mp4", "Movies/Screen recordings/recording.mp4")
        val lowQuality = media(3L, "image/jpeg", "DCIM/Camera/IMG_3.jpg")
        val highlight = media(4L, "video/mp4", "DCIM/Camera/VID_4.mp4")

        val groups = repository.persistedGroups(
            assets = listOf(screenshot, recording, lowQuality, highlight),
            qualityResults = listOf(quality(lowQuality.id, 0.2f)),
            candidateSummaries = listOf(
                AnalysisCandidateSummary(
                    mediaAssetId = highlight.id,
                    candidateCount = 3L,
                    maxScore = 92,
                ),
            ),
        )

        assertEquals(
            setOf(
                OrganizerFilter.LOW_QUALITY,
                OrganizerFilter.SCREENSHOT,
                OrganizerFilter.SCREEN_RECORDING,
                OrganizerFilter.VIDEO_HIGHLIGHT,
            ),
            groups.map(OrganizationGroup::filter).toSet(),
        )
        assertTrue(groups.single { it.filter == OrganizerFilter.LOW_QUALITY }.items.none {
            it.selectedByDefault
        })
        assertFalse(
            groups.single { it.filter == OrganizerFilter.VIDEO_HIGHLIGHT }
                .items.single().selectableForDeletion,
        )
        assertEquals(
            "已保存 3 个精彩候选帧，最高评分 92",
            groups.single { it.filter == OrganizerFilter.VIDEO_HIGHLIGHT }.items.single().detail,
        )
    }

    @Test
    fun `手动扫描结果按完全重复和相似规则加入统一列表`() {
        val keep = media(1L, "image/jpeg", "DCIM/1.jpg")
        val copy = media(2L, "image/jpeg", "DCIM/2.jpg")
        val group = DuplicateGroup("group", listOf(keep, copy), keep)

        val exact = repository.exactGroups(listOf(group)).single()
        val similar = repository.similarGroups(listOf(group)).single()

        assertEquals(OrganizerFilter.EXACT_DUPLICATE, exact.filter)
        assertEquals(setOf(copy.id), exact.items.filter { it.selectedByDefault }.map { it.mediaId }.toSet())
        assertEquals(OrganizerFilter.SIMILAR_MEDIA, similar.filter)
        assertTrue(similar.items.none { it.selectedByDefault })
    }

    private fun quality(mediaId: Long, score: Float) = MediaQualityResultEntity(
        id = mediaId,
        mediaAssetId = mediaId,
        timestampMillis = 0L,
        analyzerVersion = "quality-v1",
        totalScore = score,
        sharpnessScore = score,
        exposureScore = score,
        noiseScore = score,
        compositionScore = score,
    )

    private fun media(id: Long, mimeType: String, path: String) = MediaAssetEntity(
        id = id,
        uri = "content://media/$id/$path",
        mimeType = mimeType,
        takenAtMillis = 0L,
        durationMillis = if (mimeType.startsWith("video/")) 1_000L else 0L,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = path.substringBeforeLast('/'),
        lastModifiedMillis = 0L,
    )
}
