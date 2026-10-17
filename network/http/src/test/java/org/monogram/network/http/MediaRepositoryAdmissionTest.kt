package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MediaRepositoryAdmissionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun prioritySelectsTheNativeDownloadProfile() {
        assertEquals(
            DownloadProfile.Thumb,
            MediaPriority.downloadProfile(MediaPriority.USER, MediaFetchKind.Thumb),
        )
        assertEquals(1, DownloadProfile.Thumb.requestsInFlight())
        assertEquals(
            DownloadProfile.User,
            MediaPriority.downloadProfile(MediaPriority.USER, MediaFetchKind.Full),
        )
        assertEquals(
            DownloadProfile.Visible,
            MediaPriority.downloadProfile(MediaPriority.VISIBLE, MediaFetchKind.Display),
        )
        assertEquals(
            DownloadProfile.Visible,
            MediaPriority.downloadProfile(MediaPriority.DISPLAY, MediaFetchKind.Full),
        )
        assertEquals(
            DownloadProfile.Background,
            MediaPriority.downloadProfile(MediaPriority.IDLE, MediaFetchKind.Full),
        )
        assertEquals(
            DownloadProfile.Ordinary,
            MediaPriority.downloadProfile(MediaPriority.DEFAULT, MediaFetchKind.Full),
        )
        assertEquals(8, DownloadProfile.User.requestsInFlight())
        assertEquals(4, DownloadProfile.Ordinary.requestsInFlight())
        assertFalse(isLargeDownload(null))
        assertFalse(isLargeDownload(19_999_999))
        assertTrue(isLargeDownload(20_000_000))
    }

    @Test
    fun largeBackgroundFilesAreCappedWhileThumbsAndSmallUserFilesStillStart() = runBlocking {
        val largeInFlight = AtomicInteger(0)
        val maxLarge = AtomicInteger(0)
        val thumbs = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            httpClientFactory = { error("DC file parts must not use the HTTP queue") },
            telegramFetcher = TelegramMediaFetcher { _, messageId, destPath, kind, _ ->
                if (messageId in 1..3 && kind != MediaFetchKind.Thumb) {
                    val now = largeInFlight.incrementAndGet()
                    maxLarge.updateAndGet { current -> maxOf(current, now) }
                    if (now == 2) started.complete(Unit)
                    release.await()
                    largeInFlight.decrementAndGet()
                } else {
                    if (kind == MediaFetchKind.Thumb) thumbs.incrementAndGet()
                }
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
        )
        val chat = PeerId(4)
        val large = (1..3).map { id ->
            async {
                repo.ensureLocalMessageMedia(
                    photo(
                        chat,
                        id,
                        "full-$id"
                    ).copy(fileSize = 25_000_000), MediaPriority.IDLE
                )
            }
        }
        withTimeout(2_000) { started.await() }
        val thumbJobs = (4..5).map { id ->
            async {
                repo.ensureLocalMessageThumb(photo(chat, id, "thumb-$id"), MediaPriority.THUMB)
            }
        }
        val thumbResults = withTimeout(2_000) { thumbJobs.awaitAll() }
        val small = withTimeout(2_000) {
            repo.ensureLocalMessageMedia(
                photo(chat, 6, "small").copy(fileSize = 1_000),
                MediaPriority.USER
            )
        }
        assertTrue(small is Outcome.Ok)
        assertEquals(2, thumbs.get())
        assertTrue(thumbResults.all { it is Outcome.Ok })
        assertTrue(maxLarge.get() <= MediaRepository.MAX_LARGE_PIPELINES)
        release.complete(Unit)
        val largeResults = large.awaitAll()
        assertTrue(largeResults.all { it is Outcome.Ok })
        assertTrue(maxLarge.get() <= MediaRepository.MAX_LARGE_PIPELINES)
    }

    private fun photo(chat: PeerId, id: Int, key: String) = Message(
        id = MessageId(chat, id),
        senderId = null,
        text = "",
        date = 0L,
        outgoing = false,
        mediaKind = "photo",
        mediaCacheKey = key,
    )
}
