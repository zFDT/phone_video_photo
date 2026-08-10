package com.frameyu.app.analysis.batch

import com.frameyu.app.data.db.AnalysisBatchEntity
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.BatchAnalysisDao
import com.frameyu.app.data.db.MediaAssetDao
import com.frameyu.app.data.db.MediaAssetEntity
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val IMAGE_MIME_PREFIX = "image/"

interface BatchAnalysisCreationStore {
    suspend fun latestOpenBatch(): AnalysisBatchEntity?

    suspend fun openBatchItems(batchId: Long): List<AnalysisWorkItemEntity>

    suspend fun analyzableMedia(): List<MediaAssetEntity>

    suspend fun createBatch(
        batch: AnalysisBatchEntity,
        items: List<AnalysisWorkItemEntity>,
    ): Long

    suspend fun replaceOpenBatch(
        openBatchId: Long,
        batch: AnalysisBatchEntity,
        items: List<AnalysisWorkItemEntity>,
    ): Long
}

class RoomBatchAnalysisCreationStore(
    private val mediaAssetDao: MediaAssetDao,
    private val batchAnalysisDao: BatchAnalysisDao,
) : BatchAnalysisCreationStore {
    override suspend fun latestOpenBatch(): AnalysisBatchEntity? =
        batchAnalysisDao.latestOpenBatch()

    override suspend fun openBatchItems(batchId: Long): List<AnalysisWorkItemEntity> =
        batchAnalysisDao.batchWorkItems(batchId)

    override suspend fun analyzableMedia(): List<MediaAssetEntity> =
        mediaAssetDao.getAll().filter { media ->
            media.mimeType.lowercase(Locale.ROOT).startsWith(IMAGE_MIME_PREFIX)
        }

    override suspend fun createBatch(
        batch: AnalysisBatchEntity,
        items: List<AnalysisWorkItemEntity>,
    ): Long = batchAnalysisDao.createBatch(batch, items)

    override suspend fun replaceOpenBatch(
        openBatchId: Long,
        batch: AnalysisBatchEntity,
        items: List<AnalysisWorkItemEntity>,
    ): Long = batchAnalysisDao.replaceOpenBatch(
        openBatchId = openBatchId,
        batch = batch,
        items = items,
        updatedAtMillis = batch.updatedAtMillis,
    )
}

class BatchAnalysisCoordinator(
    private val store: BatchAnalysisCreationStore,
    private val enqueue: suspend (Long) -> Unit,
    private val analyzerVersions: Map<AnalysisStage, String>,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val openOrCreateMutex = Mutex()
    private val enqueuedBatchIds = mutableSetOf<Long>()

    init {
        require(analyzerVersions.keys.containsAll(SUPPORTED_IMAGE_STAGES)) {
            "批次分析器版本必须覆盖全部图片阶段"
        }
        require(analyzerVersions.values.all { it.isNotBlank() }) {
            "批次分析器版本不能为空"
        }
    }

    suspend fun openOrCreateBatch(): Long = openOrCreateMutex.withLock {
        val openBatch = store.latestOpenBatch()
        if (openBatch != null) {
            val workItems = store.openBatchItems(openBatch.id)
            if (isReusableImageBatch(workItems, analyzerVersions)) {
                if (openBatch.status in ACTIVE_BATCH_STATUSES &&
                    openBatch.id !in enqueuedBatchIds
                ) {
                    enqueue(openBatch.id)
                    enqueuedBatchIds += openBatch.id
                }
                return@withLock openBatch.id
            }
        }

        val media = store.analyzableMedia()
            .filter { asset ->
                asset.mimeType.lowercase(Locale.ROOT).startsWith(IMAGE_MIME_PREFIX)
            }
        check(media.isNotEmpty()) { "媒体库中没有可进行深度分析的图片" }
        val now = nowMillis()
        val items = media.flatMap { asset ->
            SUPPORTED_IMAGE_STAGES.map { stage ->
                AnalysisWorkItemEntity(
                    batchId = 0L,
                    mediaAssetId = asset.id,
                    stage = stage.name,
                    status = WorkItemStatus.PENDING.name,
                    analyzerVersion = analyzerVersions.getValue(stage),
                    updatedAtMillis = now,
                )
            }
        }
        val batch = AnalysisBatchEntity(
            status = "QUEUED",
            scope = "ALL_IMAGES",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val batchId = if (openBatch == null) {
            store.createBatch(batch, items)
        } else {
            store.replaceOpenBatch(openBatch.id, batch, items)
        }
        enqueue(batchId)
        enqueuedBatchIds += batchId
        batchId
    }

    companion object {
        val SUPPORTED_IMAGE_STAGES = listOf(
            AnalysisStage.QUALITY,
            AnalysisStage.CLASSIFICATION,
            AnalysisStage.EMBEDDING,
            AnalysisStage.CONDITIONAL_DETECTION,
        )

        private val ACTIVE_BATCH_STATUSES = setOf("QUEUED", "RUNNING")
    }
}

internal fun isReusableImageBatch(
    items: List<AnalysisWorkItemEntity>,
    analyzerVersions: Map<AnalysisStage, String>,
): Boolean {
    if (items.isEmpty()) return false
    val expectedStages = BatchAnalysisCoordinator.SUPPORTED_IMAGE_STAGES
    val expectedStageNames = expectedStages.mapTo(linkedSetOf()) { it.name }
    return items.groupBy(AnalysisWorkItemEntity::mediaAssetId).values.all { mediaItems ->
        if (mediaItems.size != expectedStages.size) return@all false
        val itemsByStage = mediaItems.groupBy(AnalysisWorkItemEntity::stage)
        if (itemsByStage.keys != expectedStageNames ||
            itemsByStage.values.any { stageItems -> stageItems.size != 1 }
        ) {
            return@all false
        }
        expectedStages.all { stage ->
            itemsByStage.getValue(stage.name).single().analyzerVersion ==
                analyzerVersions.getValue(stage)
        }
    }
}
