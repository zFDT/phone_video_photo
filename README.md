# Android 手机视频 / 照片智能分析工具

一款运行在 Android 端、完全本地化的视频 / 照片智能分析工具，通过本地视觉模型完成画质评分、关键帧抽取与场景切换检测，全程数据不上云。

## 项目简介

手机相册里的视频和照片数量庞大，人工逐条筛选「哪段视频画质更好、哪个画面值得保留」成本极高。本项目提供一套端侧（on-device）智能分析能力，在**不联网、不上传数据**的前提下，对本地媒体完成自动化理解与打分：

- **本地优先**：所有分析均在设备端完成，媒体文件不会上传到任何云端服务，隐私零外泄
- **画质评分**：对视频帧 / 照片进行客观画质打分，辅助用户筛选高质量内容
- **关键帧抽取**：通过帧采样（Sampling）从视频中抽取代表性帧
- **场景切换检测**：自动识别视频中的镜头切换边界，辅助内容切分与摘要生成
- **批量后台分析**：借助 WorkManager 在后台批量处理整个媒体库，不阻塞主线程

## 核心功能 / 技术特性

**核心功能**

- **媒体扫描（MediaStoreScanner）**：基于 Android MediaStore 扫描设备上的视频与照片，作为分析的统一数据入口
- **相册分类（AlbumClassifier）**：对媒体按相册 / 类别进行归类组织
- **帧采样（Sampling）**：对视频按策略抽取采样帧，并通过 Room 持久化采样进度（RoomVideoAnalysisLifecycleStore、RoomSamplingCheckpointStore、RoomVideoFrameModelStore），支持断点续跑
- **画质评分（QualityScorer）**：对帧 / 照片输出 FrameScore 画质分数
- **场景切换检测（SceneChangeDetector）**：检测视频中的场景 / 镜头切换点
- **本地视觉模型（Vision）**：内置端侧视觉模型（model-manifest.properties），不依赖云 API
- **批量分析（BatchAnalysisCoordinator）**：基于 WorkManager 在后台编排全库批量分析任务
- **Room 持久化（data/db）**：通过 AnalysisCandidateCoverDao、BatchAnalysisDao 等持久化分析结果与任务状态

**技术特性**

- **清晰分层架构**：`album` / `analysis` / `sampling` / `vision` / `data` 职责边界明确，分析管线（ImageAnalysisCoordinator）统一编排
- **断点续传式采样**：采样进度与生命周期状态落库，长视频分析可中断恢复
- **完整测试体系**：包含大量 androidTest，覆盖 DAO、MediaStore、分析管线与 Vision 模型

## 技术特色与复盘

### 技术亮点

- **本地优先 + 隐私保护**：整套分析链路完全在端侧闭环，视觉模型内置、媒体不出设备，契合当下对隐私与数据主权的诉求，无需服务端即可交付核心能力
- **完整测试体系**：针对 Room DAO、MediaStore 扫描、分析管线与 Vision 模型均编写了 androidTest，通过测试守护核心逻辑的稳定性
- **清晰的分层与模块化**：相册 / 分析 / 采样 / 视觉 / 数据各层解耦，采样进度与生命周期持久化到 Room，支持长任务断点恢复

### 个人贡献

- 独立设计并实现端侧媒体分析的整体架构，覆盖媒体扫描、帧采样、画质评分、场景切换检测、批量分析全链路
- 完成 Room 数据库建模与采样断点续传机制，实现采样进度与任务状态持久化
- 引入并接入本地视觉模型，实现不上云的端侧推理方案
- 编写 androidTest，覆盖 DAO、MediaStore、分析管线与视觉模型等关键模块

### 收获与规划

- **收获**：通过本项目系统实践了 Android 端侧 AI 能力的落地路径，掌握了 MediaStore、Room、WorkManager 等核心组件的组合使用，加深了对「本地优先、隐私优先」产品形态的理解
- **规划**：后续可引入更丰富的视觉能力（目标检测、人脸 / 场景标签），补充分析结果可视化（评分曲线、关键帧墙），并优化长视频批量分析的调度与电量 / 内存开销

## 技术栈

- **语言**：Kotlin
- **架构组件**：Room（持久化）、WorkManager（后台任务）
- **媒体**：Android MediaStore
- **视觉**：端侧本地视觉模型（model-manifest.properties）

## 目录结构

```
album/           # 相册分类（AlbumClassifier）
analysis/        # 分析核心：FrameAnalyzer、QualityScorer、SceneChangeDetector、ImageAnalysisCoordinator
analysis/batch/  # 批量分析（BatchAnalysisCoordinator，WorkManager 后台）
sampling/        # 帧采样 + 采样进度 / 生命周期持久化（Room）
vision/          # 本地视觉模型
data/db/         # Room 数据库（DAO）
data/media/      # 媒体扫描（MediaStoreScanner）
```

## 构建与运行

```bash
# 使用 Android Studio 打开工程，同步 Gradle 依赖后直接构建运行
./gradlew assembleDebug
```
