package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity

enum class RecommendationConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

enum class RecommendationType {
    EXACT_DUPLICATE,
    SIMILAR_MEDIA,
    LOW_QUALITY,
}

data class OrganizationRecommendation(
    val id: String,
    val type: RecommendationType,
    val confidence: RecommendationConfidence,
    val media: List<MediaAssetEntity>,
    val suggestedKeepId: Long?,
    val defaultSelectedMediaIds: Set<Long>,
) {
    val mediaIds: List<Long>
        get() = media.map(MediaAssetEntity::id)

    val defaultSelected: Boolean
        get() = defaultSelectedMediaIds.isNotEmpty()
}

class RecommendationEngine {
    fun exactDuplicateRecommendation(group: DuplicateGroup): OrganizationRecommendation {
        requireValidGroup(group)
        return OrganizationRecommendation(
            id = "exact:${group.duplicateKey}",
            type = RecommendationType.EXACT_DUPLICATE,
            confidence = RecommendationConfidence.HIGH,
            media = group.assets,
            suggestedKeepId = group.suggestedKeep.id,
            defaultSelectedMediaIds = group.assets
                .asSequence()
                .map(MediaAssetEntity::id)
                .filterNot { it == group.suggestedKeep.id }
                .toSet(),
        )
    }

    fun similarMediaRecommendation(group: DuplicateGroup): OrganizationRecommendation {
        requireValidGroup(group)
        return OrganizationRecommendation(
            id = "similar:${group.duplicateKey}",
            type = RecommendationType.SIMILAR_MEDIA,
            confidence = RecommendationConfidence.MEDIUM,
            media = group.assets,
            suggestedKeepId = group.suggestedKeep.id,
            defaultSelectedMediaIds = emptySet(),
        )
    }

    fun lowQualityRecommendation(
        asset: MediaAssetEntity,
        score: Float,
    ): OrganizationRecommendation {
        require(score.isFinite() && score in 0f..1f) { "质量分数必须是 0 到 1 之间的有限数" }
        return OrganizationRecommendation(
            id = "low-quality:${asset.id}",
            type = RecommendationType.LOW_QUALITY,
            confidence = RecommendationConfidence.LOW,
            media = listOf(asset),
            suggestedKeepId = null,
            defaultSelectedMediaIds = emptySet(),
        )
    }

    private fun requireValidGroup(group: DuplicateGroup) {
        require(group.assets.size >= 2) { "重复推荐至少需要两个媒体" }
        require(group.assets.any { it.id == group.suggestedKeep.id }) {
            "建议保留项必须属于当前重复组"
        }
    }
}

enum class OrganizerFilter {
    EXACT_DUPLICATE,
    SIMILAR_MEDIA,
    LOW_QUALITY,
    SCREENSHOT,
    SCREEN_RECORDING,
    VIDEO_HIGHLIGHT,
}

data class OrganizationMediaItem(
    val mediaId: Long,
    val uri: String,
    val label: String,
    val selectableForDeletion: Boolean,
    val selectedByDefault: Boolean = false,
    val detail: String? = null,
)

data class OrganizationGroup(
    val id: String,
    val filter: OrganizerFilter,
    val confidence: RecommendationConfidence?,
    val suggestedKeepId: Long?,
    val items: List<OrganizationMediaItem>,
)

fun defaultSelectedMediaIds(groups: List<OrganizationGroup>): Set<Long> =
    groups.asSequence()
        .filter { it.filter == OrganizerFilter.EXACT_DUPLICATE }
        .flatMap { group ->
            group.items.asSequence()
                .filter(OrganizationMediaItem::selectedByDefault)
                .map(OrganizationMediaItem::mediaId)
                .filterNot { it == group.suggestedKeepId }
        }
        .toSet()

fun canSelectForDeletion(group: OrganizationGroup, mediaId: Long): Boolean =
    mediaId != group.suggestedKeepId &&
        group.items.any { item ->
            item.mediaId == mediaId && item.selectableForDeletion
        }

fun deletionUrisForVisibleSelection(
    groups: List<OrganizationGroup>,
    visibleFilter: OrganizerFilter,
    selectedMediaIds: Set<Long>,
): List<String> = groups.asSequence()
    .filter { it.filter == visibleFilter }
    .flatMap { group ->
        group.items.asSequence()
            .filter { item -> item.mediaId in selectedMediaIds }
            .filter { item -> canSelectForDeletion(group, item.mediaId) }
            .map(OrganizationMediaItem::uri)
    }
    .distinct()
    .toList()
