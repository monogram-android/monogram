package org.monogram.network.http

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.*
import java.io.File

class ScheduledMediaRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private fun message(id: Int, key: String) = Message(
        MessageId(PeerId(7), id), senderId = null,
        text = "fixture", date = 100, outgoing = true, mediaKind = "photo", mediaCacheKey = key,
        thumbCacheKey = "$key:thumb"
    )

    @Test
    fun scheduledFullAndThumbUseEncodedIdsWithoutOpeningPendingLocalSlots() = runBlocking {
        val requests = mutableListOf<Pair<Int, MediaFetchKind>>()
        val repository = MediaRepository(
            temporary.newFolder(),
            telegramFetcher = TelegramMediaFetcher { peer, id, path, kind, _ ->
                assertEquals(PeerId(7), peer)
                requests += id to kind
                File(path).writeBytes(byteArrayOf(1, 2, 3))
                Outcome.Ok(path)
            })
        val pending = message(-38, "local-fixture")
        assertTrue(repository.ensureLocalMessageMedia(pending) is Outcome.Err)
        assertTrue(repository.ensureLocalMessageThumb(pending) is Outcome.Err)
        assertTrue(requests.isEmpty())
        val scheduled = ScheduledMessage(message(37, "queue-fixture"))
        assertTrue(repository.ensureLocalScheduledMessageMedia(scheduled) is Outcome.Ok)
        assertTrue(repository.ensureLocalScheduledMessageThumb(scheduled) is Outcome.Ok)
        assertEquals(listOf(-38 to MediaFetchKind.Full, -38 to MediaFetchKind.Thumb), requests)
        assertTrue(repository.ensureLocalMessageMedia(message(37, "history-fixture")) is Outcome.Ok)
        assertEquals(37 to MediaFetchKind.Full, requests.last())
        assertTrue(repository.ensureLocalScheduledMessageMedia(scheduled) is Outcome.Ok)
        assertEquals(3, requests.size)
        assertEquals(37, scheduled.message.id.id)
    }

    @Test
    fun dismissingScheduledPreviewCancelsItsUnsharedTransfer() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = MediaRepository(
            temporary.newFolder(),
            telegramFetcher = TelegramMediaFetcher { _, id, _, _, _ ->
                assertEquals(-38, id)
                try {
                    started.complete(Unit); awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            })
        val waiter = launch {
            repository.ensureLocalScheduledMessageMedia(
                ScheduledMessage(
                    message(
                        37,
                        "cancel-fixture"
                    )
                )
            )
        }
        withTimeout(5000) { started.await() }
        waiter.cancelAndJoin()
        withTimeout(5000) { cancelled.await() }
    }
}
