package com.frameyu.app.sampling

/** 视频采样的可持久化断点，不包含 Bitmap、解码器或文件句柄。 */
data class SamplingCheckpoint(
    val planId: String,
    val rangeIndex: Int,
    val nextTimestampMillis: Long,
    val sampledFrameCount: Long,
) {
    init {
        require(planId.isNotBlank()) { "采样计划标识不能为空" }
        require(rangeIndex >= 0) { "采样区间索引不能为负数" }
        require(nextTimestampMillis >= 0L) { "下一个采样时间不能为负数" }
        require(sampledFrameCount >= 0L) { "已采样帧数不能为负数" }
    }
}
