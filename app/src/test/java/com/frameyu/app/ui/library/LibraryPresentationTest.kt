package com.frameyu.app.ui.library

import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresentationTest {
    @Test
    fun `视频时长格式化为分秒`() {
        assertEquals("01:05", formatDurationMillis(65_000L))
        assertEquals("00:00", formatDurationMillis(0L))
    }

    @Test
    fun `分析任务状态显示中文文案`() {
        assertEquals("等待分析", analysisTaskStatusText("QUEUED", 0))
        assertEquals("分析中 42%", analysisTaskStatusText("RUNNING", 42))
        assertEquals("分析完成", analysisTaskStatusText("COMPLETED", 100))
        assertEquals("分析失败", analysisTaskStatusText("FAILED", 20))
    }

    @Test
    fun `分析任务摘要统计排队运行和完成`() {
        val tasks = listOf("QUEUED", "RUNNING", "COMPLETED", "FAILED")
            .mapIndexed { index, status ->
                AnalysisTaskEntity(
                    id = index.toLong(),
                    mediaAssetId = index.toLong(),
                    status = status,
                    progress = 0,
                    currentTimestampMillis = 0L,
                    errorMessage = null,
                    updatedAtMillis = 0L,
                )
            }

        assertEquals(AnalysisTaskSummary(4, 1, 1, 1), analysisTaskSummary(tasks))
    }

    @Test
    fun `深度分析只在媒体库存在图片时启用`() {
        val videos = listOf(video(1L), video(2L))

        assertEquals(
            0,
            analyzableImageCount(LibraryUiState(mediaCount = 2, videos = videos)),
        )
        assertEquals(
            1,
            analyzableImageCount(LibraryUiState(mediaCount = 3, videos = videos)),
        )
    }

    private fun video(id: Long) = MediaAssetEntity(
        id = id,
        uri = "content://video/$id",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = 1_000L,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )
}
