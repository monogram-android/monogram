package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MessagesSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Messages_3c331441fb

class MtProtoHistoryResultStagerTest {
    @Test
    fun `stages full history messages`() = runBlocking {
        val messages = RecordingMessageStore()
        val result = Messages_3c331441fb(listOf(MessageEmpty(10, PeerChat(7))), emptyList(), emptyList(), emptyList())

        val staged = MtProtoHistoryResultStager(messageStore = messages).stage(scope(), result)

        assertEquals(1, staged.size)
        assertEquals(listOf(10), messages.ids)
        assertEquals(false, messages.scheduled)
    }

    @Test
    fun `stages scheduled messages separately`() = runBlocking {
        val messages = RecordingMessageStore()
        val result = Messages_3c331441fb(listOf(MessageEmpty(12, PeerChat(7))), emptyList(), emptyList(), emptyList())

        MtProtoHistoryResultStager(messageStore = messages).stageScheduled(scope(), result)

        assertEquals(listOf(12), messages.ids)
        assertEquals(true, messages.scheduled)
    }

    @Test
    fun `stages sliced history messages`() = runBlocking {
        val messages = RecordingMessageStore()
        val result = MessagesSlice(false, 2, null, null, null, listOf(MessageEmpty(11, PeerChat(7))), emptyList(), emptyList(), emptyList())

        assertTrue(MtProtoHistoryResultStager(messageStore = messages).stage(scope(), result).isNotEmpty())
        assertEquals(listOf(11), messages.ids)
    }

    private fun scope() = MtProtoAuthKeyScope("account", MtProtoEnvironment.PRODUCTION, 2)

    private class RecordingMessageStore : MtProtoMessageProjectionStore by NoOpMtProtoMessageProjectionStore {
        var ids = emptyList<Int>()
        var scheduled = false

        override suspend fun stageMessages(
            scope: MtProtoAuthKeyScope,
            messages: List<org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4>,
            isScheduled: Boolean,
        ) {
            ids = messages.map { (it as MessageEmpty).id }
            scheduled = isScheduled
        }
    }
}
