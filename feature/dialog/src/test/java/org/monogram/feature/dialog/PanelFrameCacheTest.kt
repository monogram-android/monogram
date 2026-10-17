package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

class PanelFrameCacheTest {
    @Test
    fun cacheAt96IsNotAHitFor216() {
        val dir = File(System.getProperty("java.io.tmpdir"), "panel-side-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val clip = PanelFrameClip(
                width = 96,
                height = 96,
                delayMs = 33,
                frames = listOf(ByteArray(96 * 96 * 4) { 7 }),
                complete = true,
                sourceDurationMs = 33L,
            )
            val file = PanelFrameCache.file(dir, 4L, PanelBucket.TabStrip, PanelTag.Tgs)
            PanelFrameCache.write(file, clip)
            assertNull(PanelFrameCache.read(file, requestedSide = 216))
            assertEquals(96, PanelFrameCache.read(file, requestedSide = 96)?.width)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun partialClipDoesNotReportCompleteAndMustNotLoop() {
        val dir = File(System.getProperty("java.io.tmpdir"), "panel-partial-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val frame = ByteArray(8 * 8 * 4) { 3 }
            val partial = PanelFrameClip(
                width = 8,
                height = 8,
                delayMs = 33,
                frames = listOf(frame, frame),
                complete = false,
                sourceDurationMs = 2_000L,
            )
            assertFalse(partial.complete)
            assertFalse(panelClipMayLoop(partial))
            val file = PanelFrameCache.file(dir, 5L, PanelBucket.KeyboardSticker, PanelTag.Tgs)
            PanelFrameCache.write(file, partial)
            val read = PanelFrameCache.read(file, requestedSide = 8)
            assertFalse(read?.complete ?: true)
            assertEquals(2_000L, read?.sourceDurationMs)
            assertFalse(panelClipMayLoop(read!!))
            assertTrue(panelClipMayLoop(partial.copy(complete = true)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun packedSizeAboveTheCapReturnsNull() {
        val file =
            File(System.getProperty("java.io.tmpdir"), "panel-packed-${System.nanoTime()}.frames")
        try {
            DataOutputStream(file.outputStream()).use { data ->
                data.writeBytes("PF02")
                data.writeInt(2)
                data.writeInt(2)
                data.writeInt(33)
                data.writeInt(1)
                data.writeBoolean(true)
                data.writeLong(33L)
                data.writeInt(PanelFrameCache.PACKED_BYTE_CAP + 1)
            }
            assertNull(PanelFrameCache.read(file))
            assertNull(PanelFrameCache.read(file, requestedSide = 2))
        } finally {
            file.delete()
        }
    }

    @Test
    fun oldMagicIsAMissRatherThanACompletedClip() {
        val file =
            File(System.getProperty("java.io.tmpdir"), "panel-pf01-${System.nanoTime()}.frames")
        try {
            DataOutputStream(file.outputStream()).use { data ->
                data.writeBytes("PF01")
                data.writeInt(2)
                data.writeInt(2)
                data.writeInt(33)
                data.writeInt(1)
                data.writeInt(4)
                data.write(byteArrayOf(1, 2, 3, 4))
            }
            assertNull(PanelFrameCache.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sourceFrameIndexAt48FpsStaysOnTheSourceTimeline() {
        val index = sourceFrameIndex(elapsedMs = 500L, frameCount = 120, fps = 48f)
        val fixedStep = (500 / 33) * 2
        assertEquals(24, index)
        assertTrue(fixedStep >= 30)
        assertNotEquals(fixedStep, index)
    }
}
