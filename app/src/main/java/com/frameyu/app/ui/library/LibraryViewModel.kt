package com.frameyu.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameyu.app.data.media.MediaScanState
import com.frameyu.app.data.media.MediaStoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val scanState: MediaScanState = MediaScanState.Idle,
    val mediaCount: Int = 0,
    val indexedCount: Int = 0,
)

/** 媒体库页面状态协调器，不直接接触 ContentResolver。 */
class LibraryViewModel(
    private val repository: MediaStoreRepository,
) : ViewModel() {
    private val _mediaCount = MutableStateFlow(0)
    private val _indexedCount = MutableStateFlow(0)
    private val scanJob = MutableStateFlow<Job?>(null)
    private var lastScanModifiedMillis: Long? = null

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.scanState,
        _mediaCount,
        _indexedCount,
    ) { scanState, mediaCount, indexedCount ->
        LibraryUiState(
            scanState = scanState,
            mediaCount = mediaCount,
            indexedCount = indexedCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

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
