package org.monogram.feature.dialog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.network.http.TelegramMediaFetcher
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class DialogMediaPreloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun openPrefetchesVisibleMediaAndInstantView() = runBlocking {
        val fetches = CopyOnWriteArrayList<Int>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("preload"),
            telegramFetcher = TelegramMediaFetcher { _, messageId, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(1))
                fetches += messageId
                started.complete(Unit)
                release.await()
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 2,
        )
        val ivCalls = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preloader = DialogMediaPreloader(
            chatId = PeerId(1),
            mediaRepository = repo,
            prefetchInstantView = { url, _ -> ivCalls += url },
            scope = scope,
        )
        preloader.onVisible(listOf(webpage(5), photo(4)), visibleIds = setOf(5))
        withTimeout(1_000) { started.await() }
        assertTrue(fetches.contains(5))
        withTimeout(1_000) { while ("https://ex/5" !in ivCalls) delay(10) }
        preloader.close()
        release.complete(Unit)
        scope.cancel()
        repo.shutdown()
    }

    @Test
    fun closeCancelsInstantViewPrefetch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preloader = DialogMediaPreloader(
            chatId = PeerId(1),
            mediaRepository = null,
            prefetchInstantView = { _, _ ->
                started.complete(Unit)
                delay(10_000)
                finished.set(true)
            },
            scope = scope,
        )
        preloader.onVisible(listOf(webpage(5)), visibleIds = setOf(5))
        withTimeout(1_000) { started.await() }
        preloader.close()
        delay(80)
        assertFalse(finished.get())
        scope.cancel()
    }

    @Test
    fun closeCancelsNonUserAndKeepsUser() = runBlocking {
        val startedIdle = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("close"),
            telegramFetcher = TelegramMediaFetcher { _, messageId, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(1))
                if (messageId == 3) startedIdle.complete(Unit)
                release.await()
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 3,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preloader = DialogMediaPreloader(
            chatId = PeerId(1),
            mediaRepository = repo,
            prefetchInstantView = { _, _ -> },
            scope = scope,
        )
        preloader.onVisible(listOf(photo(3), photo(2), photo(1)), visibleIds = setOf(3))
        withTimeout(1_000) { startedIdle.await() }
        val user = async {
            repo.ensureLocalMessageMedia(photo(99), MediaPriority.USER)
        }
        preloader.close()
        delay(40)
        assertTrue(user.isActive)
        release.complete(Unit)
        assertTrue(withTimeout(1_000) { user.await() } is Outcome.Ok)
        assertNull(repo.cachedFile("photo:3"))
        scope.cancel()
        repo.shutdown()
    }

    private fun photo(id: Int) = Message(
        id = MessageId(PeerId(1), id),
        senderId = null,
        text = null,
        date = id.toLong(),
        outgoing = false,
        mediaKind = "photo",
        mediaCacheKey = "photo:$id",
        thumbCacheKey = "thumb:$id",
    )

    private fun webpage(id: Int) = Message(
        id = MessageId(PeerId(1), id),
        senderId = null,
        text = null,
        date = id.toLong(),
        outgoing = false,
        mediaKind = "webpage",
        mediaCacheKey = "web:$id",
        thumbCacheKey = "webthumb:$id",
        fileName = """{"u":"https://ex/$id","iv":true,"h":1}""",
    )
}
