package com.frameyu.app.vision

import java.util.Locale

/** 仅在分类不确定、主体场景或高价值候选上启用成本更高的目标检测。 */
class ObjectDetectionPolicy {
    fun shouldDetect(labels: List<LabelScore>, highValue: Boolean): Boolean {
        if (highValue || labels.isEmpty()) return true

        val sortedLabels = labels.sortedByDescending(LabelScore::score)
        if (sortedLabels.first().score < MIN_CONFIDENCE) return true
        if (sortedLabels.size >= 2 &&
            isUncertainConfidenceGap(sortedLabels[0].score - sortedLabels[1].score)
        ) {
            return true
        }
        return sortedLabels.any { it.label.trim().lowercase(Locale.ROOT) in SUBJECT_LABELS }
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.45f
        val SUBJECT_LABELS = setOf(
            "person",
            "human",
            "performer",
            "singer",
            "musician",
            "dog",
            "cat",
            "pet",
            "vehicle",
        )
    }
}

internal fun isUncertainConfidenceGap(gap: Float): Boolean = gap < 0.10f
