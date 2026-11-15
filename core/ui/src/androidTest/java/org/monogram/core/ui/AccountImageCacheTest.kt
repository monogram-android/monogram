package org.monogram.core.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import coil.memory.MemoryCache
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(coil.annotation.ExperimentalCoilApi::class)
class AccountImageCacheTest {
    @Test
    fun identicalKeysAndClearingRemainAccountLocal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "image-accounts-${UUID.randomUUID()}")
        val firstRoot = File(directory, "11/media")
        val secondRoot = File(directory, "22/media")
        val first = ImageCache.accountLoader(context, firstRoot)
        val second = ImageCache.accountLoader(context, secondRoot)
        val key = MemoryCache.Key("same-peer-photo")
        val firstBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val secondBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            assertNotSame(first, second)
            assertSame(first, ImageCache.accountLoader(context, firstRoot))
            assertNotEquals(first.diskCache!!.directory, second.diskCache!!.directory)
            first.memoryCache!![key] = MemoryCache.Value(firstBitmap)
            second.memoryCache!![key] = MemoryCache.Value(secondBitmap)
            assertSame(firstBitmap, first.memoryCache!![key]?.bitmap)
            assertSame(secondBitmap, second.memoryCache!![key]?.bitmap)
            for (loader in listOf(first, second)) {
                loader.diskCache!!.openEditor("same-peer-photo")!!.let { editor ->
                    File(editor.data.toString()).writeText("cached")
                    editor.commit()
                }
            }
            ImageCache.clear(firstRoot)
            assertNull(first.memoryCache!![key])
            assertSame(secondBitmap, second.memoryCache!![key]?.bitmap)
            assertNull(first.diskCache!!.openSnapshot("same-peer-photo"))
            second.diskCache!!.openSnapshot("same-peer-photo").use { assertNotNull(it) }
        } finally {
            val firstDisk = File(first.diskCache!!.directory.toString())
            val secondDisk = File(second.diskCache!!.directory.toString())
            ImageCache.release(firstRoot)
            ImageCache.release(secondRoot)
            firstDisk.deleteRecursively()
            secondDisk.deleteRecursively()
            directory.deleteRecursively()
        }
    }
}
