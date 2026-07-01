package org.monogram.presentation.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.presentation.features.share.PendingAttachmentKind
import org.monogram.presentation.features.share.inferPendingAttachmentKind

class UriTempFileUtilsTest {
    @Test
    fun `resolveMediaExtension keeps heic for heic images`() {
        assertEquals("heic", resolveMediaExtension("image/heic"))
    }

    @Test
    fun `resolveMediaExtension keeps png for png images`() {
        assertEquals("png", resolveMediaExtension("image/png"))
    }

    @Test
    fun `resolveMediaExtension keeps webp for webp images`() {
        assertEquals("webp", resolveMediaExtension("image/webp"))
    }

    @Test
    fun `resolveMediaExtension falls back to jpg for unknown image mime`() {
        assertEquals("jpg", resolveMediaExtension("image/unknown"))
    }

    @Test
    fun `inferPendingAttachmentKind detects video by mime without mp4 extension`() {
        assertEquals(
            PendingAttachmentKind.VIDEO,
            inferPendingAttachmentKind(
                localPath = "/tmp/clip.bin",
                mimeType = "video/quicktime"
            )
        )
    }

    @Test
    fun `inferPendingAttachmentKind detects video by common extension`() {
        assertEquals(
            PendingAttachmentKind.VIDEO,
            inferPendingAttachmentKind("/tmp/clip.MOV")
        )
    }

    @Test
    fun `inferPendingAttachmentKind detects gif before photo fallback`() {
        assertEquals(
            PendingAttachmentKind.GIF,
            inferPendingAttachmentKind("/tmp/animation.gif")
        )
    }
}
