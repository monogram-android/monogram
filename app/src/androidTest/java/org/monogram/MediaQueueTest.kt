package org.monogram

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.monogram.core.common.Outcome
import org.monogram.core.models.PeerId
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramMediaFetcher
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MediaQueueTest {
    @Test
    fun mixedPriorityDownloadsFinishWithoutHang(): Unit = runBlocking {
        val cache = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "media-queue")
        cache.deleteRecursively()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val repo = MediaRepository(
            cacheRoot = cache,
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(25)
                active.decrementAndGet()
                File(destPath).writeBytes(byteArrayOf(1, 2, 3, 4))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 5,
        )
        try {
            val jobs = (1L..20L).map { id ->
                async(Dispatchers.IO) {
                    val priority = if (id > 12) MediaPriority.VISIBLE else MediaPriority.IDLE
                    repo.ensureLocalAvatar(PeerId(id), "photo:$id", priority)
                }
            }
            val results = withTimeout(8_000) { jobs.map { it.await() } }
            assertTrue(results.all { it is Outcome.Ok })
            assertTrue("peak=${peak.get()}", peak.get() in 1..5)
        } finally {
            repo.shutdown()
        }
    }
}
