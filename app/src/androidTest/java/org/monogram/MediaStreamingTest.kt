package org.monogram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.network.http.TelegramChunkFetcher
import org.monogram.network.http.TelegramVideoDataSource
import org.monogram.network.http.FileCache
import java.io.File
import java.io.RandomAccessFile

class MediaStreamingTest {
    @Test
    fun streamedRangesSurviveClearUntilAllReadersClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "streamed-range-lease")
        directory.deleteRecursively()
        val cache = FileCache(directory)
        val expected = ByteArray(TelegramVideoDataSource.PART_SIZE.toInt()) { (it % 251).toByte() }
        val message = Message(
            MessageId(PeerId(42), 12),
            senderId = null,
            text = null,
            date = 0,
            outgoing = false,
            mediaKind = "video",
            mediaCacheKey = "video:range-lease",
            fileSize = expected.size.toLong()
        )
        var fetches = 0
        val factory = TelegramVideoDataSource.Factory(
            message,
            directory,
            TelegramChunkFetcher { _, _, path, _ ->
                fetches++
                File(path).writeBytes(expected)
                Outcome.Ok(path)
            },
            cache
        )
        val first = factory.createDataSource()
        val second = factory.createDataSource()
        val spec = DataSpec.Builder().setUri("telegram://media/lease").build()
        try {
            first.open(spec)
            second.open(spec)
            assertEquals(1, first.read(ByteArray(1), 0, 1))
            assertEquals(1, second.read(ByteArray(1), 0, 1))
            assertEquals(1, fetches)
            assertEquals(0L, cache.clear())
            first.close()
            val actual = ByteArray(32)
            assertEquals(32, second.read(actual, 0, 32))
            assertArrayEquals(expected.copyOfRange(1, 33), actual)
            assertEquals(expected.size.toLong(), cache.totalBytes())
            second.close()
            assertEquals(0L, cache.totalBytes())
        } finally {
            first.close()
            second.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun cachedPlaybackKeepsLeaseUntilReaderCloses() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "leased-playback-regression")
        directory.deleteRecursively()
        val repository = org.monogram.network.http.MediaRepository(
            directory,
            telegramFetcher = org.monogram.network.http.TelegramMediaFetcher { _, _, path, _, _ ->
                File(path).writeBytes(byteArrayOf(1, 2, 3, 4))
                Outcome.Ok(path)
            })
        val source = repository.createCachedDataSourceFactory("photo:lease").createDataSource()
        try {
            val result = repository.ensureIndexedMedia(
                PeerId(42),
                1,
                "photo:lease",
                priority = org.monogram.network.http.MediaPriority.USER
            )
            assertTrue(result is Outcome.Ok)
            val file = (result as Outcome.Ok).value
            source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).build())
            assertEquals(0L, repository.clearCache())
            val bytes = ByteArray(4)
            assertEquals(4, source.read(bytes, 0, 4))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
            source.close()
            assertTrue(!file.exists())
        } finally {
            source.close()
            repository.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun arbitrarySeekReadsAlignedPartsAndCleansTemporaryFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "streaming-test")
        val partSize = TelegramVideoDataSource.PART_SIZE.toInt()
        val bytes = ByteArray(partSize * 2 + 91) { (it % 251).toByte() }
        val offsets = mutableListOf<Long>()
        val message = Message(
            id = MessageId(PeerId(42), 12), senderId = null, text = null,
            date = 0, outgoing = false, mediaKind = "video", fileSize = bytes.size.toLong(),
        )
        val source = TelegramVideoDataSource.Factory(message, directory, TelegramChunkFetcher { _, _, destination, offset ->
            offsets += offset
            val start = offset.toInt()
            val chunk = bytes.copyOfRange(start, minOf(start + partSize, bytes.size))
            RandomAccessFile(destination, "rw").use { file ->
                file.setLength(0)
                file.write(chunk)
            }
            Outcome.Ok(destination)
        }).createDataSource()
        val uri = Uri.parse("telegram://media/test")
        try {
            assertEquals(15L, source.open(DataSpec.Builder().setUri(uri).setPosition(partSize - 5L).setLength(15).build()))
            val actual = ByteArray(15)
            assertEquals(5, source.read(actual, 0, actual.size))
            assertEquals(10, source.read(actual, 5, 10))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(actual, 0, 1))
            assertArrayEquals(bytes.copyOfRange(partSize - 5, partSize + 10), actual)
            assertEquals(listOf(0L, partSize.toLong()), offsets)
            source.close()
            assertEquals(91L, source.open(DataSpec.Builder().setUri(uri).setPosition(partSize * 2L).build()))
            val tail = ByteArray(91)
            assertEquals(91, source.read(tail, 0, tail.size))
            assertArrayEquals(bytes.takeLast(91).toByteArray(), tail)
            assertEquals(C.RESULT_END_OF_INPUT, source.read(tail, 0, 1))
        } finally {
            source.close()
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        directory.delete()
    }

    @Test
    fun incompletePartIsReadableAtTheRequestedOffset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "streaming-incomplete")
        val part = TelegramVideoDataSource.PART_SIZE
        val offset = part
        val partial = ByteArray(48) { (it + 7).toByte() }
        val message = Message(
            id = MessageId(PeerId(7), 3),
            senderId = null,
            text = null,
            date = 0,
            outgoing = false,
            mediaKind = "video",
            fileSize = part * 4,
        )
        val source = TelegramVideoDataSource.Factory(
            message,
            directory,
            TelegramChunkFetcher { _, _, destination, requested ->
                assertEquals(offset, requested)
                RandomAccessFile(destination, "rw").use { file ->
                    file.setLength(0)
                    file.write(partial)
                }
                Outcome.Ok(destination)
            },
        ).createDataSource()
        val uri = Uri.parse("telegram://media/incomplete")
        try {
            source.open(
                DataSpec.Builder().setUri(uri).setPosition(offset + 8).setLength(16).build(),
            )
            val actual = ByteArray(16)
            assertEquals(16, source.read(actual, 0, 16))
            assertArrayEquals(partial.copyOfRange(8, 24), actual)
        } finally {
            source.close()
            directory.deleteRecursively()
        }
    }
}
