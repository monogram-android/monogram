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
import java.io.File
import java.io.RandomAccessFile

class MediaStreamingTest {
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
                file.seek(offset)
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
}
