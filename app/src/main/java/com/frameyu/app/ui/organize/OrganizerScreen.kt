package com.frameyu.app.ui.organize

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.organize.ContentHashDuplicateResult
import com.frameyu.app.organize.OrganizationGroup
import com.frameyu.app.organize.OrganizationMediaItem
import com.frameyu.app.organize.OrganizerFilter
import com.frameyu.app.organize.OrganizerRepository
import com.frameyu.app.organize.RecommendationConfidence
import com.frameyu.app.organize.SimilarMediaDuplicateResult
import com.frameyu.app.organize.canSelectForDeletion
import com.frameyu.app.organize.defaultSelectedMediaIds
import com.frameyu.app.organize.deletionUrisForVisibleSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 汇总 Room 分析结果和手动重复扫描结果；删除必须逐媒体勾选并再次确认。 */
@Composable
fun OrganizerScreen(
    database: FrameYuDatabase,
    repository: OrganizerRepository,
    onExactDuplicateScan: suspend (List<MediaAssetEntity>) -> ContentHashDuplicateResult,
    onSimilarDuplicateScan: suspend (List<MediaAssetEntity>) -> SimilarMediaDuplicateResult,
    onOpenVideoCandidates: (MediaAssetEntity) -> Unit = {},
    onBack: () -> Unit,
) {
    val assets by database.mediaAssetDao().observeAll().collectAsState(initial = emptyList())
    val qualityResults by database.batchAnalysisDao().observeQualityResults()
        .collectAsState(initial = emptyList())
    val candidates by database.analysisCandidateDao().observeAll()
        .collectAsState(initial = emptyList())
    val persistedGroups = remember(assets, qualityResults, candidates) {
        repository.persistedGroups(assets, qualityResults, candidates)
    }
    var exactGroups by remember { mutableStateOf(emptyList<OrganizationGroup>()) }
    var similarGroups by remember { mutableStateOf(emptyList<OrganizationGroup>()) }
    val allGroups = remember(persistedGroups, exactGroups, similarGroups) {
        persistedGroups + exactGroups + similarGroups
    }
    val assetsById = remember(assets) { assets.associateBy(MediaAssetEntity::id) }
    var activeFilter by remember { mutableStateOf(OrganizerFilter.EXACT_DUPLICATE) }
    val visibleGroups = remember(allGroups, activeFilter) {
        allGroups.filter { group -> group.filter == activeFilter }
    }
    val filterCounts = remember(allGroups) {
        OrganizerFilter.entries.associateWith { filter ->
            allGroups.count { group -> group.filter == filter }
        }
    }
    var selectedMediaIds by remember { mutableStateOf(defaultSelectedMediaIds(allGroups)) }
    val deleteUris = remember(allGroups, activeFilter, selectedMediaIds) {
        deletionUrisForVisibleSelection(allGroups, activeFilter, selectedMediaIds)
    }
    val scope = rememberCoroutineScope()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var isScanningExact by remember { mutableStateOf(false) }
    var isScanningSimilar by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(text = "统一整理", style = MaterialTheme.typography.headlineSmall)
            Text(text = "${assets.size} 项", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = "这里仅生成建议，不会自动删除任何媒体。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(OrganizerFilter.entries, key = OrganizerFilter::name) { filter ->
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = { activeFilter = filter },
                    label = { Text(text = "${filterLabel(filter)} ${filterCounts[filter] ?: 0}") },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    isScanningExact = true
                    scope.launch {
                        try {
                            val result = onExactDuplicateScan(assets)
                            exactGroups = repository.exactGroups(result.groups)
                            selectedMediaIds += defaultSelectedMediaIds(exactGroups)
                            activeFilter = OrganizerFilter.EXACT_DUPLICATE
                            message = scanMessage("完全重复", result.groups.size, result.failedUris.size)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "完全重复扫描失败：${error.message ?: "未知错误"}"
                        } finally {
                            isScanningExact = false
                        }
                    }
                },
                enabled = assets.isNotEmpty() && !isScanningExact && !isScanningSimilar,
            ) {
                Text(text = if (isScanningExact) "扫描中…" else "扫描完全重复")
            }
            OutlinedButton(
                onClick = {
                    isScanningSimilar = true
                    scope.launch {
                        try {
                            val result = onSimilarDuplicateScan(assets)
                            similarGroups = repository.similarGroups(result.groups)
                            activeFilter = OrganizerFilter.SIMILAR_MEDIA
                            message = scanMessage("相似媒体", result.groups.size, result.failedUris.size)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "相似媒体扫描失败：${error.message ?: "未知错误"}"
                        } finally {
                            isScanningSimilar = false
                        }
                    }
                },
                enabled = assets.isNotEmpty() && !isScanningExact && !isScanningSimilar,
            ) {
                Text(text = if (isScanningSimilar) "扫描中…" else "扫描相似媒体")
            }
        }
        message?.let {
            Text(
                text = it,
                color = if ("失败" in it) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (visibleGroups.isEmpty()) {
            Text(
                text = emptyStateText(activeFilter),
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visibleGroups, key = OrganizationGroup::id) { group ->
                    OrganizationGroupCard(
                        group = group,
                        selectedMediaIds = selectedMediaIds,
                        onToggle = { mediaId ->
                            if (canSelectForDeletion(group, mediaId)) {
                                selectedMediaIds = if (mediaId in selectedMediaIds) {
                                    selectedMediaIds - mediaId
                                } else {
                                    selectedMediaIds + mediaId
                                }
                            }
                        },
                        onOpenVideoCandidates = {
                            group.items.firstOrNull()?.mediaId
                                ?.let(assetsById::get)
                                ?.let(onOpenVideoCandidates)
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    selectedMediaIds += visibleGroups.asSequence()
                        .flatMap { group ->
                            group.items.asSequence()
                                .filter { item -> canSelectForDeletion(group, item.mediaId) }
                                .map(OrganizationMediaItem::mediaId)
                        }
                        .toSet()
                },
                enabled = visibleGroups.any { group ->
                    group.items.any { item -> canSelectForDeletion(group, item.mediaId) }
                },
            ) {
                Text(text = "选择当前可删项")
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = { showDeleteConfirmation = true },
                enabled = deleteUris.isNotEmpty(),
            ) {
                Text(text = "确认删除 ${deleteUris.size} 项")
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(text = "确认删除 ${deleteUris.size} 项？") },
            text = {
                Text(text = "仅处理当前“${filterLabel(activeFilter)}”筛选中已勾选的媒体；建议保留项已锁定。")
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirmation = false
                    scope.launch {
                        try {
                            repository.delete(deleteUris.map(Uri::parse))
                            selectedMediaIds -= deleteUris.mapTo(mutableSetOf()) { uri ->
                                allGroups.asSequence()
                                    .flatMap(OrganizationGroup::items)
                                    .first { item -> item.uri == uri }
                                    .mediaId
                            }
                            message = "已提交 ${deleteUris.size} 项到系统最近删除"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = "删除请求失败：${error.message ?: "未知错误"}"
                        }
                    }
                }) {
                    Text(text = "确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(text = "取消")
                }
            },
        )
    }
}

@Composable
private fun OrganizationGroupCard(
    group: OrganizationGroup,
    selectedMediaIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onOpenVideoCandidates: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = groupTitle(group),
                style = MaterialTheme.typography.titleSmall,
            )
            group.confidence?.let { confidence ->
                Text(
                    text = confidenceLabel(confidence),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        group.items.forEach { item ->
            val locked = item.mediaId == group.suggestedKeepId || !item.selectableForDeletion
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = !locked && item.mediaId in selectedMediaIds,
                    onCheckedChange = { onToggle(item.mediaId) },
                    enabled = !locked,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.label, maxLines = 1)
                    val detail = when {
                        item.mediaId == group.suggestedKeepId &&
                            group.filter != OrganizerFilter.VIDEO_HIGHLIGHT -> "建议保留 · 已锁定"
                        item.detail != null -> item.detail
                        locked -> "仅供查看，不会删除"
                        else -> "由你决定是否删除"
                    }
                    Text(text = detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (group.filter == OrganizerFilter.VIDEO_HIGHLIGHT) {
            TextButton(onClick = onOpenVideoCandidates) {
                Text(text = "查看精彩候选帧")
            }
        }
    }
}

private fun groupTitle(group: OrganizationGroup): String = when (group.filter) {
    OrganizerFilter.EXACT_DUPLICATE -> "完全重复 · ${group.items.size} 项"
    OrganizerFilter.SIMILAR_MEDIA -> "相似媒体 · ${group.items.size} 项"
    OrganizerFilter.LOW_QUALITY -> "低质量建议"
    OrganizerFilter.SCREENSHOT -> "截图"
    OrganizerFilter.SCREEN_RECORDING -> "录屏"
    OrganizerFilter.VIDEO_HIGHLIGHT -> "视频精彩候选"
}

private fun filterLabel(filter: OrganizerFilter): String = when (filter) {
    OrganizerFilter.EXACT_DUPLICATE -> "完全重复"
    OrganizerFilter.SIMILAR_MEDIA -> "相似"
    OrganizerFilter.LOW_QUALITY -> "低质量"
    OrganizerFilter.SCREENSHOT -> "截图"
    OrganizerFilter.SCREEN_RECORDING -> "录屏"
    OrganizerFilter.VIDEO_HIGHLIGHT -> "视频精彩"
}

private fun confidenceLabel(confidence: RecommendationConfidence): String = when (confidence) {
    RecommendationConfidence.HIGH -> "高置信"
    RecommendationConfidence.MEDIUM -> "中置信"
    RecommendationConfidence.LOW -> "低置信"
}

private fun emptyStateText(filter: OrganizerFilter): String = when (filter) {
    OrganizerFilter.EXACT_DUPLICATE -> "暂无完全重复结果；可点击“扫描完全重复”补充。"
    OrganizerFilter.SIMILAR_MEDIA -> "暂无相似结果；可点击“扫描相似媒体”补充。"
    OrganizerFilter.LOW_QUALITY -> "已持久化分析结果中暂无低质量建议。"
    OrganizerFilter.SCREENSHOT -> "媒体索引中暂无截图。"
    OrganizerFilter.SCREEN_RECORDING -> "媒体索引中暂无录屏。"
    OrganizerFilter.VIDEO_HIGHLIGHT -> "暂无已保存的视频精彩候选。"
}

private fun scanMessage(type: String, groupCount: Int, failedCount: Int): String =
    if (failedCount == 0) {
        "$type 扫描完成：发现 $groupCount 组"
    } else {
        "$type 扫描完成：发现 $groupCount 组，$failedCount 项读取失败"
    }
