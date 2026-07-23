package com.frameyu.app.vision

import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/** 可替换的模型可用性探针，便于在 JVM 测试中验证状态映射。 */
fun interface VisionModelProbe {
    fun probe(model: VisionModel): VisionModelProbeResult
}

/** 模型探针的原始结果，不携带 UI 或任务层语义。 */
sealed interface VisionModelProbeResult {
    data object BuiltInAvailable : VisionModelProbeResult
    data object RequiresDownload : VisionModelProbeResult
    data class LoadFailed(val reason: String) : VisionModelProbeResult
}

/** 将模型探针结果统一映射为应用可识别的状态。 */
class ModelAvailabilityChecker(
    private val probe: VisionModelProbe,
) {
    fun check(model: VisionModel): VisionModelStatus = try {
        when (val result = probe.probe(model)) {
            VisionModelProbeResult.BuiltInAvailable -> VisionModelStatus.BuiltInAvailable
            VisionModelProbeResult.RequiresDownload -> VisionModelStatus.RequiresDownload
            is VisionModelProbeResult.LoadFailed -> VisionModelStatus.LoadFailed(
                reason = result.reason.ifBlank { "模型探针返回空失败原因" },
            )
        }
    } catch (error: Exception) {
        VisionModelStatus.LoadFailed(
            reason = error.message?.ifBlank { null } ?: "模型探针执行失败",
            cause = error,
        )
    }

    companion object {
        /**
         * 检查当前 ML Kit Face Detection 依赖是否能在本机初始化。
         * 当前工程使用 bundled face-detection 依赖，正常情况下应返回内置可用。
         */
        fun forMlKitFaceDetection(): ModelAvailabilityChecker {
            return ModelAvailabilityChecker { model ->
                if (model != VisionModel.FaceDetection) {
                    return@ModelAvailabilityChecker VisionModelProbeResult.LoadFailed(
                        "不支持的模型：$model",
                    )
                }

                try {
                    FaceDetection.getClient(
                        FaceDetectorOptions.Builder()
                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                            .build(),
                    ).close()
                    VisionModelProbeResult.BuiltInAvailable
                } catch (error: MlKitException) {
                    mapMlKitException(error)
                } catch (error: Exception) {
                    VisionModelProbeResult.LoadFailed(
                        error.message ?: "ML Kit 人脸模型初始化失败",
                    )
                }
            }
        }

        /** 将 ML Kit 初始化阶段的错误映射为模型状态；分析 Task 的错误也应复用此规则。 */
        fun mapMlKitException(error: MlKitException): VisionModelProbeResult =
            if (error.errorCode == MlKitException.UNAVAILABLE) {
                VisionModelProbeResult.RequiresDownload
            } else {
                VisionModelProbeResult.LoadFailed(
                    error.message?.ifBlank { null } ?: "ML Kit 人脸模型加载失败",
                )
            }
    }
}
