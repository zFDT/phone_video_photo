# 帧遇 Android 版实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从空工作区搭建一个原生 Kotlin Android 应用，实现本地媒体索引、视频精彩帧分析、演唱会长视频任务、图片/GIF/MP4 导出和相册整理基础能力。

**架构：** 使用 Jetpack Compose 负责界面，MediaStore 负责媒体访问，Room 保存索引和分析结果，WorkManager 负责可恢复的本地任务。媒体解码、分析器和导出器通过 Kotlin 接口隔离，模型实现可替换。

**技术栈：** Kotlin、Android Gradle Plugin、Jetpack Compose、Room、WorkManager、Media3、ML Kit、MediaPipe Tasks Vision、ExifInterface、JUnit、AndroidX Test。

---

## 文件结构

### 任务 1 创建项目骨架

- 创建：`settings.gradle.kts`
- 创建：`build.gradle.kts`
- 创建：`gradle.properties`
- 创建：`app/build.gradle.kts`
- 创建：`app/src/main/AndroidManifest.xml`
- 创建：`app/src/main/java/com/frameyu/app/MainActivity.kt`
- 创建：`app/src/main/java/com/frameyu/app/FrameYuApplication.kt`
- 创建：`app/src/main/res/values/strings.xml`
- 创建：`app/src/test/java/com/frameyu/app/SmokeTest.kt`
- 创建：`gradlew`
- 创建：`gradlew.bat`
- 创建：`gradle/wrapper/gradle-wrapper.jar`
- 创建：`gradle/wrapper/gradle-wrapper.properties`

### 任务 2 建立领域模型和本地数据库

- 创建：`app/src/main/java/com/frameyu/app/domain/model/MediaAsset.kt`
- 创建：`app/src/main/java/com/frameyu/app/domain/model/AnalysisCandidate.kt`
- 创建：`app/src/main/java/com/frameyu/app/domain/model/AnalysisTask.kt`
- 创建：`app/src/main/java/com/frameyu/app/data/db/FrameYuDatabase.kt`
- 创建：`app/src/main/java/com/frameyu/app/data/db/MediaAssetDao.kt`
- 创建：`app/src/main/java/com/frameyu/app/data/db/AnalysisCandidateDao.kt`
- 创建：`app/src/test/java/com/frameyu/app/domain/model/AnalysisCandidateTest.kt`

### 任务 3 实现 MediaStore 媒体索引

- 创建：`app/src/main/java/com/frameyu/app/data/media/MediaStoreRepository.kt`
- 创建：`app/src/main/java/com/frameyu/app/data/media/MediaStoreScanner.kt`
- 创建：`app/src/main/java/com/frameyu/app/data/media/MediaUriAccess.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/library/LibraryViewModel.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/library/LibraryScreen.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/data/media/MediaStoreScannerTest.kt`

### 任务 4 建立分析接口和基础画质评分

- 创建：`app/src/main/java/com/frameyu/app/analysis/FrameAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/FrameSample.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/FrameScore.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/QualityScorer.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/SceneChangeDetector.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/QualityScorerTest.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/SceneChangeDetectorTest.kt`

### 任务 5 接入本地视觉模型

- 创建：`app/src/main/java/com/frameyu/app/analysis/vision/FaceAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/vision/ImageLabelAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/vision/ImageEmbeddingAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/vision/AnalyzerPipeline.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/vision/ModelAvailability.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/vision/AnalyzerPipelineTest.kt`

### 任务 6 实现视频采样和长任务调度

- 创建：`app/src/main/java/com/frameyu/app/analysis/video/VideoFrameSampler.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/video/AdaptiveSamplingPolicy.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/video/ConcertAudioAnalyzer.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/video/VideoAnalysisWorker.kt`
- 创建：`app/src/main/java/com/frameyu/app/analysis/video/AnalysisTaskScheduler.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/video/AdaptiveSamplingPolicyTest.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/video/ConcertAudioAnalyzerTest.kt`

### 任务 7 实现精彩帧浏览和人工选择

- 创建：`app/src/main/java/com/frameyu/app/ui/analyze/AnalysisViewModel.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/analyze/AnalysisScreen.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/analyze/FrameTimeline.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/analyze/CropEditor.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/ui/analyze/AnalysisScreenTest.kt`

### 任务 8 实现图片、GIF、WebP 和 MP4 导出

- 创建：`app/src/main/java/com/frameyu/app/export/StillImageExporter.kt`
- 创建：`app/src/main/java/com/frameyu/app/export/AnimatedImageExporter.kt`
- 创建：`app/src/main/java/com/frameyu/app/export/VideoClipExporter.kt`
- 创建：`app/src/main/java/com/frameyu/app/export/MetadataCopier.kt`
- 创建：`app/src/main/java/com/frameyu/app/export/MotionPhotoExporter.kt`
- 创建：`app/src/test/java/com/frameyu/app/export/MetadataCopierTest.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/export/ExportIntegrationTest.kt`

### 任务 9 实现相册整理和安全删除

- 创建：`app/src/main/java/com/frameyu/app/organize/MediaClassifier.kt`
- 创建：`app/src/main/java/com/frameyu/app/organize/DuplicateDetector.kt`
- 创建：`app/src/main/java/com/frameyu/app/organize/OrganizerRepository.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/organize/OrganizerViewModel.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/organize/OrganizerScreen.kt`
- 创建：`app/src/androidTest/java/com/frameyu/app/organize/TrashSafetyTest.kt`

### 任务 10 实现缓存策略、设置和温控

- 创建：`app/src/main/java/com/frameyu/app/storage/AnalysisCacheManager.kt`
- 创建：`app/src/main/java/com/frameyu/app/storage/CacheSettings.kt`
- 创建：`app/src/main/java/com/frameyu/app/system/ThermalMonitor.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/settings/SettingsViewModel.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/settings/SettingsScreen.kt`
- 创建：`app/src/test/java/com/frameyu/app/storage/AnalysisCacheManagerTest.kt`

### 任务 11 完成应用导航和验收验证

- 修改：`app/src/main/java/com/frameyu/app/MainActivity.kt`
- 创建：`app/src/main/java/com/frameyu/app/ui/FrameYuNavGraph.kt`
- 创建：`app/src/test/java/com/frameyu/app/analysis/AcceptanceChecklistTest.kt`
- 创建：`docs/testing/android-device-test-matrix.md`

---

## 任务 1：创建项目骨架

- [ ] **步骤 1：创建 Gradle 和 Android 应用配置**

配置 Kotlin、Compose、Room、WorkManager、Media3、ML Kit、MediaPipe 和 ExifInterface 依赖；不引入网络服务或云端模型依赖。最低 API 设为 29，目标 API 使用本机 Android SDK 可用的稳定版本。

- [ ] **步骤 2：创建最小 Activity 和 Application**

`MainActivity` 使用 Compose 显示应用名称和当前媒体权限状态；`FrameYuApplication` 初始化 Room 数据库和任务调度器依赖。

- [ ] **步骤 3：接入 Gradle Wrapper 和核心依赖**

使用 Gradle 8.12 Wrapper，接入 Room + KSP、WorkManager、Media3、ML Kit、MediaPipe、ExifInterface。Android SDK 使用本机已安装的 API 35。

- [ ] **步骤 4：运行编译验证**

运行：`./gradlew :app:assembleDebug :app:testDebugUnitTest`

验证记录：`./gradlew.bat --offline :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 已成功执行。任务 3 已完成代码实现；真实设备上的 `connectedDebugAndroidTest` 仍需连接 Redmi K70 Pro 后执行。

## 任务 2：建立领域模型和本地数据库

- [ ] **步骤 1：先写领域模型测试**

覆盖候选帧时间戳、评分范围、分析任务状态转换和缓存大小配置的边界。

- [ ] **步骤 2：实现不可变领域模型**

定义 `MediaAsset`、`AnalysisCandidate` 和 `AnalysisTask`，时间统一使用毫秒，媒体引用统一使用 `Uri.toString()`。

- [ ] **步骤 3：实现 Room Entity 和 DAO**

DAO 必须支持按媒体 URI 查询、按任务查询候选帧、断点保存采样进度和删除单个媒体的索引记录。

- [ ] **步骤 4：运行模型和数据库测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*AnalysisCandidateTest'`

预期：测试通过，评分范围和状态转换没有越界结果。

## 任务 3：实现 MediaStore 媒体索引

- [x] **步骤 1：实现扫描查询**

查询图片和视频集合，读取 URI、MIME、拍摄时间、时长、宽高、方向、文件大小、HDR 标记和相册相对路径；使用分页或批量读取，避免一次性加载全部结果。

- [x] **步骤 2：实现增量同步**

以 URI、修改时间和文件大小作为首轮变更判断，只有新媒体或元数据发生变化时才更新 Room。

- [x] **步骤 3：实现权限和 URI 访问封装**

集中处理 Photo Picker 持久化授权、媒体读取权限和 `ACCESS_MEDIA_LOCATION`，业务层不直接调用权限 API。

- [x] **步骤 4：实现媒体库界面**

显示媒体类型、扫描进度、最近分析任务和用户选择入口。

- [ ] **步骤 5：运行设备测试**

运行：`./gradlew :app:connectedDebugAndroidTest`

预期：测试设备上可以读取测试媒体元数据，未授权时显示明确的授权引导。

说明：2026-07-24 已连接 Redmi K70 Pro（`23117RK66C`，Android 16）执行 `connectedDebugAndroidTest`；Room 断点和异步视觉管线测试通过，MediaStore 测试因未授予媒体读取权限跳过。

## 任务 4：建立分析接口和基础画质评分

- [x] **步骤 1：写评分器测试**

测试清晰度、曝光、黑帧、重复帧和权重合成；确保分数始终处于 0～100。

- [x] **步骤 2：实现 `FrameAnalyzer` 接口**

接口接收 `FrameSample` 和分析上下文，返回 `FrameScore`，不依赖 Android UI。

- [x] **步骤 3：实现规则评分器和场景切换检测**

使用图像灰度差、边缘强度和时间邻近关系实现首版算法；将每项分数保留，便于后续调参。

- [x] **步骤 4：运行单元测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*QualityScorerTest' --tests '*SceneChangeDetectorTest'`

预期：所有评分边界、空帧和相邻重复帧测试通过。

验证记录：`./gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug` 已成功执行。规则分析器不依赖 Android UI 或新增依赖，真实 ML Kit/MediaPipe 模型接入归入任务 5。

## 任务 5：接入本地视觉模型

- [x] **步骤 1：实现模型可用性检查**

区分内置模型、Google Play Services 模型未下载和模型加载失败三种状态；模型未就绪时不能静默返回空结果。

- [x] **步骤 2：实现人脸分析器**

使用 ML Kit Face Detection 的快速模式完成位置、角度、眼睛和微笑结果映射，确保每次分析后释放 detector。

- [ ] **步骤 3：实现图像标签和向量分析器**

通过 ML Kit Image Labeling 生成通用标签，通过 MediaPipe Image Embedder 生成用于聚类的向量；模型输入使用分析缩略图，不读取原始 4K Bitmap。

当前进度：已完成缩略图约束、ML Kit 标签适配器、向量数据模型和 MediaPipe Embedder 适配器；默认 `.task` 模型资产尚未纳入项目，因此本步骤保持未完成。

- [x] **步骤 4：实现分析管线**

串联规则评分、视觉模型和可选模型，单个模型失败时保留其他结果并记录可诊断错误。

- [ ] **步骤 5：运行模型管线测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*AnalyzerPipelineTest'`

预期：模型不可用、空结果和部分失败场景都有明确状态，不会被误判为“没有精彩帧”。

验证记录：`./gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` 已成功执行；延迟模型失败场景已加入 instrumentation 测试，真实设备运行待连接 Redmi K70 Pro。

## 任务 6：实现视频采样和长任务调度

- [x] **步骤 1：写自适应采样策略测试**

验证普通视频每秒 1 帧初筛，重点区间进入每秒 5～10 帧和每秒 15～30 帧精筛；验证 2 小时视频的区间不会重叠或遗漏。

当前进度：已完成 `SampleRange`、`SamplingPlan`、`SamplingCheckpoint` 和 JVM 边界测试；视频解码、音频特征与长任务调度尚未接入。

- [x] **步骤 2：实现视频帧采样器**

封装 Media3/MediaCodec 或等价 Android 解码接口，按时间戳获取帧；每次只保留当前帧和必要的相邻帧。

当前进度：已完成可取消采样协调器和 `MediaItem`/`Uri` 输入适配；真实设备解码性能测试统一放到最终验收阶段。

- [x] **步骤 3：实现音频特征分析**

读取音轨的能量、频谱变化和节奏变化，输出重点时间区间，不保存音频副本。

当前进度：已完成归一化音频窗口摘要到重点区间的规则合并；实际音轨读取和设备性能测试待后续接入。

- [x] **步骤 4：实现 `VideoAnalysisWorker`**

每个视频按区间持久化进度，使用前台通知报告总进度、当前文件和可取消状态；任务恢复时从最后确认的时间区间继续。

当前进度：已完成 `VideoAnalysisTask`、Room 断点适配、WorkManager 前台任务和默认执行器。唯一工作链负责串行排队，Worker 负责前台通知、进度 Data、取消和失败映射；默认执行器从 Room 读取视频，解码到最长边 1024 的分析图，执行规则评分并保存候选帧。真实设备迁移、通知和解码性能验证仍待接入。

- [ ] **步骤 5：实现任务调度器**

支持单个视频、多个视频和全库任务，默认串行处理大视频，避免多个硬件解码器造成过热。

- [ ] **步骤 6：运行长任务测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*AdaptiveSamplingPolicyTest' --tests '*ConcertAudioAnalyzerTest'`

预期：采样区间、音频峰值和任务状态转换测试通过。

## 任务 7：实现精彩帧浏览和人工选择

- [ ] **步骤 1：实现分析状态 ViewModel**

提供候选帧列表、时间轴、评分分项、任务状态和选中状态。

- [ ] **步骤 2：实现 Compose 时间轴和候选网格**

支持按分数排序、按时间排序、按场景分组和预览播放。

- [ ] **步骤 3：实现逐帧定位和裁切**

逐帧定位使用原视频时间戳；裁切只保存裁切参数，导出阶段再读取原始帧。

- [ ] **步骤 4：运行 UI 测试**

运行：`./gradlew :app:connectedDebugAndroidTest`

预期：选择、取消选择、时间轴定位和裁切参数状态在重建后保持一致。

## 任务 8：实现图片、GIF、WebP 和 MP4 导出

- [ ] **步骤 1：写元数据复制测试**

覆盖拍摄时间、GPS、方向、分辨率和 HDR 标记；验证用户关闭位置复制时 GPS 不写入目标文件。

- [ ] **步骤 2：实现静态图片导出**

根据源媒体格式选择 SDR JPEG、Ultra HDR JPEG 或无损 PNG；通过 MediaStore 插入目标文件并使用 `IS_PENDING` 保证半成品不出现在相册。

- [ ] **步骤 3：实现 GIF/WebP 和 MP4 导出**

使用固定的帧率、尺寸和时长配置生成导出文件；HDR 输入导出 GIF 时明确降为 SDR。

- [ ] **步骤 4：实现动态照片导出**

先读取 Redmi K70 Pro 动态照片样本，提取封面和视频资源结构，再实现同结构导出；无法识别时输出封面 JPEG/Ultra HDR JPEG 与 MP4 兜底文件。

- [ ] **步骤 5：运行导出测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*MetadataCopierTest'; ./gradlew :app:connectedDebugAndroidTest`

预期：目标文件可被 MediaStore 查询，元数据符合预期，导出失败时不会留下 `IS_PENDING` 文件。

## 任务 9：实现相册整理和安全删除

- [ ] **步骤 1：实现媒体分类器**

使用 MediaStore 元数据、目录、MIME、文件名、OCR、条码和标签生成候选分类，并保留分类置信度。

- [ ] **步骤 2：实现完全重复和相似检测**

完全重复使用文件哈希；相似照片使用图像向量和时间窗口分组；每组提供推荐保留项，不自动删除。

- [ ] **步骤 3：实现整理界面**

按分类显示数量、缩略图、推荐保留项和用户选择结果。

- [ ] **步骤 4：实现安全删除**

使用系统批量删除确认流程；删除前保存待处理 URI，失败时展示未删除项，禁止直接调用物理文件删除。

- [ ] **步骤 5：运行安全测试**

运行：`./gradlew :app:connectedDebugAndroidTest`

预期：未确认的项目不会进入删除流程，删除取消后原媒体仍可查询。

## 任务 10：实现缓存策略、设置和温控

- [ ] **步骤 1：写缓存淘汰测试**

验证 50 GB、100 GB、150 GB、200 GB 四个配置；淘汰顺序必须是临时文件、低评分候选、旧特征数据，不能淘汰原始媒体。

- [ ] **步骤 2：实现缓存管理器**

统一管理缩略图、候选帧、向量和任务中间文件，记录大小、来源、评分和最后访问时间。

- [ ] **步骤 3：实现温控和省电策略**

监听电量、充电状态和热状态，在温度升高或电量过低时降低采样密度或暂停任务。

- [ ] **步骤 4：实现设置界面**

支持缓存上限、分析模式、演唱会模式、默认导出格式、位置元数据复制和后台分析开关。

- [ ] **步骤 5：运行缓存测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*AnalysisCacheManagerTest'`

预期：缓存淘汰不影响原始媒体 URI 和数据库主记录。

## 任务 11：完成应用导航和验收验证

- [ ] **步骤 1：实现导航**

连接媒体库、分析详情、导出队列、相册整理和设置页面；导航状态不直接持有 Bitmap 或媒体文件句柄。

- [ ] **步骤 2：建立设备测试矩阵**

记录 Redmi K70 Pro 上的 Android 版本、HyperOS 版本、视频格式、HDR 状态、分析耗时、内存峰值、温度和电量变化。

- [ ] **步骤 3：运行完整验证**

运行：`./gradlew :app:testDebugUnitTest :app:assembleDebug`

连接 Redmi K70 Pro 后运行：`./gradlew :app:connectedDebugAndroidTest`

预期：单元测试、Debug 构建和设备测试均完成；若设备测试无法执行，必须记录具体环境原因和未验证项目。

---

## 计划自检

- 规格中的本地分析、演唱会长视频、动态照片、导出、相册整理、缓存、安全和测试均有对应任务。
- 已扫描禁止占位符词语；动态照片兼容性以真实设备样本验证，不把未知格式假设为已知格式。
- 所有任务使用一致的领域名：`MediaAsset`、`AnalysisCandidate`、`AnalysisTask`、`Analyzer` 和 `VideoAnalysisWorker`。
- 计划只覆盖一个 Android 应用子项目；模型训练数据收集和专用模型训练不纳入首轮实现，首轮通过可插拔接口保留扩展点。
