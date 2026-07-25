package com.frameyu.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCopierTest {
    @Test
    fun `复制可用的时间分辨率旋转和 HDR 元数据`() {
        val values = MetadataCopier.toValues(
            LocalMetadata(
                capturedAtMillis = 1_725_000_000_123L,
                width = 1_920,
                height = 1_080,
                rotationDegrees = 90,
                isHdr = true,
            ),
        )

        assertEquals(1_725_000_000_123L, values[MetadataCopier.DATE_TAKEN])
        assertEquals(1_920, values[MetadataCopier.WIDTH])
        assertEquals(1_080, values[MetadataCopier.HEIGHT])
        assertEquals(90, values[MetadataCopier.ORIENTATION])
        assertEquals(true, values[MetadataCopier.IS_HDR])
    }

    @Test
    fun `缺失或默认元数据不会被伪造`() {
        val values = MetadataCopier.toValues(LocalMetadata())

        assertFalse(values.containsKey(MetadataCopier.DATE_TAKEN))
        assertFalse(values.containsKey(MetadataCopier.WIDTH))
        assertFalse(values.containsKey(MetadataCopier.HEIGHT))
        assertFalse(values.containsKey(MetadataCopier.ORIENTATION))
        assertFalse(values.containsKey(MetadataCopier.IS_HDR))
        assertFalse(values.containsKey(MetadataCopier.LATITUDE))
        assertFalse(values.containsKey(MetadataCopier.LONGITUDE))
    }

    @Test
    fun `GPS 只有在经纬度成对可用时才复制`() {
        val missingLongitude = MetadataCopier.toValues(
            LocalMetadata(gpsLatitude = 31.2304),
        )
        val complete = MetadataCopier.toValues(
            LocalMetadata(gpsLatitude = 31.2304, gpsLongitude = 121.4737),
        )

        assertFalse(missingLongitude.containsKey(MetadataCopier.LATITUDE))
        assertFalse(missingLongitude.containsKey(MetadataCopier.LONGITUDE))
        assertEquals(31.2304, complete[MetadataCopier.LATITUDE])
        assertEquals(121.4737, complete[MetadataCopier.LONGITUDE])
    }

    @Test
    fun `非法 GPS 不会进入导出元数据`() {
        val values = MetadataCopier.toValues(
            LocalMetadata(gpsLatitude = 91.0, gpsLongitude = Double.NaN),
        )

        assertTrue(values.isEmpty())
    }
}
