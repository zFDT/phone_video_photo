package com.frameyu.app.organize

import com.frameyu.app.album.AlbumClassifier
import com.frameyu.app.data.db.MediaAssetEntity

/** 一组具有相同重复键的媒体候选，以及建议保留的媒体。 */
data class DuplicateGroup(
    val duplicateKey: String,
    val assets: List<MediaAssetEntity>,
    val suggestedKeep: MediaAssetEntity,
)

/** 基于 MediaAssetEntity 的 duplicateKey 生成重复候选，不执行任何删除。 */
class DuplicateDetector(
    private val duplicateKey: (MediaAssetEntity) -> String = AlbumClassifier()::duplicateKey,
) {
    /** 只返回至少包含两个媒体的重复组，并按规则选出建议保留项。 */
    fun detect(assets: Iterable<MediaAssetEntity>): List<DuplicateGroup> =
        assets.groupBy(duplicateKey)
            .filterValues { it.size > 1 }
            .map { (key, group) ->
                DuplicateGroup(
                    duplicateKey = key,
                    assets = group,
                    suggestedKeep = group.minWithOrNull(keepPreference)!!,
                )
            }

    private val keepPreference = compareBy<MediaAssetEntity> { it.takenAtMillis }
        .thenByDescending { it.sizeBytes }
        .thenBy { it.id }
        .thenBy { it.uri }
}
