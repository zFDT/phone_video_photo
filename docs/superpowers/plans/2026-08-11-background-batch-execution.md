# 帧遇后台批量分析与暂停恢复实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让图片批次和视频分析在帧遇进入后台后以较低负载继续运行，支持通知暂停、通知返回批次页、可靠断点恢复，并准确区分失败工作项和失败媒体数量。

**架构：** 保留现有 WorkManager 唯一串行链和 `SystemForegroundService`，通过进程可见性跟踪器与纯 Kotlin 节流策略在安全 checkpoint 后让出 CPU。通知操作复用现有 scheduler 和 Room 控制态；MainActivity 只消费显式批次 Intent，不持有后台任务。损坏媒体继续按永久失败隔离，不自动重写原文件。

**技术栈：** Kotlin、Coroutines、WorkManager 2.11.2、Room 2.7.2、Jetpack Compose、Android Notification、JUnit 4、AndroidX Test。

---

## 文件结构

### 应用可见性与节流

- 创建：`app/src/main/java/com/frameyu/app/system/AppVisibilityTracker.kt`，把 Activity 生命周期转换为线程安全的进程可见状态。
- 创建：`app/src/main/java/com/frameyu/app/system/BackgroundPacingPolicy.kt`，定义前台、后台和温控状态的延迟决策。
- 创建：`app/src/test/java/com/frameyu/app/system/AppVisibilityTrackerTest.kt`，覆盖 started Activity 计数。
- 创建：`app/src/test/java/com/frameyu/app/system/BackgroundPacingPolicyTest.kt`，覆盖 200 ms 后台策略和重模型阶段筛选。
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`，注册 tracker 并注入图片、视频执行器。

### 批次与视频执行

- 修改：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`，在已提交的重模型工作项后调用 pacer。
- 修改：`app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisRunnerTest.kt`，验证节流发生在持久化完成后，并跳过轻量阶段和受控暂停。
- 修改：`app/src/main/java/com/frameyu/app/sampling/VideoAnalysisTask.kt`，增加 checkpoint 提交后回调。
- 修改：`app/src/test/java/com/frameyu/app/sampling/VideoAnalysisTaskTest.kt`，验证回调顺序与取消传播。
- 修改：`app/src/main/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunner.kt`，在每个视频 checkpoint 后应用后台节流。
- 修改：`app/src/androidTest/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunnerTest.kt`，验证视频 pacer 不越过 checkpoint。

### 通知、路由与 UI

- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchNotificationPresentation.kt`，生成模式、失败数和 ETA 文案。
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchNotificationPresentationTest.kt`，覆盖 ETA 阈值和模式切换。
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisActionReceiver.kt`，处理通知暂停。
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisIntents.kt`，集中构造和解析显式通知 Intent。
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisNotificationFactory.kt`，集中创建可测试的通知和 PendingIntent。
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisIntentsTest.kt`，覆盖批次 ID 校验。
- 创建：`app/src/androidTest/java/com/frameyu/app/analysis/batch/BatchAnalysisNotificationTest.kt`，检查通知 action 和 Receiver 暂停结果。
- 修改：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`，接入通知文案和两个 PendingIntent。
- 修改：`app/src/main/java/com/frameyu/app/MainActivity.kt`，在 `onCreate`/`onNewIntent` 打开指定批次。
- 修改：`app/src/main/AndroidManifest.xml`，注册非导出的 Receiver。
- 修改：`app/src/main/java/com/frameyu/app/ui/analysis/BatchAnalysisPresentation.kt`，暴露失败媒体数和后台说明。
- 修改：`app/src/main/java/com/frameyu/app/ui/analysis/BatchAnalysisScreen.kt`，显示运行/暂停说明及“工作项/媒体”失败统计。
- 修改：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisDao.kt`，汇总失败媒体去重数量。
- 修改：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisEntities.kt`，承接 `failed_media_count` 查询列，不增加数据库表。
- 修改：`app/src/test/java/com/frameyu/app/ui/analysis/BatchAnalysisPresentationTest.kt`，覆盖一张媒体多阶段失败的文案。
- 修改：`app/src/androidTest/java/com/frameyu/app/data/db/BatchAnalysisDaoTest.kt`，验证 `COUNT(DISTINCT media_asset_id)`。

### 文档与验收

- 修改：`docs/mvp-foundation.md`，记录后台语义、损坏 PNG 根因和真机结果。
- 修改：`docs/superpowers/specs/2026-08-11-background-batch-execution-design.md`，只在实现证据与规格出现差异时同步修正。
- 修改：本计划，完成后勾选任务并写入验证数据。

---

### 任务 1：建立进程可见性与后台节流策略

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/system/AppVisibilityTracker.kt`
- 创建：`app/src/main/java/com/frameyu/app/system/BackgroundPacingPolicy.kt`
- 创建：`app/src/test/java/com/frameyu/app/system/AppVisibilityTrackerTest.kt`
- 创建：`app/src/test/java/com/frameyu/app/system/BackgroundPacingPolicyTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`

- [x] **步骤 1：编写可见性计数失败测试**

```kotlin
class AppVisibilityTrackerTest {
    @Test
    fun `无已启动页面时视为后台且多个页面按计数保持前台`() {
        val state = AppVisibilityState()
        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        state.onActivityStarted()
        state.onActivityStarted()
        state.onActivityStopped()
        assertEquals(AppVisibility.FOREGROUND, state.visibility)
        state.onActivityStopped()
        assertEquals(AppVisibility.BACKGROUND, state.visibility)
    }

    @Test
    fun `重复 stopped 不会产生负计数`() {
        val state = AppVisibilityState()
        state.onActivityStopped()
        assertEquals(AppVisibility.BACKGROUND, state.visibility)
        assertEquals(0, state.startedActivityCount)
    }
}
```

- [x] **步骤 2：编写节流策略失败测试**

```kotlin
@Test
fun `后台只节流重模型阶段`() {
    val policy = BackgroundPacingPolicy(backgroundDelayMillis = 200L)
    assertEquals(200L, policy.delayAfter(AppVisibility.BACKGROUND, isHeavyUnit = true))
    assertEquals(0L, policy.delayAfter(AppVisibility.BACKGROUND, isHeavyUnit = false))
    assertEquals(0L, policy.delayAfter(AppVisibility.FOREGROUND, isHeavyUnit = true))
}
```

- [x] **步骤 3：运行测试确认失败**

运行：

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.system.AppVisibilityTrackerTest' --tests 'com.frameyu.app.system.BackgroundPacingPolicyTest'
```

预期：FAIL，缺少 `AppVisibilityState` 和 `BackgroundPacingPolicy`。

- [x] **步骤 4：实现最小可见性边界**

```kotlin
enum class AppVisibility { FOREGROUND, BACKGROUND }

class AppVisibilityState {
    private val started = AtomicInteger(0)
    val startedActivityCount: Int get() = started.get()
    val visibility: AppVisibility
        get() = if (started.get() > 0) AppVisibility.FOREGROUND else AppVisibility.BACKGROUND

    fun onActivityStarted() = started.incrementAndGet()
    fun onActivityStopped() {
        started.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }
}
```

`ProcessAppVisibilityTracker` 实现 `Application.ActivityLifecycleCallbacks`，只在 `onActivityStarted` 和 `onActivityStopped` 更新上述状态，其余回调为空实现。

- [x] **步骤 5：实现纯节流策略并在 Application 注册**

```kotlin
class BackgroundPacingPolicy(
    private val backgroundDelayMillis: Long = 200L,
) {
    fun delayAfter(visibility: AppVisibility, isHeavyUnit: Boolean): Long =
        if (visibility == AppVisibility.BACKGROUND && isHeavyUnit) {
            backgroundDelayMillis
        } else {
            0L
        }
}
```

`FrameYuApplication.onCreate()` 调用 `registerActivityLifecycleCallbacks(appVisibilityTracker)`；tracker 属性在 Worker factory 和 runner 创建前可读取。

- [x] **步骤 6：运行测试和编译**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.system.*' :app:compileDebugKotlin
```

预期：PASS，Kotlin 编译成功。

- [x] **步骤 7：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/system app/src/test/java/com/frameyu/app/system app/src/main/java/com/frameyu/app/FrameYuApplication.kt
git commit -m "feat(P1): 增加后台分析节流策略"
```

### 任务 2：在图片批次安全边界应用节流

**文件：**
- 修改：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`
- 修改：`app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisRunnerTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`

- [x] **步骤 1：编写持久化顺序失败测试**

在 `BatchAnalysisRunnerTest` 创建一个 `CLASSIFICATION` 工作项，记录事件：

```kotlin
val events = mutableListOf<String>()
val runner = BatchAnalysisRunner(
    store = store(events),
    coordinator = BatchWorkItemCoordinator { events += "analyze" },
    pacer = BatchWorkPacer { stage -> events += "pace:$stage" },
)
runner.run(BATCH_ID)
assertTrue(events.indexOf("store.complete") < events.indexOf("pace:CLASSIFICATION"))
```

再验证 `QUALITY` 不调用 pacer，以及 pause/cancel 控制态被观察到后不调用 pacer。

- [x] **步骤 2：运行测试确认失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.BatchAnalysisRunnerTest'
```

预期：FAIL，`BatchAnalysisRunner` 尚无 `pacer` 参数。

- [x] **步骤 3：实现批次 pacer 边界**

```kotlin
fun interface BatchWorkPacer {
    suspend fun awaitAfter(stage: String)
}

class BatchAnalysisRunner(
    private val store: BatchAnalysisStore,
    private val coordinatorProvider: () -> BatchWorkItemCoordinator,
    private val pacer: BatchWorkPacer = BatchWorkPacer {},
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
)
```

只有工作项已经 `completeItem` 或 `failItem` 成功、进度已发布、控制态仍为当前 `RUNNING` generation 时才调用 `pacer.awaitAfter(item.stage)`。依赖未完成、旧 generation、暂停和取消路径不得人为等待。

- [x] **步骤 4：在 Application 注入可取消 delay**

```kotlin
pacer = BatchWorkPacer { stage ->
    val waitMillis = backgroundPacingPolicy.delayAfter(
        appVisibilityTracker.visibility,
        isHeavyUnit = stage in HEAVY_IMAGE_STAGES,
    )
    if (waitMillis > 0L) delay(waitMillis)
}
```

不得捕获 `CancellationException`。

- [x] **步骤 5：运行批次回归测试**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.*' :app:compileDebugKotlin
```

预期：PASS，现有暂停、generation、恢复和通知节流测试均通过。

- [x] **步骤 6：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisRunnerTest.kt app/src/main/java/com/frameyu/app/FrameYuApplication.kt
git commit -m "feat(P1): 批次后台运行时主动让出 CPU"
```

### 任务 3：在视频 checkpoint 后应用节流

**文件：**
- 修改：`app/src/main/java/com/frameyu/app/sampling/VideoAnalysisTask.kt`
- 修改：`app/src/test/java/com/frameyu/app/sampling/VideoAnalysisTaskTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunner.kt`
- 修改：`app/src/androidTest/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunnerTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`

- [x] **步骤 1：编写 checkpoint 后回调顺序测试**

```kotlin
@Test
fun `节流回调发生在 checkpoint 保存和进度发布之后`() = runBlocking {
    val events = mutableListOf<String>()
    VideoAnalysisTask(SamplingCheckpointStore { events += "save" }).run(
        plan = SamplingPlan.create(1L),
        onProgress = { events += "progress" },
        onCheckpointCommitted = { events += "pace" },
    ) { events += "frame" }
    assertEquals(listOf("frame", "save", "progress", "pace"), events)
}
```

增加一个 `onCheckpointCommitted` 抛出 `CancellationException` 的测试，断言同一实例原样传播。

- [x] **步骤 2：运行测试确认失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.sampling.VideoAnalysisTaskTest'
```

预期：FAIL，缺少 `onCheckpointCommitted` 参数。

- [x] **步骤 3：实现 checkpoint 后回调**

```kotlin
suspend fun run(
    plan: SamplingPlan,
    checkpoint: SamplingCheckpoint? = null,
    onProgress: suspend (VideoAnalysisProgress) -> Unit = {},
    shouldContinue: suspend () -> Boolean = { true },
    onCheckpointCommitted: suspend (VideoAnalysisProgress) -> Unit = {},
    onTimestamp: suspend (Long) -> Unit,
): VideoAnalysisTaskResult
```

固定顺序为 `checkpointStore.save(progress)`、`onProgress(progress)`、`onCheckpointCommitted(progress)`。任何节流取消不得改写已保存 checkpoint。

- [x] **步骤 4：给视频 runner 注入 pacer**

`RoomVideoAnalysisWorkerRunner` 增加 `afterCheckpoint: suspend () -> Unit = {}`。生产注入读取 tracker，并以 `isHeavyUnit = true` 调用同一 `BackgroundPacingPolicy`；后台等待 200 ms，前台不等待。调用前复查 taskId + generation 仍为 `RUNNING`，温控等待继续由 `awaitThermalWindow` 独立处理且优先。

- [x] **步骤 5：编写并运行 Room runner 仪器测试**

测试用内存 Room 和假帧源执行两个采样点，记录 `save checkpoint` 后才出现 `pace`，并验证控制态改变后不再 pace。

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.sampling.VideoAnalysisTaskTest' :app:compileDebugAndroidTestKotlin
adb shell am instrument -w -e class com.frameyu.app.sampling.RoomVideoAnalysisWorkerRunnerTest com.frameyu.app.test/androidx.test.runner.AndroidJUnitRunner
```

预期：JVM 测试 PASS；仪器测试 `OK` 且 0 failures。

- [x] **步骤 6：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/sampling/VideoAnalysisTask.kt app/src/test/java/com/frameyu/app/sampling/VideoAnalysisTaskTest.kt app/src/main/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunner.kt app/src/androidTest/java/com/frameyu/app/sampling/RoomVideoAnalysisWorkerRunnerTest.kt app/src/main/java/com/frameyu/app/FrameYuApplication.kt
git commit -m "feat(P1): 长视频按帧断点后台节流"
```

### 任务 4：增加后台模式和粗略 ETA 通知文案

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchNotificationPresentation.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchNotificationPresentationTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`

- [x] **步骤 1：编写 ETA 失败测试**

```kotlin
@Test
fun `不足三十项或三十秒不显示ETA且模式切换重置窗口`() {
    val estimator = BatchEtaEstimator(minSamples = 30, minWindowMillis = 30_000L)
    estimator.record(processed = 0, total = 100, mode = AnalysisRunMode.FOREGROUND, nowMillis = 0L)
    assertNull(estimator.record(29, 100, AnalysisRunMode.FOREGROUND, 30_000L))
    assertNotNull(estimator.record(30, 100, AnalysisRunMode.FOREGROUND, 30_000L))
    assertNull(estimator.record(31, 100, AnalysisRunMode.BACKGROUND_FRIENDLY, 31_000L))
}
```

另测小时级和分钟级文案，不显示秒级伪精度。

- [x] **步骤 2：运行测试确认失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.BatchNotificationPresentationTest'
```

预期：FAIL，缺少 estimator 和 presentation。

- [x] **步骤 3：实现单调时钟估算器和文案**

```kotlin
enum class AnalysisRunMode { FOREGROUND, BACKGROUND_FRIENDLY }

data class BatchNotificationText(
    val title: String,
    val content: String,
)
```

`BatchEtaEstimator` 按当前模式保存窗口起点和 processed 基线；模式变化或 processed 回退时重置。`remainingMillis = remainingItems / itemsPerMillis`，结果只格式化为“约 N 分钟”或“约 N 小时”。

- [x] **步骤 4：接入 Worker**

Worker 使用 `SystemClock.elapsedRealtime()`，每次通过现有 1 秒通知节流后读取 tracker 状态、更新 estimator，并生成：

```text
图片分类 · 后台友好：12820/37012 · 失败 4 · 约 2 小时
```

准备阶段和 ETA 样本不足时省略对应字段。

- [x] **步骤 5：运行通知与批次测试**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.*' :app:compileDebugKotlin
```

预期：PASS。

- [x] **步骤 6：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/analysis/batch/BatchNotificationPresentation.kt app/src/test/java/com/frameyu/app/analysis/batch/BatchNotificationPresentationTest.kt app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt
git commit -m "feat(P1): 显示后台模式与批次预计耗时"
```

### 任务 5：增加通知暂停与打开批次操作

**文件：**
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisActionReceiver.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisIntents.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisNotificationFactory.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/batch/BatchAnalysisIntentsTest.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/analysis/batch/BatchAnalysisNotificationTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/analysis/batch/BatchAnalysisWorker.kt`
- 修改：`app/src/main/java/com/frameyu/app/MainActivity.kt`
- 修改：`app/src/main/AndroidManifest.xml`

- [x] **步骤 1：编写 Intent 解析失败测试**

`BatchAnalysisIntents` 的纯边界只接受正数批次 ID：

```kotlin
assertEquals(42L, validBatchId(42L))
assertNull(validBatchId(0L))
assertNull(validBatchId(-1L))
```

- [x] **步骤 2：运行测试确认失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.BatchAnalysisIntentsTest'
```

预期：FAIL，缺少 Intent 边界。

- [x] **步骤 3：实现显式 Intent 与 Receiver**

```kotlin
class BatchAnalysisActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val batchId = BatchAnalysisIntents.batchId(intent) ?: return
        if (intent.action != BatchAnalysisIntents.ACTION_PAUSE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                (context.applicationContext as FrameYuApplication)
                    .batchAnalysisWorkScheduler.pause(batchId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "通知暂停失败 batchId=$batchId", error)
            } finally {
                pending.finish()
            }
        }
    }
}
```

Manifest 注册 `android:exported="false"`。Pause Intent 显式指定 Receiver；Open Intent 显式指定 MainActivity。全部 PendingIntent 使用 `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`。

- [x] **步骤 4：接入通知 action 与 Activity 路由**

通知增加：

```kotlin
.setContentIntent(openPendingIntent)
.addAction(0, "暂停", pausePendingIntent)
.addAction(0, "打开帧遇", openPendingIntent)
```

`MainActivity` 在 `onCreate` 和 `onNewIntent` 调用同一 `openBatchFromIntent(intent)`；有效批次 ID 写入 `selectedBatchId`，同时关闭 organizer/settings/candidate 状态。

- [x] **步骤 5：编写 Android 通知测试**

通过 `ApplicationProvider` 调用 `BatchAnalysisNotificationFactory` 构造通知并断言两个 action 的标题和 PendingIntent 非空。创建一个 `QUEUED` 测试批次，显式发送暂停广播，条件轮询 Room 直到 `PAUSED`；无效批次 ID 不改变测试批次。

- [ ] **步骤 6：运行测试**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.analysis.batch.*' :app:assembleDebugAndroidTest
adb shell am instrument -w -e class com.frameyu.app.analysis.batch.BatchAnalysisNotificationTest com.frameyu.app.test/androidx.test.runner.AndroidJUnitRunner
```

预期：JVM PASS；仪器测试 `OK` 且 0 failures。

- [x] **步骤 7：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/analysis/batch app/src/test/java/com/frameyu/app/analysis/batch app/src/androidTest/java/com/frameyu/app/analysis/batch app/src/main/java/com/frameyu/app/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(P1): 增加批次通知暂停与返回入口"
```

### 任务 6：澄清失败媒体数量与后台交互文案

**文件：**
- 修改：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisDao.kt`
- 修改：`app/src/main/java/com/frameyu/app/data/db/BatchAnalysisEntities.kt`
- 修改：`app/src/androidTest/java/com/frameyu/app/data/db/BatchAnalysisDaoTest.kt`
- 修改：`app/src/main/java/com/frameyu/app/ui/analysis/BatchAnalysisPresentation.kt`
- 修改：`app/src/main/java/com/frameyu/app/ui/analysis/BatchAnalysisScreen.kt`
- 修改：`app/src/test/java/com/frameyu/app/ui/analysis/BatchAnalysisPresentationTest.kt`

- [x] **步骤 1：编写一张媒体多阶段失败测试**

DAO 测试为同一 `media_asset_id` 创建四个失败工作项，另一个媒体创建一个失败工作项，断言：

```kotlin
assertEquals(5L, progress.failedWorkItems)
assertEquals(2L, progress.failedMediaCount)
```

Presentation 测试断言 `failureCountLabel == "失败工作项：4（涉及 1 个媒体）"`。

- [x] **步骤 2：运行测试确认失败**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.ui.analysis.BatchAnalysisPresentationTest' :app:compileDebugAndroidTestKotlin
```

预期：FAIL，查询结果和 UI state 尚无 `failedMediaCount`。

- [x] **步骤 3：实现 SQL 去重汇总**

在 `observeBatch` SELECT 增加：

```sql
COUNT(DISTINCT CASE
    WHEN w.status IN ('RETRYABLE_FAILED', 'PERMANENT_FAILED')
    THEN w.media_asset_id
END) AS failed_media_count
```

`AnalysisBatchWithProgress` 增加映射字段，不修改 Room schema，不升级数据库版本。

- [x] **步骤 4：更新 UI 文案**

运行时页面显示：

```text
失败工作项：4（涉及 1 个媒体）
可以切换到其他应用，分析会通过通知继续运行；后台时会自动降低速度。
```

暂停时显示：

```text
已保存当前进度。退出帧遇后仍会保持暂停，下次打开可继续。
```

失败详情仍按工作项展示阶段和错误；不得把损坏原图自动转码或覆盖。

- [ ] **步骤 5：运行 JVM 与 DAO 真机测试**

```powershell
./gradlew.bat --offline :app:testDebugUnitTest --tests 'com.frameyu.app.ui.analysis.*' :app:assembleDebugAndroidTest
adb shell am instrument -w -e class com.frameyu.app.data.db.BatchAnalysisDaoTest com.frameyu.app.test/androidx.test.runner.AndroidJUnitRunner
```

预期：PASS；DAO instrumentation `OK` 且 0 failures。

- [x] **步骤 6：Commit**

```powershell
git add app/src/main/java/com/frameyu/app/data/db/BatchAnalysisDao.kt app/src/main/java/com/frameyu/app/data/db/BatchAnalysisEntities.kt app/src/androidTest/java/com/frameyu/app/data/db/BatchAnalysisDaoTest.kt app/src/main/java/com/frameyu/app/ui/analysis app/src/test/java/com/frameyu/app/ui/analysis
git commit -m "fix(P1): 区分失败工作项与失败媒体"
```

### 任务 7：全量验证、覆盖安装与后台真机验收

**文件：**
- 修改：`docs/mvp-foundation.md`
- 修改：`docs/superpowers/plans/2026-08-11-background-batch-execution.md`

- [x] **步骤 1：运行完整 JVM、构建和差异检查**

```powershell
./gradlew.bat --offline --no-daemon '-Dkotlin.compiler.execution.strategy=in-process' :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks
git diff --check
```

预期：`BUILD SUCCESSFUL`，0 JVM failures，`git diff --check` 无输出。

- [ ] **步骤 2：覆盖安装，不卸载、不清数据**

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

预期：两次均输出 `Success`；MediaStore 权限和批次 #1 保留。

- [ ] **步骤 3：运行全部 instrumentation 测试**

使用显式 class 列表避免自动发现卡住，至少覆盖新增通知测试、DAO、视频 runner、模型资产和既有 54 项套件。每组输出必须为 `OK`，总 failure 为 0。

- [ ] **步骤 4：验证后台持续运行**

记录批次完成数 A，执行：

```powershell
adb shell input keyevent KEYCODE_HOME
```

在不启动其他压力测试的情况下等待 60 秒，再读取 UI/Room 可见进度 B。验收 `B > A`、前台服务仍存在、通知文案包含“后台友好”、进程无 `AndroidRuntime` 崩溃。

- [ ] **步骤 5：验证通知暂停和重新打开继续**

通过通知“暂停”，确认批次状态为 `PAUSED` 且前台服务退出；划走 Activity 后重新打开，状态仍为暂停且完成数不归零。点击“继续”，确认同一批次 ID 从原 checkpoint 增长。

- [x] **步骤 6：记录损坏 PNG 诊断证据**

文档记录媒体 `1001180282`：`Pictures/Lark/1768878273557.png`、PNG 签名正确、1488×2105、459,827 字节、首个 `IDAT` CRC 错误；说明 4 个失败工作项来自同一损坏媒体，应用未改写原文件。

- [x] **步骤 7：更新文档并 Commit**

```powershell
git add docs/mvp-foundation.md docs/superpowers/plans/2026-08-11-background-batch-execution.md
git commit -m "docs: 记录后台分析与损坏媒体验收"
```

- [ ] **步骤 8：最终检查**

```powershell
git status --short
git log -8 --oneline
Get-FileHash app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
```

预期：工作区干净；所有实现与文档 commit 存在；Debug APK 保留在构建目录并报告 SHA-256。
