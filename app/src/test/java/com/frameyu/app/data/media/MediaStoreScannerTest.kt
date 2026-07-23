package com.frameyu.app.data.media

import com.frameyu.app.data.db.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaStoreScannerTest {
    @Test
    fun rejectsNonPositiveBatchSize() {
        assertThrows(IllegalArgumentException::class.java) {
            validateMediaStoreBatchSize(0)
        }
    }

    @Test
    fun acceptsPositiveBatchSize() {
        validateMediaStoreBatchSize(1)
        validateMediaStoreBatchSize(100)
    }

    @Test
    fun filtersUnchangedMediaAndKeepsNewOrChangedMedia() {
        val unchanged = mediaAsset(uri = "content://media/unchanged", modified = 1_000L, size = 10L)
        val changedTime = unchanged.copy(
            uri = "content://media/changed-time",
            lastModifiedMillis = 2_000L,
        )
        val changedSize = unchanged.copy(
            uri = "content://media/changed-size",
            sizeBytes = 20L,
        )
        val newMedia = unchanged.copy(uri = "content://media/new")

        val result = filterChangedMediaAssets(
            candidates = listOf(unchanged, changedTime, changedSize, newMedia),
            existingByUri = listOf(unchanged, changedTime.copy(lastModifiedMillis = 1_000L), changedSize)
                .associateBy(MediaAssetEntity::uri),
        )

        assertEquals(listOf(changedTime, newMedia), result)
    }

    @Test
    fun convertsMediaStoreSecondsToMillis() {
        assertEquals(1_700_000_000_000L, mediaStoreSecondsToMillis(1_700_000_000L))
        assertEquals(0L, mediaStoreSecondsToMillis(-1L))
        assertEquals((Long.MAX_VALUE / 1_000L) * 1_000L, mediaStoreSecondsToMillis(Long.MAX_VALUE))
    }

    @Test
    fun buildsIncrementalWindowWithStableExclusiveUpperBoundary() {
        val window = createMediaStoreScanWindow(
            sinceLastModifiedMillis = 10_999L,
            scanBoundaryMillis = 20_999L,
        )

        assertEquals(
            "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} >= ? AND " +
                "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} < ?",
            window.selection,
        )
        assertEquals(listOf("10", "20"), window.selectionArgs.toList())
    }

    @Test
    fun filtersMediaWhenOnlyFileSizeChanges() {
        val existing = mediaAsset(uri = "content://media/photo", modified = 1_000L, size = 10L)
        val resized = existing.copy(sizeBytes = 11L)

        val result = filterChangedMediaAssets(
            candidates = listOf(resized),
            existingByUri = mapOf(existing.uri to existing),
        )

        assertEquals(listOf(resized), result)
    }

    private fun mediaAsset(uri: String, modified: Long, size: Long): MediaAssetEntity = MediaAssetEntity(
        id = uri.hashCode().toLong().let { if (it < 0L) -it else it },
        uri = uri,
        mimeType = "image/jpeg",
        takenAtMillis = modified,
        durationMillis = 0L,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        sizeBytes = size,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = modified,
    )
}
