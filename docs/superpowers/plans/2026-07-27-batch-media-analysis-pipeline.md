# 批量媒体分析管线实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为帧遇接入可离线批量运行、可中断恢复的图片/视频分类、向量、条件目标检测、质量评分和整理推荐管线。

**架构：** MediaStore 继续负责原媒体索引，Room 新增批次和阶段工作项，WorkManager 使用唯一串行链恢复任务。EfficientNet-Lite2、MobileNetV3 Embedder 和 EfficientDet-Lite2 通过独立分析器接入，模型结果按 SHA-256 版本化，推荐引擎只生成候选，删除仍由用户确认。

**技术栈：** Kotlin、Room 2.7.2、WorkManager 2.11.2、MediaPipe Tasks Vision 0.10.26、ML Kit Face Detection、Jetpack Compose、JUnit、AndroidX Test。

---

## 文件结构

### 模型资产

- 创建：`app/src/main/assets/models/model-manifest.properties`，记录模型 URL、文件名和 SHA-256。
- 创建：`scripts/fetch-models.ps1`，从 `module/` 复用或从官方 URL 下载并校验模型。
- 创建：`app/src/main/java/com/frameyu/app/vision/model/ModelAssetCatalog.kt`，解析清单并定义模型标识。
- 创建：`app/src/main/java/com/frameyu/app/vision/model/ModelAssetVerifier.kt`，运行时校验 assets。
- 修改：`.gitignore`，忽略本地暂存目录和生成的 `.tflite` assets。

### 批次和持久化

- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisModel.kt`，定义批次、阶段和工作项状态。
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchStateMachine.kt`，集中管理合法状态转换。
- 创建：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisEntities.kt`，定义批次、工作项和模型结果表。
- 创建：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisDao.kt`，提供可恢复工作项查询和事务写入。
- 修改：`app/src/main/java/com/frameyu/app/data/db/FrameYuDatabase.kt`，升级到版本 4。
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`，注册 3→4 迁移。

### 模型分析

- 创建：`app/src/main/java/com/frameyu/app/vision/ImageClassifierAnalyzer.kt`，封装 EfficientNet-Lite2。
- 创建：`app/src/main/java/com/frameyu/app/vision/ObjectDetectorAnalyzer.kt`，封装 EfficientDet-Lite2。
- 创建：`app/src/main/java/com/frameyu/app/vision/ObjectDetectionPolicy.kt`，决定何时运行目标检测。
- 创建：`app/src/main/java/com/frameyu/app/vision/FrameModelAnalyzer.kt`，定义统一帧模型结果和存储边界。
- 修改：`app/src/main/java/com/frameyu/app/vision/ImageEmbeddingAnalyzer.kt`，使用模型目录并暴露稳定模型 SHA。
- 创建：`app/src/main/java/com/frameyu/app/vision/MediaImageDecoder.kt`，受限尺寸解码并归一化方向。
- 创建：`app/src/main/java/com/frameyu/app/analysis/ImageAnalysisCoordinator.kt`，编排图片各阶段。

### 特征和推荐

- 创建：`app/src/main/java/com/frameyu/app/storage/FeatureCache.kt`，原子写入和读取向量。
- 完成：`app/src/main/java/com/frameyu/app/organize/PerceptualHash.kt`，感知哈希兜底。
- 完成：`app/src/main/java/com/frameyu/app/organize/SimilarMediaDuplicateDetector.kt`，相似候选分组。
- 创建：`app/src/main/java/com/frameyu/app/organize/RecommendationEngine.kt`，生成高、中、低置信整理建议。
- 修改：`app/src/main/java/com/frameyu/app/organize/OrganizerRepository.kt`，暴露精确重复、相似和建议查询。

### Worker、视频和 UI

- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`。
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisScheduler.kt`。
- 修改：`app/src/main/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunner.kt`，写入视频帧模型结果。
- 创建：`app/src/main/java/com/frameyu/app/sampling/VideoClassificationAggregator.kt`，聚合视频标签。
- 创建：`app/src/main/java/com/frameyu/app/ui/analysis/BatchAnalysisScreen.kt`。
- 修改：`app/src/main/java/com/frameyu/app/ui/organize/OrganizerScreen.kt`。
- 修改：`app/src/main/java/com/frameyu/app/MainActivity.kt`。

---

### 任务 1：完成感知哈希相似扫描并修复整理确认数据源

**文件：**
- 创建：`app/src/test/java/com/frameyu/app/organize/PerceptualHashTest.kt`
- 完成：`app/src/main/java/com/frameyu/app/organize/PerceptualHash.kt`
- 完成：`app/src/main/java/com/frameyu/app/organize/SimilarMediaDuplicateDetector.kt`
- 创建：`app/src/main/java/com/frameyu/app/organize/AndroidVisualHashReader.kt`
- 完成：`app/src/test/java/com/frameyu/app/organize/SimilarMediaDuplicateDetectorTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/ui/organize/OrganizerScreen.kt`
- 修改：`app/src/main/java/com/frameyu/app/MainActivity.kt`

- [ ] **步骤 1：补充感知哈希稳定性测试**

```kotlin
class PerceptualHashTest {
    @Test
    fun `相同像素产生相同哈希`() {
        val pixels = IntArray(64) { if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertEquals(
            PerceptualHash.fromPixels(8, 8, pixels),
            PerceptualHash.fromPixels(8, 8, pixels.copyOf()),
        )
    }

    @Test
    fun `哈希距离统计不同位数量`() {
        assertEquals(2, PerceptualHash.hammingDistance(0b0001L, 0b0111L))
    }
}
```

- [ ] **步骤 2：运行测试确认当前工作区实现状态**

运行：

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.organize.PerceptualHashTest --tests com.frameyu.app.organize.SimilarMediaDuplicateDetectorTest
```

预期：新增测试在类或行为未完成时失败；不得跳过失败直接提交。

- [ ] **步骤 3：完成相似扫描 UI 数据流**

在 `OrganizerScreen` 增加：

```kotlin
onSimilarDuplicateScan: suspend (List<MediaAssetEntity>) -> SimilarMediaDuplicateResult
```

统一当前展示组：

```kotlin
val displayGroups = when {
    similarResult != null -> similarResult!!.groups
    exactResult != null -> exactResult!!.groups
    else -> metadataGroups
}
```

删除确认必须使用：

```kotlin
val selectedGroups = displayGroups.filter { it.duplicateKey in selectedKeys }
```

禁止继续使用元数据 `groups` 计算精确重复或相似扫描的删除 URI。

- [ ] **步骤 4：实现受限解码的感知哈希读取器并接入 MainActivity**

创建 `AndroidVisualHashReader`：

```kotlin
class AndroidVisualHashReader(
    private val contentResolver: ContentResolver,
) : VisualHashReader {
    override suspend fun readHash(uri: String): Long {
        val sourceUri = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(sourceUri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: error("无法打开媒体：$uri")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片尺寸：$uri" }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 512) sampleSize *= 2
        val bitmap = contentResolver.openInputStream(sourceUri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: error("无法解码媒体：$uri")
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            try {
                val pixels = IntArray(32 * 32)
                scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                PerceptualHash.fromPixels(32, 32, pixels)
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
```

`MainActivity` 使用 `AndroidVisualHashReader(contentResolver)` 创建 `SimilarMediaDuplicateDetector`。

- [ ] **步骤 5：运行整理相关测试和编译**

运行：

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.organize.*' :app:compileDebugKotlin
```

预期：PASS，且 Kotlin 编译成功。

- [ ] **步骤 6：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/organize app/src/test/java/com/frameyu/app/organize app/src/main/java/com/frameyu/app/ui/organize/OrganizerScreen.kt app/src/main/java/com/frameyu/app/MainActivity.kt
git commit -m "feat(P1): 接入感知哈希相似扫描"
```

### 任务 2：建立可复现模型资产管理

**文件：**
- 修改：`.gitignore`
- 创建：`app/src/main/assets/models/model-manifest.properties`
- 创建：`scripts/fetch-models.ps1`
- 创建：`app/src/main/java/com/frameyu/app/vision/model/ModelAssetCatalog.kt`
- 创建：`app/src/main/java/com/frameyu/app/vision/model/ModelAssetVerifier.kt`
- 创建：`app/src/test/java/com/frameyu/app/vision/model/ModelAssetCatalogTest.kt`

- [ ] **步骤 1：编写清单解析和 SHA 校验测试**

```kotlin
class ModelAssetCatalogTest {
    private val testManifest = """
        image_classifier.file=efficientnet_lite2.tflite
        image_classifier.sha256=94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83
        image_classifier.url=https://example.invalid/classifier.tflite
        object_detector.file=efficientdet_lite2.tflite
        object_detector.sha256=AD2ABBF2B4E10585E15176FD7B5EF03C28DDA959AE26FC142549FDD1814DB91D
        object_detector.url=https://example.invalid/detector.tflite
        image_embedder.file=mobilenet_v3_small_075_224_embedder.tflite
        image_embedder.sha256=0F786F8EB8BCECD1F9C5BC50F00A5E6B9F62600EDB503AD3BFFF5F51035B0EA5
        image_embedder.url=https://example.invalid/embedder.tflite
    """.trimIndent()

    @Test
    fun `解析三个模型且保留 SHA`() {
        val catalog = ModelAssetCatalog.parse(testManifest.byteInputStream())
        assertEquals(3, catalog.models.size)
        assertEquals(
            "94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83",
            catalog.require(ModelId.IMAGE_CLASSIFIER).sha256,
        )
    }

    @Test
    fun `校验器拒绝错误内容`() {
        val descriptor = ModelDescriptor(ModelId.IMAGE_EMBEDDER, "embedder.tflite", "00", "https://example.invalid")
        assertFalse(ModelAssetVerifier.matches(descriptor, "wrong".byteInputStream()))
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.vision.model.ModelAssetCatalogTest
```

预期：FAIL，缺少 `ModelAssetCatalog` 和 `ModelAssetVerifier`。

- [ ] **步骤 3：实现纯 Kotlin 清单模型**

```kotlin
enum class ModelId { IMAGE_CLASSIFIER, OBJECT_DETECTOR, IMAGE_EMBEDDER }

data class ModelDescriptor(
    val id: ModelId,
    val fileName: String,
    val sha256: String,
    val url: String,
)

class ModelAssetCatalog private constructor(val models: Map<ModelId, ModelDescriptor>) {
    fun require(id: ModelId): ModelDescriptor = requireNotNull(models[id])

    companion object {
        fun parse(input: InputStream): ModelAssetCatalog {
            val properties = Properties().apply { input.use { load(it) } }
            val models = ModelId.entries.associateWith { id ->
                val key = id.name.lowercase()
                ModelDescriptor(
                    id = id,
                    fileName = properties.getProperty("$key.file").orEmpty(),
                    sha256 = properties.getProperty("$key.sha256").orEmpty().uppercase(),
                    url = properties.getProperty("$key.url").orEmpty(),
                )
            }
            require(models.values.all { it.fileName.isNotBlank() && it.sha256.length == 64 && it.url.startsWith("https://") })
            return ModelAssetCatalog(models)
        }
    }
}
```

校验器使用 `MessageDigest.getInstance("SHA-256")` 分块读取，不一次性加载模型。

- [ ] **步骤 4：创建模型清单和下载脚本**

清单写入以下官方 URL 和固定 SHA：

```properties
image_classifier.file=efficientnet_lite2.tflite
image_classifier.sha256=94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83
image_classifier.url=https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite2/float32/latest/efficientnet_lite2.tflite
object_detector.file=efficientdet_lite2.tflite
object_detector.sha256=AD2ABBF2B4E10585E15176FD7B5EF03C28DDA959AE26FC142549FDD1814DB91D
object_detector.url=https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/latest/efficientdet_lite2.tflite
image_embedder.file=mobilenet_v3_small_075_224_embedder.tflite
image_embedder.sha256=0F786F8EB8BCECD1F9C5BC50F00A5E6B9F62600EDB503AD3BFFF5F51035B0EA5
image_embedder.url=https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite
```

脚本行为：

```powershell
param(
    [string]$SourceDirectory = "module",
    [string]$OutputDirectory = "app/src/main/assets/models",
    [switch]$ValidateOnly
)
```

每个文件先检查 `$SourceDirectory`，存在且 SHA 匹配则复制；否则使用 `Invoke-WebRequest` 下载到临时文件，校验后移动到输出目录。任何 SHA 不匹配时退出码必须非零。

- [ ] **步骤 5：更新 .gitignore**

```gitignore
/module/
/app/src/main/assets/models/*.tflite
```

保留 `model-manifest.properties` 可提交。

- [ ] **步骤 6：运行脚本校验本地模型**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/fetch-models.ps1 -SourceDirectory module
powershell -ExecutionPolicy Bypass -File scripts/fetch-models.ps1 -ValidateOnly
```

预期：三个模型 SHA 全部匹配，输出文件存在；`git status` 不显示 `.tflite`。

- [ ] **步骤 7：运行单元测试并 Commit**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.vision.model.ModelAssetCatalogTest
git add .gitignore app/src/main/assets/models/model-manifest.properties scripts/fetch-models.ps1 app/src/main/java/com/frameyu/app/vision/model app/src/test/java/com/frameyu/app/vision/model
git commit -m "build: 增加本地模型资产校验"
```

### 任务 3：定义批次状态机和阶段依赖

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisModel.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchStateMachine.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchStateMachineTest.kt`

- [ ] **步骤 1：编写状态转换失败测试**

```kotlin
class BatchStateMachineTest {
    @Test
    fun `暂停任务可以重新排队`() {
        assertEquals(
            WorkItemStatus.PENDING,
            BatchStateMachine.transition(WorkItemStatus.PAUSED, WorkItemEvent.RESUME),
        )
    }

    @Test
    fun `完成任务不能再次开始`() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchStateMachine.transition(WorkItemStatus.COMPLETED, WorkItemEvent.START)
        }
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.analysis.batch.BatchStateMachineTest
```

预期：FAIL，缺少批次类型。

- [ ] **步骤 3：实现状态和阶段**

```kotlin
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
```

`BatchStateMachine` 只允许设计规格状态图中的转换。`analyzerVersion` 由阶段名、模型 SHA 和策略版本生成 SHA-256。

- [ ] **步骤 4：运行测试并 Commit**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.analysis.batch.BatchStateMachineTest
git add app/src/main/java/com/frameyu/app/analysis/batch app/src/test/java/com/frameyu/app/analysis/batch
git commit -m "feat(P1): 定义批量分析状态机"
```

### 任务 4：新增 Room 批次、工作项和模型结果表

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisEntities.kt`
- 创建：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisDao.kt`
- 修改：`app/src/main/java/com/frameyu/app/data/db/FrameYuDatabase.kt`
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/data/db/BatchAnalysisDaoTest.kt`

- [ ] **步骤 1：编写 Room 工作项恢复测试**

测试创建批次和两个工作项，把一个更新为 `COMPLETED`，验证 `nextRunnableItems(batchId)` 只返回 `PENDING` 与 `RETRYABLE_FAILED`。

```kotlin
val pending = dao.nextRunnableItems(batchId, limit = 10)
assertEquals(listOf(pendingId, retryableId), pending.map { it.id })
```

- [ ] **步骤 2：运行 AndroidTest 编译验证失败**

```powershell
./gradlew.bat --offline :app:compileDebugAndroidTestKotlin
```

预期：FAIL，缺少实体和 DAO。

- [ ] **步骤 3：实现实体**

使用独立主键并建立唯一索引：

```kotlin
@Entity(
    tableName = "analysis_work_items",
    indices = [
        Index(value = ["batch_id", "media_asset_id", "stage", "analyzer_version"], unique = true),
        Index("status"),
    ],
)
data class AnalysisWorkItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "media_asset_id") val mediaAssetId: Long,
    val stage: String,
    val status: String,
    @ColumnInfo(name = "analyzer_version") val analyzerVersion: String,
    @ColumnInfo(name = "next_timestamp_millis") val nextTimestampMillis: Long = 0,
    val attempts: Int = 0,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)
```

分类表每个 `(mediaId, timestamp, modelSha, rank)` 一行；检测表每个目标一行；特征引用表只保存路径和维度；质量表保存分项和算法版本。

- [ ] **步骤 4：实现 DAO 事务边界**

DAO 必须提供：

```kotlin
suspend fun createBatch(batch: AnalysisBatchEntity, items: List<AnalysisWorkItemEntity>): Long
suspend fun nextRunnableItems(batchId: Long, limit: Int): List<AnalysisWorkItemEntity>
suspend fun markRunning(id: Long, updatedAtMillis: Long)
suspend fun saveCheckpoint(id: Long, nextTimestampMillis: Long, updatedAtMillis: Long)
suspend fun completeItem(id: Long, updatedAtMillis: Long)
suspend fun failItem(id: Long, retryable: Boolean, message: String, updatedAtMillis: Long)
fun observeBatch(batchId: Long): Flow<AnalysisBatchWithProgress>
```

- [ ] **步骤 5：增加 MIGRATION_3_4**

`FrameYuDatabase` 升级到版本 4，迁移显式创建所有新表和索引。`FrameYuApplication` 注册：

```kotlin
.addMigrations(
    FrameYuDatabase.MIGRATION_1_2,
    FrameYuDatabase.MIGRATION_2_3,
    FrameYuDatabase.MIGRATION_3_4,
)
```

- [ ] **步骤 6：运行数据库验证并 Commit**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
git add app/src/main/java/com/frameyu/app/data/db app/src/androidTest/java/com/frameyu/app/data/db app/src/main/java/com/frameyu/app/FrameYuApplication.kt
git commit -m "feat(P1): 持久化批量分析工作项"
```

### 任务 5：接入分类、条件检测和向量模型适配器

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/vision/ImageClassifierAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/vision/ObjectDetectorAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/vision/ObjectDetectionPolicy.kt`
- 创建：`app/src/main/java/com/frameyu/app/vision/FrameModelAnalyzer.kt`
- 修改：`app/src/main/java/com/frameyu/app/vision/ImageEmbeddingAnalyzer.kt`
- 创建：`app/src/test/java/com/frameyu/app/vision/ObjectDetectionPolicyTest.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/vision/BundledVisionModelsTest.kt`

- [ ] **步骤 1：编写目标检测触发策略测试**

```kotlin
class ObjectDetectionPolicyTest {
    @Test
    fun `低置信分类触发目标检测`() {
        assertTrue(ObjectDetectionPolicy().shouldDetect(listOf(LabelScore("person", 0.40f)), highValue = false))
    }

    @Test
    fun `高置信普通场景不触发检测`() {
        assertFalse(ObjectDetectionPolicy().shouldDetect(listOf(LabelScore("lakeside", 0.90f)), highValue = false))
    }
}
```

覆盖最高置信度 `< 0.45`、前两名差值 `< 0.10`、主体标签和高价值候选四个条件。

- [ ] **步骤 2：运行策略测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.vision.ObjectDetectionPolicyTest
```

预期：FAIL。

- [ ] **步骤 3：实现稳定结果模型和策略**

```kotlin
data class LabelScore(val label: String, val score: Float, val index: Int = -1)
data class DetectionObservation(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class FrameModelResult(
    val labels: List<LabelScore>,
    val embedding: FloatArray?,
    val detections: List<DetectionObservation>,
    val failures: List<String>,
)

fun interface FrameModelAnalyzer {
    suspend fun analyze(bitmap: Bitmap, timestampMillis: Long, highValue: Boolean): FrameModelResult
}

fun interface FrameModelResultStore {
    suspend fun saveFrameResult(mediaId: Long, timestampMillis: Long, result: FrameModelResult)
}
```

所有坐标归一化到 `0f..1f`，分析器不把 MediaPipe 类型泄漏给数据库和 UI。

- [ ] **步骤 4：实现 MediaPipe 适配器**

分类器使用 `RunningMode.IMAGE` 处理图片和独立视频采样帧；视频时间关系由现有采样层维护。分类器选项：

```kotlin
ImageClassifier.ImageClassifierOptions.builder()
    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelPath).build())
    .setRunningMode(RunningMode.IMAGE)
    .setMaxResults(5)
    .build()
```

目标检测器使用 `setMaxResults(10)` 和 `setScoreThreshold(0.30f)`。Embedder 保持 L2 归一化、float32 输出。

- [ ] **步骤 5：编写 assets 模型仪器测试**

测试逐个打开模型、校验 SHA、初始化任务，并用 224×224 纯色 Bitmap 验证返回结构或明确的空结果，不要求具体标签。

- [ ] **步骤 6：运行模型测试编译和 JVM 测试**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.vision.*' :app:compileDebugAndroidTestKotlin
```

预期：PASS。真机推理留到最终验收。

- [ ] **步骤 7：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/vision app/src/test/java/com/frameyu/app/vision app/src/androidTest/java/com/frameyu/app/vision
git commit -m "feat(P1): 接入本地图像分类和目标检测"
```

### 任务 6：实现特征缓存和单张图片分析协调器

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/storage/FeatureCache.kt`
- 创建：`app/src/main/java/com/frameyu/app/vision/MediaImageDecoder.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/ImageAnalysisCoordinator.kt`
- 创建：`app/src/test/java/com/frameyu/app/storage/FeatureCacheTest.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/ImageAnalysisCoordinatorTest.kt`

- [ ] **步骤 1：编写原子向量缓存测试**

```kotlin
@Test
fun `向量写入后可以按模型和媒体读取`() {
    val cache = FeatureCache(tempFolder.root)
    cache.write("model-sha", 42L, floatArrayOf(0.1f, 0.2f))
    assertArrayEquals(floatArrayOf(0.1f, 0.2f), cache.read("model-sha", 42L), 0f)
}
```

同时验证写入失败不留下最终文件，非法 SHA 和媒体 ID 被拒绝。

- [ ] **步骤 2：运行缓存测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.storage.FeatureCacheTest
```

预期：FAIL。

- [ ] **步骤 3：实现 FeatureCache**

路径固定为：

```text
files/features/<model-sha>/<media-id>.f32
```

格式为 4 字节维度头加 little-endian float32。先写 `.tmp`，`File.renameTo` 成功后才返回最终路径。

- [ ] **步骤 4：编写协调器阶段复用测试**

注入假分类器、Embedder、质量分析器和 DAO，第一次执行全部阶段；第二次使用相同模型 SHA 时断言分类器和 Embedder 调用次数不增加。

- [ ] **步骤 5：实现受限解码和协调器**

`MediaImageDecoder.decode(uri, maxEdge = 1024)`：

- 先读取 bounds。
- 计算 `inSampleSize`。
- 第二次打开 URI 解码。
- 按 EXIF/媒体方向旋转。
- 调用方 `close` 或 `recycle`。

`ImageAnalysisCoordinator.analyze(workItem)` 每完成一个阶段立即事务提交，异常只失败当前工作项。

- [ ] **步骤 6：运行测试和编译**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.storage.FeatureCacheTest --tests com.frameyu.app.analysis.ImageAnalysisCoordinatorTest :app:compileDebugKotlin
```

- [ ] **步骤 7：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/storage app/src/main/java/com/frameyu/app/vision/MediaImageDecoder.kt app/src/main/java/com/frameyu/app/analysis/ImageAnalysisCoordinator.kt app/src/test/java/com/frameyu/app/storage app/src/test/java/com/frameyu/app/analysis
git commit -m "feat(P1): 实现图片模型分析协调器"
```

### 任务 7：实现可恢复批量 Worker 和调度器

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisScheduler.kt`
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisRunnerTest.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisSchedulerTest.kt`

- [ ] **步骤 1：编写恢复和失败隔离测试**

使用内存假 DAO：

```kotlin
val result = runner.run(batchId)
assertEquals(2, result.completed)
assertEquals(1, result.failed)
assertEquals(WorkItemStatus.COMPLETED, dao.item(successId).status)
assertEquals(WorkItemStatus.RETRYABLE_FAILED, dao.item(failedId).status)
```

第二次运行只重试失败项。

- [ ] **步骤 2：运行测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.*'
```

- [ ] **步骤 3：实现 Runner**

Runner 每次最多获取固定数量工作项，逐项：

```kotlin
dao.markRunning(item.id, now())
runCatching { coordinator.run(item) }
    .onSuccess { dao.completeItem(item.id, now()) }
    .onFailure { error ->
        dao.failItem(item.id, error is IOException, error.message ?: "分析失败", now())
    }
```

协程取消必须重新抛出，不标记永久失败。

- [ ] **步骤 4：实现 WorkManager Worker 和前台通知**

输入只携带 `batch_id`。进度 Data 包含完成、失败和总数。通知显示当前阶段和媒体数量，前台服务类型继续使用 `dataSync`。

- [ ] **步骤 5：实现唯一批次串行调度**

```kotlin
private const val UNIQUE_BATCH_WORK = "frameyu-batch-analysis"
```

使用 `ExistingWorkPolicy.APPEND_OR_REPLACE`；提供 `enqueue(batchId)`、`pause(batchId)`、`resume(batchId)`、`cancel(batchId)` 和 `retryFailures(batchId)`。

- [ ] **步骤 6：运行调度测试和构建**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.*' :app:assembleDebug
```

- [ ] **步骤 7：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/analysis/batch app/src/test/java/com/frameyu/app/analysis/batch app/src/main/java/com/frameyu/app/FrameYuApplication.kt
git commit -m "feat(P1): 实现可恢复批量分析任务"
```

### 任务 8：把模型管线接入视频采样和标签聚合

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/sampling/VideoClassificationAggregator.kt`
- 创建：`app/src/test/java/com/frameyu/app/sampling/VideoClassificationAggregatorTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunner.kt`
- 修改：`app/src/main/java/com/frameyu/app/sampling/AnalysisCandidateMapper.kt`
- 使用：`app/src/main/java/com/frameyu/app/vision/FrameModelAnalyzer.kt`

- [ ] **步骤 1：编写视频标签聚合测试**

```kotlin
@Test
fun `偶发单帧标签不会覆盖持续标签`() {
    val result = VideoClassificationAggregator().aggregate(
        listOf(
            TimedLabels(0, listOf(LabelScore("concert", 0.8f))),
            TimedLabels(1_000, listOf(LabelScore("concert", 0.9f))),
            TimedLabels(2_000, listOf(LabelScore("bird", 0.99f))),
        ),
    )
    assertEquals("concert", result.first().label)
}
```

- [ ] **步骤 2：运行测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.sampling.VideoClassificationAggregatorTest
```

- [ ] **步骤 3：实现聚合器**

每个标签计算：

```text
聚合分 = 0.5 × 出现帧比例 + 0.3 × 平均置信度 + 0.2 × 最高置信度
```

至少出现两帧或覆盖率达到 20% 才进入视频级标签；采样帧不足两帧时允许单帧结果。

- [ ] **步骤 4：注入 FrameModelAnalyzer**

在现有规则评分之后：

```kotlin
val modelResult = frameModelAnalyzer.analyze(
    bitmap = analysisBitmap,
    timestampMillis = timestampMillis,
    highValue = score.totalScore >= 70,
)
frameModelResultStore.saveFrameResult(media.id, timestampMillis, modelResult)
```

目标检测由 `ObjectDetectionPolicy` 决定。每个成功帧保存模型结果后，再保存下一时间断点，避免断点越过未提交结果。

- [ ] **步骤 5：保证模型失败不丢失规则候选**

模型异常记录到帧结果错误字段；规则候选仍写入 `analysis_candidates`。模型全局初始化失败时该模型阶段标记可重试，不使视频规则分析失败。

- [ ] **步骤 6：运行采样和模型测试**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.sampling.*' --tests 'com.frameyu.app.vision.*' :app:assembleDebug
```

- [ ] **步骤 7：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/sampling app/src/test/java/com/frameyu/app/sampling
git commit -m "feat(P1): 接入视频帧模型分析"
```

### 任务 9：实现推荐引擎、批次页面和统一人工确认

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/organize/RecommendationEngine.kt`
- 创建：`app/src/test/java/com/frameyu/app/organize/RecommendationEngineTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/organize/OrganizerRepository.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/analysis/BatchAnalysisScreen.kt`
- 修改：`app/src/main/java/com/frameyu/app/ui/organize/OrganizerScreen.kt`
- 修改：`app/src/main/java/com/frameyu/app/MainActivity.kt`

- [ ] **步骤 1：编写推荐置信等级测试**

```kotlin
@Test
fun `完全重复为高置信且低质量不会默认选中`() {
    val exact = engine.exactDuplicateRecommendation(group)
    val lowQuality = engine.lowQualityRecommendation(asset, score = 20)
    assertEquals(RecommendationConfidence.HIGH, exact.confidence)
    assertTrue(exact.defaultSelected)
    assertEquals(RecommendationConfidence.LOW, lowQuality.confidence)
    assertFalse(lowQuality.defaultSelected)
}
```

- [ ] **步骤 2：运行测试验证失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.organize.RecommendationEngineTest
```

- [ ] **步骤 3：实现推荐规则**

```kotlin
enum class RecommendationConfidence { HIGH, MEDIUM, LOW }
enum class RecommendationType { EXACT_DUPLICATE, SIMILAR_MEDIA, LOW_QUALITY }

data class OrganizationRecommendation(
    val type: RecommendationType,
    val confidence: RecommendationConfidence,
    val mediaIds: List<Long>,
    val suggestedKeepId: Long?,
    val defaultSelected: Boolean,
)
```

只有完全重复可默认选中待删除副本；相似和低质量默认不选。

- [ ] **步骤 4：实现批次状态页面**

页面观察 Room 批次进度，显示总数、完成、失败、当前阶段，并提供暂停、继续、取消和重试失败项。页面状态只持有 ID 和统计值，不持有 Bitmap。

- [ ] **步骤 5：统一整理页面数据源**

筛选项包括完全重复、相似、低质量、截图、录屏和视频精彩候选。删除对话框必须根据当前可见且选中的推荐计算 URI。建议保留项默认锁定。

- [ ] **步骤 6：接入 MainActivity 导航**

媒体库新增“批量深度分析”入口；批次完成后可进入整理页。现有候选帧导出页面保持不变。

- [ ] **步骤 7：运行 UI 纯逻辑测试和构建**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.organize.*' --tests 'com.frameyu.app.ui.*' :app:assembleDebug
```

- [ ] **步骤 8：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/organize app/src/test/java/com/frameyu/app/organize app/src/main/java/com/frameyu/app/ui app/src/main/java/com/frameyu/app/MainActivity.kt
git commit -m "feat(P1): 增加批量分析和整理确认页面"
```

### 任务 10：文档、全量验证和真机交接点

**文件：**
- 修改：`docs/mvp-foundation.md`
- 修改：`docs/superpowers/plans/2026-07-27-batch-media-analysis-pipeline.md`

- [ ] **步骤 1：运行模型脚本验证**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/fetch-models.ps1 -ValidateOnly
```

预期：三个模型 SHA 匹配。

- [ ] **步骤 2：运行完整离线验证**

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME='D:\flutter\sdk\android'
$env:ANDROID_SDK_ROOT='D:\flutter\sdk\android'
./gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

预期：`BUILD SUCCESSFUL`。

- [ ] **步骤 3：检查版本控制状态**

```powershell
git diff --check
git status --short
```

预期：只有本任务文档修改；模型二进制和 `module/` 不出现。

- [ ] **步骤 4：更新里程碑文档**

记录：

- 三个模型及 SHA。
- 批次状态机和 Room 版本。
- 图片与视频模型阶段。
- 已运行的测试命令。
- 尚未执行的 Redmi K70 Pro 性能、温控和动态照片验证。

- [ ] **步骤 5：Commit**

```powershell
git add docs/mvp-foundation.md docs/superpowers/plans/2026-07-27-batch-media-analysis-pipeline.md
git commit -m "docs: 更新批量模型分析里程碑"
```

- [ ] **步骤 6：请求真机接入**

只有上述离线验证全部通过后，才请用户连接 Redmi K70 Pro。真机阶段运行模型 assets 仪器测试、1 万级媒体恢复测试、演唱会长视频温控测试和 HyperOS 动态照片验证。
