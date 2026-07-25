package com.frameyu.app.export

import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StillImageExporterTest {
    @Test
    fun `导出文件名包含源视频和候选时间点`() {
        val media = MediaAssetEntity(
            id = 1L,
            uri = "content://media/external/video/media/42",
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
        val candidate = AnalysisCandidateEntity(
            id = 2L,
            mediaAssetId = 1L,
            timestampMillis = 1_250L,
            score = 90,
            sharpnessScore = 90,
            subjectScore = 0,
            expressionScore = 0,
            compositionScore = 90,
            sceneScore = 0,
            thumbnailPath = null,
            isSelected = true,
        )

        assertEquals("42-帧-001250.jpg", StillImageExporter.buildStillImageName(media, candidate))
        assertEquals(
            "42-帧-001250.webp",
            StillImageExporter.buildStillImageName(media, candidate, StillImageFormat.WEBP),
        )
    }
}
