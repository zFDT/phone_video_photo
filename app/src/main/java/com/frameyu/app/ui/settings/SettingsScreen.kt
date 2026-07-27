package com.frameyu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frameyu.app.settings.SettingsSnapshot
import kotlin.math.roundToInt

/** 可复用的设置内容组件；状态由调用方持有，组件只通过回调报告修改。 */
@Composable
fun SettingsScreen(
    settings: SettingsSnapshot,
    onSettingsChanged: (SettingsSnapshot) -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(text = "设置", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.padding(horizontal = 24.dp))
        }
        CacheLimitSetting(
            cacheLimitGb = settings.cacheLimitGb,
            onCacheLimitChanged = { cacheLimitGb ->
                onSettingsChanged(settings.copy(cacheLimitGb = cacheLimitGb))
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
        SettingsSwitchRow(
            title = "演唱会模式",
            description = "针对长演唱会视频使用更适合的分析策略",
            checked = settings.concertModeEnabled,
            onCheckedChange = { enabled ->
                onSettingsChanged(settings.copy(concertModeEnabled = enabled))
            },
        )
        SettingsSwitchRow(
            title = "复制位置元数据",
            description = "导出媒体时复制原文件中的 GPS 信息",
            checked = settings.copyLocationMetadata,
            onCheckedChange = { enabled ->
                onSettingsChanged(settings.copy(copyLocationMetadata = enabled))
            },
        )
        SettingsSwitchRow(
            title = "后台分析",
            description = "允许应用在后台继续执行媒体分析任务",
            checked = settings.backgroundAnalysisEnabled,
            onCheckedChange = { enabled ->
                onSettingsChanged(settings.copy(backgroundAnalysisEnabled = enabled))
            },
        )
    }
}

@Composable
private fun CacheLimitSetting(
    cacheLimitGb: Int,
    onCacheLimitChanged: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "分析缓存上限：${cacheLimitGb}GB")
        Text(
            text = "可设置 50–200GB",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = cacheLimitGb.toFloat(),
            onValueChange = { value -> onCacheLimitChanged(value.roundToInt()) },
            valueRange = SettingsSnapshot.MIN_CACHE_LIMIT_GB.toFloat()..
                SettingsSnapshot.MAX_CACHE_LIMIT_GB.toFloat(),
            steps = SettingsSnapshot.MAX_CACHE_LIMIT_GB - SettingsSnapshot.MIN_CACHE_LIMIT_GB - 1,
        )
    }
}

/** 可复用的带说明开关行。 */
@Composable
fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
