package com.frameyu.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceObservationMapperTest {
    @Test(expected = IllegalArgumentException::class)
    fun `旋转角度只允许标准媒体旋转值`() {
        validateRotationDegrees(45)
    }

    @Test
    fun `映射人脸框角度眼睛和微笑概率`() {
        val raw = RawFaceDetection(
            bounds = FaceBounds(left = 10, top = 20, right = 110, bottom = 180),
            headEulerAngleX = -4.5f,
            headEulerAngleY = 8.0f,
            headEulerAngleZ = 1.25f,
            leftEyeOpenProbability = 0.92f,
            rightEyeOpenProbability = 0.12f,
            smilingProbability = 0.78f,
            trackingId = 7,
        )

        val result = FaceObservationMapper.map(raw)

        assertEquals(raw.bounds, result.bounds)
        assertEquals(-4.5f, result.headEulerAngleX)
        assertEquals(8.0f, result.headEulerAngleY)
        assertEquals(1.25f, result.headEulerAngleZ)
        assertEquals(0.92f, result.leftEyeOpenProbability)
        assertEquals(0.12f, result.rightEyeOpenProbability)
        assertEquals(0.78f, result.smilingProbability)
        assertEquals(7, result.trackingId)
    }

    @Test
    fun `模型未提供分类概率时保留为空`() {
        val result = FaceObservationMapper.map(
            RawFaceDetection(
                bounds = FaceBounds(0, 0, 20, 20),
                headEulerAngleX = 0f,
                headEulerAngleY = 0f,
                headEulerAngleZ = 0f,
                leftEyeOpenProbability = null,
                rightEyeOpenProbability = null,
                smilingProbability = null,
                trackingId = null,
            ),
        )

        assertNull(result.leftEyeOpenProbability)
        assertNull(result.rightEyeOpenProbability)
        assertNull(result.smilingProbability)
        assertNull(result.trackingId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `概率超出范围时拒绝`() {
        FaceObservationMapper.map(
            RawFaceDetection(
                bounds = FaceBounds(0, 0, 20, 20),
                headEulerAngleX = 0f,
                headEulerAngleY = 0f,
                headEulerAngleZ = 0f,
                leftEyeOpenProbability = 1.1f,
                rightEyeOpenProbability = null,
                smilingProbability = null,
                trackingId = null,
            ),
        )
    }
}
