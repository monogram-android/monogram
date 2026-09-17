package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.io.File

class MediaRepositoryCancelChatTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cancelChatStopsNonUserAndLeavesUserAndOtherChats() = runBlocking {
        val started = mutableMapOf(
            1 to CompletableDeferred<Unit>(),
            2 to CompletableDeferred(),
            3 to CompletableDeferred(),
        )
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-chat"),
            telegramFetcher = TelegramMediaFetcher { _, messageId, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(1))
                started.getValue(messageId).complete(Unit)
                release.await()
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 3,
        )
        val idle = async { repo.ensureLocalMessageMedia(photo(1, 1, "photo:idle"), MediaPriority.IDLE) }
        val user = async { repo.ensureLocalMessageMedia(photo(1, 2, "photo:user"), MediaPriority.USER) }
        val other = async { repo.ensureLocalMessageMedia(photo(2, 3, "photo:other"), MediaPriority.IDLE) }
        started.getValue(1).await()
        started.getValue(2).await()
        started.getValue(3).await()
        repo.cancelChatAwait(PeerId(1), belowPriority = MediaPriority.USER)
        val idleResult = withTimeout(1_000) { idle.await() }
        assertTrue(idleResult is Outcome.Err)
        assertEquals("cancelled", (idleResult as Outcome.Err).message)
        assertTrue(user.isActive)
        assertTrue(other.isActive)
        release.complete(Unit)
        assertTrue(withTimeout(1_000) { user.await() } is Outcome.Ok)
        assertTrue(withTimeout(1_000) { other.await() } is Outcome.Ok)
        repo.shutdown()
    }

    @Test
    fun cancelChatKeepKeysLeavesMatchingIdleJob() = runBlocking {
        val started = mutableMapOf(
            1 to CompletableDeferred<Unit>(),
            2 to CompletableDeferred(),
        )
        val release = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-keep"),
            telegramFetcher = TelegramMediaFetcher { _, messageId, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(1))
                started.getValue(messageId).complete(Unit)
                release.await()
                Outcome.Ok(destPath)
            },
            maxConcurrentTelegram = 2,
        )
        val keep = async { repo.ensureLocalMessageThumb(photo(1, 1, "photo:keep"), MediaPriority.IDLE) }
        val drop = async { repo.ensureLocalMessageThumb(photo(1, 2, "photo:drop"), MediaPriority.IDLE) }
        started.getValue(1).await()
        started.getValue(2).await()
        repo.cancelChatAwait(
            chatId = PeerId(1),
            belowPriority = MediaPriority.DEFAULT,
            keepKeys = setOf("photo:keep"),
        )
        val dropped = withTimeout(1_000) { drop.await() }
        assertEquals("cancelled", (dropped as Outcome.Err).message)
        assertTrue(keep.isActive)
        release.complete(Unit)
        assertTrue(withTimeout(1_000) { keep.await() } is Outcome.Ok)
        repo.shutdown()
    }

    private fun photo(chat: Long, id: Int, key: String) = Message(
        id = MessageId(PeerId(chat), id),
        senderId = null,
        text = null,
        date = 0L,
        outgoing = false,
        mediaKind = "photo",
        mediaCacheKey = key,
        thumbCacheKey = key,
    )
}
