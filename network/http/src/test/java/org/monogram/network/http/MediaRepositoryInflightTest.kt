package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

class MediaRepositoryInflightTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun concurrentAvatarFetchSharesOneDownload() = runBlocking {
        val fetches = AtomicInteger(0)
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                fetches.incrementAndGet()
                delay(80)
                File(destPath).writeBytes(byteArrayOf(1, 2, 3, 4))
                Outcome.Ok(destPath)
            },
        )
        val first = async { repo.ensureLocalAvatar(PeerId(11), "photo:11") }
        val second = async { repo.ensureLocalAvatar(PeerId(11), "photo:11") }
        val a = first.await()
        val b = second.await()
        assertTrue(a is Outcome.Ok)
        assertTrue(b is Outcome.Ok)
        assertEquals(1, fetches.get())
    }

    @Test
    fun telegramDownloadPublishesGrowingProgressWithoutCachingPartial() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                val staged = File("$destPath.123.1.part")
                staged.writeBytes(ByteArray(1000))
                started.complete(Unit)
                proceed.await()
                FileOutputStream(staged, true).use { it.write(ByteArray(1000)) }
                check(staged.renameTo(File(destPath)))
                Outcome.Ok(destPath)
            },
        )
        val message = Message(
            id = MessageId(PeerId(7), 3),
            senderId = null,
            text = "notes.pdf",
            date = 0L,
            outgoing = false,
            mediaKind = "document",
            mediaCacheKey = "doc:progress",
            fileSize = 2000L,
        )
        val job = async { repo.ensureLocalMessageMedia(message) }
        started.await()
        withTimeout(1_000) {
            while (repo.progressBytes("doc:progress") < 1000L) delay(10)
        }
        assertNull(repo.cachedFile("doc:progress"))
        proceed.complete(Unit)
        val result = job.await()
        assertTrue(result is Outcome.Ok)
        assertEquals(2000L, (result as Outcome.Ok).value.length())
        assertEquals(0L, repo.progressBytes("doc:progress"))
        assertEquals(2000L, repo.cachedFile("doc:progress")!!.length())
    }

    @Test
    fun cancelStopsInflightTelegramDownload() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-cancel"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                File(destPath).writeBytes(ByteArray(500))
                started.complete(Unit)
                delay(10_000)
                File(destPath).writeBytes(ByteArray(2000))
                Outcome.Ok(destPath)
            },
        )
        val message = Message(
            id = MessageId(PeerId(8), 4),
            senderId = null,
            text = "notes.pdf",
            date = 0L,
            outgoing = false,
            mediaKind = "document",
            mediaCacheKey = "doc:cancel",
            fileSize = 2000L,
        )
        val job = async { repo.ensureLocalMessageMedia(message) }
        started.await()
        repo.cancel("doc:cancel")
        val result = withTimeout(1_000) { job.await() }
        assertTrue(result is Outcome.Err)
        assertEquals("cancelled", (result as Outcome.Err).message)
        assertNull(repo.cachedFile("doc:cancel"))
        repo.shutdown()
    }

    @Test
    fun twoKeysDownloadConcurrently() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-parallel"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                release.await()
                active.decrementAndGet()
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
        )
        val a = async {
            repo.ensureLocalAvatar(PeerId(1), "photo:1")
        }
        val b = async {
            repo.ensureLocalAvatar(PeerId(2), "photo:2")
        }
        withTimeout(1_000) {
            while (peak.get() < 2) delay(10)
        }
        release.complete(Unit)
        assertTrue(a.await() is Outcome.Ok)
        assertTrue(b.await() is Outcome.Ok)
        assertEquals(2, peak.get())
        repo.shutdown()
    }

    @Test
    fun manyDownloadsCompleteWithoutExceedingWorkers(): Unit = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(20)
                active.decrementAndGet()
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 5,
        )
        val jobs = (1L..24L).map { id ->
            async {
                repo.ensureLocalAvatar(PeerId(id), "photo:$id", MediaPriority.IDLE)
            }
        }
        val results = withTimeout(5_000) { jobs.map { it.await() } }
        assertTrue(results.all { it is Outcome.Ok })
        assertTrue("peak=${peak.get()}", peak.get() in 1..5)
        repo.shutdown()
    }
}
