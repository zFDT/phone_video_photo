package com.frameyu.app.sampling

import androidx.room.withTransaction
import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.FrameYuDatabase

data class VideoAnalysisExecutionKey(
    val taskId: Long,
    val executionGeneration: Long,
) {
    init {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
    }
}

data class VideoAnalysisControlState(
    val status: String,
    val executionGeneration: Long,
)

interface VideoAnalysisLifecycleStore {
    suspend fun prepareExecution(taskId: Long): VideoAnalysisExecutionKey

    suspend fun cancel(taskId: Long)

    suspend fun cancelAll()

    suspend fun markEnqueueFailed(
        execution: VideoAnalysisExecutionKey,
        message: String,
    ): Boolean
}

interface VideoAnalysisRunnerStore {
    suspend fun claim(execution: VideoAnalysisExecutionKey): Boolean

    suspend fun controlState(taskId: Long): VideoAnalysisControlState?

    suspend fun markCompleted(execution: VideoAnalysisExecutionKey): Boolean

    suspend fun markFailed(execution: VideoAnalysisExecutionKey, message: String): Boolean

    suspend fun markCancelled(execution: VideoAnalysisExecutionKey, message: String): Boolean
}

/** 视频任务生命周期的 Room 事务边界。 */
class RoomVideoAnalysisLifecycleStore(
    private val database: FrameYuDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : VideoAnalysisLifecycleStore, VideoAnalysisRunnerStore {
    override suspend fun prepareExecution(taskId: Long): VideoAnalysisExecutionKey {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        return database.withTransaction {
            val dao = database.analysisTaskDao()
            val current = dao.getTaskProgress(taskId)
            when {
                current == null -> {
                    val task = AnalysisTaskEntity(
                        id = taskId,
                        mediaAssetId = taskId,
                        status = STATUS_QUEUED,
                        progress = 0,
                        currentTimestampMillis = 0L,
                        errorMessage = null,
                        updatedAtMillis = nowMillis(),
                    )
                    dao.saveTaskProgress(task)
                    task.executionKey()
                }
                current.status in ACTIVE_STATUSES -> current.executionKey()
                else -> {
                    val retried = current.copy(
                        status = STATUS_QUEUED,
                        progress = 0,
                        currentTimestampMillis = 0L,
                        errorMessage = null,
                        updatedAtMillis = nowMillis(),
                        planId = null,
                        rangeIndex = 0,
                        sampledFrameCount = 0L,
                        totalSampleCount = 0L,
                        executionGeneration = current.executionGeneration + 1L,
                    )
                    dao.saveTaskProgress(retried)
                    retried.executionKey()
                }
            }
        }
    }

    override suspend fun cancel(taskId: Long) {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        database.analysisTaskDao().cancelTask(taskId, CANCELLED_MESSAGE, nowMillis())
    }

    override suspend fun cancelAll() {
        database.analysisTaskDao().cancelActiveTasks(CANCELLED_MESSAGE, nowMillis())
    }

    override suspend fun markEnqueueFailed(
        execution: VideoAnalysisExecutionKey,
        message: String,
    ): Boolean = database.analysisTaskDao().failActiveExecution(
        taskId = execution.taskId,
        executionGeneration = execution.executionGeneration,
        errorMessage = message,
        updatedAtMillis = nowMillis(),
    ) == 1

    override suspend fun claim(execution: VideoAnalysisExecutionKey): Boolean =
        database.withTransaction {
            val dao = database.analysisTaskDao()
            if (dao.claimExecution(
                    taskId = execution.taskId,
                    executionGeneration = execution.executionGeneration,
                    updatedAtMillis = nowMillis(),
                ) != 1
            ) {
                return@withTransaction false
            }
            val task = requireNotNull(dao.getTaskProgress(execution.taskId))
            database.analysisCandidateDao().deleteByMediaAssetId(task.mediaAssetId)
            true
        }

    override suspend fun controlState(taskId: Long): VideoAnalysisControlState? =
        database.analysisTaskDao().getTaskProgress(taskId)?.let { task ->
            VideoAnalysisControlState(task.status, task.executionGeneration)
        }

    override suspend fun markCompleted(execution: VideoAnalysisExecutionKey): Boolean =
        database.analysisTaskDao().finishRunningExecution(
            taskId = execution.taskId,
            executionGeneration = execution.executionGeneration,
            status = STATUS_COMPLETED,
            errorMessage = null,
            updatedAtMillis = nowMillis(),
        ) == 1

    override suspend fun markFailed(
        execution: VideoAnalysisExecutionKey,
        message: String,
    ): Boolean = database.analysisTaskDao().failActiveExecution(
        taskId = execution.taskId,
        executionGeneration = execution.executionGeneration,
        errorMessage = message,
        updatedAtMillis = nowMillis(),
    ) == 1

    override suspend fun markCancelled(
        execution: VideoAnalysisExecutionKey,
        message: String,
    ): Boolean = database.analysisTaskDao().cancelExecution(
        taskId = execution.taskId,
        executionGeneration = execution.executionGeneration,
        errorMessage = message,
        updatedAtMillis = nowMillis(),
    ) == 1

    private fun AnalysisTaskEntity.executionKey(): VideoAnalysisExecutionKey =
        VideoAnalysisExecutionKey(id, executionGeneration)

    private companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val CANCELLED_MESSAGE = "用户取消分析"
        val ACTIVE_STATUSES = setOf(STATUS_QUEUED, "RUNNING")
    }
}
