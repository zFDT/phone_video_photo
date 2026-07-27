package com.frameyu.app.album

import com.frameyu.app.data.db.MediaAssetEntity
import java.util.Locale

/** 相册整理阶段使用的元数据分类。 */
enum class AlbumCategory {
    SCREENSHOT,
    SCREEN_RECORDING,
    RECEIPT,
    DOCUMENT,
    BARCODE,
    PARKING,
    OTHER,
}

/** 可扩展的元数据规则；规则不应把视觉识别结果伪装成元数据结论。 */
class AlbumRule(
    val category: AlbumCategory,
    private val predicate: (MediaAssetEntity) -> Boolean,
) {
    fun matches(asset: MediaAssetEntity): Boolean = predicate(asset)
}

/** 基于 MediaAssetEntity 已有字段进行确定性分类和重复候选键生成。 */
class AlbumClassifier(
    private val rules: List<AlbumRule> = emptyList(),
) {
    /** 返回所有命中的规则；没有元数据规则命中时返回 OTHER。 */
    fun classify(asset: MediaAssetEntity): Set<AlbumCategory> {
        val categories = rules.ifEmpty { defaultRules }
            .filter { it.matches(asset) }
            .mapTo(linkedSetOf()) { it.category }
        return if (categories.isEmpty()) setOf(AlbumCategory.OTHER) else categories
    }

    /** 使用媒体特征生成重复键，不包含 URI，允许识别不同路径下的同一份媒体。 */
    fun duplicateKey(asset: MediaAssetEntity): String = buildString {
        appendPart(asset.width.toString())
        appendPart(asset.height.toString())
        appendPart(asset.durationMillis.toString())
        appendPart(asset.sizeBytes.toString())
        appendPart(asset.takenAtMillis.toString())
        appendPart(asset.rotationDegrees.toString())
        appendPart(asset.isHdr.toString())
        appendPart(asset.mimeType.trim().lowercase(Locale.ROOT))
    }

    private fun StringBuilder.appendPart(value: String?) {
        if (value == null) {
            append("-1:")
        } else {
            append(value.length).append(':').append(value)
        }
        append('|')
    }

    private fun metadataText(asset: MediaAssetEntity): String = listOfNotNull(
        asset.uri,
        asset.relativePath,
        asset.uri.substringAfterLast('/'),
        asset.relativePath?.substringAfterLast('/'),
    ).joinToString(" ").lowercase(Locale.ROOT)

    private fun isScreenshot(asset: MediaAssetEntity): Boolean {
        val text = metadataText(asset)
        return listOf("screenshot", "screen_shot", "screen-shot", "截图")
            .any(text::contains)
    }

    private fun isScreenRecording(asset: MediaAssetEntity): Boolean {
        if (!asset.mimeType.trim().lowercase(Locale.ROOT).startsWith("video/")) return false
        if (asset.durationMillis <= 0L) return false
        val text = metadataText(asset)
        return listOf("screenrecord", "screen_record", "screen-record", "screen recording", "录屏")
            .any(text::contains)
    }

    private val defaultRules: List<AlbumRule> = listOf(
        AlbumRule(AlbumCategory.SCREENSHOT, ::isScreenshot),
        AlbumRule(AlbumCategory.SCREEN_RECORDING, ::isScreenRecording),
        // 这些分类暂不基于弱元数据猜测，保留规则扩展入口。
        AlbumRule(AlbumCategory.RECEIPT) { false },
        AlbumRule(AlbumCategory.DOCUMENT) { false },
        AlbumRule(AlbumCategory.BARCODE) { false },
        AlbumRule(AlbumCategory.PARKING) { false },
    )
}
