package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.PeerId
import java.io.File
import java.util.Collections

class MediaRepositoryPriorityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun higherPriorityRunsBeforeQueuedIdle(): Unit = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<Long>())
        val blockerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { chatId, _, destPath, _, _ ->
                order += chatId.value
                if (chatId.value == 1L) {
                    blockerStarted.complete(Unit)
                    release.await()
                }
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val blocker = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.IDLE)
        }
        blockerStarted.await()
        val idle = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.IDLE)
        }
        val visible = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(3), "photo:3", MediaPriority.VISIBLE)
        }
        delay(80)
        assertEquals(listOf(1L), order.toList())
        release.complete(Unit)
        assertTrue(blocker.await() is Outcome.Ok)
        assertTrue(idle.await() is Outcome.Ok)
        assertTrue(visible.await() is Outcome.Ok)
        assertEquals(listOf(1L, 3L, 2L), order.toList())
        repo.shutdown()
    }

    @Test
    fun displayDoesNotCancelRunningDefault(): Unit = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<Long>())
        val blockerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { chatId, _, destPath, _, _ ->
                started += chatId.value
                if (chatId.value == 1L) {
                    blockerStarted.complete(Unit)
                    release.await()
                }
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val full = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.DEFAULT)
        }
        blockerStarted.await()
        val display = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.DISPLAY)
        }
        delay(80)
        assertEquals(listOf(1L), started.toList())
        release.complete(Unit)
        assertTrue(display.await() is Outcome.Ok)
        assertTrue(full.await() is Outcome.Ok)
        assertEquals(listOf(1L, 2L), started.toList())
        repo.shutdown()
    }

    @Test
    fun cancelRunningDoesNotAbortADifferentKey(): Unit = runBlocking {
        val started = Collections.synchronizedList(mutableListOf<Long>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { chatId, _, destPath, _, _ ->
                started += chatId.value
                if (chatId.value == 1L) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val first = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.DEFAULT)
        }
        firstStarted.await()
        val second = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.DISPLAY)
        }
        delay(40)
        repo.cancelRunning("photo:2")
        delay(40)
        assertEquals(listOf(1L), started.toList())
        releaseFirst.complete(Unit)
        assertTrue(first.await() is Outcome.Ok)
        val secondResult = second.await()
        assertTrue(secondResult is Outcome.Err)
        assertEquals("cancelled", (secondResult as Outcome.Err).message)
        repo.shutdown()
    }

    @Test
    fun cancelRunningDoesNotAbortUserDownload(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-user"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                started.complete(Unit)
                release.await()
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val download = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "doc:user", MediaPriority.USER)
        }
        started.await()
        assertEquals(true, repo.isUserDownload("doc:user"))
        repo.cancelRunning("doc:user")
        delay(40)
        assertEquals(true, repo.isUserDownload("doc:user"))
        release.complete(Unit)
        assertTrue(download.await() is Outcome.Ok)
        assertEquals(false, repo.isUserDownload("doc:user"))
        repo.shutdown()
    }

    @Test
    fun equalPriorityStaysFifo(): Unit = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<Long>())
        val blockerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { chatId, _, destPath, _, _ ->
                order += chatId.value
                if (chatId.value == 1L) {
                    blockerStarted.complete(Unit)
                    release.await()
                }
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val blocker = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.DEFAULT)
        }
        blockerStarted.await()
        val first = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.DEFAULT)
        }
        delay(40)
        val second = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(3), "photo:3", MediaPriority.DEFAULT)
        }
        delay(40)
        release.complete(Unit)
        assertTrue(blocker.await() is Outcome.Ok)
        assertTrue(first.await() is Outcome.Ok)
        assertTrue(second.await() is Outcome.Ok)
        assertEquals(listOf(1L, 2L, 3L), order.toList())
        repo.shutdown()
    }

    @Test
    fun laterVisibleRequestBumpsQueuedIdleKey(): Unit = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<Long>())
        val blockerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { chatId, _, destPath, _, _ ->
                order += chatId.value
                if (chatId.value == 1L) {
                    blockerStarted.complete(Unit)
                    release.await()
                }
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val blocker = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.IDLE)
        }
        blockerStarted.await()
        val idleTarget = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.IDLE)
        }
        delay(40)
        val idleOther = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(3), "photo:3", MediaPriority.IDLE)
        }
        delay(40)
        val bumped = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.VISIBLE)
        }
        delay(80)
        assertEquals(listOf(1L), order.toList())
        release.complete(Unit)
        assertTrue(blocker.await() is Outcome.Ok)
        assertTrue(idleTarget.await() is Outcome.Ok)
        assertTrue(idleOther.await() is Outcome.Ok)
        assertTrue(bumped.await() is Outcome.Ok)
        assertEquals(listOf(1L, 2L, 3L), order.toList())
        repo.shutdown()
    }

    @Test
    fun queuedIdleDoesNotStartWhileVisibleWaits(): Unit = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<Long>())
        val started = Collections.synchronizedList(mutableListOf<Long>())
        val blockerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { chatId, _, destPath, _, _ ->
                started += chatId.value
                if (chatId.value == 1L) {
                    blockerStarted.complete(Unit)
                    release.await()
                }
                order += chatId.value
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val blocker = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.IDLE)
        }
        blockerStarted.await()
        val visible = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(2), "photo:2", MediaPriority.VISIBLE)
        }
        val idle = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(3), "photo:3", MediaPriority.IDLE)
        }
        delay(80)
        assertEquals(listOf(1L), started.toList())
        release.complete(Unit)
        assertTrue(blocker.await() is Outcome.Ok)
        assertTrue(visible.await() is Outcome.Ok)
        assertTrue(idle.await() is Outcome.Ok)
        assertEquals(listOf(1L, 2L, 3L), order.toList())
        repo.shutdown()
    }

    @Test
    fun nativeProgressUpdatesWithoutDiskPolling(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var repo: MediaRepository
        repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-progress"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                started.complete(Unit)
                repo.onNativeProgress(destPath, 8192)
                release.await()
                File(destPath).writeBytes(byteArrayOf(1, 2, 3))
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 1,
        )
        val job = async(Dispatchers.IO) {
            repo.ensureLocalAvatar(PeerId(1), "photo:1", MediaPriority.USER)
        }
        started.await()
        delay(30)
        assertEquals(8192L, repo.downloadProgress.value["photo:1"])
        release.complete(Unit)
        assertTrue(job.await() is Outcome.Ok)
        repo.shutdown()
    }
}
