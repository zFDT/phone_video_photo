package com.frameyu.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchAnalysisDaoTest {
    @Test
    fun workItemUsesPlannedDefaults() {
        val item = workItem(id = 10L, mediaAssetId = 1L, stage = "QUALITY")

        assertEquals(0L, item.nextTimestampMillis)
        assertEquals(0, item.attempts)
        assertEquals(null, item.errorMessage)
    }

    @Test
    fun batchWorkItemsSupportCheckpointRetryAndProgress() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()

            val batchId = dao.createBatch(
                batch = batch(),
                items = listOf(
                    workItem(id = 11L, mediaAssetId = 1L, stage = "QUALITY"),
                    workItem(id = 12L, mediaAssetId = 2L, stage = "CLASSIFICATION"),
                    workItem(id = 13L, mediaAssetId = 1L, stage = "EMBEDDING"),
                ),
            )

            val createdItems = dao.nextRunnableItems(batchId, limit = 10)
            assertEquals(listOf(11L, 12L, 13L), createdItems.map { it.id })
            assertTrue(createdItems.all { it.batchId == batchId })

            dao.markRunning(itemId = 11L, updatedAtMillis = 100L)
            assertWorkItem(
                database = database,
                itemId = 11L,
                expectedStatus = "RUNNING",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 0L,
            )
            dao.saveCheckpoint(itemId = 11L, nextTimestampMillis = 750L, updatedAtMillis = 110L)
            assertWorkItem(
                database = database,
                itemId = 11L,
                expectedStatus = "RUNNING",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 750L,
            )
            dao.completeItem(itemId = 11L, updatedAtMillis = 120L)

            dao.markRunning(itemId = 13L, updatedAtMillis = 130L)
            dao.failItem(
                itemId = 13L,
                retryable = true,
                message = "模型暂不可用",
                updatedAtMillis = 140L,
            )

            val runnableItems = dao.nextRunnableItems(batchId, limit = 10)
            assertEquals(listOf(12L, 13L), runnableItems.map { it.id })
            assertEquals("RETRYABLE_FAILED", runnableItems[1].status)
            assertEquals("模型暂不可用", runnableItems[1].errorMessage)
            val progress = dao.observeBatch(batchId).first()
            val counts: List<Long> = listOf(
                progress.totalWorkItems,
                progress.completedWorkItems,
                progress.runningWorkItems,
                progress.pendingWorkItems,
                progress.failedWorkItems,
            )
            assertEquals(listOf(3L, 1L, 0L, 1L, 1L), counts)
        } finally {
            database.close()
        }
    }

    @Test
    fun stateChangesRejectIllegalSourcesAndMapFailureKinds() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()
            dao.createBatch(
                batch = batch(),
                items = listOf(
                    workItem(21L, 1L, "QUALITY", status = "RUNNING"),
                    workItem(22L, 1L, "QUALITY", status = "COMPLETED"),
                    workItem(23L, 1L, "QUALITY", status = "PENDING"),
                    workItem(24L, 1L, "QUALITY", status = "PERMANENT_FAILED"),
                    workItem(26L, 2L, "QUALITY", status = "PENDING"),
                    workItem(27L, 2L, "QUALITY", status = "PENDING"),
                    workItem(28L, 2L, "QUALITY", status = "PENDING"),
                ),
            )

            assertFalse(dao.markRunning(itemId = 21L, updatedAtMillis = 10L))
            assertFalse(dao.markRunning(itemId = 22L, updatedAtMillis = 10L))
            assertIllegalState { dao.markRunning(itemId = 999L, updatedAtMillis = 10L) }

            assertIllegalState {
                dao.saveCheckpoint(itemId = 23L, nextTimestampMillis = 100L, updatedAtMillis = 10L)
            }
            assertFalse(dao.completeItem(itemId = 23L, updatedAtMillis = 10L))
            assertFalse(
                dao.failItem(
                    itemId = 23L,
                    retryable = true,
                    message = "仍在等待",
                    updatedAtMillis = 10L,
                ),
            )
            assertIllegalState { dao.completeItem(itemId = 999L, updatedAtMillis = 10L) }
            assertIllegalState {
                dao.failItem(
                    itemId = 999L,
                    retryable = true,
                    message = "不存在",
                    updatedAtMillis = 10L,
                )
            }

            assertFalse(dao.completeItem(itemId = 22L, updatedAtMillis = 10L))
            assertFalse(
                dao.failItem(
                    itemId = 22L,
                    retryable = false,
                    message = "已经完成",
                    updatedAtMillis = 10L,
                ),
            )
            assertFalse(dao.completeItem(itemId = 24L, updatedAtMillis = 10L))
            assertFalse(
                dao.failItem(
                    itemId = 24L,
                    retryable = false,
                    message = "已经失败",
                    updatedAtMillis = 10L,
                ),
            )

            dao.markRunning(itemId = 26L, updatedAtMillis = 20L)
            dao.failItem(
                itemId = 26L,
                retryable = true,
                message = " 临时故障 ",
                updatedAtMillis = 30L,
            )
            assertWorkItem(
                database = database,
                itemId = 26L,
                expectedStatus = "RETRYABLE_FAILED",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 0L,
                expectedErrorMessage = " 临时故障 ",
            )

            dao.markRunning(itemId = 27L, updatedAtMillis = 20L)
            dao.failItem(
                itemId = 27L,
                retryable = false,
                message = "不可恢复",
                updatedAtMillis = 30L,
            )
            assertWorkItem(
                database = database,
                itemId = 27L,
                expectedStatus = "PERMANENT_FAILED",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 0L,
                expectedErrorMessage = "不可恢复",
            )

            dao.markRunning(itemId = 28L, updatedAtMillis = 20L)
            dao.failItem(
                itemId = 28L,
                retryable = true,
                message = "",
                updatedAtMillis = 30L,
            )
            assertWorkItem(
                database = database,
                itemId = 28L,
                expectedStatus = "RETRYABLE_FAILED",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 0L,
                expectedErrorMessage = "",
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun recoverInterruptedItemsPreservesCheckpointAndIsBatchScoped() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()
            val batchId = dao.createBatch(
                batch = batch(),
                items = listOf(
                    workItem(31L, 1L, "QUALITY"),
                    workItem(32L, 2L, "CLASSIFICATION"),
                ),
            )
            val otherBatchId = dao.createBatch(
                batch = batch(),
                items = listOf(workItem(33L, 1L, "EMBEDDING")),
            )
            dao.markRunning(itemId = 31L, updatedAtMillis = 10L)
            dao.saveCheckpoint(itemId = 31L, nextTimestampMillis = 444L, updatedAtMillis = 20L)
            dao.markRunning(itemId = 33L, updatedAtMillis = 10L)

            val recovered = dao.recoverInterruptedItems(
                batchId = batchId,
                message = "进程中断",
                updatedAtMillis = 30L,
            )

            assertEquals(1, recovered)
            assertWorkItem(
                database = database,
                itemId = 31L,
                expectedStatus = "RETRYABLE_FAILED",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 444L,
                expectedErrorMessage = "进程中断",
            )
            assertEquals(
                listOf(31L, 32L),
                dao.nextRunnableItems(batchId = batchId, limit = 10).map { it.id },
            )
            assertWorkItem(
                database = database,
                itemId = 33L,
                expectedStatus = "RUNNING",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 0L,
            )
            assertTrue(otherBatchId != batchId)
        } finally {
            database.close()
        }
    }

    @Test
    fun batchLifecycleUsesLegalSourcesAndKeepsIdempotentControlOperations() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()
            val pausedBatchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(workItem(41L, 1L, "QUALITY")),
            )
            dao.pauseBatch(pausedBatchId, updatedAtMillis = 10L)
            dao.pauseBatch(pausedBatchId, updatedAtMillis = 11L)
            dao.resumeBatch(pausedBatchId, "恢复中断", updatedAtMillis = 12L)
            dao.resumeBatch(pausedBatchId, "恢复中断", updatedAtMillis = 13L)
            assertEquals("QUEUED", dao.batchStatus(pausedBatchId))
            assertEquals(2L, dao.batchControlState(pausedBatchId).executionGeneration)

            val cancelledBatchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(workItem(42L, 2L, "QUALITY")),
            )
            dao.cancelBatch(cancelledBatchId, updatedAtMillis = 20L)
            dao.cancelBatch(cancelledBatchId, updatedAtMillis = 21L)
            assertEquals(1L, dao.batchControlState(cancelledBatchId).executionGeneration)
            assertIllegalState {
                dao.resumeBatch(cancelledBatchId, "不得复活", updatedAtMillis = 22L)
            }

            val completedBatchId = dao.createBatch(
                batch = batch().copy(status = "COMPLETED"),
                items = listOf(workItem(43L, 1L, "QUALITY", status = "COMPLETED")),
            )
            assertIllegalState { dao.pauseBatch(completedBatchId, updatedAtMillis = 30L) }
            assertIllegalState { dao.cancelBatch(completedBatchId, updatedAtMillis = 31L) }

            val attentionBatchId = dao.createBatch(
                batch = batch().copy(status = "NEEDS_ATTENTION"),
                items = listOf(
                    workItem(44L, 1L, "QUALITY", status = "PERMANENT_FAILED"),
                    workItem(45L, 2L, "QUALITY", status = "COMPLETED"),
                ),
            )
            dao.retryFailures(attentionBatchId, updatedAtMillis = 40L)
            assertEquals("QUEUED", dao.batchStatus(attentionBatchId))
            assertEquals(1L, dao.batchControlState(attentionBatchId).executionGeneration)
            assertEquals(
                listOf(44L),
                dao.nextRunnableItems(attentionBatchId, limit = 10).map { it.id },
            )
            assertIllegalState { dao.retryFailures(attentionBatchId, updatedAtMillis = 41L) }
        } finally {
            database.close()
        }
    }

    @Test
    fun runnerFailureConvergesRecoverableItemsAndAllowsCompletedOnlyRetry() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll((1L..6L).map(::media))
            val dao = database.batchAnalysisDao()
            val mixedBatchId = dao.createBatch(
                batch = batch().copy(status = "RUNNING"),
                items = listOf(
                    workItem(81L, 1L, "QUALITY", status = "PENDING"),
                    workItem(82L, 2L, "QUALITY", status = "RUNNING"),
                    workItem(83L, 3L, "QUALITY", status = "RETRYABLE_FAILED"),
                    workItem(84L, 4L, "QUALITY", status = "PERMANENT_FAILED"),
                    workItem(85L, 5L, "QUALITY", status = "COMPLETED"),
                    workItem(86L, 6L, "QUALITY", status = "CANCELLED"),
                ),
            )

            assertTrue(
                dao.failActiveExecution(
                    mixedBatchId,
                    executionGeneration = 0L,
                    message = "Runner 级失败",
                    updatedAtMillis = 45L,
                ),
            )

            assertEquals("NEEDS_ATTENTION", dao.batchStatus(mixedBatchId))
            listOf(81L, 82L, 83L).forEach { itemId ->
                assertWorkItem(
                    database,
                    itemId = itemId,
                    expectedStatus = "RETRYABLE_FAILED",
                    expectedAttempts = 0,
                    expectedNextTimestampMillis = 0L,
                    expectedErrorMessage = "Runner 级失败",
                )
            }
            assertWorkItem(
                database,
                itemId = 84L,
                expectedStatus = "PERMANENT_FAILED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
            )
            assertWorkItem(
                database,
                itemId = 85L,
                expectedStatus = "COMPLETED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
            )
            assertWorkItem(
                database,
                itemId = 86L,
                expectedStatus = "CANCELLED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
            )

            val queuedBatchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(workItem(88L, 1L, "QUALITY", status = "PENDING")),
            )
            assertTrue(
                dao.markBatchRunning(
                    queuedBatchId,
                    executionGeneration = 0L,
                    updatedAtMillis = 45L,
                ),
            )
            assertTrue(
                dao.failActiveExecution(
                    queuedBatchId,
                    executionGeneration = 0L,
                    message = "首次前台任务启动失败",
                    updatedAtMillis = 46L,
                ),
            )
            assertEquals("NEEDS_ATTENTION", dao.batchStatus(queuedBatchId))
            assertWorkItem(
                database,
                itemId = 88L,
                expectedStatus = "RETRYABLE_FAILED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
                expectedErrorMessage = "首次前台任务启动失败",
            )

            val completedOnlyBatchId = dao.createBatch(
                batch = batch().copy(status = "RUNNING"),
                items = listOf(workItem(87L, 1L, "QUALITY", status = "COMPLETED")),
            )
            assertTrue(
                dao.failActiveExecution(
                    completedOnlyBatchId,
                    executionGeneration = 0L,
                    message = "最终收尾失败",
                    updatedAtMillis = 46L,
                ),
            )
            assertEquals("NEEDS_ATTENTION", dao.batchStatus(completedOnlyBatchId))

            dao.retryFailures(completedOnlyBatchId, updatedAtMillis = 47L)

            assertEquals("QUEUED", dao.batchStatus(completedOnlyBatchId))
            assertWorkItem(
                database,
                itemId = 87L,
                expectedStatus = "COMPLETED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun resumedBatchRejectsFailureFromWorkerThatClaimedBeforePause() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L)))
            val dao = database.batchAnalysisDao()
            val batchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(workItem(89L, 1L, "QUALITY")),
            )
            val oldGeneration = dao.batchControlState(batchId).executionGeneration
            assertTrue(
                dao.markBatchRunning(
                    batchId,
                    executionGeneration = oldGeneration,
                    updatedAtMillis = 60L,
                ),
            )

            dao.pauseBatch(batchId, updatedAtMillis = 61L)
            dao.resumeBatch(batchId, "恢复新 attempt", updatedAtMillis = 62L)

            assertFalse(
                dao.failActiveExecution(
                    batchId,
                    executionGeneration = oldGeneration,
                    message = "旧 Worker 前台化失败",
                    updatedAtMillis = 63L,
                ),
            )
            assertEquals("QUEUED", dao.batchStatus(batchId))

            val newGeneration = dao.batchControlState(batchId).executionGeneration
            assertTrue(newGeneration > oldGeneration)
            assertTrue(
                dao.markBatchRunning(
                    batchId,
                    executionGeneration = newGeneration,
                    updatedAtMillis = 64L,
                ),
            )
            assertFalse(
                dao.finishBatch(
                    batchId,
                    executionGeneration = oldGeneration,
                    status = "COMPLETED",
                    updatedAtMillis = 64L,
                ),
            )
            assertEquals("RUNNING", dao.batchStatus(batchId))
            assertTrue(dao.markRunning(89L, updatedAtMillis = 65L))
            assertTrue(dao.completeItem(89L, updatedAtMillis = 66L))
            assertTrue(
                dao.finishBatch(
                    batchId,
                    executionGeneration = newGeneration,
                    status = "COMPLETED",
                    updatedAtMillis = 67L,
                ),
            )
            assertEquals("COMPLETED", dao.batchStatus(batchId))
        } finally {
            database.close()
        }
    }

    @Test
    fun staleGenerationCannotClaimAfterPauseAndResume() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L)))
            val dao = database.batchAnalysisDao()
            val batchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(workItem(90L, 1L, "QUALITY")),
            )
            val staleControl = dao.batchControlState(batchId)

            dao.pauseBatch(batchId, updatedAtMillis = 1L)
            dao.resumeBatch(batchId, "恢复新 attempt", updatedAtMillis = 1L)

            assertFalse(
                dao.markBatchRunning(
                    batchId = batchId,
                    executionGeneration = staleControl.executionGeneration,
                    updatedAtMillis = 1L,
                ),
            )
            assertEquals("QUEUED", dao.batchStatus(batchId))

            val currentControl = dao.batchControlState(batchId)
            assertTrue(
                currentControl.executionGeneration > staleControl.executionGeneration,
            )
            assertTrue(
                dao.markBatchRunning(
                    batchId = batchId,
                    executionGeneration = currentControl.executionGeneration,
                    updatedAtMillis = 1L,
                ),
            )
            assertTrue(
                dao.markBatchRunning(
                    batchId = batchId,
                    executionGeneration = currentControl.executionGeneration,
                    updatedAtMillis = 2L,
                ),
            )
            assertEquals("RUNNING", dao.batchStatus(batchId))
        } finally {
            database.close()
        }
    }

    @Test
    fun reconcilableBatchesAndEnqueueFailureStateArePersistent() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()
            val queuedBatchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(
                    workItem(46L, 1L, "QUALITY"),
                    workItem(47L, 2L, "QUALITY", status = "COMPLETED"),
                ),
            )
            val completedBatchId = dao.createBatch(
                batch = batch().copy(status = "COMPLETED"),
                items = listOf(workItem(48L, 1L, "QUALITY", status = "COMPLETED")),
            )
            val emptyQueuedBatchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = emptyList(),
            )
            val completedItemsRunningBatchId = dao.createBatch(
                batch = batch().copy(status = "RUNNING"),
                items = listOf(workItem(49L, 1L, "QUALITY", status = "COMPLETED")),
            )

            assertEquals(
                listOf(queuedBatchId, emptyQueuedBatchId, completedItemsRunningBatchId),
                dao.reconcilableExecutions().map { execution -> execution.batchId },
            )

            assertTrue(
                dao.markBatchNeedsAttention(
                    queuedBatchId,
                    executionGeneration = 0L,
                    message = "WorkManager 入队失败",
                    updatedAtMillis = 50L,
                ),
            )

            assertEquals("NEEDS_ATTENTION", dao.batchStatus(queuedBatchId))
            assertWorkItem(
                database,
                itemId = 46L,
                expectedStatus = "RETRYABLE_FAILED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
                expectedErrorMessage = "WorkManager 入队失败",
            )
            assertWorkItem(
                database,
                itemId = 47L,
                expectedStatus = "COMPLETED",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
            )
            assertTrue(
                completedBatchId !in
                    dao.reconcilableExecutions().map { execution -> execution.batchId },
            )

            val staleFailureBatchId = dao.createBatch(
                batch = batch().copy(status = "QUEUED"),
                items = listOf(workItem(50L, 2L, "QUALITY")),
            )
            val staleFailureGeneration =
                dao.batchControlState(staleFailureBatchId).executionGeneration
            dao.pauseBatch(staleFailureBatchId, updatedAtMillis = 51L)
            dao.resumeBatch(staleFailureBatchId, "恢复新控制代", updatedAtMillis = 52L)
            assertFalse(
                dao.markBatchNeedsAttention(
                    staleFailureBatchId,
                    executionGeneration = staleFailureGeneration,
                    message = "旧 generation 入队失败",
                    updatedAtMillis = 53L,
                ),
            )
            assertEquals("QUEUED", dao.batchStatus(staleFailureBatchId))
            assertWorkItem(
                database,
                itemId = 50L,
                expectedStatus = "PENDING",
                expectedAttempts = 0,
                expectedNextTimestampMillis = 0L,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun classificationAndDetectionWritesReplaceCompleteNaturalKeySets() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()
            val timestampMillis = 100L
            val modelSha256 = "model-sha"

            dao.upsertClassifications(
                mediaAssetId = 1L,
                timestampMillis = timestampMillis,
                modelSha256 = modelSha256,
                results = (0 until 5).map { rank ->
                    classification(1L, timestampMillis, modelSha256, rank, label = "旧-$rank")
                },
            )
            val mismatchFailure = runCatching {
                dao.upsertClassifications(
                    mediaAssetId = 1L,
                    timestampMillis = timestampMillis,
                    modelSha256 = modelSha256,
                    results = listOf(classification(2L, timestampMillis, modelSha256, 0, "错键")),
                )
            }.exceptionOrNull()
            assertTrue(mismatchFailure is IllegalArgumentException)
            assertEquals(5, dao.getClassifications(1L, timestampMillis, modelSha256).size)

            dao.upsertClassifications(
                mediaAssetId = 1L,
                timestampMillis = timestampMillis,
                modelSha256 = modelSha256,
                results = (0 until 2).map { rank ->
                    classification(1L, timestampMillis, modelSha256, rank, label = "新-$rank")
                },
            )
            assertEquals(
                listOf("新-0", "新-1"),
                dao.getClassifications(1L, timestampMillis, modelSha256).map { it.label },
            )
            dao.upsertClassifications(1L, timestampMillis, modelSha256, emptyList())
            assertTrue(dao.getClassifications(1L, timestampMillis, modelSha256).isEmpty())

            dao.upsertDetections(
                mediaAssetId = 1L,
                timestampMillis = timestampMillis,
                modelSha256 = modelSha256,
                results = (0 until 5).map { rank ->
                    detection(1L, timestampMillis, modelSha256, rank, category = "旧-$rank")
                },
            )
            dao.upsertDetections(
                mediaAssetId = 1L,
                timestampMillis = timestampMillis,
                modelSha256 = modelSha256,
                results = (0 until 2).map { rank ->
                    detection(1L, timestampMillis, modelSha256, rank, category = "新-$rank")
                },
            )
            assertEquals(
                listOf("新-0", "新-1"),
                dao.getDetections(1L, timestampMillis, modelSha256).map { it.category },
            )
            dao.upsertDetections(1L, timestampMillis, modelSha256, emptyList())
            assertTrue(dao.getDetections(1L, timestampMillis, modelSha256).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun frameClassificationQueryExcludesVideoAggregateSentinel() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L)))
            val dao = database.batchAnalysisDao()
            val modelSha256 = "model-sha"
            listOf(-1L, 0L, 1_000L).forEach { timestampMillis ->
                dao.upsertClassifications(
                    mediaAssetId = 1L,
                    timestampMillis = timestampMillis,
                    modelSha256 = modelSha256,
                    results = listOf(
                        classification(
                            mediaAssetId = 1L,
                            timestampMillis = timestampMillis,
                            modelSha256 = modelSha256,
                            rank = 0,
                            label = "label-$timestampMillis",
                        ),
                    ),
                )
            }

            assertEquals(
                listOf(0L, 1_000L),
                dao.getFrameClassifications(1L, modelSha256).map { it.timestampMillis },
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun featureAndQualityUpsertsKeepStableRowIds() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()

            dao.upsertFeatureReference(
                MediaFeatureReferenceEntity(
                    id = 999L,
                    mediaAssetId = 1L,
                    timestampMillis = 200L,
                    modelSha256 = "feature-model",
                    cachePath = "first.cache",
                    dimension = 128,
                ),
            )
            val firstFeature = dao.getFeatureReference(1L, 200L, "feature-model")!!
            dao.upsertFeatureReference(
                firstFeature.copy(id = 888L, cachePath = "second.cache", dimension = 256),
            )
            val updatedFeature = dao.getFeatureReference(1L, 200L, "feature-model")!!
            assertEquals(firstFeature.id, updatedFeature.id)
            assertEquals("second.cache", updatedFeature.cachePath)
            assertEquals(256, updatedFeature.dimension)

            dao.upsertQualityResult(
                qualityResult(
                    id = 999L,
                    mediaAssetId = 1L,
                    timestampMillis = 300L,
                    analyzerVersion = "quality-v1",
                    totalScore = 0.4f,
                ),
            )
            val firstQuality = dao.getQualityResult(1L, 300L, "quality-v1")!!
            dao.upsertQualityResult(
                firstQuality.copy(id = 888L, totalScore = 0.9f),
            )
            val updatedQuality = dao.getQualityResult(1L, 300L, "quality-v1")!!
            assertEquals(firstQuality.id, updatedQuality.id)
            assertEquals(0.9f, updatedQuality.totalScore)
        } finally {
            database.close()
        }
    }

    @Test
    fun duplicateWorkItemIdentityIsRejected() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val duplicate = workItem(id = 0L, mediaAssetId = 1L, stage = "QUALITY")

            val failure = runCatching {
                database.batchAnalysisDao().createBatch(
                    batch = batch(),
                    items = listOf(duplicate, duplicate.copy(mediaAssetId = 1L)),
                )
            }.exceptionOrNull()

            assertTrue(failure is SQLiteConstraintException)
            assertEquals(0L, tableCount(database, "analysis_batches"))
            assertEquals(0L, tableCount(database, "analysis_work_items"))
        } finally {
            database.close()
        }
    }

    @Test
    fun migration3To7IsolatesActiveBatchesAndInvalidatesLegacyVideoWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "batch-analysis-migration-3-7.db"
        context.deleteDatabase(databaseName)
        var roomDatabase: FrameYuDatabase? = null
        try {
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                database.execSQL(
                    """
                    CREATE TABLE media_assets (
                        id INTEGER NOT NULL,
                        uri TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        taken_at_millis INTEGER NOT NULL,
                        duration_millis INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        rotation_degrees INTEGER NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        is_hdr INTEGER NOT NULL,
                        relative_path TEXT,
                        last_modified_millis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE UNIQUE INDEX index_media_assets_uri ON media_assets(uri)")
                database.execSQL(
                    """
                    CREATE TABLE analysis_candidates (
                        id INTEGER NOT NULL,
                        media_asset_id INTEGER NOT NULL,
                        timestamp_millis INTEGER NOT NULL,
                        score INTEGER NOT NULL,
                        sharpness_score INTEGER NOT NULL,
                        subject_score INTEGER NOT NULL,
                        expression_score INTEGER NOT NULL,
                        composition_score INTEGER NOT NULL,
                        scene_score INTEGER NOT NULL,
                        thumbnail_path TEXT,
                        is_selected INTEGER NOT NULL,
                        is_cover INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(id),
                        FOREIGN KEY(media_asset_id) REFERENCES media_assets(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX index_analysis_candidates_media_asset_id " +
                        "ON analysis_candidates(media_asset_id)",
                )
                database.execSQL(
                    """
                    CREATE TABLE analysis_tasks (
                        id INTEGER NOT NULL,
                        media_asset_id INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        progress INTEGER NOT NULL,
                        current_timestamp_millis INTEGER NOT NULL,
                        error_message TEXT,
                        updated_at_millis INTEGER NOT NULL,
                        plan_id TEXT,
                        range_index INTEGER NOT NULL DEFAULT 0,
                        sampled_frame_count INTEGER NOT NULL DEFAULT 0,
                        total_sample_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(id),
                        FOREIGN KEY(media_asset_id) REFERENCES media_assets(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX index_analysis_tasks_media_asset_id " +
                        "ON analysis_tasks(media_asset_id)",
                )
                database.execSQL(
                    """
                    INSERT INTO media_assets (
                        id, uri, mime_type, taken_at_millis, duration_millis, width, height,
                        rotation_degrees, size_bytes, is_hdr, relative_path, last_modified_millis
                    ) VALUES (
                        99, 'content://media/99', 'image/jpeg', 123, 0, 100, 100,
                        0, 456, 0, NULL, 789
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO analysis_tasks (
                        id, media_asset_id, status, progress, current_timestamp_millis,
                        error_message, updated_at_millis, plan_id, range_index,
                        sampled_frame_count, total_sample_count
                    ) VALUES (
                        99, 99, 'RUNNING', 50, 500, NULL, 1, 'legacy-plan', 0, 1, 2
                    )
                    """.trimIndent(),
                )
                database.execSQL("PRAGMA user_version = 3")
            }

            val seedActiveV4Batches = object : Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    FrameYuDatabase.MIGRATION_3_4.migrate(db)
                    db.execSQL(
                        """
                        INSERT INTO analysis_batches (
                            id, status, scope, created_at_millis, updated_at_millis
                        ) VALUES
                            (201, 'QUEUED', 'ALL', 1, 1),
                            (202, 'RUNNING', 'ALL', 1, 1),
                            (203, 'COMPLETED', 'ALL', 1, 1)
                        """.trimIndent(),
                    )
                }
            }
            val migratedDatabase = Room.databaseBuilder(context, FrameYuDatabase::class.java, databaseName)
                .addMigrations(
                    seedActiveV4Batches,
                    FrameYuDatabase.MIGRATION_4_5,
                    FrameYuDatabase.MIGRATION_5_6,
                    FrameYuDatabase.MIGRATION_6_7,
                )
                .build()
            roomDatabase = migratedDatabase
            assertEquals("content://media/99", migratedDatabase.mediaAssetDao().getById(99L)?.uri)
            val migratedTask = migratedDatabase.analysisTaskDao().getTaskProgress(99L)!!
            assertEquals("FAILED", migratedTask.status)
            assertEquals(1L, migratedTask.executionGeneration)
            assertEquals("应用升级后需重试视频分析", migratedTask.errorMessage)
            val dao = migratedDatabase.batchAnalysisDao()
            assertEquals(1L, dao.batchControlState(201L).executionGeneration)
            assertEquals(1L, dao.batchControlState(202L).executionGeneration)
            assertEquals(0L, dao.batchControlState(203L).executionGeneration)
            assertFalse(dao.markBatchRunning(201L, executionGeneration = 0L, updatedAtMillis = 2L))
            assertFalse(dao.markBatchRunning(202L, executionGeneration = 0L, updatedAtMillis = 2L))
            assertTrue(dao.markBatchRunning(201L, executionGeneration = 1L, updatedAtMillis = 2L))
            assertTrue(dao.markBatchRunning(202L, executionGeneration = 1L, updatedAtMillis = 2L))
            val batchId = migratedDatabase.batchAnalysisDao().createBatch(batch(), emptyList())
            assertEquals(0L, dao.batchControlState(batchId).executionGeneration)
            assertEquals(
                0L,
                dao.observeBatch(batchId).first().totalWorkItems,
            )
            migratedDatabase.openHelper.readableDatabase
                .query("PRAGMA table_info(analysis_batches)")
                .use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    var generationDefault: String? = null
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "execution_generation") {
                            generationDefault = cursor.getString(defaultIndex)
                        }
                    }
                    assertEquals("0", generationDefault)
                }
        } finally {
            roomDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createDatabase(): FrameYuDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
    }

    private fun batch() = AnalysisBatchEntity(
        status = "QUEUED",
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun workItem(
        id: Long,
        mediaAssetId: Long,
        stage: String,
        status: String = "PENDING",
    ) = AnalysisWorkItemEntity(
        id = id,
        batchId = 0L,
        mediaAssetId = mediaAssetId,
        stage = stage,
        status = status,
        analyzerVersion = "analyzer-v$id",
        updatedAtMillis = 1L,
    )

    private fun media(id: Long) = MediaAssetEntity(
        id = id,
        uri = "content://media/$id",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = 1_000L,
        width = 1_920,
        height = 1_080,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )

    private fun classification(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        rank: Int,
        label: String,
    ) = MediaClassificationEntity(
        id = 100L + rank,
        mediaAssetId = mediaAssetId,
        timestampMillis = timestampMillis,
        modelSha256 = modelSha256,
        rank = rank,
        label = label,
        confidence = 0.8f,
    )

    private fun detection(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        rank: Int,
        category: String,
    ) = MediaDetectionEntity(
        id = 200L + rank,
        mediaAssetId = mediaAssetId,
        timestampMillis = timestampMillis,
        modelSha256 = modelSha256,
        rank = rank,
        category = category,
        confidence = 0.7f,
        left = 0.1f,
        top = 0.2f,
        right = 0.8f,
        bottom = 0.9f,
    )

    private fun qualityResult(
        id: Long,
        mediaAssetId: Long,
        timestampMillis: Long,
        analyzerVersion: String,
        totalScore: Float,
    ) = MediaQualityResultEntity(
        id = id,
        mediaAssetId = mediaAssetId,
        timestampMillis = timestampMillis,
        analyzerVersion = analyzerVersion,
        totalScore = totalScore,
        sharpnessScore = 0.5f,
        exposureScore = 0.5f,
        noiseScore = 0.5f,
        compositionScore = 0.5f,
    )

    private fun assertWorkItem(
        database: FrameYuDatabase,
        itemId: Long,
        expectedStatus: String,
        expectedAttempts: Int,
        expectedNextTimestampMillis: Long,
        expectedErrorMessage: String? = null,
    ) {
        database.openHelper.readableDatabase.query(
            """
            SELECT status, attempts, next_timestamp_millis, error_message
            FROM analysis_work_items
            WHERE id = ?
            """.trimIndent(),
            arrayOf(itemId.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedStatus, cursor.getString(0))
            assertEquals(expectedAttempts, cursor.getInt(1))
            assertEquals(expectedNextTimestampMillis, cursor.getLong(2))
            assertEquals(expectedErrorMessage, cursor.getString(3))
        }
    }

    private suspend fun assertIllegalState(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("应拒绝非法来源状态", failure is IllegalStateException)
    }

    private fun tableCount(database: FrameYuDatabase, tableName: String): Long {
        return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $tableName").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }
}
