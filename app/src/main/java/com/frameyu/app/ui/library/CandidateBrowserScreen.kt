package com.frameyu.app.ui.library

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.export.StillImageExporter
import com.frameyu.app.export.StillImageFormat
import com.frameyu.app.export.VideoClipExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CandidateBrowserScreen(
    media: MediaAssetEntity,
    database: FrameYuDatabase,
    exporter: StillImageExporter,
    clipExporter: VideoClipExporter,
    onBack: () -> Unit,
) {
    val candidates by database.analysisCandidateDao()
        .observeByMediaAssetId(media.id)
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var sortByScore by rememberSaveable { mutableStateOf(true) }
    var exportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val sortedCandidates = sortCandidates(candidates, sortByScore)
    val selectedCount = candidates.count { it.isSelected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text(text = "返回") }
            Text(text = "精彩帧候选", style = MaterialTheme.typography.titleLarge)
            Text(text = "${candidates.size} 帧 · 已选 $selectedCount")
        }
        Text(
            text = media.uri.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        exportMessage?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { sortByScore = true }, enabled = !sortByScore) {
                Text(text = "按评分")
            }
            TextButton(onClick = { sortByScore = false }, enabled = sortByScore) {
                Text(text = "按时间")
            }
        }
        if (candidates.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "暂时没有候选帧")
                Text(text = "请先完成一次视频分析。", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(sortedCandidates, key = AnalysisCandidateEntity::id) { candidate ->
                    CandidateRow(
                        media = media,
                        candidate = candidate,
                        onSelectionChanged = { selected ->
                            scope.launch {
                                database.analysisCandidateDao().setSelected(media.id, candidate.id, selected)
                            }
                        },
                        onExport = {
                            scope.launch {
                                exportMessage = try {
                                    exporter.exportCandidate(media, candidate)
                                    "已导出 ${formatCandidateTimestamp(candidate.timestampMillis)}"
                                } catch (error: Throwable) {
                                    "导出失败：${error.message ?: "未知错误"}"
                                }
                            }
                        },
                        onExportWebp = {
                            scope.launch {
                                exportMessage = try {
                                    exporter.exportCandidate(media, candidate, StillImageFormat.WEBP)
                                    "已导出 WebP ${formatCandidateTimestamp(candidate.timestampMillis)}"
                                } catch (error: Throwable) {
                                    "导出失败：${error.message ?: "未知错误"}"
                                }
                            }
                        },
                        onExportMp4 = {
                            scope.launch {
                                exportMessage = try {
                                    clipExporter.exportCandidate(media, candidate)
                                    "已导出 MP4 ${formatCandidateTimestamp(candidate.timestampMillis)}"
                                } catch (error: Throwable) {
                                    "导出失败：${error.message ?: "未知错误"}"
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    media: MediaAssetEntity,
    candidate: AnalysisCandidateEntity,
    onSelectionChanged: (Boolean) -> Unit,
    onExport: () -> Unit,
    onExportWebp: () -> Unit,
    onExportMp4: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        CandidatePreview(
            uri = media.uri,
            timestampMillis = candidate.timestampMillis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatCandidateTimestamp(candidate.timestampMillis))
            Text(text = "评分 ${candidate.score}")
        }
        Button(onClick = { onSelectionChanged(!candidate.isSelected) }) {
            Text(text = if (candidate.isSelected) "取消选择" else "选择此帧")
        }
        Button(onClick = onExport) { Text(text = "导出图片") }
        Button(onClick = onExportWebp) { Text(text = "导出 WebP") }
        Button(onClick = onExportMp4) { Text(text = "导出 3 秒 MP4") }
        Text(
            text = "清晰度 ${candidate.sharpnessScore} · 曝光 ${candidate.compositionScore} · 场景 ${candidate.sceneScore}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun sortCandidates(
    candidates: List<AnalysisCandidateEntity>,
    sortByScore: Boolean,
): List<AnalysisCandidateEntity> = if (sortByScore) {
    candidates.sortedWith(compareByDescending<AnalysisCandidateEntity> { it.score }.thenBy { it.timestampMillis })
} else {
    candidates.sortedBy { it.timestampMillis }
}

@Composable
private fun CandidatePreview(uri: String, timestampMillis: Long) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, timestampMillis) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getFrameAtTime(
                    timestampMillis * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )?.let { source ->
                    val maxEdge = maxOf(source.width, source.height)
                    if (maxEdge <= 1_024) source else {
                        val scale = 1_024f / maxEdge
                        Bitmap.createScaledBitmap(
                            source,
                            (source.width * scale).toInt().coerceAtLeast(1),
                            (source.height * scale).toInt().coerceAtLeast(1),
                            true,
                        ).also { source.recycle() }
                    }
                }
            } finally {
                retriever.release()
            }
        }
        awaitDispose { value?.recycle() }
    }

    if (bitmap == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "${formatCandidateTimestamp(timestampMillis)} 候选帧",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun formatCandidateTimestamp(timestampMillis: Long): String {
    val safeMillis = timestampMillis.coerceAtLeast(0L)
    val totalSeconds = safeMillis / 1_000L
    return "%02d:%02d.%03d".format(
        totalSeconds / 60,
        totalSeconds % 60,
        safeMillis % 1_000L,
    )
}
