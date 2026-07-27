package com.frameyu.app.album

import com.frameyu.app.data.db.MediaAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumClassifierTest {
    private val classifier = AlbumClassifier()

    @Test
    fun classifiesScreenshotFromPathMetadata() {
        val asset = media(uri = "content://media/DCIM/Screenshots/shot.png")

        assertEquals(setOf(AlbumCategory.SCREENSHOT), classifier.classify(asset))
    }

    @Test
    fun classifiesScreenRecordingOnlyWhenVideoAndNameMatches() {
        val asset = media(
            uri = "content://media/Movies/ScreenRecord_20260725.mp4",
            mimeType = "video/mp4",
            durationMillis = 3_000L,
        )

        assertEquals(setOf(AlbumCategory.SCREEN_RECORDING), classifier.classify(asset))
    }

    @Test
    fun doesNotTreatEveryVideoAsScreenRecording() {
        val asset = media(
            uri = "content://media/Movies/holiday.mp4",
            mimeType = "video/mp4",
            durationMillis = 3_000L,
        )

        assertEquals(setOf(AlbumCategory.OTHER), classifier.classify(asset))
    }

    @Test
    fun returnsOtherForCategoriesWithoutMetadataRule() {
        val asset = media(uri = "content://media/Documents/invoice.png")

        assertEquals(setOf(AlbumCategory.OTHER), classifier.classify(asset))
    }

    @Test
    fun supportsAddingAConservativeMetadataRule() {
        val classifier = AlbumClassifier(
            rules = listOf(
                AlbumRule(AlbumCategory.DOCUMENT) { asset ->
                    asset.uri.contains("/Documents/", ignoreCase = true)
                },
            ),
        )

        assertEquals(
            setOf(AlbumCategory.DOCUMENT),
            classifier.classify(media(uri = "content://media/Documents/invoice.png")),
        )
    }

    @Test
    fun duplicateKeyIsStableForSameMetadata() {
        val first = media(uri = "content://media/DCIM/a.jpg", sizeBytes = 123L)
        val second = first.copy()

        assertEquals(classifier.duplicateKey(first), classifier.duplicateKey(second))
    }

    @Test
    fun duplicateKeyIncludesStableMediaFieldsButNotUri() {
        val first = media(uri = "content://media/DCIM/a.jpg")
        val differentSize = first.copy(sizeBytes = first.sizeBytes + 1L)
        val differentUri = first.copy(uri = "content://media/DCIM/b.jpg")

        assertNotEquals(classifier.duplicateKey(first), classifier.duplicateKey(differentSize))
        assertEquals(classifier.duplicateKey(first), classifier.duplicateKey(differentUri))
    }

    private fun media(
        uri: String,
        mimeType: String = "image/jpeg",
        durationMillis: Long = 0L,
        sizeBytes: Long = 100L,
    ): MediaAssetEntity = MediaAssetEntity(
        id = uri.hashCode().toLong(),
        uri = uri,
        mimeType = mimeType,
        takenAtMillis = 1_000L,
        durationMillis = durationMillis,
        width = 1_080,
        height = 1_920,
        rotationDegrees = 0,
        sizeBytes = sizeBytes,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 2_000L,
    )
}
