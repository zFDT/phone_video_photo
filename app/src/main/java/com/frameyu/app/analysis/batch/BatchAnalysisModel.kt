package com.frameyu.app.analysis.batch

import java.security.MessageDigest
import java.util.Collections

enum class AnalysisStage {
    METADATA,
    CONTENT_HASH,
    QUALITY,
    CLASSIFICATION,
    EMBEDDING,
    CONDITIONAL_DETECTION,
    RECOMMENDATION,
}

enum class WorkItemStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PAUSED,
    RETRYABLE_FAILED,
    PERMANENT_FAILED,
    CANCELLED,
}

enum class WorkItemEvent {
    START,
    COMPLETE,
    PAUSE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    CANCEL,
    RESUME,
    RETRY,
}

object AnalysisStageDependencies {
    private val dependencies = mapOf(
        AnalysisStage.METADATA to readOnlySet(),
        AnalysisStage.CONTENT_HASH to readOnlySet(AnalysisStage.METADATA),
        AnalysisStage.QUALITY to readOnlySet(AnalysisStage.METADATA),
        AnalysisStage.CLASSIFICATION to readOnlySet(AnalysisStage.METADATA),
        AnalysisStage.EMBEDDING to readOnlySet(AnalysisStage.METADATA),
        AnalysisStage.CONDITIONAL_DETECTION to readOnlySet(AnalysisStage.CLASSIFICATION),
        AnalysisStage.RECOMMENDATION to readOnlySet(
            AnalysisStage.CONTENT_HASH,
            AnalysisStage.QUALITY,
            AnalysisStage.CLASSIFICATION,
            AnalysisStage.EMBEDDING,
            AnalysisStage.CONDITIONAL_DETECTION,
        ),
    )

    fun requiredFor(stage: AnalysisStage): Set<AnalysisStage> = dependencies.getValue(stage)

    private fun readOnlySet(vararg stages: AnalysisStage): Set<AnalysisStage> =
        Collections.unmodifiableSet(stages.toSet())
}

object AnalyzerVersion {
    private const val NO_MODEL = "NO_MODEL"
    private const val CONDITIONAL_CLASSIFICATION_DEPENDENCY_POLICY =
        "conditional-detection-classification-dependency-v1"
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private val modelSha256Pattern = Regex("[0-9A-Fa-f]{64}")

    fun create(
        stage: AnalysisStage,
        modelSha256: String?,
        policyVersion: String,
    ): String {
        val normalizedModelSha256 = modelSha256?.let { sha256 ->
            require(modelSha256Pattern.matches(sha256)) {
                "模型 SHA-256 必须为 64 位十六进制字符串"
            }
            sha256.uppercase()
        } ?: NO_MODEL
        val normalizedPolicyVersion = policyVersion.trim()
        require(normalizedPolicyVersion.isNotEmpty()) {
            "策略版本去除首尾空白后不能为空"
        }
        val canonical = buildString {
            append("stage=")
            append(stage.name)
            append('\n')
            append("modelSha256=")
            append(normalizedModelSha256)
            append('\n')
            append("policyVersion=")
            append(normalizedPolicyVersion)
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .toUppercaseHex()
    }

    fun createConditionalDetection(
        classificationModelSha256: String,
        detectionModelSha256: String,
        detectionPolicyVersion: String,
    ): String {
        val normalizedDetectionPolicyVersion = detectionPolicyVersion.trim()
        require(normalizedDetectionPolicyVersion.isNotEmpty()) {
            "检测策略版本去除首尾空白后不能为空"
        }
        val classificationAnalyzerVersion = create(
            stage = AnalysisStage.CLASSIFICATION,
            modelSha256 = classificationModelSha256,
            policyVersion = CONDITIONAL_CLASSIFICATION_DEPENDENCY_POLICY,
        )
        return create(
            stage = AnalysisStage.CONDITIONAL_DETECTION,
            modelSha256 = detectionModelSha256,
            policyVersion = buildString {
                append("classificationAnalyzerVersion=")
                append(classificationAnalyzerVersion)
                append('\n')
                append("detectionPolicyVersion=")
                append(normalizedDetectionPolicyVersion)
            },
        )
    }

    private fun ByteArray.toUppercaseHex(): String = buildString(size * 2) {
        this@toUppercaseHex.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}
