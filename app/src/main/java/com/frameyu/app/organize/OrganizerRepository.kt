package com.frameyu.app.organize

import android.net.Uri
import com.frameyu.app.data.db.MediaAssetEntity

/** 相册整理仓库，只提供重复候选查询和显式 Uri 删除入口。 */
class OrganizerRepository(
    private val detector: DuplicateDetector,
    private val contentHashDetector: ContentHashDuplicateDetector? = null,
    private val deleter: suspend (List<Uri>) -> Unit,
) {
    /** 查询重复候选；查询过程不会触发删除。 */
    fun duplicateCandidates(assets: Iterable<MediaAssetEntity>): List<DuplicateGroup> =
        detector.detect(assets)

    /** 执行用户主动发起的内容级完全重复扫描；扫描过程不会触发删除。 */
    suspend fun exactDuplicateCandidates(assets: Iterable<MediaAssetEntity>): ContentHashDuplicateResult =
        requireNotNull(contentHashDetector) { "未配置完全重复扫描器" }.detect(assets)

    /** 仅删除调用方明确传入的 Uri，并交给注入的删除器执行。 */
    suspend fun delete(uris: List<Uri>) {
        deleter(uris.toList())
    }
}
