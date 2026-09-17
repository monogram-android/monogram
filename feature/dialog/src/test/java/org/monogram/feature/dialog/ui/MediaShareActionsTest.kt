package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaShareActionsTest {
    @Test
    fun mediaMimeFollowsTheFileName() {
        assertEquals("image/png", MediaShareActions.mimeFor("photo.png", fallbackMedia = false))
        assertEquals("image/jpeg", MediaShareActions.mimeFor("photo.jpg", fallbackMedia = false))
        assertEquals("image/webp", MediaShareActions.mimeFor("sticker.webp", fallbackMedia = false))
        assertEquals("video/mp4", MediaShareActions.mimeFor("clip.mp4", fallbackMedia = true))
    }

    @Test
    fun clipboardCopyGetsARealExtension() {
        assertEquals("jpg", org.monogram.core.ui.clipboardMediaExtension("image/jpeg"))
        assertEquals("png", org.monogram.core.ui.clipboardMediaExtension("image/png"))
        assertEquals("mp4", org.monogram.core.ui.clipboardMediaExtension("video/mp4"))
    }

    @Test
    fun clipboardCopyWritesANamedFile() {
        val src = kotlin.io.path.createTempFile(prefix = "hash", suffix = "").toFile()
        src.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        val dest = org.monogram.core.ui.clipboardMediaCopy(src, src.parentFile!!, "image/jpeg")
        assertEquals("copy.jpg", dest.name)
        assertEquals(src.readBytes().toList(), dest.readBytes().toList())
        dest.delete()
        src.delete()
    }

    @Test
    fun unknownNameFallsBackToTheItemKind() {
        assertEquals("image/jpeg", MediaShareActions.mimeFor(null, fallbackMedia = false))
        assertEquals("video/mp4", MediaShareActions.mimeFor(null, fallbackMedia = true))
        assertEquals("image/jpeg", MediaShareActions.mimeFor("blob", fallbackMedia = false))
        assertEquals("video/mp4", MediaShareActions.mimeFor("blob", fallbackMedia = true))
    }

    @Test
    fun saveFileNameIsSafeAndKeepsItsExtension() {
        assertEquals("photo.png", shareFileName("photo.png"))
        assertEquals("clip.mp4", shareFileName("clip.mp4"))
        assertEquals("inva_lid.png", shareFileName("inva?lid.png"))
        assertEquals("file", shareFileName("///"))
    }
}
