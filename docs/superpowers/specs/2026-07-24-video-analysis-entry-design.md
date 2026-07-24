# 视频分析入口与任务状态设计

## 目标

让用户在媒体扫描完成后，可以从视频列表选择一个视频，启动精彩帧分析，并看到任务排队、运行、完成或失败状态。

## 范围

- 媒体库页面增加视频列表，使用 Room 索引数据，不重新扫描 MediaStore。
- 每个视频提供“开始分析”入口。
- 创建 `AnalysisTaskEntity` 后交给现有 `VideoAnalysisWorkScheduler`。
- 列表显示任务状态和百分比。
- 本阶段不实现候选帧浏览、导出和相册整理页面。

## 数据流

```text
Room media_assets
    ↓ observeVideos()
LibraryViewModel
    ↓ startVideoAnalysis()
analysis_tasks(QUEUED)
    ↓
VideoAnalysisWorkScheduler
    ↓
VideoAnalysisWorker → RoomVideoAnalysisWorkerRunner
    ↓
analysis_tasks / analysis_candidates
```

## 交互约束

- 没有视频索引时显示明确的空状态和扫描提示。
- 已排队或运行中的任务不重复入队。
- 已完成任务显示“重新分析”，重新分析会从新任务状态开始并覆盖同一媒体的候选帧。
- 页面只显示媒体元数据和任务状态，不持有 Bitmap 或解码器。

## 验证

- JVM 单元测试覆盖任务创建、重复入队保护和媒体列表状态映射。
- Debug APK 编译验证。
- 真机 UI 和长视频分析留到代码阶段完成后统一验证。
