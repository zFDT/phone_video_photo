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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CandidateDetailScreen(
    media: MediaAssetEntity,
    candidate: AnalysisCandidateEntity,
    onBack: () -> Unit,
) {
    var currentMillis by rememberSaveable { mutableStateOf(candidate.timestampMillis) }
    val normalized = CandidateTimeline.normalizeTime(currentMillis, media.durationMillis)
    val frameTimestamps = CandidateTimeline.frameTimestamps(
        durationMillis = media.durationMillis,
        currentMillis = currentMillis,
        frameRate = 30.0,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text(text = "返回") }
            Text(text = "逐帧定位", style = MaterialTheme.typography.titleLarge)
            Text(text = "评分 ${candidate.score}")
        }
        DetailFramePreview(
            uri = media.uri,
            timestampMillis = currentMillis,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
        )
        Text(
            text = "当前时间 ${formatCandidateTimestamp(currentMillis)} / ${formatCandidateTimestamp(media.durationMillis)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = normalized,
            onValueChange = { value ->
                currentMillis = CandidateTimeline.timeFromNormalized(value, media.durationMillis)
            },
            valueRange = 0f..1f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { currentMillis = frameTimestamps.previousMillis },
            ) { Text(text = "上一帧") }
            Button(
                modifier = Modifier.weight(1f),
                onClick = { currentMillis = frameTimestamps.nextMillis },
            ) { Text(text = "下一帧") }
        }
        Text(
            text = "候选评分明细：清晰度 ${candidate.sharpnessScore} · 曝光 ${candidate.compositionScore} · 场景 ${candidate.sceneScore}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DetailFramePreview(uri: String, timestampMillis: Long, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, timestampMillis) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getFrameAtTime(
                    timestampMillis.coerceAtLeast(0L) * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
            } finally {
                retriever.release()
            }
        }
        awaitDispose { value?.recycle() }
    }
    if (bitmap == null) {
        Text(text = "正在读取画面…", modifier = modifier.padding(20.dp))
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "${formatCandidateTimestamp(timestampMillis)} 画面",
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}
