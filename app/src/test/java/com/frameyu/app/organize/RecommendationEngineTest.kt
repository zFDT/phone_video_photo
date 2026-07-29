package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    private val engine = RecommendationEngine()

    @Test
    fun `完全重复为高置信且只默认选择副本`() {
        val keep = media(1L)
        val duplicate = media(2L)

        val recommendation = engine.exactDuplicateRecommendation(
            DuplicateGroup("exact", listOf(keep, duplicate), keep),
        )

        assertEquals(RecommendationConfidence.HIGH, recommendation.confidence)
        assertEquals(RecommendationType.EXACT_DUPLICATE, recommendation.type)
        assertEquals(keep.id, recommendation.suggestedKeepId)
        assertEquals(setOf(duplicate.id), recommendation.defaultSelectedMediaIds)
        assertFalse(keep.id in recommendation.defaultSelectedMediaIds)
    }

    @Test
    fun `相似媒体和低质量媒体不会默认选中`() {
        val keep = media(1L)
        val similar = media(2L)

        val similarRecommendation = engine.similarMediaRecommendation(
            DuplicateGroup("similar", listOf(keep, similar), keep),
        )
        val lowQualityRecommendation = engine.lowQualityRecommendation(similar, score = 0.2f)

        assertEquals(RecommendationConfidence.MEDIUM, similarRecommendation.confidence)
        assertEquals(RecommendationType.SIMILAR_MEDIA, similarRecommendation.type)
        assertTrue(similarRecommendation.defaultSelectedMediaIds.isEmpty())
        assertEquals(RecommendationConfidence.LOW, lowQualityRecommendation.confidence)
        assertEquals(RecommendationType.LOW_QUALITY, lowQualityRecommendation.type)
        assertTrue(lowQualityRecommendation.defaultSelectedMediaIds.isEmpty())
    }

    @Test
    fun `默认选择只来自完全重复且建议保留项始终锁定`() {
        val exact = recommendationGroup(
            id = "exact",
            filter = OrganizerFilter.EXACT_DUPLICATE,
            suggestedKeepId = 1L,
            items = listOf(
                reviewItem(1L, selectedByDefault = false),
                reviewItem(2L, selectedByDefault = true),
            ),
        )
        val similar = recommendationGroup(
            id = "similar",
            filter = OrganizerFilter.SIMILAR_MEDIA,
            suggestedKeepId = 3L,
            items = listOf(
                reviewItem(3L, selectedByDefault = false),
                reviewItem(4L, selectedByDefault = true),
            ),
        )

        assertEquals(setOf(2L), defaultSelectedMediaIds(listOf(exact, similar)))
        assertFalse(canSelectForDeletion(exact, 1L))
        assertTrue(canSelectForDeletion(exact, 2L))
    }

    @Test
    fun `删除集合只包含当前筛选可见且选中的媒体`() {
        val exact = recommendationGroup(
            id = "exact",
            filter = OrganizerFilter.EXACT_DUPLICATE,
            suggestedKeepId = 1L,
            items = listOf(reviewItem(1L), reviewItem(2L)),
        )
        val lowQuality = recommendationGroup(
            id = "low",
            filter = OrganizerFilter.LOW_QUALITY,
            suggestedKeepId = null,
            items = listOf(reviewItem(3L)),
        )
        val selection = setOf(1L, 2L, 3L)

        assertEquals(
            listOf("content://media/2"),
            deletionUrisForVisibleSelection(
                groups = listOf(exact, lowQuality),
                visibleFilter = OrganizerFilter.EXACT_DUPLICATE,
                selectedMediaIds = selection,
            ),
        )
        assertEquals(
            listOf("content://media/3"),
            deletionUrisForVisibleSelection(
                groups = listOf(exact, lowQuality),
                visibleFilter = OrganizerFilter.LOW_QUALITY,
                selectedMediaIds = selection,
            ),
        )
    }

    private fun recommendationGroup(
        id: String,
        filter: OrganizerFilter,
        suggestedKeepId: Long?,
        items: List<OrganizationMediaItem>,
    ) = OrganizationGroup(
        id = id,
        filter = filter,
        confidence = RecommendationConfidence.HIGH,
        suggestedKeepId = suggestedKeepId,
        items = items,
    )

    private fun reviewItem(
        id: Long,
        selectedByDefault: Boolean = false,
    ) = OrganizationMediaItem(
        mediaId = id,
        uri = "content://media/$id",
        label = "媒体 $id",
        selectableForDeletion = true,
        selectedByDefault = selectedByDefault,
    )

    private fun media(id: Long) = MediaAssetEntity(
        id = id,
        uri = "content://media/$id",
        mimeType = "image/jpeg",
        takenAtMillis = id,
        durationMillis = 0L,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        sizeBytes = id,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = id,
    )
}
