package com.frameyu.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frameyu.app.data.media.MediaScanState

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    hasMediaReadPermission: Boolean = true,
    onRequestMediaReadPermission: () -> Unit = {},
    onSelectMedia: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val isScanning = uiState.scanState is MediaScanState.Scanning
    val canScan = hasMediaReadPermission && !isScanning

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "媒体库",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "已索引媒体数量：${uiState.mediaCount}")
        Text(text = "提示：该数量是本地索引记录，不等同于系统相册当前数量。")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "扫描进度：${scanProgressText(uiState.scanState)}")
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = scanStateText(uiState.scanState))

        if (!hasMediaReadPermission) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "尚未获得照片和视频访问权限，无法扫描媒体库。")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestMediaReadPermission) {
                Text(text = "打开系统权限入口")
            }
        }

        if (isScanning) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
        }

        Spacer(modifier = Modifier.height(24.dp))
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
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onSelectMedia) {
            Text(text = "选择媒体")
        }
    }
}

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
