# mvp-foundation 里程碑

## 目的

记录帧遇 Android 版的第一个可协作基础阶段，作为后续提交到 GitHub 私有仓库和多设备协作开发的共同基线。

## 当前范围

- 原生 Kotlin + Jetpack Compose Android 项目骨架。
- 目标设备：Redmi K70 Pro（ALSC，1 TB）。
- 本地媒体分析方向：规则评分、ML Kit、MediaPipe Image Embedder、可插拔 LiteRT 模型。
- 任务 2 的领域模型和 Room 数据层基础文件。
- 暂不包含真实媒体扫描、视频采样、导出和相册整理业务。

## 版本控制约定

- 里程碑名称：`mvp-foundation`。
- 推荐分支：`mvp-foundation`。
- 后续功能按独立任务提交，提交前运行对应的单元测试或静态检查。
- 不提交原始照片、视频、分析缓存、Android Studio 本地配置、签名文件和任何 Token。
- 推送 GitHub 前先创建私有仓库，再配置远程地址；远程地址和凭据不写入项目文件。

## 当前验证限制

当前开发环境没有 Gradle Wrapper，也没有系统 `gradle` 命令，因此尚未执行 APK 构建和 Gradle 测试。Android Studio 或配置好 Gradle Wrapper 后，首个验证命令为：

```powershell
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

连接 Redmi K70 Pro 后再执行：

```powershell
./gradlew :app:connectedDebugAndroidTest
```

## 已提交记录

- `31770f2 chore: initialize mvp foundation`：规格、计划、项目骨架、基础 Room 数据层和版本控制配置。
- `bf3f84b feat: add media domain and room data layer`：应用模块配置和领域模型测试。
