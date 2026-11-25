package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.common.WorkOwnership
import org.monogram.core.models.PeerId
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class MediaRepositoryConsumerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun releaseOneConsumerKeepsTheSharedTransfer() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fetches = AtomicInteger()
            val repo =
                MediaRepository(
                    cacheRoot = tmp.newFolder("shared"),
                    telegramFetcher =
                        TelegramMediaFetcher { _, _, dest, _, _ ->
                            fetches.incrementAndGet()
                            started.complete(Unit)
                            release.await()
                            File(dest).writeBytes(byteArrayOf(1, 2))
                            Outcome.Ok(dest)
                        },
                    maxConcurrentTelegram = 1,
                )
            try {
                val panel = repo.openDownloadConsumer("photo:shared")
                val panelJob =
                    async {
                        repo.ensureIndexedMedia(
                            PeerId(1),
                            1,
                            "photo:shared",
                            priority = MediaPriority.IDLE,
                            consumer = panel,
                        )
                    }
                withTimeout(3_000) { started.await() }
                val owner =
                    async {
                        repo.ensureIndexedMedia(
                            PeerId(1),
                            1,
                            "photo:shared",
                            priority = MediaPriority.VISIBLE,
                        )
                    }
                withTimeout(3_000) {
                    while (WorkOwnership.state("media:photo:shared")?.consumers != 2) delay(10)
                }
                panelJob.cancel()
                withTimeout(3_000) { panelJob.join() }
                withTimeout(3_000) {
                    while (WorkOwnership.state("media:photo:shared")?.consumers != 1) delay(10)
                }
                assertEquals(1, fetches.get())
                release.complete(Unit)
                assertTrue(withTimeout(3_000) { owner.await() } is Outcome.Ok)
                assertEquals(1, fetches.get())
            } finally {
                release.complete(Unit)
                repo.shutdown()
            }
        }

    @Test
    fun priorityUpdateDoesNotStartASecondTransfer() =
        runBlocking {
            val seen = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
            val blockerStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repo =
                MediaRepository(
                    cacheRoot = tmp.newFolder("priority"),
                    customEmojiFetcher = { id, dest, priority ->
                        seen += id to priority
                        if (id == 1L) {
                            blockerStarted.complete(Unit)
                            release.await()
                        }
                        File(dest).writeBytes(byteArrayOf(1))
                        Outcome.Ok(dest)
                    },
                    maxConcurrentTelegram = 1,
                )
            try {
                val blocker = async { repo.ensureCustomEmoji(1L, MediaPriority.DEFAULT) }
                withTimeout(3_000) { blockerStarted.await() }
                val panel = repo.openDownloadConsumer("emoji:2")
                val queued =
                    async {
                        repo.ensureCustomEmoji(2L, MediaPriority.IDLE, consumer = panel)
                    }
                withTimeout(3_000) {
                    while (WorkOwnership.state("media:emoji:2")?.consumers != 1) delay(10)
                }
                val other = async { repo.ensureCustomEmoji(3L, MediaPriority.DEFAULT) }
                withTimeout(3_000) {
                    while (WorkOwnership.state("media:emoji:3")?.consumers != 1) delay(10)
                }
                repo.awaitDownloadConsumerPriority(panel, MediaPriority.VISIBLE)
                release.complete(Unit)
                assertTrue(blocker.await() is Outcome.Ok)
                assertTrue(queued.await() is Outcome.Ok)
                assertTrue(other.await() is Outcome.Ok)
                assertEquals(listOf(1L, 2L, 3L), seen.map { it.first })
                assertEquals(MediaPriority.VISIBLE, seen.first { it.first == 2L }.second)
                assertEquals(1, seen.count { it.first == 2L })
            } finally {
                release.complete(Unit)
                repo.shutdown()
            }
        }

    @Test
    fun departingAnonymousDisplayCallerRestoresPrefetchPriority() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val seen = mutableListOf<Pair<Long, Int>>()
            val repo =
                MediaRepository(
                    cacheRoot = tmp.newFolder("anonymous"),
                    customEmojiFetcher = { id, dest, priority ->
                        seen += id to priority
                        if (id == 1L) {
                            started.complete(Unit)
                            release.await()
                        }
                        File(dest).writeBytes(byteArrayOf(1))
                        Outcome.Ok(dest)
                    },
                    maxConcurrentTelegram = 1,
                )
            try {
                withTimeout(5_000) {
                    val blocker = async { repo.ensureCustomEmoji(1L) }
                    started.await()
                    val prefetch =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            repo.ensureCustomEmoji(2L, MediaPriority.IDLE)
                        }
                    val display =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            repo.ensureCustomEmoji(2L, MediaPriority.DISPLAY)
                        }
                    while (WorkOwnership.state("media:emoji:2")?.consumers != 2) delay(10)
                    display.cancelAndJoin()
                    val visible =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            repo.ensureCustomEmoji(3L, MediaPriority.VISIBLE)
                        }
                    while (WorkOwnership.state("media:emoji:3")?.consumers != 1) delay(10)
                    release.complete(Unit)
                    assertTrue(blocker.await() is Outcome.Ok)
                    assertTrue(prefetch.await() is Outcome.Ok)
                    assertTrue(visible.await() is Outcome.Ok)
                    assertEquals(
                        listOf(3L to MediaPriority.VISIBLE, 2L to MediaPriority.IDLE),
                        seen.drop(1)
                    )
                }
            } finally {
                release.complete(Unit)
                repo.shutdown()
            }
        }

    @Test
    fun explicitUserDownloadSurvivesOtherConsumerRelease() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fetches = AtomicInteger()
            val repo =
                MediaRepository(
                    cacheRoot = tmp.newFolder("user"),
                    customEmojiFetcher = { _, dest, _ ->
                        fetches.incrementAndGet()
                        started.complete(Unit)
                        release.await()
                        File(dest).writeBytes(byteArrayOf(1))
                        Outcome.Ok(dest)
                    },
                    maxConcurrentTelegram = 1,
                )
            try {
                val user = async { repo.ensureCustomEmoji(8L, MediaPriority.USER) }
                withTimeout(3_000) { started.await() }
                assertTrue(repo.isUserDownload("emoji:8"))
                val panel = repo.openDownloadConsumer("emoji:8")
                val panelJob =
                    async {
                        repo.ensureCustomEmoji(8L, MediaPriority.DEFAULT, consumer = panel)
                    }
                withTimeout(3_000) {
                    while (WorkOwnership.state("media:emoji:8")?.consumers != 2) delay(10)
                }
                panelJob.cancel()
                withTimeout(3_000) { panelJob.join() }
                delay(40)
                assertTrue(repo.isUserDownload("emoji:8"))
                assertEquals(1, fetches.get())
                release.complete(Unit)
                assertTrue(withTimeout(3_000) { user.await() } is Outcome.Ok)
                assertEquals(1, fetches.get())
                assertEquals(false, repo.isUserDownload("emoji:8"))
            } finally {
                release.complete(Unit)
                repo.shutdown()
            }
        }
}
