package com.frameyu.app.vision

import android.graphics.Bitmap
import android.media.Image
import com.google.android.gms.tasks.Task
import java.io.Closeable

/** 人脸分析输入，保留 Android 原生图像类型并将旋转角度显式传入。 */
sealed interface FaceInput {
    val rotationDegrees: Int

    data class BitmapInput(
        val bitmap: Bitmap,
        override val rotationDegrees: Int = 0,
    ) : FaceInput {
        init { validateRotationDegrees(rotationDegrees) }
    }

    data class MediaImageInput(
        val image: Image,
        override val rotationDegrees: Int = 0,
    ) : FaceInput {
        /** image 必须保持打开，直到返回的 ML Kit Task 完成；调用方负责关闭 image。 */
        init { validateRotationDegrees(rotationDegrees) }
    }
}

internal fun validateRotationDegrees(rotationDegrees: Int) {
    require(rotationDegrees in setOf(0, 90, 180, 270)) { "旋转角度必须是 0、90、180 或 270 度" }
}

/** 人脸框，坐标使用输入图像像素坐标。 */
data class FaceBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left) { "人脸框宽度必须大于零" }
        require(bottom > top) { "人脸框高度必须大于零" }
    }
}

/** 可被评分器和持久化层消费的轻量人脸结果。概率为空表示模型未返回该分类结果。 */
data class FaceObservation(
    val bounds: FaceBounds,
    val headEulerAngleX: Float,
    val headEulerAngleY: Float,
    val headEulerAngleZ: Float,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val smilingProbability: Float?,
    val trackingId: Int?,
) {
    init {
        require(headEulerAngleX.isFinite()) { "头部 X 角度必须是有限数" }
        require(headEulerAngleY.isFinite()) { "头部 Y 角度必须是有限数" }
        require(headEulerAngleZ.isFinite()) { "头部 Z 角度必须是有限数" }
        listOf(
            leftEyeOpenProbability,
            rightEyeOpenProbability,
            smilingProbability,
        ).forEach { probability ->
            require(probability == null || probability in 0f..1f) {
                "人脸分类概率必须在 0 到 1 之间"
            }
        }
    }
}

/** 纯数据的人脸检测结果，用于隔离 ML Kit 映射并支持 JVM 测试。 */
data class RawFaceDetection(
    val bounds: FaceBounds,
    val headEulerAngleX: Float,
    val headEulerAngleY: Float,
    val headEulerAngleZ: Float,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val smilingProbability: Float?,
    val trackingId: Int?,
)

object FaceObservationMapper {
    fun map(raw: RawFaceDetection): FaceObservation = FaceObservation(
        bounds = raw.bounds,
        headEulerAngleX = raw.headEulerAngleX,
        headEulerAngleY = raw.headEulerAngleY,
        headEulerAngleZ = raw.headEulerAngleZ,
        leftEyeOpenProbability = raw.leftEyeOpenProbability,
        rightEyeOpenProbability = raw.rightEyeOpenProbability,
        smilingProbability = raw.smilingProbability,
        trackingId = raw.trackingId,
    )
}

/** 本地人脸分析器；调用方必须在生命周期结束时 close。 */
interface FaceAnalyzer : Closeable {
    fun analyze(input: FaceInput): Task<List<FaceObservation>>
}
