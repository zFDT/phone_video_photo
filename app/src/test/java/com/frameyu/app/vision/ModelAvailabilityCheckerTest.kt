package com.frameyu.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAvailabilityCheckerTest {
    @Test
    fun `探针结果映射为内置可用`() {
        val checker = ModelAvailabilityChecker {
            VisionModelProbeResult.BuiltInAvailable
        }

        assertEquals(
            VisionModelStatus.BuiltInAvailable,
            checker.check(VisionModel.FaceDetection),
        )
    }

    @Test
    fun `探针结果映射为需要下载`() {
        val checker = ModelAvailabilityChecker {
            VisionModelProbeResult.RequiresDownload
        }

        assertEquals(
            VisionModelStatus.RequiresDownload,
            checker.check(VisionModel.FaceDetection),
        )
    }

    @Test
    fun `探针失败映射为加载失败并保留原因`() {
        val checker = ModelAvailabilityChecker {
            VisionModelProbeResult.LoadFailed("模型文件损坏")
        }

        val status = checker.check(VisionModel.FaceDetection)

        assertTrue(status is VisionModelStatus.LoadFailed)
        assertEquals("模型文件损坏", (status as VisionModelStatus.LoadFailed).reason)
    }

    @Test
    fun `探针返回空失败原因时仍返回明确失败状态`() {
        val status = ModelAvailabilityChecker {
            VisionModelProbeResult.LoadFailed("")
        }.check(VisionModel.FaceDetection)

        assertTrue(status is VisionModelStatus.LoadFailed)
        assertTrue((status as VisionModelStatus.LoadFailed).reason.isNotBlank())
    }

    @Test
    fun `探针抛出异常时不会静默返回可用`() {
        val checker = ModelAvailabilityChecker {
            error("初始化失败")
        }

        val status = checker.check(VisionModel.FaceDetection)

        assertTrue(status is VisionModelStatus.LoadFailed)
    }

    @Test
    fun `异常没有消息时仍返回非空失败原因`() {
        val status = ModelAvailabilityChecker {
            throw RuntimeException("")
        }.check(VisionModel.FaceDetection)

        assertTrue(status is VisionModelStatus.LoadFailed)
        assertTrue((status as VisionModelStatus.LoadFailed).reason.isNotBlank())
    }
}
