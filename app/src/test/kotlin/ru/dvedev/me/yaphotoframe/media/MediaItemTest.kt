package ru.dvedev.me.yaphotoframe.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemTest {

    private fun item(kind: MediaKind, preview: PreviewUrl?) = MediaItem(
        path = "/a/x", name = "x", kind = kind, mimeType = "x/y", sizeBytes = 1,
        takenAtMillis = null, addedAtMillis = null, preview = preview,
    )

    @Test
    fun `снимку без превью нечем показаться`() {
        assertFalse(item(MediaKind.PHOTO, null).isShowable)
    }

    @Test
    fun `ролик играет и без превью`() {
        assertTrue(item(MediaKind.VIDEO, null).isShowable)
    }
}
