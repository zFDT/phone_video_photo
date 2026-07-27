package com.frameyu.app.ui.library

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.export.GifExporter
import com.frameyu.app.export.CropRect
import com.frameyu.app.export.MotionPhotoExporter
import com.frameyu.app.export.StillImageExporter
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
    gifExporter: GifExporter,
    motionPhotoExporter: MotionPhotoExporter,
    onOpenDetail: (AnalysisCandidateEntity) -> Unit = {},
    onBack: () -> Unit,
) {
    val candidates by database.analysisCandidateDao()
        .observeByMediaAssetId(media.id)
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var sortByScore by rememberSaveable { mutableStateOf(true) }
    var clipDurationMillis by rememberSaveable { mutableStateOf(3_000L) }
    var cropMode by rememberSaveable { mutableStateOf(0) }
    var exportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var isExporting by rememberSaveable { mutableStateOf(false) }
    val sortedCandidates = sortCandidates(candidates, sortByScore)
    val selectedCandidates = candidates.filter { it.isSelected }

    fun setAllSelected(selected: Boolean) {
        scope.launch { database.analysisCandidateDao().setAllSelected(media.id, selected) }
    }

    fun selectTopCandidates() {
        scope.launch {
            val dao = database.analysisCandidateDao()
            dao.setAllSelected(media.id, false)
            topCandidateIds(candidates, 5).forEach { dao.setSelected(media.id, it, true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "精彩帧候选", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${candidates.size} 帧 · 已选 ${selectedCandidates.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { setAllSelected(true) }, enabled = candidates.isNotEmpty()) {
                Text(text = "全选")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "静态构图", style = MaterialTheme.typography.bodySmall)
            listOf("原始", "1:1", "16:9").forEachIndexed { index, label ->
                TextButton(
                    onClick = { cropMode = index },
                    enabled = cropMode != index,
                ) {
                    Text(text = label)
                }
            }
        }
        Text(
            text = media.uri.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        exportMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
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
            TextButton(onClick = ::selectTopCandidates, enabled = candidates.isNotEmpty()) {
                Text(text = "最高分 5 帧")
            }
            TextButton(onClick = { setAllSelected(false) }, enabled = selectedCandidates.isNotEmpty()) {
                Text(text = "清空")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "动态时长", style = MaterialTheme.typography.bodySmall)
            listOf(3_000L, 5_000L, 10_000L).forEach { durationMillis ->
                TextButton(
                    onClick = { clipDurationMillis = durationMillis },
                    enabled = clipDurationMillis != durationMillis,
                ) {
                    Text(text = formatDurationSeconds(durationMillis))
                }
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sortedCandidates, key = AnalysisCandidateEntity::id) { candidate ->
                    CandidateCard(
                        media = media,
                        candidate = candidate,
                        onClick = {
                            scope.launch {
                                database.analysisCandidateDao().setSelected(
                                    media.id,
                                    candidate.id,
                                    !candidate.isSelected,
                                )
                            }
                        },
                        onOpenDetail = { onOpenDetail(candidate) },
                    )
                }
            }
        }
        if (selectedCandidates.isNotEmpty()) {
            BatchExportBar(
                selectedCount = selectedCandidates.size,
                clipDurationMillis = clipDurationMillis,
                enabled = !isExporting,
                onExportJpeg = {
                    scope.launch {
                        isExporting = true
                        exportMessage = exportSelected(selectedCandidates) { candidate ->
                            exporter.exportCandidate(media, candidate, cropRect = cropRectForMode(cropMode))
                        }
                        isExporting = false
                    }
                },
                onExportGif = {
                    scope.launch {
                        isExporting = true
                        exportMessage = exportSelected(selectedCandidates) { candidate ->
                            gifExporter.exportCandidate(media, candidate, clipDurationMillis)
                        }
                        isExporting = false
                    }
                },
                onExportMp4 = {
                    scope.launch {
                        isExporting = true
                        exportMessage = exportSelected(selectedCandidates) { candidate ->
                            clipExporter.exportCandidate(media, candidate, clipDurationMillis)
                        }
                        isExporting = false
                    }
                },
                onExportMotionPhoto = {
                    scope.launch {
                        isExporting = true
                        exportMessage = exportMotionPhotoSelected(
                            media = media,
                            candidates = selectedCandidates,
                            exporter = motionPhotoExporter,
                            clipDurationMillis = clipDurationMillis,
                            cropRect = cropRectForMode(cropMode),
                        )
                        isExporting = false
                    }
                },
            )
        }
    }
}

private fun cropRectForMode(mode: Int): CropRect? = when (mode) {
    1 -> CropRect.fromCenter(1f)
    2 -> CropRect.fromCenter(16f / 9f)
    else -> null
}

@Composable
private fun CandidateCard(
    media: MediaAssetEntity,
    candidate: AnalysisCandidateEntity,
    onClick: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box {
            CandidatePreview(
                uri = media.uri,
                timestampMillis = candidate.timestampMillis,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
            )
            if (candidate.isSelected) {
                Text(
                    text = "✓ 已选",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatCandidateTimestamp(candidate.timestampMillis))
            Text(text = "${candidate.score} 分")
            TextButton(onClick = onOpenDetail) { Text(text = "详情") }
        }
    }
}

@Composable
private fun BatchExportBar(
    selectedCount: Int,
    clipDurationMillis: Long,
    enabled: Boolean,
    onExportJpeg: () -> Unit,
    onExportGif: () -> Unit,
    onExportMp4: () -> Unit,
    onExportMotionPhoto: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "已选择 $selectedCount 帧", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onExportJpeg, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(text = "导出图片")
            }
            Button(onClick = onExportGif, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(text = "GIF")
            }
            Button(onClick = onExportMp4, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(text = "${formatDurationSeconds(clipDurationMillis)} MP4")
            }
        }
        TextButton(onClick = onExportMotionPhoto, enabled = enabled) {
            Text(text = "动态照片（静态图 + MP4 兜底）")
        }
    }
}

private suspend fun exportMotionPhotoSelected(
    media: MediaAssetEntity,
    candidates: List<AnalysisCandidateEntity>,
    exporter: MotionPhotoExporter,
    clipDurationMillis: Long,
    cropRect: CropRect?,
): String {
    var completedCount = 0
    return try {
        candidates.forEach { candidate ->
            exporter.exportCandidate(media, candidate, clipDurationMillis, cropRect)
            completedCount++
        }
        "已导出 $completedCount 组动态照片兜底文件"
    } catch (error: Throwable) {
        "已导出 $completedCount/${candidates.size} 组：${error.message ?: "部分失败"}"
    }
}

private suspend fun exportSelected(
    candidates: List<AnalysisCandidateEntity>,
    export: suspend (AnalysisCandidateEntity) -> Uri,
): String {
    var completedCount = 0
    return try {
        candidates.forEach {
            export(it)
            completedCount++
        }
        "已导出 $completedCount 帧"
    } catch (error: Throwable) {
        "已导出 $completedCount/${candidates.size} 帧：${error.message ?: "部分失败"}"
    }
}

internal fun topCandidateIds(
    candidates: List<AnalysisCandidateEntity>,
    count: Int,
): List<Long> = candidates.sortedWith(
    compareByDescending<AnalysisCandidateEntity> { it.score }.thenBy { it.timestampMillis },
).take(count.coerceAtLeast(0)).map { it.id }

internal fun selectedCandidateIds(candidates: List<AnalysisCandidateEntity>): List<Long> =
    candidates.filter { it.isSelected }.map { it.id }

internal fun sortCandidates(
    candidates: List<AnalysisCandidateEntity>,
    sortByScore: Boolean,
): List<AnalysisCandidateEntity> = if (sortByScore) {
    candidates.sortedWith(compareByDescending<AnalysisCandidateEntity> { it.score }.thenBy { it.timestampMillis })
} else {
    candidates.sortedBy { it.timestampMillis }
}

@Composable
private fun CandidatePreview(
    uri: String,
    timestampMillis: Long,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, timestampMillis) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getFrameAtTime(
                    timestampMillis.coerceAtLeast(0L) * 1_000L,
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
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "${formatCandidateTimestamp(timestampMillis)} 候选帧",
            contentScale = ContentScale.Crop,
            modifier = modifier,
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

internal fun formatDurationSeconds(durationMillis: Long): String =
    "${durationMillis.coerceAtLeast(0L) / 1_000L} 秒"
