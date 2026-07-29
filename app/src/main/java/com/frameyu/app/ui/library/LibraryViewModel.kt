package com.frameyu.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameyu.app.data.db.AnalysisTaskDao
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.media.MediaScanState
import com.frameyu.app.data.media.MediaStoreRepository
import com.frameyu.app.sampling.VideoAnalysisWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val scanState: MediaScanState = MediaScanState.Idle,
    val mediaCount: Int = 0,
    val indexedCount: Int = 0,
    val videos: List<MediaAssetEntity> = emptyList(),
    val analysisTasks: Map<Long, AnalysisTaskEntity> = emptyMap(),
)

/** 媒体库页面状态协调器，不直接接触 ContentResolver。 */
class LibraryViewModel(
    private val repository: MediaStoreRepository,
    private val analysisTaskDao: AnalysisTaskDao,
    private val workScheduler: VideoAnalysisWorkScheduler,
) : ViewModel() {
    private val _mediaCount = MutableStateFlow(0)
    private val _indexedCount = MutableStateFlow(0)
    private val scanJob = MutableStateFlow<Job?>(null)
    private var lastScanModifiedMillis: Long? = null
    private val videos = repository.observeVideos()
    private val analysisTasks = analysisTaskDao.observeTasks().map { tasks ->
        tasks.associateBy { it.mediaAssetId }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.scanState,
        _mediaCount,
        _indexedCount,
        videos,
        analysisTasks,
    ) { scanState, mediaCount, indexedCount, videoAssets, tasks ->
        LibraryUiState(
            scanState = scanState,
            mediaCount = mediaCount,
            indexedCount = indexedCount,
            videos = videoAssets,
            analysisTasks = tasks,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    fun startVideoAnalysis(media: MediaAssetEntity) {
        viewModelScope.launch {
            queueVideoAnalysis(media)
        }
    }

    /** 将媒体库中尚未完成的视频加入同一条串行分析链。 */
    fun startBatchVideoAnalysis() {
        viewModelScope.launch {
            videoIdsForBatchAnalysis(uiState.value.videos, uiState.value.analysisTasks)
                .forEach { queueVideoAnalysis(it) }
        }
    }

    /** 协作取消当前串行分析链中的全部活动任务。 */
    fun cancelBatchVideoAnalysis() {
        viewModelScope.launch {
            workScheduler.cancelAll()
        }
    }

    private suspend fun queueVideoAnalysis(media: MediaAssetEntity) {
        workScheduler.enqueue(media.id)
    }

    /** 触发一次全量扫描。 */
    fun scanAll() {
        val scanBoundaryMillis = System.currentTimeMillis()
        launchScan(scanBoundaryMillis) { repository.scanAll(scanBoundaryMillis) }
    }

    /** 触发从上一次扫描时间开始的增量扫描；首次调用会执行全量读取。 */
    fun scanIncremental() {
        val sinceLastModifiedMillis = lastScanModifiedMillis
        val scanBoundaryMillis = System.currentTimeMillis()
        launchScan(scanBoundaryMillis) { repository.scanIncremental(sinceLastModifiedMillis, scanBoundaryMillis) }
    }

    private fun launchScan(scanBoundaryMillis: Long, operation: suspend () -> Int) {
        if (scanJob.value?.isActive == true) return

        _indexedCount.value = 0
        scanJob.value = viewModelScope.launch {
            try {
                _indexedCount.value = operation()
                _mediaCount.value = repository.mediaCount()
                lastScanModifiedMillis = scanBoundaryMillis
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Repository 已将失败原因写入 scanState，这里避免异常退出 viewModelScope。
            } finally {
                scanJob.value = null
            }
        }
    }
}

internal fun videoIdsForBatchAnalysis(
    videos: List<MediaAssetEntity>,
    tasks: Map<Long, AnalysisTaskEntity>,
): List<MediaAssetEntity> = videos.filter { video ->
    tasks[video.id]?.status !in setOf("QUEUED", "RUNNING", "COMPLETED")
}
