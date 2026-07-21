package com.frameyu.app.data.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 媒体索引仓库，只负责扫描及扫描状态，不负责分析或删除媒体。 */
class MediaStoreRepository(
    private val scanner: MediaStoreScanner,
) {
    private val _scanState = MutableStateFlow<MediaScanState>(MediaScanState.Idle)

    val scanState: StateFlow<MediaScanState> = _scanState.asStateFlow()

    /** 扫描指定修改时间之后的媒体；传入 null 时执行首次全量扫描。 */
    suspend fun scanIncremental(sinceLastModifiedMillis: Long? = null): Int {
        _scanState.value = MediaScanState.Scanning(sinceLastModifiedMillis)
        return try {
            val indexedCount = scanner.scan(sinceLastModifiedMillis)
            _scanState.value = MediaScanState.Completed(indexedCount)
            indexedCount
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val message = throwable.message?.takeIf { it.isNotBlank() } ?: "媒体扫描失败"
            _scanState.value = MediaScanState.Failed(message)
            throw throwable
        }
    }
}

/** 媒体扫描状态，供 ViewModel 和 Compose UI 观察。 */
sealed interface MediaScanState {
    data object Idle : MediaScanState

    data class Scanning(
        val sinceLastModifiedMillis: Long?,
    ) : MediaScanState

    data class Completed(
        val indexedCount: Int,
    ) : MediaScanState

    data class Failed(
        val message: String,
    ) : MediaScanState
}
