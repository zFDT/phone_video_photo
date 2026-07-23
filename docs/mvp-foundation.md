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
