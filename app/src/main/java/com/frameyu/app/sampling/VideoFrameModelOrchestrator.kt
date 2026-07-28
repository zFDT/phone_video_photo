package com.frameyu.app.sampling

import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.vision.FrameModelResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CandidateModelStatus {
    PENDING,
    COMPLETED,
    RETRYABLE_FAILED,
}

fun interface VideoFrameModelOperation<Frame> {
    suspend fun analyze(
        frame: Frame,
        timestampMillis: Long,
        highValue: Boolean,
    ): FrameModelResult
}

interface VideoFrameModelStore {
    suspend fun saveRuleCandidate(candidate: AnalysisCandidateEntity)

    suspend fun saveFrameResult(
        mediaId: Long,
        timestampMillis: Long,
        result: FrameModelResult,
    )

    suspend fun updateCandidateModelStatus(
        candidateId: Long,
        status: CandidateModelStatus,
        errorMessage: String?,
    )
}

/**
 * 单次视频运行内的帧模型编排边界。
 * 分析器初始化失败会被记忆，后续帧只持久化同一可重试错误，不重复初始化。
 */
class VideoFrameModelOrchestrator<Frame>(
    private val analyzerProvider: () -> VideoFrameModelOperation<Frame>,
    private val store: VideoFrameModelStore,
) {
    private val initializationMutex = Mutex()
    private var initialization: AnalyzerInitialization<Frame> = AnalyzerInitialization.Uninitialized()

    suspend fun process(
        mediaId: Long,
        candidate: AnalysisCandidateEntity,
        frame: Frame,
    ) {
        require(mediaId > 0L) { "媒体 ID 必须大于 0" }
        require(candidate.mediaAssetId == mediaId) { "候选帧与模型分析媒体不一致" }
        val pendingCandidate = candidate.copy(
            modelStatus = CandidateModelStatus.PENDING.name,
            modelErrorMessage = null,
        )
        store.saveRuleCandidate(pendingCandidate)

        when (val initialized = analyzerInitialization()) {
            is AnalyzerInitialization.Failed -> {
                store.updateCandidateModelStatus(
                    candidate.id,
                    CandidateModelStatus.RETRYABLE_FAILED,
                    initialized.message,
                )
            }
            is AnalyzerInitialization.Ready -> analyzeAndSave(
                mediaId = mediaId,
                candidate = candidate,
                frame = frame,
                analyzer = initialized.analyzer,
            )
            is AnalyzerInitialization.Uninitialized -> error("分析器初始化状态不可达")
        }
    }

    private suspend fun analyzeAndSave(
        mediaId: Long,
        candidate: AnalysisCandidateEntity,
        frame: Frame,
        analyzer: VideoFrameModelOperation<Frame>,
    ) {
        val result = try {
            analyzer.analyze(
                frame = frame,
                timestampMillis = candidate.timestampMillis,
                highValue = candidate.score >= HIGH_VALUE_SCORE,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            store.updateCandidateModelStatus(
                candidate.id,
                CandidateModelStatus.RETRYABLE_FAILED,
                error.persistedMessage(),
            )
            return
        }

        store.saveFrameResult(mediaId, candidate.timestampMillis, result)
        if (result.failures.isEmpty()) {
            store.updateCandidateModelStatus(
                candidate.id,
                CandidateModelStatus.COMPLETED,
                null,
            )
        } else {
            store.updateCandidateModelStatus(
                candidate.id,
                CandidateModelStatus.RETRYABLE_FAILED,
                result.failures.joinToString(separator = "\n"),
            )
        }
    }

    private suspend fun analyzerInitialization(): AnalyzerInitialization<Frame> =
        initializationMutex.withLock {
            val current = initialization
            if (current !is AnalyzerInitialization.Uninitialized) return@withLock current
            val initialized = try {
                AnalyzerInitialization.Ready(analyzerProvider())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AnalyzerInitialization.Failed<Frame>(error.persistedMessage())
            }
            initialization = initialized
            initialized
        }

    private sealed interface AnalyzerInitialization<Frame> {
        class Uninitialized<Frame> : AnalyzerInitialization<Frame>

        data class Ready<Frame>(
            val analyzer: VideoFrameModelOperation<Frame>,
        ) : AnalyzerInitialization<Frame>

        data class Failed<Frame>(
            val message: String,
        ) : AnalyzerInitialization<Frame>
    }

    private companion object {
        const val HIGH_VALUE_SCORE = 70
    }
}

private fun Exception.persistedMessage(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
