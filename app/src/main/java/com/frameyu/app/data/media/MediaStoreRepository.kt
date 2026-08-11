package com.frameyu.app.data.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import com.frameyu.app.data.db.MediaAssetEntity

/** 媒体索引仓库，只负责扫描及扫描状态，不负责分析或删除媒体。 */
class MediaStoreRepository(
    private val scanner: MediaStoreScanner,
) {
    private val _scanState = MutableStateFlow<MediaScanState>(MediaScanState.Idle)

    val scanState: StateFlow<MediaScanState> = _scanState.asStateFlow()

    suspend fun mediaCount(): Int = scanner.mediaCount()

    fun observeMediaCount(): Flow<Int> = scanner.observeMediaCount()

    fun observeVideos(): Flow<List<MediaAssetEntity>> = scanner.observeVideos()

    /** 执行全量扫描，并将扫描状态暴露给上层。 */
    suspend fun scanAll(scanBoundaryMillis: Long = System.currentTimeMillis()): Int =
        scanIncremental(sinceLastModifiedMillis = null, scanBoundaryMillis = scanBoundaryMillis)

    /** 扫描指定修改时间之后的媒体；传入 null 时执行首次全量扫描。 */
    suspend fun scanIncremental(
        sinceLastModifiedMillis: Long? = null,
        scanBoundaryMillis: Long = System.currentTimeMillis(),
    ): Int {
        _scanState.value = MediaScanState.Scanning(sinceLastModifiedMillis, 0, 0)
        return try {
            val indexedCount = scanner.scan(sinceLastModifiedMillis, scanBoundaryMillis) { processed, total ->
                _scanState.value = MediaScanState.Scanning(sinceLastModifiedMillis, processed, total)
            }
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
        val processed: Int,
        val total: Int,
    ) : MediaScanState

    data class Completed(
        val indexedCount: Int,
    ) : MediaScanState

    data class Failed(
        val message: String,
    ) : MediaScanState
}
