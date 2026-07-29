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

data class DeletionPlanItem(
    val mediaId: Long,
    val uri: String,
)

class DeletionPlan(
    val visibleFilter: OrganizerFilter,
    items: List<DeletionPlanItem>,
) {
    val items: List<DeletionPlanItem> = items.toList()
    val mediaIds: Set<Long> = items.mapTo(linkedSetOf(), DeletionPlanItem::mediaId)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DeletionPlan &&
            visibleFilter == other.visibleFilter &&
            items == other.items

    override fun hashCode(): Int = 31 * visibleFilter.hashCode() + items.hashCode()
}

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

fun revalidateManualGroups(
    groups: List<OrganizationGroup>,
    currentAssets: List<MediaAssetEntity>,
): List<OrganizationGroup> {
    val currentAssetsById = currentAssets.associateBy(MediaAssetEntity::id)
    return groups.mapNotNull { group ->
        val suggestedKeepId = group.suggestedKeepId ?: return@mapNotNull null
        val validItems = group.items.filter { item ->
            currentAssetsById[item.mediaId]?.uri == item.uri
        }
        val suggestedKeepIsValid = validItems.any { item -> item.mediaId == suggestedKeepId }
        if (!suggestedKeepIsValid || validItems.size < 2) {
            null
        } else {
            group.copy(items = validItems)
        }
    }
}

fun invalidatedManualMediaIds(
    groups: List<OrganizationGroup>,
    currentAssets: List<MediaAssetEntity>,
): Set<Long> {
    val validGroupsById = revalidateManualGroups(groups, currentAssets)
        .associateBy(OrganizationGroup::id)
    return groups.asSequence()
        .flatMap { originalGroup ->
            val validGroup = validGroupsById[originalGroup.id]
            val validMediaIds = validGroup?.items
                ?.mapTo(hashSetOf(), OrganizationMediaItem::mediaId)
                .orEmpty()
            originalGroup.items.asSequence()
                .map(OrganizationMediaItem::mediaId)
                .filterNot(validMediaIds::contains)
        }
        .toSet()
}

fun reconcileSelectedMediaIds(
    groups: List<OrganizationGroup>,
    selectedMediaIds: Set<Long>,
    invalidatedMediaIds: Set<Long> = emptySet(),
): Set<Long> {
    val validSelectableIds = groups.asSequence()
        .flatMap { group ->
            group.items.asSequence()
                .filter { item -> canSelectForDeletion(group, item.mediaId) }
                .map(OrganizationMediaItem::mediaId)
        }
        .toSet()
    return selectedMediaIds
        .minus(invalidatedMediaIds)
        .intersect(validSelectableIds)
}

fun buildDeletionPlan(
    groups: List<OrganizationGroup>,
    visibleFilter: OrganizerFilter,
    selectedMediaIds: Set<Long>,
): DeletionPlan {
    val candidates = groups.asSequence()
        .filter { group -> group.filter == visibleFilter }
        .flatMap { group ->
            group.items.asSequence()
                .filter { item -> item.mediaId in selectedMediaIds }
                .filter { item -> canSelectForDeletion(group, item.mediaId) }
                .map { item -> DeletionPlanItem(item.mediaId, item.uri) }
        }
        .groupBy(DeletionPlanItem::mediaId)
        .mapNotNull { (_, items) ->
            items.firstOrNull()?.takeIf { candidate ->
                items.all { item -> item.uri == candidate.uri }
            }
        }
        .sortedWith(compareBy(DeletionPlanItem::mediaId).thenBy(DeletionPlanItem::uri))
    return DeletionPlan(
        visibleFilter = visibleFilter,
        items = candidates.toList(),
    )
}

fun validateDeletionPlan(
    plan: DeletionPlan,
    groups: List<OrganizationGroup>,
    visibleFilter: OrganizerFilter,
    selectedMediaIds: Set<Long>,
): Boolean = plan == buildDeletionPlan(groups, visibleFilter, selectedMediaIds)

fun deletionUrisForVisibleSelection(
    groups: List<OrganizationGroup>,
    visibleFilter: OrganizerFilter,
    selectedMediaIds: Set<Long>,
): List<String> = buildDeletionPlan(groups, visibleFilter, selectedMediaIds)
    .items
    .map(DeletionPlanItem::uri)
