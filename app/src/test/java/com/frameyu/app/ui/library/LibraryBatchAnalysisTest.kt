package com.frameyu.app.ui.library

import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBatchAnalysisTest {
    @Test
    fun `批量分析跳过排队运行和已完成视频`() {
        val videos = listOf(video(1L), video(2L), video(3L), video(4L))
        val tasks = mapOf(
            1L to task(1L, "QUEUED"),
            2L to task(2L, "RUNNING"),
            3L to task(3L, "COMPLETED"),
        )

        assertEquals(listOf(4L), videoIdsForBatchAnalysis(videos, tasks).map { it.id })
    }

    private fun video(id: Long) = MediaAssetEntity(
        id = id,
        uri = "content://video/$id",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = 10_000L,
        width = 1_920,
        height = 1_080,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )

    private fun task(id: Long, status: String) = AnalysisTaskEntity(
        id = id,
        mediaAssetId = id,
        status = status,
        progress = 0,
        currentTimestampMillis = 0L,
        errorMessage = null,
        updatedAtMillis = 0L,
    )
}
