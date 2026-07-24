package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.date.Fake12HourDateFormatManagerImpl
import org.monogram.core.date.Fake24HourDateFormatManagerImpl
import org.monogram.domain.repository.StringProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMapperTest {

    @Test
    fun `formatMessageInfo uses 12 hour time format`() {
        val mapper = ChatMapper(FakeStringProvider(), Fake12HourDateFormatManagerImpl())
        val timestampSeconds = 1710948000
        val message = createTextMessage(timestampSeconds)

        val (_, _, time) = mapper.formatMessageInfo(message) { null }

        val expected =
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampSeconds * 1000L))
        assertEquals(expected, time)
    }

    @Test
    fun `formatMessageInfo uses 24 hour time format`() {
        val mapper = ChatMapper(FakeStringProvider(), Fake24HourDateFormatManagerImpl())
        val timestampSeconds = 1710948000
        val message = createTextMessage(timestampSeconds)

        val (_, _, time) = mapper.formatMessageInfo(message) { null }

        val expected =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampSeconds * 1000L))
        assertEquals(expected, time)
    }

    @Test
    fun `encodeChatPositions keeps folder ids`() {
        val positions = arrayOf(
            TdApi.ChatPosition(TdApi.ChatListMain(), 10L, true, null),
            TdApi.ChatPosition(TdApi.ChatListFolder(42), 7L, false, null),
            TdApi.ChatPosition(TdApi.ChatListArchive(), 3L, false, null)
        )

        assertEquals("m:10:1|f:42:7:0|a:3:0", encodeChatPositions(positions))
    }

    private fun createTextMessage(timestampSeconds: Int): TdApi.Message {
        return TdApi.Message().apply {
            date = timestampSeconds
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText("test", emptyArray())
            }
        }
    }

    private class FakeStringProvider : StringProvider {
        override fun getString(resName: String): String = resName

        override fun getString(resName: String, vararg formatArgs: Any): String = resName

        override fun getQuantityString(
            resName: String,
            quantity: Int,
            vararg formatArgs: Any
        ): String = resName
    }
}
