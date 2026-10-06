package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.io.File

class ThumbnailLaneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun visibleThumbStartsWhileFullCapacityIsBlocked() = runBlocking {
        val fullStarted = CompletableDeferred<Unit>()
        val releaseFull = CompletableDeferred<Unit>()
        val repository = MediaRepository(
            cacheRoot = tmp.newFolder(), maxConcurrentTelegram = 1,
            telegramFetcher = TelegramMediaFetcher { _, _, path, kind, _ ->
                if (kind != MediaFetchKind.Thumb) {
                    fullStarted.complete(Unit)
                    releaseFull.await()
                }
                File(path).writeBytes(byteArrayOf(1))
                Outcome.Ok(path)
            },
        )
        val message = Message(
            MessageId(PeerId(7), 10), null, null, 1, outgoing = false,
            mediaCacheKey = "doc:10", mediaKind = "document", thumbCacheKey = "doc:10:thumb"
        )
        try {
            withTimeout(5_000) {
                val full = async { repository.ensureLocalMessageMedia(message, MediaPriority.USER) }
                fullStarted.await()
                assertTrue(
                    repository.ensureLocalMessageThumb(
                        message,
                        MediaPriority.THUMB
                    ) is Outcome.Ok
                )
                assertTrue(full.isActive)
                releaseFull.complete(Unit)
                assertTrue(full.await() is Outcome.Ok)
            }
        } finally {
            releaseFull.complete(Unit)
            repository.shutdown()
        }
    }
}
