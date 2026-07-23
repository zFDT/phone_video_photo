package com.frameyu.app.vision

/** 当前本地视觉模型的类型。 */
enum class VisionModel {
    FaceDetection,
    ImageLabeling,
    ImageEmbedding,
}

/** 模型可用性必须显式区分，避免把模型未就绪误判为空结果。 */
sealed interface VisionModelStatus {
    /** 模型随应用或当前 ML Kit 依赖可直接使用。 */
    data object BuiltInAvailable : VisionModelStatus

    /** 模型需要由设备上的模型服务先下载。 */
    data object RequiresDownload : VisionModelStatus

    /** 模型初始化或加载失败，保留可诊断原因。 */
    data class LoadFailed(
        val reason: String,
        val cause: Throwable? = null,
    ) : VisionModelStatus {
        init {
            require(reason.isNotBlank()) { "模型加载失败原因不能为空" }
        }
    }
}
