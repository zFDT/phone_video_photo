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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.frameyu.app.album.AlbumCategory
import com.frameyu.app.album.AlbumClassifier
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.organize.DuplicateDetector
import com.frameyu.app.organize.DuplicateGroup
import com.frameyu.app.organize.OrganizerRepository
import kotlinx.coroutines.launch

/** 相册整理页：只展示候选，删除必须经过用户明确确认。 */
@Composable
fun OrganizerScreen(
    database: FrameYuDatabase,
    repository: OrganizerRepository,
    onBack: () -> Unit,
) {
    val assets by database.mediaAssetDao().observeAll().collectAsState(initial = emptyList())
    val groups = remember(assets) { repository.duplicateCandidates(assets) }
    val classifier = remember { AlbumClassifier() }
    val categories = remember(assets) {
        assets.groupingBy { classifier.classify(it).first() }.eachCount()
    }
    val scope = rememberCoroutineScope()
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
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
            Text(text = "相册整理", style = MaterialTheme.typography.headlineSmall)
            Text(text = "${assets.size} 项", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = "截图 ${categories[AlbumCategory.SCREENSHOT] ?: 0} · 录屏 ${categories[AlbumCategory.SCREEN_RECORDING] ?: 0} · 重复组 ${groups.size}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "只会处理你勾选并确认的重复项，建议保留项不会被删除。",
            style = MaterialTheme.typography.bodySmall,
        )
        message?.let { Text(text = it, color = MaterialTheme.colorScheme.primary) }
        Spacer(modifier = Modifier.height(12.dp))
        if (groups.isEmpty()) {
            Text(text = "暂未发现可整理的重复媒体。", modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(groups, key = DuplicateGroup::duplicateKey) { group ->
                    val selected = group.duplicateKey in selectedKeys
                    DuplicateGroupRow(
                        group = group,
                        selected = selected,
                        onToggle = {
                            selectedKeys = if (selected) {
                                selectedKeys - group.duplicateKey
                            } else {
                                selectedKeys + group.duplicateKey
                            }
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
                onClick = { selectedKeys = groups.map { it.duplicateKey }.toSet() },
                enabled = groups.isNotEmpty(),
            ) { Text(text = "全选重复组") }
            Button(
                modifier = Modifier.weight(1f),
                onClick = { showDeleteConfirmation = true },
                enabled = selectedKeys.isNotEmpty(),
            ) { Text(text = "确认整理") }
        }
    }

    if (showDeleteConfirmation) {
        val selectedGroups = groups.filter { it.duplicateKey in selectedKeys }
        val deleteUris = selectedGroups.flatMap { group ->
            group.assets.filter { it.id != group.suggestedKeep.id }.map { Uri.parse(it.uri) }
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(text = "确认整理重复媒体？") },
            text = { Text(text = "将把 ${deleteUris.size} 项移入系统最近删除；建议保留项不会处理。") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirmation = false
                    scope.launch {
                        repository.delete(deleteUris)
                        selectedKeys = emptySet()
                        message = "已提交 ${deleteUris.size} 项到系统回收区"
                    }
                }) { Text(text = "确认") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(text = "取消") }
            },
        )
    }
}

@Composable
private fun DuplicateGroupRow(
    group: DuplicateGroup,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "重复 ${group.assets.size} 项", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "保留：${displayName(group.suggestedKeep)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
            Text(
                text = group.assets.joinToString("、") { displayName(it) },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

private fun displayName(asset: MediaAssetEntity): String =
    asset.uri.substringAfterLast('/').ifBlank { "媒体 ${asset.id}" }
