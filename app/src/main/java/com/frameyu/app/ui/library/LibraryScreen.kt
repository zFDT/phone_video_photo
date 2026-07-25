package com.frameyu.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.media.MediaScanState
import java.util.Locale

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    hasMediaReadPermission: Boolean = true,
    onRequestMediaReadPermission: () -> Unit = {},
    onSelectMedia: () -> Unit = {},
    onStartAnalysis: (MediaAssetEntity) -> Unit = {},
    onStartBatchAnalysis: () -> Unit = {},
    onCancelBatchAnalysis: () -> Unit = {},
    onOpenCandidates: (MediaAssetEntity) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val isScanning = uiState.scanState is MediaScanState.Scanning
    val canScan = hasMediaReadPermission && !isScanning
    val taskSummary = analysisTaskSummary(uiState.analysisTasks.values)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(text = "媒体库", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "已索引媒体数量：${uiState.mediaCount}")
        Text(
            text = "扫描进度：${scanProgressText(uiState.scanState)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = scanStateText(uiState.scanState),
            style = MaterialTheme.typography.bodySmall,
        )

        if (!hasMediaReadPermission) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "尚未获得照片和视频访问权限，无法扫描媒体库。")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestMediaReadPermission) {
                Text(text = "打开系统权限入口")
            }
        }

        if (isScanning) {
            Spacer(modifier = Modifier.height(12.dp))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "视频 ${uiState.videos.size}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (taskSummary.total > 0) {
            Text(
                text = "分析任务：${taskSummary.completed}/${taskSummary.total} 完成" +
                    " · ${taskSummary.running + taskSummary.queued} 排队/进行中",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (uiState.videos.any { uiState.analysisTasks[it.id]?.status !in setOf("QUEUED", "RUNNING", "COMPLETED") }) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStartBatchAnalysis,
            ) {
                Text(text = "批量分析未完成视频")
            }
        }
        if (taskSummary.running + taskSummary.queued > 0) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancelBatchAnalysis,
            ) {
                Text(text = "取消当前批量分析")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (uiState.videos.isEmpty()) {
            Text(
                text = "扫描完成后，这里会显示可分析的视频。",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.videos, key = MediaAssetEntity::id) { video ->
                    VideoAnalysisRow(
                        video = video,
                        task = uiState.analysisTasks[video.id],
                        onStartAnalysis = { onStartAnalysis(video) },
                        onOpenCandidates = { onOpenCandidates(video) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = viewModel::scanAll,
                enabled = canScan,
            ) {
                Text(text = "开始全量扫描")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = viewModel::scanIncremental,
                enabled = canScan,
            ) {
                Text(text = "增量扫描")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClick = onSelectMedia,
        ) {
            Text(text = "选择媒体")
        }
    }
}

internal data class AnalysisTaskSummary(
    val total: Int,
    val queued: Int,
    val running: Int,
    val completed: Int,
)

internal fun analysisTaskSummary(tasks: Collection<AnalysisTaskEntity>): AnalysisTaskSummary =
    AnalysisTaskSummary(
        total = tasks.size,
        queued = tasks.count { it.status == "QUEUED" },
        running = tasks.count { it.status == "RUNNING" },
        completed = tasks.count { it.status == "COMPLETED" },
    )

@Composable
private fun VideoAnalysisRow(
    video: MediaAssetEntity,
    task: AnalysisTaskEntity?,
    onStartAnalysis: () -> Unit,
    onOpenCandidates: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(text = videoDisplayName(video), maxLines = 1)
        Text(
            text = "${formatDurationMillis(video.durationMillis)} · ${video.width}×${video.height}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = analysisTaskStatusText(task?.status, task?.progress ?: 0))
                if (!task?.errorMessage.isNullOrBlank()) {
                    Text(
                        text = task?.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
            if (task?.status == "COMPLETED") {
                Button(onClick = onOpenCandidates) { Text(text = "查看候选帧") }
            } else {
                Button(
                    onClick = onStartAnalysis,
                    enabled = task?.status != "QUEUED" && task?.status != "RUNNING",
                ) {
                    Text(text = analysisActionText(task?.status))
                }
            }
        }
    }
}

internal fun formatDurationMillis(durationMillis: Long): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
    return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

internal fun analysisTaskStatusText(status: String?, progress: Int): String = when (status) {
    null -> "尚未分析"
    "QUEUED" -> "等待分析"
    "RUNNING" -> "分析中 ${progress.coerceIn(0, 100)}%"
    "COMPLETED" -> "分析完成"
    "CANCELLED" -> "已取消"
    "FAILED" -> "分析失败"
    else -> "状态未知"
}

private fun analysisActionText(status: String?): String = when (status) {
    "COMPLETED" -> "重新分析"
    "FAILED", "CANCELLED" -> "重试分析"
    "QUEUED", "RUNNING" -> "分析中"
    else -> "开始分析"
}

private fun videoDisplayName(video: MediaAssetEntity): String =
    video.uri.substringAfterLast('/').ifBlank { "视频 ${video.id}" }

private fun scanStateText(scanState: MediaScanState): String = when (scanState) {
    MediaScanState.Idle -> "尚未开始扫描"
    is MediaScanState.Scanning -> "正在扫描媒体…"
    is MediaScanState.Completed -> "扫描完成，本次更新 ${scanState.indexedCount} 个媒体"
    is MediaScanState.Failed -> "扫描失败：${scanState.message}"
}

private fun scanProgressText(scanState: MediaScanState): String = when (scanState) {
    is MediaScanState.Scanning -> "已读取 ${scanState.processed}/${scanState.total} 个媒体"
    is MediaScanState.Completed -> "本次更新 ${scanState.indexedCount} 个媒体"
    else -> "等待开始"
}
