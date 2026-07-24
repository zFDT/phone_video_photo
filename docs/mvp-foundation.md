# mvp-foundation 里程碑

## 目的

记录帧遇 Android 版的第一个可协作基础阶段，作为后续提交到 GitHub 私有仓库和多设备协作开发的共同基线。

## 当前范围

- 原生 Kotlin + Jetpack Compose Android 项目骨架。
- 目标设备：Redmi K70 Pro（ALSC，1 TB）。
- 本地媒体分析方向：规则评分、ML Kit、MediaPipe Image Embedder、可插拔 LiteRT 模型。
- 任务 2 的领域模型和 Room 数据层基础文件。
- 已包含 MediaStore 媒体索引 MVP、权限入口、Photo Picker 入口和 Room 索引持久化；视频采样、导出和相册整理仍待后续任务实现。
- 已完成首版精彩帧规则分析接口：支持清晰度、曝光、黑帧、重复帧和场景变化评分，后续可替换为 ML Kit/LiteRT 实现。
- 已完成 ML Kit 人脸分析第一阶段，以及图像标签/向量适配层：本地模型状态、Bitmap/Image 输入、人脸特征、缩略图约束、标签映射和 MediaPipe Embedder 调用均已具备；MediaPipe `.task` 模型资产和统一分析管线仍待后续配置与实现。
- 已完成统一分析管线：规则评分与三类视觉后端异步组合，区分未配置、未就绪、成功空结果和失败，单模型失败不会丢失其他结果。
- 已完成视频自适应采样计划第一阶段：三档采样密度、最长 2 小时、半开区间覆盖、断点恢复；Media3 解码器、音频特征和 WorkManager 长任务仍待实现。
- 已完成视频帧采样器第一阶段：支持 MediaItem/Uri 输入、按计划读取帧、去重、取消和失败状态；音频分析与长任务调度仍待实现。
- 已完成音频摘要特征分析第一阶段：根据能量、频谱变化和节奏变化生成并合并重点区间，不保存音频副本；实际音轨读取仍待接入。
- 已完成可恢复视频分析任务核心：按采样区间逐帧回调，逐帧保存下一时间点、区间索引和累计进度；任务核心不持有 Bitmap、解码器或音频副本，可供 WorkManager 调度层接入。
- 已完成分析断点的 Room 持久化适配：分析任务表升级到 v2，保存计划 ID、区间索引、下一时间点和累计帧数；已补充 1→2 迁移及内存数据库 instrumentation 测试。
- 已完成 WorkManager 前台任务与串行调度骨架：使用唯一工作链串行排队视频任务，前台通知实时报告任务进度，支持取消、失败和批量入队；真实视频分析执行器通过 `VideoAnalysisWorkerRunner` 注入，尚未接入默认实现。

## 版本控制约定

- 里程碑名称：`mvp-foundation`。
- 推荐分支：`mvp-foundation`。
- 后续功能按独立任务提交，提交前运行对应的单元测试或静态检查。
- 不提交原始照片、视频、分析缓存、Android Studio 本地配置、签名文件和任何 Token。
- 推送 GitHub 前先创建私有仓库，再配置远程地址；远程地址和凭据不写入项目文件。

## 当前验证限制

本机存在 Android SDK 和 Java 17：

- Android SDK：`D:\flutter\sdk\android`。
- 已安装 SDK Platform：`android-31`、`android-33`、`android-34`、`android-35`、`android-36`。
- 已安装 Build Tools：`35.0.0`、`35.0.1`、`36.0.0`。
- Java：Eclipse Temurin 17.0.19。

当前项目已接入 Gradle 8.13 Wrapper，并补齐 Room + KSP、WorkManager、Media3、ML Kit、MediaPipe、ExifInterface 等依赖。Gradle 8.13 distribution 已存在于本机缓存；项目同时配置了 Maven Central、Google Maven 和阿里云公共镜像。

验证结果：`:app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 已在离线模式下成功通过（56 个任务，14 个实际执行、42 个复用缓存）。当前扫描支持图片/视频分批查询、修改时间和文件大小增量过滤、稳定扫描上界、实时进度、Android 14 部分媒体权限和 Photo Picker 授权。

最近验证：`./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.sampling.VideoAnalysisTaskTest` 已成功通过，覆盖完整区间、断点续采和回调失败进度保留。

断点验证：`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 已成功通过；Room 迁移和实际设备数据库运行仍需在连接 Redmi K70 Pro 后执行。

任务调度验证：`:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` 已成功通过；WorkManager 运行时通知和后台限制仍需在真实 Android 环境验证。

首个验证命令为：

```powershell
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

连接 Redmi K70 Pro 后再执行：

```powershell
./gradlew :app:connectedDebugAndroidTest
```

多设备协作时应保留项目内的 `gradlew`、`gradlew.bat` 和 `gradle/wrapper`，不要提交 `local.properties`、`.gradle` 缓存或本地媒体文件。

## 已提交记录

- `31770f2 chore: initialize mvp foundation`：规格、计划、项目骨架、基础 Room 数据层和版本控制配置。
- `bf3f84b feat: add media domain and room data layer`：应用模块配置和领域模型测试。
- `94d48cd feat: 添加媒体扫描和访问功能，支持媒体索引和权限管理`：MediaStore 扫描、增量索引状态和 URI 访问封装。
- 任务 3 的 UI、增量边界修复和 AndroidTest 已通过上述验证，待本阶段提交后补充 commit 记录。
- 任务 4 的纯 Kotlin 评分器和单元测试已通过 `:app:testDebugUnitTest :app:assembleDebug`，尚未接入真实视觉模型。
- 任务 5 第一阶段已通过 `:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`；真实设备上的 ML Kit 运行验证待连接 Redmi K70 Pro。
