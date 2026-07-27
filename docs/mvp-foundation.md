# mvp-foundation 里程碑

## 目的

记录帧遇 Android 版的第一个可协作基础阶段，作为后续提交到 GitHub 私有仓库和多设备协作开发的共同基线。

## 当前范围

- 原生 Kotlin + Jetpack Compose Android 项目骨架。
- 目标设备：Redmi K70 Pro（ALSC，1 TB）。
- 本地媒体分析方向：规则评分、ML Kit、MediaPipe Image Embedder、可插拔 LiteRT 模型。
- 任务 2 的领域模型和 Room 数据层基础文件。
- 已包含 MediaStore 媒体索引 MVP、权限入口、Photo Picker 入口和 Room 索引持久化；视频分析、候选帧导出和相册整理基础流程已接通。
- 已完成首版精彩帧规则分析接口：支持清晰度、曝光、黑帧、重复帧和场景变化评分，后续可替换为 ML Kit/LiteRT 实现。
- 已完成 ML Kit 人脸分析第一阶段，以及图像标签/向量适配层：本地模型状态、Bitmap/Image 输入、人脸特征、缩略图约束、标签映射和 MediaPipe Embedder 调用均已具备；MediaPipe `.task` 模型资产和统一分析管线仍待后续配置与实现。
- 已完成统一分析管线：规则评分与三类视觉后端异步组合，区分未配置、未就绪、成功空结果和失败，单模型失败不会丢失其他结果。
- 已完成视频自适应采样计划第一阶段：三档采样密度、最长 2 小时、半开区间覆盖、断点恢复；Media3 解码器、音频特征和 WorkManager 长任务仍待实现。
- 已完成视频帧采样器第一阶段：支持 MediaItem/Uri 输入、按计划读取帧、去重、取消和失败状态；音频分析与长任务调度仍待实现。
- 已完成音频摘要特征分析第一阶段：根据能量、频谱变化和节奏变化生成并合并重点区间，不保存音频副本；实际音轨读取仍待接入。
- 已完成可恢复视频分析任务核心：按采样区间逐帧回调，逐帧保存下一时间点、区间索引和累计进度；任务核心不持有 Bitmap、解码器或音频副本，可供 WorkManager 调度层接入。
- 已完成分析断点的 Room 持久化适配：分析任务表升级到 v2，保存计划 ID、区间索引、下一时间点和累计帧数；已补充 1→2 迁移及内存数据库 instrumentation 测试。
- 已完成 WorkManager 前台任务与串行调度：使用唯一工作链串行排队视频任务，前台通知实时报告任务进度，支持取消、失败和批量入队；默认执行器从 Room 读取视频，使用 `MediaMetadataRetriever` 解码、规则评分并保存候选帧。
- 已接通扫描后的第一条用户流程：媒体库页面从 Room 展示视频列表，支持单个视频开始分析、重复任务保护和排队/运行/完成/失败状态显示。
- 已接通候选帧浏览第一阶段：分析完成后可进入候选列表，按评分排序，从原视频时间点加载缩略预览并显示评分明细。
- 已接通候选帧人工选择第一阶段：支持按评分/时间排序，并将选择状态持久化到 Room，后续导出可直接使用已选候选。
- 已接通静态图片导出第一阶段：从候选时间点读取原视频画面，以高质量 SDR JPEG 写入系统相册，并使用 `IS_PENDING` 防止半成品可见。
- 已扩展静态导出格式：候选帧可直接导出 JPEG 或 WebP，仍使用系统相册的 `IS_PENDING` 生命周期控制。
- 已接通 MP4 片段导出第一阶段：以候选时间点为中心生成默认 3 秒片段，使用原生 `MediaExtractor`/`MediaMuxer` 复用音视频轨道并写入系统相册。
- 已接通 GIF 导出第一阶段：候选点附近按 8 FPS 抽取最多 480 像素边长的 SDR 帧，使用本地 GIF89a 编码器写入系统相册。
- 已增加静态图片归一化裁切：详情导出可传入裁切矩形，按源帧像素区域裁切后再写入 JPEG/WebP；MP4/GIF 的视频级裁切仍待接入。
- GIF 已支持逐帧裁切；MP4 继续使用无重编码复用，选择裁切构图时会明确禁用 MP4 按钮，避免输出未裁切内容或伪装支持。
- 静态 JPEG 在 Android 14+ 且源帧携带 gain map 时会保留 Ultra HDR 标记；无法确认 gain map 时明确降为 SDR，不伪造 HDR。
- 已增加动态照片兜底导出：当前返回静态图 + MP4 两个资源，并明确标记尚未生成厂商动态照片容器，待 Redmi 真机样本确认容器结构后再启用单文件封装。
- 已支持动态导出时长选择：候选页可选择 3/5/10 秒，MP4 和 GIF 使用相同的窗口配置并在视频边界自动裁切。
- 已修复 Android 16/targetSdk 35 前台服务崩溃：WorkManager 分析任务声明并使用 `dataSync` 前台服务类型；媒体库新增批量分析未完成视频入口，任务仍按唯一工作链串行执行。
- 已优化候选帧第一阶段交互：改为两列网格，卡片点击切换选中状态，支持全选、清空、选择最高分 5 帧，并通过底部操作栏批量导出图片/GIF/MP4。
- 已接入候选帧详情第一阶段：支持从候选网格进入详情，按时间轴拖动定位，并以 30 FPS 进行上一帧/下一帧定位；导出流程开始复制可用的拍摄时间、宽高和旋转元数据，缺失 GPS 不会伪造。
- 已补齐候选帧封面帧持久化：数据库升级到 v3，按视频原子地维护唯一封面帧，详情页支持将当前候选设为封面；批量分析页显示完成/排队/运行汇总，并支持取消当前串行分析链后重试。
- WorkManager 调度器支持按任务取消、状态查询和恢复排队；由于 WorkManager 2.11 的 `WorkInfo` 不暴露输入数据，任务 ID 同步写入请求 Tag 用于本地状态关联。
- 已建立相册整理规则基础：截图/录屏可按文件名和路径元数据确定性识别，票据/证件/条码/停车位保留视觉模型扩展入口；重复候选生成长度编码键，避免路径分隔符碰撞。
- 已接入相册整理页：展示重复组和建议保留项，删除必须由用户明确确认；Android 10+ 移入系统回收区，旧系统才使用显式删除。
- 已增加 50～200 GB 缓存容量策略和分析温控决策：中高温度暂停演唱会长视频分析，临界温度停止，缓存清理只生成计划不隐式删除。
- 已接入设置持久化和设置页：缓存上限、演唱会模式、位置元数据复制、后台分析四项配置通过 SharedPreferences 保存。

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

验证结果：`:app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 已在离线模式下成功通过。当前扫描支持图片/视频分批查询、修改时间和文件大小增量过滤、稳定扫描上界、实时进度、Android 14 部分媒体权限和 Photo Picker 授权。

最近验证：`./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.sampling.VideoAnalysisTaskTest` 已成功通过，覆盖完整区间、断点续采和回调失败进度保留。

断点验证：`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 已成功通过；2026-07-24 已在 Redmi K70 Pro（`23117RK66C`，Android 16，HyperOS 构建 `OS3.0.304.0.WNMCNXM`）运行 Room 断点 instrumentation 测试并通过。

任务调度验证：`:app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` 已成功通过；2026-07-24 已在 Redmi K70 Pro 完成 AndroidTest，3 个测试中 2 个通过、1 个因未授予媒体读取权限跳过。WorkManager 前台通知、后台限制和真实视频解码性能仍需专项手动验证。

真机安装验证：2026-07-24 已通过 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装 Debug APK，包名为 `com.frameyu.app`，启动入口为 `com.frameyu.app/.MainActivity`，版本 `0.1.0 (versionCode 1)`。设备已授予图片和视频读取权限；通知权限仍为未授予状态。桌面图标已配置为项目内 `ic_launcher` 矢量资源。

扫描真机验证：Redmi K70 Pro Android 16 首次扫描暴露 `Invalid column has_gain_map`，已增加 HDR 可选列失败降级；修复后重新安装并点击全量扫描，设备显示扫描完成并更新 `10097` 个媒体。

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
