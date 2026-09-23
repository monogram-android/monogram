package org.monogram.network.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
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

class MediaRepositoryThumbTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ensureLocalMessageThumbAlwaysRequestsThumb() = runBlocking {
        var lastThumb: Boolean? = null
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, kind, _ ->
                lastThumb = kind == MediaFetchKind.Thumb
                File(destPath).writeBytes(byteArrayOf(7, 7, 7))
                Outcome.Ok(destPath)
            },
        )
        val message = Message(
            id = MessageId(PeerId(1), 9),
            senderId = null,
            text = null,
            date = 0L,
            outgoing = false,
            mediaCacheKey = "doc:1:thumb",
            thumbCacheKey = "doc:1:thumb",
        )
        val result = repo.ensureLocalMessageThumb(message)
        assertTrue(result is Outcome.Ok)
        assertEquals(true, lastThumb)
    }

    @Test
    fun documentWithoutDistinctThumbDoesNotHitNetwork() = runBlocking {
        var fetches = 0
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("doc-thumb"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                fetches += 1
                File(destPath).writeBytes(byteArrayOf(1))
                Outcome.Ok(destPath)
            },
        )
        val message = Message(
            id = MessageId(PeerId(1), 9),
            senderId = null,
            text = "file.pdf",
            date = 0L,
            outgoing = false,
            mediaKind = "document",
            mediaCacheKey = "doc:5285428961101259905",
        )
        val result = repo.ensureLocalMessageThumb(message)
        assertTrue(result is Outcome.Err)
        assertEquals("no downloadable thumb", (result as Outcome.Err).message)
        assertEquals(0, fetches)
    }

    @Test
    fun ensureLocalMessageDisplayRequestsDisplayKind() = runBlocking {
        var lastKind: MediaFetchKind? = null
        var lastPriority: Int? = null
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("cache-display"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, kind, priority ->
                lastKind = kind
                lastPriority = priority
                File(destPath).writeBytes(byteArrayOf(8, 8, 8))
                Outcome.Ok(destPath)
            },
        )
        val message = Message(
            id = MessageId(PeerId(1), 9),
            senderId = null,
            text = null,
            date = 0L,
            outgoing = false,
            mediaKind = "photo",
            mediaCacheKey = "photo:9",
            thumbCacheKey = "photo:9:thumb",
        )
        val result = repo.ensureLocalMessageDisplay(message)
        assertTrue(result is Outcome.Ok)
        assertEquals(MediaFetchKind.Display, lastKind)
        assertEquals(MediaPriority.DISPLAY, lastPriority)
        assertEquals("photo:9:display", photoDisplayCacheKey("photo:9"))
    }

    @Test
    fun cachedMediaDoesNotFetchAdditionalData() = runBlocking {
        val root = tmp.newFolder("consolidated-cache-hit")
        val primed = MediaRepository(
            cacheRoot = root,
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(1, 2, 3, 4))
                Outcome.Ok(destPath)
            },
        )
        var networkFetches = 0
        val failingRepo = MediaRepository(
            cacheRoot = root,
            telegramFetcher = TelegramMediaFetcher { _, _, _, _, _ ->
                networkFetches++
                Outcome.Err("network")
            },
        )

        val thumbMessage = Message(
            id = MessageId(PeerId(4), 2),
            senderId = null,
            text = null,
            date = 0L,
            outgoing = false,
            mediaCacheKey = "doc:4:thumb",
            thumbCacheKey = "doc:4:thumb",
        )
        val fullMediaMessage = Message(
            id = MessageId(PeerId(6), 3),
            senderId = null,
            text = "notes.pdf",
            date = 0L,
            outgoing = false,
            mediaKind = "document",
            mediaCacheKey = "doc:6",
            fileSize = 4L,
        )
        val avatarPeer = PeerId(11)
        val avatarKey = "avatar:11"

        assertTrue(primed.ensureLocalMessageThumb(thumbMessage) is Outcome.Ok)
        assertTrue(primed.ensureLocalMessageMedia(fullMediaMessage) is Outcome.Ok)
        assertTrue(primed.ensureLocalAvatar(avatarPeer, avatarKey) is Outcome.Ok)

        assertTrue(failingRepo.ensureLocalMessageThumb(thumbMessage) is Outcome.Ok)
        assertTrue(failingRepo.ensureLocalMessageMedia(fullMediaMessage) is Outcome.Ok)
        assertTrue(failingRepo.ensureLocalAvatar(avatarPeer, avatarKey) is Outcome.Ok)

        assertEquals(0, networkFetches)
        assertEquals(4L, failingRepo.cachedFile("doc:6")!!.length())
        assertTrue(failingRepo.cachedAvatar(avatarPeer) != null)
    }

    @Test
    fun videoAvatarKeyDoesNotReuseStillJpeg() = runBlocking {
        val root = tmp.newFolder("avatar-video")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val mp4 = byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 1, 2, 3, 4)
        val primed = MediaRepository(
            cacheRoot = root,
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                File(destPath).writeBytes(jpeg)
                Outcome.Ok(destPath)
            },
        )
        assertTrue(primed.ensureLocalAvatar(PeerId(11), "avatar:11") is Outcome.Ok)
        var fetches = 0
        val repo = MediaRepository(
            cacheRoot = root,
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                fetches++
                File(destPath).writeBytes(mp4)
                Outcome.Ok(destPath)
            },
        )
        val video = repo.ensureLocalAvatar(PeerId(11), "avatar:11:video")
        assertTrue(video is Outcome.Ok)
        assertEquals(1, fetches)
        assertEquals(mp4.toList(), (video as Outcome.Ok).value.readBytes().toList())
    }

    @Test
    fun avatarDownloadBumpsCacheGeneration() = runBlocking {
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("gen"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(3, 3, 3))
                Outcome.Ok(destPath)
            },
        )
        assertEquals(0L, repo.cacheGeneration.value)
        assertTrue(repo.ensureLocalAvatar(PeerId(5), "avatar:5") is Outcome.Ok)
        assertTrue(repo.cacheGeneration.value > 0L)
    }

    @Test
    fun keyedCacheGenerationOnlyEmitsForObservedKey() = runBlocking {
        val repo = MediaRepository(
            cacheRoot = tmp.newFolder("keyed-gen"),
            telegramFetcher = TelegramMediaFetcher { _, _, destPath, _, _ ->
                File(destPath).writeBytes(byteArrayOf(3, 3, 3))
                Outcome.Ok(destPath)
            },
        )
        val observed = CompletableDeferred<Long>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.cacheGeneration("avatar:5").drop(1).collect { observed.complete(it) }
        }

        assertTrue(repo.ensureLocalAvatar(PeerId(6), "avatar:6") is Outcome.Ok)
        assertFalse(observed.isCompleted)

        assertTrue(repo.ensureLocalAvatar(PeerId(5), "avatar:5") is Outcome.Ok)
        assertTrue(withTimeout(1_000) { observed.await() } > 0L)
        observer.cancelAndJoin()
        repo.shutdown()
    }
}
