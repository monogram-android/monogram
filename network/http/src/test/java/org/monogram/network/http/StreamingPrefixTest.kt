package org.monogram.network.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

class StreamingPrefixTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun video() = Message(
        MessageId(PeerId(3), 9), null, null, 1, outgoing = false,
        mediaKind = "video", mediaCacheKey = "video:9", supportsStreaming = true,
        fileSize = 20L * 1024 * 1024,
    )

    @Test
    fun metadataOutsidePrefixUsesRangesWithinTheAdditionalBudget() = runBlocking {
        val part = MediaRepository.STREAM_PART
        val metadataOffset = 16L + 4L * 1024 * 1024
        val headers = mapOf(
            0L to java.nio.ByteBuffer.allocate(16).putInt(16).putInt(0x66747970).array(),
            16L to java.nio.ByteBuffer.allocate(8).putInt(4 * 1024 * 1024).putInt(0x6d646174)
                .array(),
            metadataOffset to java.nio.ByteBuffer.allocate(8).putInt(64).putInt(0x6d6f6f76).array(),
        )
        val offsets = mutableListOf<Long>()
        val root = tmp.newFolder()
        val repository = MediaRepository(
            root,
            telegramChunkFetcher = TelegramChunkFetcher { _, _, dest, offset ->
                offsets += offset
                val bytes = ByteArray(part.toInt())
                headers.forEach { (position, header) ->
                    if (position >= offset && position + header.size <= offset + part) {
                        header.copyInto(bytes, (position - offset).toInt())
                    }
                }
                File(dest).writeBytes(bytes)
                Outcome.Ok(dest)
            })
        try {
            val message = video()
            assertTrue(repository.ensureStreamingPrefix(message) is Outcome.Ok)
            assertEquals(listOf(0L, part, part * 2, part * 3, part * 8), offsets)
            val additional = offsets.count { it >= MediaRepository.STREAM_PREFIX_BYTES } * part
            assertTrue(additional <= 2L * 1024 * 1024)
            val metadata = streamPrefixPart(root, checkNotNull(streamMediaKey(message)), part * 8)
            assertTrue(metadata.isFile)
            val calls = offsets.size
            assertTrue(repository.ensureStreamingPrefix(message) is Outcome.Ok)
            assertEquals(calls, offsets.size)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun metadataSearchCannotFetchBeyondTwoMiBExtra() = runBlocking {
        val part = MediaRepository.STREAM_PART
        val offsets = mutableListOf<Long>()
        val repository = MediaRepository(
            tmp.newFolder(),
            telegramChunkFetcher = TelegramChunkFetcher { _, _, dest, offset ->
                offsets += offset
                val bytes = ByteArray(part.toInt())
                java.nio.ByteBuffer.wrap(bytes).putInt(part.toInt())
                    .putInt(if (offset == 0L) 0x66747970 else 0x66726565)
                File(dest).writeBytes(bytes)
                Outcome.Ok(dest)
            })
        try {
            assertTrue(repository.ensureStreamingPrefix(video()) is Outcome.Ok)
            assertEquals((0L..7L).map { it * part }, offsets)
            assertEquals(
                2L * 1024 * 1024,
                offsets.count { it >= MediaRepository.STREAM_PREFIX_BYTES } * part)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun incompletePartIsNotPublished() = runBlocking {
        val root = tmp.newFolder()
        val repository =
            MediaRepository(root, telegramChunkFetcher = TelegramChunkFetcher { _, _, dest, _ ->
                File(dest).writeBytes(byteArrayOf(1, 2, 3))
                Outcome.Ok(dest)
            })
        try {
            assertTrue(repository.ensureStreamingPrefix(video()) is Outcome.Err)
            assertTrue(!streamPrefixPart(root, checkNotNull(streamMediaKey(video())), 0).exists())
            assertTrue(root.listFiles().orEmpty().none { it.extension == "part" })
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun cancellationRemovesTemporaryPart() = runBlocking {
        val root = tmp.newFolder()
        val repository =
            MediaRepository(root, telegramChunkFetcher = TelegramChunkFetcher { _, _, dest, _ ->
                File(dest).writeBytes(byteArrayOf(1))
                throw CancellationException("cancelled")
            })
        try {
            var cancelled = false
            try {
                repository.ensureStreamingPrefix(video())
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(root.listFiles().orEmpty().none { it.extension == "part" })
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun compactPartMustContainTheWholeRequestedRange() {
        val offset = MediaRepository.STREAM_PART
        val file = tmp.newFile()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(7) }
        assertEquals(null, reusableStreamPrefix(file, offset, offset + MediaRepository.STREAM_PART))
        assertEquals(file, reusableStreamPrefix(file, offset, offset + 7))
    }

    @Test
    fun collidingStringHashesHaveDifferentPartFiles() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertTrue(streamPrefixPart(tmp.root, "Aa", 0) != streamPrefixPart(tmp.root, "BB", 0))
    }

    @Test
    fun prefixFetchesPartsInsteadOfTheWholeFile() = runBlocking {
        val offsets = mutableListOf<Long>()
        val cacheRoot = tmp.newFolder()
        var fullCalls = 0
        val repository = MediaRepository(
            cacheRoot = cacheRoot,
            telegramFetcher = TelegramMediaFetcher { _, _, _, _, _ ->
                fullCalls += 1
                Outcome.Err("full file")
            },
            telegramChunkFetcher = TelegramChunkFetcher { _, _, dest, offset ->
                offsets += offset
                val file = File(dest)
                java.io.RandomAccessFile(file, "rw").use { part ->
                    part.setLength(0)
                    part.write(ByteArray(MediaRepository.STREAM_PART.toInt()))
                }
                Outcome.Ok(dest)
            },
        )
        val message = Message(
            MessageId(PeerId(3), 9),
            null,
            null,
            1,
            outgoing = false,
            mediaKind = "video",
            mediaCacheKey = "video:9",
            supportsStreaming = true,
            fileSize = 20L * 1024 * 1024,
        )
        try {
            val result = repository.ensureStreamingPrefix(message, MediaPriority.VISIBLE)
            assertTrue(result is Outcome.Ok)
            assertEquals(
                listOf(
                    0L,
                    MediaRepository.STREAM_PART,
                    MediaRepository.STREAM_PART * 2,
                    MediaRepository.STREAM_PART * 3,
                ),
                offsets,
            )
            assertTrue(offsets.all { it < MediaRepository.STREAM_PREFIX_BYTES })
            assertEquals(0, fullCalls)
            val prefix = streamPrefixPart(cacheRoot, checkNotNull(streamMediaKey(message)), 0L)
            assertTrue(prefix.isFile)
            assertEquals(prefix, reusableStreamPrefix(prefix))
            assertEquals(null, reusableStreamPrefix(File(prefix.parentFile, "missing.prefix")))
            assertTrue(repository.clearChatCache(message.id.chatId.value) > 0)
            assertTrue(!prefix.exists())
        } finally {
            repository.shutdown()
        }
    }
}
