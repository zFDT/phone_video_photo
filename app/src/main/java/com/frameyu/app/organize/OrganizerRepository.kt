package com.frameyu.app.organize

import com.frameyu.app.album.AlbumCategory
import com.frameyu.app.album.AlbumClassifier
import com.frameyu.app.data.db.AnalysisCandidateSummary
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.MediaQualityResultEntity

/** 相册整理仓库，只提供重复候选查询和显式 Uri 删除入口。 */
class OrganizerRepository(
    private val detector: DuplicateDetector,
    private val contentHashDetector: ContentHashDuplicateDetector? = null,
    private val deleter: suspend (List<String>) -> MediaDeletionResult,
    private val deletionSafetyValidator: DeletionSafetyValidator = DeletionSafetyValidator.unavailable(),
    private val recommendationEngine: RecommendationEngine = RecommendationEngine(),
    private val albumClassifier: AlbumClassifier = AlbumClassifier(),
) {
    /** 查询重复候选；查询过程不会触发删除。 */
    fun duplicateCandidates(assets: Iterable<MediaAssetEntity>): List<DuplicateGroup> =
        detector.detect(assets)

    /** 执行用户主动发起的内容级完全重复扫描；扫描过程不会触发删除。 */
    suspend fun exactDuplicateCandidates(assets: Iterable<MediaAssetEntity>): ContentHashDuplicateResult =
        requireNotNull(contentHashDetector) { "未配置完全重复扫描器" }.detect(assets)

    /** 把 Room 中已有的质量结果和视频候选映射为统一人工确认数据，不重新运行模型。 */
    fun persistedGroups(
        assets: List<MediaAssetEntity>,
        qualityResults: List<MediaQualityResultEntity>,
        candidateSummaries: List<AnalysisCandidateSummary>,
    ): List<OrganizationGroup> {
        val assetsById = assets.associateBy(MediaAssetEntity::id)
        val latestQualityByMedia = qualityResults.distinctBy(MediaQualityResultEntity::mediaAssetId)
        return buildList {
            latestQualityByMedia.forEach { quality ->
                val asset = assetsById[quality.mediaAssetId] ?: return@forEach
                if (quality.totalScore < LOW_QUALITY_THRESHOLD) {
                    add(
                        recommendationEngine.lowQualityRecommendation(asset, quality.totalScore)
                            .toGroup(OrganizerFilter.LOW_QUALITY),
                    )
                }
            }
            assets.forEach { asset ->
                val categories = albumClassifier.classify(asset)
                if (AlbumCategory.SCREENSHOT in categories) {
                    add(asset.toCategoryGroup(OrganizerFilter.SCREENSHOT))
                }
                if (AlbumCategory.SCREEN_RECORDING in categories) {
                    add(asset.toCategoryGroup(OrganizerFilter.SCREEN_RECORDING))
                }
            }
            candidateSummaries.forEach { summary ->
                val asset = assetsById[summary.mediaAssetId] ?: return@forEach
                if (asset.mimeType.startsWith(VIDEO_MIME_PREFIX)) {
                    add(asset.toVideoHighlightGroup(summary))
                }
            }
        }
    }

    fun exactGroups(groups: List<DuplicateGroup>): List<OrganizationGroup> =
        groups.map { group ->
            recommendationEngine.exactDuplicateRecommendation(group)
                .toGroup(OrganizerFilter.EXACT_DUPLICATE)
        }

    fun similarGroups(groups: List<DuplicateGroup>): List<OrganizationGroup> =
        groups.map { group ->
            recommendationEngine.similarMediaRecommendation(group)
                .toGroup(OrganizerFilter.SIMILAR_MEDIA)
        }

    /** live 验证冻结计划后，仅把计划内 Uri 交给注入的删除器。 */
    suspend fun delete(plan: DeletionPlan): DeletionExecutionResult {
        return when (val validation = deletionSafetyValidator.validate(plan)) {
            DeletionValidationResult.Valid -> DeletionExecutionResult.Completed(
                deleter(plan.items.map(DeletionPlanItem::uri)),
            )
            is DeletionValidationResult.Blocked -> DeletionExecutionResult.Blocked(validation.reason)
        }
    }

    private fun OrganizationRecommendation.toGroup(filter: OrganizerFilter): OrganizationGroup =
        OrganizationGroup(
            id = id,
            filter = filter,
            confidence = confidence,
            suggestedKeepId = suggestedKeepId,
            exactContentHash = if (filter == OrganizerFilter.EXACT_DUPLICATE) {
                id.removePrefix("exact:")
            } else {
                null
            },
            items = media.map { asset ->
                OrganizationMediaItem(
                    mediaId = asset.id,
                    uri = asset.uri,
                    sizeBytes = asset.sizeBytes,
                    lastModifiedMillis = asset.lastModifiedMillis,
                    label = displayName(asset),
                    selectableForDeletion = asset.id != suggestedKeepId,
                    selectedByDefault = asset.id in defaultSelectedMediaIds,
                )
            },
        )

    private fun MediaAssetEntity.toCategoryGroup(filter: OrganizerFilter): OrganizationGroup =
        OrganizationGroup(
            id = "${filter.name.lowercase()}:$id",
            filter = filter,
            confidence = null,
            suggestedKeepId = null,
            items = listOf(
                OrganizationMediaItem(
                    mediaId = id,
                    uri = uri,
                    sizeBytes = sizeBytes,
                    lastModifiedMillis = lastModifiedMillis,
                    label = displayName(this),
                    selectableForDeletion = true,
                ),
            ),
        )

    private fun MediaAssetEntity.toVideoHighlightGroup(
        summary: AnalysisCandidateSummary,
    ): OrganizationGroup = OrganizationGroup(
        id = "video-highlight:$id",
        filter = OrganizerFilter.VIDEO_HIGHLIGHT,
        confidence = null,
        suggestedKeepId = id,
        items = listOf(
            OrganizationMediaItem(
                mediaId = id,
                uri = uri,
                sizeBytes = sizeBytes,
                lastModifiedMillis = lastModifiedMillis,
                label = displayName(this),
                selectableForDeletion = false,
                detail = "已保存 ${summary.candidateCount} 个精彩候选帧，最高评分 ${summary.maxScore}",
            ),
        ),
    )

    private fun displayName(asset: MediaAssetEntity): String =
        asset.uri.substringAfterLast('/').ifBlank { "媒体 ${asset.id}" }

    private companion object {
        const val LOW_QUALITY_THRESHOLD = 0.35f
        const val VIDEO_MIME_PREFIX = "video/"
    }
}
