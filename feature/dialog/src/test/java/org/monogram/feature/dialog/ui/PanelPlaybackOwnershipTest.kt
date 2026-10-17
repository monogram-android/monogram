package org.monogram.feature.dialog.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.feature.dialog.PanelBucket
import org.monogram.feature.dialog.PanelFrameCache
import org.monogram.feature.dialog.PanelFrameClip
import org.monogram.feature.dialog.PanelTag
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelPlaybackOwnershipTest {
    @Test
    fun cancelAfterLottieCreateDestroysHandleAndReleasesSlot() = runTest {
        CompactVideoSlots.resetForTests()
        val owner = FakeLottieOwner()
        val worker = StandardTestDispatcher(testScheduler)
        try {
            val job = launch {
                withCompactLottie(byteArrayOf(1), worker, owner) {
                    awaitCancellation()
                }
            }
            runCurrent()
            assertEquals(1, owner.created)
            assertEquals(1, owner.live)
            assertEquals(0, owner.destroyed)
            job.cancel()
            job.join()
            assertEquals(1, owner.destroyed)
            assertEquals(0, owner.live)
            repeat(CompactVideoSlots.MAX) {
                assertTrue(CompactVideoSlots.tryAcquire())
            }
        } finally {
            CompactVideoSlots.resetForTests()
        }
    }

    @Test
    fun cancelAfterVpxCreateDestroysHandlesAndReleasesSlot() = runTest {
        VpxStickerSlots.resetForTests()
        val owner = FakeVpxOwner()
        val worker = StandardTestDispatcher(testScheduler)
        try {
            val job = launch {
                runLegacyVpxSlot(worker) {
                    withOwnedVpxHandles(owner, 2) {
                        awaitCancellation()
                    }
                }
            }
            runCurrent()
            assertEquals(2, owner.created)
            assertEquals(2, owner.live)
            assertEquals(0, owner.destroyed)
            assertEquals(1, VpxStickerSlots.inUse())
            job.cancel()
            job.join()
            assertEquals(2, owner.destroyed)
            assertEquals(0, owner.live)
            assertEquals(0, VpxStickerSlots.inUse())
        } finally {
            VpxStickerSlots.resetForTests()
        }
    }

    @Test
    fun oneFrameLoopPublishesBitmapAndDoesNotRecycleIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bitmaps = TrackingBitmaps()
        val directory = File(System.getProperty("java.io.tmpdir"), "panel-own-${System.nanoTime()}")
        directory.mkdirs()
        val file = File(directory, "animated.tgs")
        file.writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0))
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pool = PanelPlayerPool(
            cacheDir = directory,
            scope = scope,
            decodeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            bitmapDispatcher = dispatcher,
            frameBitmaps = bitmaps,
            decodeFrames = { _, _, _, _ ->
                PanelFrameClip(2, 2, 33, listOf(ByteArray(2 * 2 * 4)))
            },
        )
        try {
            pool.bind(
                viewId = "cell",
                documentId = 9L,
                bucket = PanelBucket.KeyboardSticker,
                file = file,
                playback = PanelCellPlayback(
                    vpxLoop = false,
                    tgsLoop = true,
                    bucket = PanelBucket.KeyboardSticker,
                    decode = true,
                ),
            )
            advanceUntilIdle()
            val shown = pool.frame(9L)
            assertSame(bitmaps.created.single(), shown)
            assertFalse(bitmaps.wasRecycled(shown))
            assertTrue(bitmaps.recycled.isEmpty())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun withheldDecodeDrawsCachedPosterWithoutNativeWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bitmaps = TrackingBitmaps()
        val directory =
            File(System.getProperty("java.io.tmpdir"), "panel-poster-${System.nanoTime()}")
        directory.mkdirs()
        val file = File(directory, "cached.tgs")
        file.writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0))
        val rgba = ByteArray(2 * 2 * 4) { 4 }
        PanelFrameCache.write(
            PanelFrameCache.file(directory, 9L, PanelBucket.KeyboardSticker, PanelTag.Tgs),
            PanelFrameClip(2, 2, 33, listOf(rgba)),
        )
        var decodes = 0
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val pool = PanelPlayerPool(
            cacheDir = directory,
            scope = scope,
            decodeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            bitmapDispatcher = dispatcher,
            frameBitmaps = bitmaps,
            decodeFrames = { _, _, _, _ ->
                decodes += 1
                null
            },
        )
        try {
            pool.bind(
                viewId = "cell",
                documentId = 9L,
                bucket = PanelBucket.KeyboardSticker,
                file = file,
                playback = PanelCellPlayback(
                    vpxLoop = false,
                    tgsLoop = false,
                    bucket = PanelBucket.KeyboardSticker,
                    decode = false,
                ),
            )
            advanceUntilIdle()
            val shown = pool.frame(9L)
            assertSame(bitmaps.created.single(), shown)
            assertFalse(bitmaps.wasRecycled(shown))
            assertEquals(0, decodes)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            directory.deleteRecursively()
        }
    }
}

private class FakeLottieOwner : LottieHandleOwner {
    var created = 0
    var destroyed = 0
    var live = 0

    override fun create(data: ByteArray): Long {
        created += 1
        live += 1
        return 11L
    }

    override fun destroy(handle: Long) {
        check(handle == 11L) { "destroyed an unowned Lottie handle: $handle" }
        destroyed += 1
        live -= 1
    }

    override fun frameCount(handle: Long): Int = 4

    override fun frameRate(handle: Long): Float = 30f

    override fun size(handle: Long): Pair<Int, Int> = 8 to 8

    override fun renderFrame(handle: Long, frame: Float, width: Int, height: Int): ByteArray =
        ByteArray(width * height * 4)
}

private class FakeVpxOwner : VpxHandleOwner {
    var created = 0
    var destroyed = 0
    var live = 0
    private var next = 1L

    override fun create(): Long {
        created += 1
        live += 1
        return next++
    }

    override fun destroy(handle: Long) {
        check(handle != 0L) { "destroyed an unowned VPX handle" }
        destroyed += 1
        live -= 1
    }

    override fun decode(handle: Long, packet: ByteArray): VpxDecodedFrame? = null

    override fun decodeAlpha(handle: Long, packet: ByteArray): VpxDecodedAlpha? = null
}

private class TrackingBitmaps : PanelFrameBitmaps {
    val created = ArrayList<ImageBitmap>()
    val recycled = ArrayList<ImageBitmap>()

    override fun decode(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
        val bitmap = OwnedImageBitmap(width, height)
        created += bitmap
        return bitmap
    }

    override fun recycle(image: ImageBitmap) {
        recycled += image
    }

    fun wasRecycled(image: ImageBitmap?): Boolean = recycled.any { it === image }
}

private class OwnedImageBitmap(
    override val width: Int,
    override val height: Int,
) : ImageBitmap {
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha: Boolean = true
    override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

    override fun prepareToDraw() = Unit

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit
}
