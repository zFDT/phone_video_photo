package com.frameyu.app.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.frameyu.app.analysis.batch.BatchAnalysisScheduler
import com.frameyu.app.data.db.FrameYuDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 批次状态页只观察 Room 中的 ID、状态和计数，不持有图片或 Bitmap。 */
@Composable
fun BatchAnalysisScreen(
    batchId: Long,
    database: FrameYuDatabase,
    scheduler: BatchAnalysisScheduler,
    onOpenOrganizer: () -> Unit,
    onBack: () -> Unit,
) {
    val progress by database.batchAnalysisDao().observeBatch(batchId)
        .collectAsState(initial = null)
    val currentStage by database.batchAnalysisDao().observeCurrentStage(batchId)
        .collectAsState(initial = null)
    val failedItems by database.batchAnalysisDao().observeFailedItems(batchId)
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var actionRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val state = progress?.let {
        batchAnalysisUiState(
            batchId = batchId,
            progress = it,
            currentStage = currentStage,
            failedItems = failedItems,
        )
    }

    fun runAction(action: suspend () -> Unit) {
        if (!canStartBatchAction(actionRunning)) return
        actionRunning = true
        errorMessage = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.message ?: "批次操作失败"
            } finally {
                actionRunning = false
            }
        }
    }

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
            Text(text = "批量深度分析", style = MaterialTheme.typography.headlineSmall)
            Text(text = "#$batchId", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(24.dp))
        if (state == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(
                text = "正在读取批次状态…",
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            Text(text = state.statusLabel, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "总工作项：${state.total}")
            Text(text = "已完成：${state.completed}")
            Text(text = state.failureCountLabel)
            Text(text = "当前阶段：${state.currentStageLabel}")
            state.statusDescription?.let { description ->
                Text(text = description)
            }
            Text(
                text = "总体进度：${(state.progressFraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.failureItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                state.failureSummaryLabel?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.failureItems, key = BatchFailureUiItem::id) { failure ->
                        Column {
                            Text(
                                text = "媒体 ${failure.mediaAssetId} · " +
                                    "${failure.stageLabel} · ${failure.statusLabel}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = failure.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (state.status == "RUNNING" || state.status == "QUEUED") {
                Spacer(modifier = Modifier.height(12.dp))
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            errorMessage?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.actions.canPause) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { runAction { scheduler.pause(batchId) } },
                        enabled = !actionRunning,
                    ) {
                        Text(text = "暂停")
                    }
                }
                if (state.actions.canResume) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { runAction { scheduler.resume(batchId) } },
                        enabled = !actionRunning,
                    ) {
                        Text(text = "继续")
                    }
                }
                if (state.actions.canRetryFailures) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { runAction { scheduler.retryFailures(batchId) } },
                        enabled = !actionRunning,
                    ) {
                        Text(text = "重试失败项")
                    }
                }
                if (state.actions.canCancel) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { runAction { scheduler.cancel(batchId) } },
                        enabled = !actionRunning,
                    ) {
                        Text(text = "取消批次")
                    }
                }
            }
            if (state.actions.canOpenOrganizer) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenOrganizer,
                ) {
                    Text(text = "进入统一整理")
                }
            }
        }
    }
}
