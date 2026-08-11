# mvp-foundation 里程碑

## 目的

记录帧遇 Android 版的第一个可协作基础阶段，作为后续提交到 GitHub 私有仓库和多设备协作开发的共同基线。

## 当前范围

- 原生 Kotlin + Jetpack Compose Android 项目骨架。
- 目标设备：Redmi K70 Pro（ALSC，1 TB）。
- 本地媒体分析方向：规则评分、ML Kit、MediaPipe Tasks Vision 与可插拔 LiteRT 模型。
- 已建立领域模型和 Room v7 数据层，覆盖媒体索引、视频任务、批次工作项、候选帧、分类、检测、向量引用和质量结果。
- 已包含 MediaStore 媒体索引 MVP、权限入口、Photo Picker 入口和 Room 索引持久化；视频分析、候选帧导出和相册整理基础流程已接通。
- 已完成首版精彩帧规则分析接口：支持清晰度、曝光、黑帧、重复帧和场景变化评分，后续可替换为 ML Kit/LiteRT 实现。
- 已接入 ML Kit 人脸分析和 MediaPipe 分类、向量、条件目标检测适配层：本地模型状态、Bitmap/Image 输入、人脸特征、受限解码、标签映射、模型 SHA 校验和结果持久化均已具备。
- 已完成统一分析管线：图片按 `QUALITY`、`CLASSIFICATION`、`EMBEDDING`、`CONDITIONAL_DETECTION` 四阶段执行；条件检测只在同批次、同媒体的分类阶段 `COMPLETED` 后运行。单模型失败不会丢失其他阶段结果。
- 已完成视频自适应采样计划：三档采样密度、最长 2 小时、半开区间覆盖、断点恢复；演唱会模式已将粗采样从 1 帧/秒提升到 2 帧/秒，并通过设置持久化读取。
- 已完成视频帧采样器：支持 MediaItem/Uri 输入、按计划读取帧、去重、取消和失败隔离，并将规则评分、分类、向量和条件检测结果聚合为视频标签与精彩候选。
- 音频摘要特征仍是独立的规则基础能力；实际音轨读取不属于本次批量图片/视频模型管线里程碑，也不阻塞现有视频帧分析。
- 已完成可恢复视频分析任务：逐帧保存下一时间点、区间索引和累计进度；同 generation 的 `RUNNING` 恢复 Worker 可从 checkpoint 续跑，旧 generation 或终态 Worker 才会 no-op。
- 已完成 Room v7 断点和执行代际持久化：视频任务、批次任务的状态与 checkpoint 均可在进程重建后恢复，旧执行代不能覆盖新状态。
- 已完成 WorkManager 前台任务与串行调度：视频和批次任务使用唯一串行工作链，支持批量入队、暂停、继续、取消、失败重试和进程恢复；并发创建兼容批次保持幂等，不兼容分析器版本会退役旧批次并重建。
- 已完成前后台一体化批量执行：应用进入后台后，图片批次在已提交工作项边界、长视频在已提交帧 checkpoint 后主动让出 CPU；返回前台后恢复正常速度，任务 ID、执行代际和 checkpoint 不发生切换。
- 批次常驻通知会显示当前阶段、“正常速度 / 后台友好”和样本充足后的分钟级或小时级 ETA，并提供“暂停”和“打开帧遇”操作；通知打开可直接定位到对应批次。
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
- 已增加标准 JPEG Motion Photo 1.0 封装：写入 Camera/Container XMP 并把 MP4 追加到 JPEG 末尾；若系统媒体库读写或容器生成失败，仍返回静态图 + MP4 兜底。Redmi/HyperOS 相册兼容性待真机样本验证。
- 已支持动态导出时长选择：候选页可选择 3/5/10 秒，MP4 和 GIF 使用相同的窗口配置并在视频边界自动裁切。
- 已修复 Android 16/targetSdk 35 前台服务崩溃：WorkManager 分析任务声明并使用 `dataSync` 前台服务类型；媒体库新增批量分析未完成视频入口，任务仍按唯一工作链串行执行。
- 已优化候选帧第一阶段交互：改为两列网格，卡片点击切换选中状态，支持全选、清空、选择最高分 5 帧，并通过底部操作栏批量导出图片/GIF/MP4。
- 已接入候选帧详情第一阶段：支持从候选网格进入详情，按时间轴拖动定位，并以 30 FPS 进行上一帧/下一帧定位；导出流程开始复制可用的拍摄时间、宽高和旋转元数据，缺失 GPS 不会伪造。
- 已补齐候选帧封面帧持久化：数据库升级到 v3，按视频原子地维护唯一封面帧，详情页支持将当前候选设为封面；批量分析页显示完成/排队/运行汇总，并支持取消当前串行分析链后重试。
- WorkManager 调度器支持按任务取消、状态查询和恢复排队；由于 WorkManager 2.11 的 `WorkInfo` 不暴露输入数据，任务 ID 同步写入请求 Tag 用于本地状态关联。
- 已建立统一相册整理页，覆盖精确重复、相似媒体、低质量、截图、录屏和视频精彩候选；大重复组按扁平 `LazyColumn` 展示，视频候选由 SQL 聚合，避免一次加载全部候选行。
- 删除永远需要用户明确确认，绝不自动删除。删除前会复验 live MediaStore 元数据；精确重复还会复验完整组 SHA-256，并通过全局 `suggestedKeep` 锁阻止跨组、跨筛选误删。
- Android R+ 使用 `MediaStore.createTrashRequest()` 系统授权，Android Q 处理 `RecoverableSecurityException`，Android P- 才直接删除；每批最多 100 个 URI，支持 Activity 恢复、授权过期对账和部分成功后的 Room 同步。
- 已增加可取消的分块 SHA-256 完全重复检测和基于 MediaPipe 向量的相似媒体分组；长视频按 1 MB 分块检查取消，读取失败单独报告，不会误判为重复。
- 已增加 50～200 GB 缓存容量策略和分析温控决策：通过 Android `PowerManager` 读取设备热状态，中高温度等待降温，临界温度将当前分析转为可恢复取消；缓存清理只生成计划不隐式删除。
- 已接入设置持久化和设置页：缓存上限、演唱会模式、位置元数据复制、后台分析四项配置通过 SharedPreferences 保存。
- 批次页面显示完整进度，并分别展示失败工作项总数和去重后的失败媒体数；页面提供暂停、继续、取消和失败重试，运行时明确说明可切换到其他应用，暂停后明确说明可退出并在下次打开时继续。失败详情在 SQL 层固定 `LIMIT 100`，超限时显示“前 100 条 / 总数”。
- 分析器版本包含对应模型 SHA-256 和检测策略；`CONDITIONAL_DETECTION` 版本同时绑定分类模型、检测模型和策略，防止模型或策略变化后错误复用旧结果。

## 批量模型资产

2026-08-11 运行 `scripts/fetch-models.ps1 -ValidateOnly` 成功，三项本地 assets 与 manifest 完全匹配：

- EfficientNet-Lite2（分类）：`94B6C84A5D6DE20F932558DE213B50E0338DCC06AB83D8D64B12128A476B4B83`。
- EfficientDet-Lite2（条件目标检测）：`AD2ABBF2B4E10585E15176FD7B5EF03C28DDA959AE26FC142549FDD1814DB91D`。
- MobileNetV3 Small 0.75 224 Embedder（图像向量）：`0F786F8EB8BCECD1F9C5BC50F00A5E6B9F62600EDB503AD3BFFF5F51035B0EA5`。

## 版本控制约定

- 里程碑名称：`mvp-foundation`。
- 推荐分支：`mvp-foundation`。
- 后续功能按独立任务提交，提交前运行对应的单元测试或静态检查。
- 不提交原始照片、视频、分析缓存、Android Studio 本地配置、签名文件和任何 Token。
- `module/`、模型源缓存、生成的 `.tflite` 和 APK 均不提交；现有 `.gitignore` 已覆盖这些路径，无需调整。
- 推送 GitHub 前先创建私有仓库，再配置远程地址；远程地址和凭据不写入项目文件。

## 当前验证与限制

本机验证环境：

- Android SDK：`D:\flutter\sdk\android`。
- 已安装 SDK Platform：`android-31`、`android-33`、`android-34`、`android-35`、`android-36`。
- 已安装 Build Tools：`35.0.0`、`35.0.1`、`36.0.0`。
- Java：Android Studio JBR OpenJDK 21.0.8（`D:\Program Files\Android\Android Studio\jbr`）；项目 Kotlin/JVM target 仍为 17。

当前项目已接入 Gradle 8.13 Wrapper，并补齐 Room + KSP、WorkManager、Media3、ML Kit、MediaPipe、ExifInterface 等依赖。Gradle 8.13 distribution 已存在于本机缓存；项目同时配置了 Maven Central、Google Maven 和阿里云公共镜像。

2026-08-11 使用以下命令执行完整离线验证：

```powershell
$env:JAVA_HOME='D:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='D:\flutter\sdk\android'
$env:ANDROID_SDK_ROOT='D:\flutter\sdk\android'
./gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks --stacktrace
```

结果为 `BUILD SUCCESSFUL`：71 个测试套件、476 个 JVM 测试，0 failure、0 error；74 个 Gradle 任务全部重新执行，`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 均通过。`git diff --check` 通过。

Debug APK 保留在 `app/build/outputs/apk/debug/app-debug.apk`，大小为 191,565,784 字节，SHA-256 为 `AD25E9A0B552D74DE8CB8CD0AFC8B9DDAD5B390BE9269C485A3EB20B52BFDF48`；AndroidTest APK 大小为 1,139,313 字节，SHA-256 为 `AB15A33910B05D6F2179147382E6A0730788582113E681125FE9BF331D6191ED`。两个生成物均不提交，也不在测试后删除。

当前扫描支持图片/视频分批查询、修改时间和文件大小增量过滤、稳定扫描上界、实时进度、Android 14 部分媒体权限和 Photo Picker 授权。

最近验证：`./gradlew.bat --offline :app:testDebugUnitTest --tests com.frameyu.app.sampling.VideoAnalysisTaskTest` 已成功通过，覆盖完整区间、断点续采和回调失败进度保留。

断点验证：`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 已成功通过；2026-07-24 已在 Redmi K70 Pro（`23117RK66C`，Android 16，HyperOS 构建 `OS3.0.304.0.WNMCNXM`）运行 Room 断点 instrumentation 测试并通过。

任务调度验证：`:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 已成功通过；2026-08-11 已在 Redmi K70 Pro 验证批次前台服务和通知通道。后台友好节流、通知暂停/打开和失败媒体去重已完成代码与本地回归，仍需在目标 Redmi K70 Pro 上完成最终交互验收。

真机安装验证：2026-08-11 已通过 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 覆盖安装 Debug APK，包名为 `com.frameyu.app`，启动入口为 `com.frameyu.app/.MainActivity`，版本 `0.1.0 (versionCode 1)`。设备已授予图片、视频和通知权限。桌面图标已配置为项目内 `ic_launcher` 矢量资源。

扫描真机验证：Redmi K70 Pro Android 16 首次扫描暴露 `Invalid column has_gain_map`，已增加 HDR 可选列失败降级；2026-08-11 再次全量扫描成功索引 10,293 个媒体，其中视频 1,040 个。媒体总数改为直接观察 Room `COUNT(*)`，覆盖安装或进程重建后首页仍能恢复真实计数和批次入口。

2026-08-11 已在 Redmi K70 Pro 上完成 54 项 instrumentation 测试，覆盖模型 assets 校验与真实推理、Room 迁移与批次状态机、媒体计数 Flow、视频断点恢复和取消边界；按 8 + 1 + 45 三组明确类清单运行，全部通过。真机测试后主包、测试包及运行时权限均保留。

同日创建批次 #1：10,293 个媒体生成 37,012 个工作项。已验证暂停、继续、覆盖安装、强制停止进程和重新启动后的自动续跑；批次保持同一 ID 和 checkpoint。前台通知进度增加 1 秒节流，12 秒观测窗口内 `ACTION_NOTIFY` 从 126 次降至 15 次；运行时设备温度 34.5°C，进程 PSS 约 393 MB。本轮未连接目标设备，未重新读取该批次的当前完成数。

解码失败诊断已定位到媒体 ID `1001180282`，路径为 `Pictures/Lark/1768878273557.png`。文件 PNG 签名正确，尺寸 1488×2105、大小 459,827 字节，但首个 `IDAT` 数据块 CRC 校验错误；独立解码器同样报告损坏，因此不是权限、模型或 Android 解码配置问题。质量、分类、向量和条件检测四个阶段各产生一个失败工作项，实际只涉及这一张损坏图片。应用按永久失败隔离并保留原文件，不自动转码、覆盖或删除；新页面会显示“失败工作项：4（涉及 1 个媒体）”。

本次批量模型管线尚未执行以下 Redmi K70 Pro 手动验收：

- 1 万级媒体批量分析已完成中断恢复验证；仍需跑到终态并验证真实失败重试、后台持续增长、通知暂停和通知返回批次页。
- 演唱会长视频的性能、温控、取消与断点恢复。
- Android R+ 系统回收站授权及 Activity 重建对账。
- HyperOS 动态照片兼容性。

连接设备后使用覆盖安装和手动 instrumentation，避免 Gradle `connectedDebugAndroidTest` 在任务结束时卸载测试包及被测应用，导致运行数据和权限丢失：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.frameyu.app.test/androidx.test.runner.AndroidJUnitRunner
```

除非明确需要验证首次安装流程，不执行 `adb uninstall`、`pm clear` 或测试后 APK 清理；重复验证始终使用 `adb install -r`，并保留 `app/build/outputs/apk/debug/app-debug.apk` 供真机体验。

多设备协作时应保留项目内的 `gradlew`、`gradlew.bat` 和 `gradle/wrapper`，不要提交 `local.properties`、`.gradle` 缓存或本地媒体文件。

## 关键提交记录

- `31770f2 chore: initialize mvp foundation`：规格、计划、项目骨架、基础 Room 数据层和版本控制配置。
- `bf3f84b feat: add media domain and room data layer`：应用模块配置和领域模型测试。
- `94d48cd feat: 添加媒体扫描和访问功能，支持媒体索引和权限管理`：MediaStore 扫描、增量索引状态和 URI 访问封装。
- `415e78c` 之前的阶段提交：完成模型资产、批次持久化、图片四阶段分析、视频模型聚合和 WorkManager 串行恢复基础。
- `75b9a6c fix(P0): 加固批次版本与阶段依赖`：补齐并发幂等、四阶段版本矩阵和分类到条件检测依赖。
- `542dfb9 feat(P1): 增加批量分析和整理确认页面` 及后续任务 9 修复：接入批次页面、统一整理、失败详情限流和安全删除授权。
- `e1a59ed fix(P0): 终结重复扫描流与授权过期锁`：完成可取消散列和授权过期对账的最终质量门禁。
- `d334e2a` 至 `d2e8e32`：完成应用可见性跟踪、图片/视频后台节流、通知运行模式与 ETA、通知暂停/打开，以及失败工作项和失败媒体去重展示。
