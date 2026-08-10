package com.frameyu.app.ui.organize

import com.frameyu.app.organize.OrganizationGroup
import com.frameyu.app.organize.OrganizationMediaItem
import com.frameyu.app.organize.OrganizerFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizerPresentationTest {
    @Test
    fun `扁平列表保持组标题媒体行和视频操作顺序`() {
        val imageGroup = group("images", OrganizerFilter.SIMILAR_MEDIA, 2)
        val videoGroup = group("video", OrganizerFilter.VIDEO_HIGHLIGHT, 1)

        val entries = flattenOrganizerGroups(listOf(imageGroup, videoGroup))

        assertEquals(
            listOf("header", "media:1", "media:2", "header", "media:1", "action"),
            entries.map { entry ->
                when (entry) {
                    is OrganizerListEntry.Header -> "header"
                    is OrganizerListEntry.Media -> "media:${entry.item.mediaId}"
                    is OrganizerListEntry.VideoCandidatesAction -> "action"
                }
            },
        )
    }

    @Test
    fun `扁平列表 key 唯一且相同输入下稳定`() {
        val groups = listOf(
            group("same", OrganizerFilter.SIMILAR_MEDIA, 2),
            group("same", OrganizerFilter.SIMILAR_MEDIA, 2),
        )

        val firstKeys = flattenOrganizerGroups(groups).map(OrganizerListEntry::key)
        val secondKeys = flattenOrganizerGroups(groups.map { it.copy() }).map(OrganizerListEntry::key)

        assertEquals(firstKeys.size, firstKeys.toSet().size)
        assertEquals(firstKeys, secondKeys)
    }

    @Test
    fun `万级单组被拆成独立父列表项`() {
        val entries = flattenOrganizerGroups(group("large", OrganizerFilter.EXACT_DUPLICATE, 10_000).let(::listOf))

        assertEquals(10_001, entries.size)
        assertTrue(entries.first() is OrganizerListEntry.Header)
        assertTrue(entries.drop(1).all { it is OrganizerListEntry.Media })
    }

    private fun group(id: String, filter: OrganizerFilter, itemCount: Int) = OrganizationGroup(
        id = id,
        filter = filter,
        confidence = null,
        suggestedKeepId = null,
        items = (1..itemCount).map { index ->
            OrganizationMediaItem(
                mediaId = index.toLong(),
                uri = "content://media/$index",
                sizeBytes = index.toLong(),
                lastModifiedMillis = index.toLong(),
                label = "媒体 $index",
                selectableForDeletion = true,
            )
        },
    )
}
