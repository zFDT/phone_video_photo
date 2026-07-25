package com.frameyu.app.export

/**
 * 候选帧的归一化裁切区域，坐标范围为 [0, 1]。
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "裁切坐标必须是有限数"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "裁切坐标必须位于 [0, 1]"
        }
        require(left < right) { "left 必须小于 right" }
        require(top < bottom) { "top 必须小于 bottom" }
    }

    companion object {
        /**
         * 按 width / height 比例构造最大、居中的归一化裁切区域。
         */
        fun fromCenter(aspectRatio: Float): CropRect {
            require(aspectRatio.isFinite() && aspectRatio > 0f) {
                "aspectRatio 必须是正的有限数"
            }

            val width: Float
            val height: Float
            if (aspectRatio >= 1f) {
                width = 1f
                height = 1f / aspectRatio
            } else {
                width = aspectRatio
                height = 1f
            }

            return CropRect(
                left = (1f - width) / 2f,
                top = (1f - height) / 2f,
                right = (1f + width) / 2f,
                bottom = (1f + height) / 2f
            )
        }

        /**
         * 将任意坐标裁切到归一化边界内，并拒绝裁切后为空的区域。
         */
        fun clipped(left: Float, top: Float, right: Float, bottom: Float): CropRect {
            require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
                "裁切坐标必须是有限数"
            }
            return CropRect(
                left = left.coerceIn(0f, 1f),
                top = top.coerceIn(0f, 1f),
                right = right.coerceIn(0f, 1f),
                bottom = bottom.coerceIn(0f, 1f)
            )
        }
    }
}
