package com.frameyu.app.analysis

/** 单帧分析器接口，后续可替换为 ML Kit 或 LiteRT 实现。 */
interface FrameAnalyzer {
    fun analyze(sample: FrameSample, context: FrameAnalysisContext = FrameAnalysisContext()): FrameScore
}

/** 单帧分析上下文，保存时间邻近的上一帧。 */
data class FrameAnalysisContext(
    val previousSample: FrameSample? = null,
)

/** 不依赖模型的规则分析器，批量结果顺序与输入顺序一致。 */
class RuleBasedFrameAnalyzer(
    private val qualityScorer: QualityScorer = QualityScorer(),
) : FrameAnalyzer {
    override fun analyze(sample: FrameSample, context: FrameAnalysisContext): FrameScore =
        qualityScorer.score(sample, context.previousSample)

    fun analyze(samples: List<FrameSample>): List<FrameScore> {
        val scores = ArrayList<FrameScore>(samples.size)
        var context = FrameAnalysisContext()
        samples.forEach { sample ->
            scores += analyze(sample, context)
            context = FrameAnalysisContext(sample)
        }
        return scores
    }
}
