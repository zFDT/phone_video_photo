package com.frameyu.app.export

import android.content.ContentValues

/** 导出时可安全复制的本地媒体元数据；null 表示源媒体没有提供该字段。 */
data class LocalMetadata(
    val capturedAtMillis: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val isHdr: Boolean? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
)

/** 将本地元数据转换为发布到 MediaStore 的字段，不依赖 UI 或具体导出器。 */
object MetadataCopier {
    const val DATE_TAKEN = "datetaken"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val ORIENTATION = "orientation"
    const val IS_HDR = "is_hdr"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"

    /** 只返回明确可用且在安全范围内的字段；GPS 缺失时不会生成任何坐标。 */
    fun toValues(metadata: LocalMetadata): Map<String, Any> = buildMap {
        metadata.capturedAtMillis?.takeIf { it > 0L }?.let { put(DATE_TAKEN, it) }
        metadata.width?.takeIf { it > 0 }?.let { put(WIDTH, it) }
        metadata.height?.takeIf { it > 0 }?.let { put(HEIGHT, it) }
        metadata.rotationDegrees?.takeIf { it in 0..359 }?.let { put(ORIENTATION, it) }
        metadata.isHdr?.let { put(IS_HDR, it) }

        val latitude = metadata.gpsLatitude
        val longitude = metadata.gpsLongitude
        if (latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) {
            put(LATITUDE, latitude)
            put(LONGITUDE, longitude)
        }
    }

    /** 将 [toValues] 的结果写入目标 ContentValues，便于导出器在发布前接入。 */
    fun copyTo(destination: ContentValues, metadata: LocalMetadata) {
        toValues(metadata).forEach { (key, value) ->
            when (value) {
                is Long -> destination.put(key, value)
                is Int -> destination.put(key, value)
                is Boolean -> destination.put(key, value)
                is Double -> destination.put(key, value)
            }
        }
    }
}
