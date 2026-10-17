package org.monogram.feature.dialog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.WorkOwnership
import org.monogram.core.models.SavedGif
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PickerMediaPreloadTest {
    @Test
    fun emptyVisibilityDoesNotPlanFirstItem() {
        val items = (0L..4L).map { id ->
            PickerMediaPreload.Item(
                documentId = id,
                cacheKey = "emoji:$id",
                thumbCacheKey = "emoji:$id:thumb",
            )
        }
        val unknown = PickerMediaPreload.plan(items, emptySet(), idle = true)
        assertTrue(unknown.tasks.isEmpty())
        assertTrue(unknown.visibleRange.isEmpty())
        assertTrue(unknown.window.isEmpty())
        assertFalse(unknown.keys.contains("emoji:0"))

        val first = PickerMediaPreload.plan(items, setOf(0L), idle = true)
        assertTrue(
            first.tasks.any {
                it.cacheKey == "emoji:0" && it.fetch == PickerMediaPreload.Fetch.Document
            }
        )
    }

    @Test
    fun motionDoesNotRequestNonGifDocuments() {
        val items = (0L..30L).map { id ->
            PickerMediaPreload.Item(
                documentId = id,
                cacheKey = "emoji:$id",
                thumbCacheKey = "emoji:$id:thumb",
                kind = PickerMediaPreload.DocKind.Tgs,
            )
        }
        val moving = PickerMediaPreload.plan(items, setOf(10L), idle = false)
        assertTrue(moving.tasks.none { it.fetch == PickerMediaPreload.Fetch.Document })
        assertTrue(moving.tasks.any { it.cacheKey == "emoji:10:thumb" })
        assertTrue(moving.tasks.none { it.item.documentId == 9L })

        val gifs = PickerMediaPreload.gifs(
            listOf(
                SavedGif(documentId = 1L, cacheKey = "gif:1", thumbCacheKey = "gif:1:thumb"),
                SavedGif(documentId = 2L, cacheKey = "gif:2", thumbCacheKey = "gif:2:thumb"),
            ),
        )
        val movingGifs = PickerMediaPreload.plan(gifs, setOf(1L), idle = false)
        assertEquals(listOf("gif:1:thumb"), movingGifs.tasks.map { it.cacheKey })
        val idleGifs = PickerMediaPreload.plan(gifs, setOf(1L), idle = true)
        assertTrue(idleGifs.tasks.any { it.cacheKey == "gif:2:thumb" })
        assertTrue(
            idleGifs.tasks.any {
                it.cacheKey == "gif:1" && it.fetch == PickerMediaPreload.Fetch.Document
            }
        )
    }

    @Test
    fun removingAKeyReleasesOnlyThisPanelConsumer() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val root = tempRoot("release-one")
        val repo = emojiRepository(root) { id, dest, _ ->
            if (id == 4L) {
                fetches.incrementAndGet()
                started.complete(Unit)
                release.await()
            }
            File(dest).writeBytes(byteArrayOf(1))
            Outcome.Ok(dest)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preloader = PickerMediaPreloader(repo, scope)
        try {
            val owner = async { repo.ensureCustomEmoji(4L, MediaPriority.IDLE) }
            withTimeout(3_000) { started.await() }
            preloader.onPlan(plan(task(4L, MediaPriority.IDLE)))
            withTimeout(3_000) {
                while (WorkOwnership.state("media:emoji:4")?.consumers != 2) delay(10)
            }
            preloader.onPlan(plan(task(9L, MediaPriority.IDLE)))
            withTimeout(3_000) {
                while (WorkOwnership.state("media:emoji:4")?.consumers != 1) delay(10)
            }
            assertEquals(1, fetches.get())
            release.complete(Unit)
            assertTrue(withTimeout(3_000) { owner.await() } is Outcome.Ok)
            assertEquals(1, fetches.get())
        } finally {
            preloader.close()
            scope.cancel()
            repo.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun retainedPriorityChangeDoesNotStartASecondTransfer() = runBlocking {
        val started = CompletableDeferred<Int>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val root = tempRoot("priority")
        val repo = emojiRepository(root) { _, dest, priority ->
            fetches.incrementAndGet()
            started.complete(priority)
            release.await()
            File(dest).writeBytes(byteArrayOf(1))
            Outcome.Ok(dest)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preloader = PickerMediaPreloader(repo, scope)
        try {
            preloader.onPlan(plan(task(11L, MediaPriority.IDLE)))
            assertEquals(MediaPriority.IDLE, withTimeout(3_000) { started.await() })
            preloader.onPlan(plan(task(11L, MediaPriority.VISIBLE)))
            withTimeout(3_000) {
                while (WorkOwnership.state("media:emoji:11")?.priority != MediaPriority.VISIBLE) delay(
                    10
                )
            }
            delay(40)
            assertEquals(1, fetches.get())
            release.complete(Unit)
            withTimeout(3_000) {
                while (fetches.get() == 1 && repo.cachedFile("emoji:11") == null) delay(10)
            }
            assertEquals(1, fetches.get())
            assertTrue(repo.cachedFile("emoji:11") != null)
        } finally {
            release.complete(Unit)
            preloader.close()
            scope.cancel()
            repo.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun closeAfterPlanChangesReleasesEveryPanelConsumer() = runBlocking {
        val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val root = tempRoot("close-all")
        val repo = emojiRepository(root, maxConcurrentTelegram = 5) { id, _, _ ->
            active += id
            try {
                awaitCancellation()
            } finally {
                active -= id
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preloader = PickerMediaPreloader(repo, scope)
        try {
            preloader.onPlan(plan(task(1L), task(2L), task(3L)))
            withTimeout(3_000) { while (active.size < 3) delay(10) }
            preloader.onPlan(plan(task(4L), task(5L)))
            withTimeout(3_000) { while (active.toSet() != setOf(4L, 5L)) delay(10) }
            preloader.onPlan(plan(task(6L)))
            withTimeout(3_000) { while (active.toSet() != setOf(6L)) delay(10) }
            preloader.close()
            withTimeout(3_000) { while (active.isNotEmpty()) delay(10) }
            assertTrue(active.isEmpty())
        } finally {
            preloader.close()
            scope.cancel()
            repo.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitUserDownloadSurvivesPanelClose() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val root = tempRoot("user-survives")
        val repo = emojiRepository(root) { id, dest, _ ->
            if (id == 3L) {
                fetches.incrementAndGet()
                started.complete(Unit)
                release.await()
            }
            File(dest).writeBytes(byteArrayOf(1))
            Outcome.Ok(dest)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preloader = PickerMediaPreloader(repo, scope)
        try {
            val user = async { repo.ensureCustomEmoji(3L, MediaPriority.USER) }
            withTimeout(3_000) { started.await() }
            assertTrue(repo.isUserDownload("emoji:3"))
            preloader.onPlan(plan(task(3L, MediaPriority.DEFAULT)))
            withTimeout(3_000) {
                while (WorkOwnership.state("media:emoji:3")?.consumers != 2) delay(10)
            }
            preloader.close()
            withTimeout(3_000) {
                while (WorkOwnership.state("media:emoji:3")?.consumers != 1) delay(10)
            }
            delay(40)
            assertTrue(repo.isUserDownload("emoji:3"))
            assertEquals(1, fetches.get())
            release.complete(Unit)
            assertTrue(withTimeout(3_000) { user.await() } is Outcome.Ok)
            assertEquals(1, fetches.get())
        } finally {
            release.complete(Unit)
            preloader.close()
            scope.cancel()
            repo.shutdown()
            root.deleteRecursively()
        }
    }

    private fun tempRoot(name: String): File =
        File(
            System.getProperty("java.io.tmpdir"),
            "picker-$name-${System.nanoTime()}"
        ).apply { mkdirs() }

    private fun emojiRepository(
        root: File,
        maxConcurrentTelegram: Int = 1,
        fetcher: suspend (Long, String, Int) -> Outcome<String>,
    ) = MediaRepository(
        cacheRoot = root,
        customEmojiFetcher = fetcher,
        maxConcurrentTelegram = maxConcurrentTelegram,
    )

    private fun task(id: Long, priority: Int = MediaPriority.IDLE) = PickerMediaPreload.Task(
        item = PickerMediaPreload.Item(documentId = id, cacheKey = "emoji:$id"),
        fetch = PickerMediaPreload.Fetch.Document,
        priority = priority,
        cacheKey = "emoji:$id",
    )

    private fun plan(vararg tasks: PickerMediaPreload.Task) = PickerMediaPreload.Plan(
        visibleRange = 0..0,
        window = 0..0,
        tasks = tasks.toList(),
    )
}
