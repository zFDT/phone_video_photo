package com.frameyu.app.vision

/** ML Kit 图像标签的稳定数据模型，不向上层暴露 ML Kit 类型。 */
data class LabelObservation(
    val label: String,
    val confidence: Float,
    val index: Int,
) {
    init {
        require(label.isNotBlank()) { "图像标签不能为空" }
        require(confidence.isFinite() && confidence in 0f..1f) {
            "图像标签置信度必须在 0 到 1 之间"
        }
        require(index >= 0) { "图像标签索引不能为负数" }
    }
}

data class RawLabelObservation(
    val label: String,
    val confidence: Float,
    val index: Int,
)

object LabelObservationMapper {
    fun map(raw: RawLabelObservation): LabelObservation = LabelObservation(
        label = raw.label,
        confidence = raw.confidence,
        index = raw.index,
    )
}

/** 图像向量的稳定数据模型，内部复制数组以避免调用方修改分析结果。 */
class EmbeddingObservation(values: FloatArray) {
    private val data: FloatArray = values.copyOf()
    val values: FloatArray get() = data.copyOf()

    init {
        require(values.isNotEmpty()) { "图像向量不能为空" }
        require(data.all(Float::isFinite)) { "图像向量必须只包含有限数值" }
    }

    override fun equals(other: Any?): Boolean =
        other is EmbeddingObservation && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "EmbeddingObservation(dimension=${values.size})"
}

object EmbeddingObservationMapper {
    fun map(values: FloatArray): EmbeddingObservation = EmbeddingObservation(values)
}
