package org.monogram.feature.dialog.ui

import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.database.entity.OutgoingTextSendEntity
import org.monogram.core.database.toEntity
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class OutgoingBubbleSendTest {
    private val message = Message(
        id = MessageId(PeerId(5), -1), senderId = null, text = "Pending", date = 10,
        outgoing = true, pending = true, randomId = 42,
    )

    private fun row() = OutgoingTextSendEntity(42, message.toEntity(), false, false, null, null)

    @Test
    fun restoredSendAppearsOnceAndAcknowledgementSuppressesLocalCopy() {
        val row = row()
        assertEquals(
            listOf(message),
            outgoingHistoryMessages(emptyList(), listOf(row), emptyList())
        )
        assertEquals(
            listOf(message),
            outgoingHistoryMessages(listOf(message), listOf(row), emptyList())
        )
        val sent = message.copy(id = MessageId(PeerId(5), 100), pending = false)
        assertEquals(listOf(sent), outgoingHistoryMessages(listOf(sent), listOf(row), emptyList()))
    }

    @Test
    fun scheduledSendStaysOutsideNormalHistory() {
        assertTrue(
            outgoingHistoryMessages(
                emptyList(),
                listOf(row().copy(scheduleDate = 1000)),
                emptyList()
            ).isEmpty()
        )
    }

    @Test
    fun mediaAlbumRestoresEachItemAndSharesTheBatchRetry() {
        val batch = org.monogram.core.database.entity.OutgoingMediaBatchEntity(
            77,
            5,
            10,
            false,
            null,
            true,
            OutgoingSendPhase.FAILED
        )
        val source = org.monogram.core.database.RetainedMediaSource("fixture", 1, "hash")
        val first = org.monogram.core.database.entity.OutgoingMediaItemEntity(
            42,
            77,
            0,
            "image/jpeg",
            source,
            message.toEntity()
        )
        val secondMessage = message.copy(id = MessageId(PeerId(5), -2), randomId = 43)
        val second = first.copy(randomId = 43, position = 1, message = secondMessage.toEntity())
        val send = org.monogram.core.database.entity.OutgoingMediaSend(batch, listOf(first, second))
        assertEquals(
            listOf(message, secondMessage),
            outgoingHistoryMessages(emptyList(), emptyList(), listOf(send))
        )
        for (item in listOf(message, secondMessage)) {
            assertEquals(
                OutgoingBubbleSend(77, OutgoingSendPhase.FAILED, true),
                outgoingBubbleSend(item, emptyList(), listOf(send))
            )
        }
        assertTrue(
            outgoingHistoryMessages(
                emptyList(),
                emptyList(),
                listOf(send.copy(batch = batch.copy(scheduleDate = 1000)))
            ).isEmpty()
        )
    }

    @Test
    fun acknowledgementKeepsTheOutgoingIdentityForTheStatusTransition() {
        val state =
            org.monogram.feature.dialog.DialogStore.State(PeerId(5), messages = listOf(message))
        val sent = message.copy(id = MessageId(PeerId(5), 100), randomId = null, pending = false)
        val result = with(org.monogram.feature.dialog.store.DialogReducer) {
            state.reduce(org.monogram.feature.dialog.store.Msg.ReplacePending(-1, sent))
        }
        assertEquals(1, result.messages.size)
        assertEquals(sent.id, result.messages.single().id)
        assertEquals(message.randomId, result.messages.single().randomId)
        assertFalse(result.messages.single().pending)
    }

    @Test
    fun failureStateRemainsAttachedToTheMatchingBubble() {
        for (phase in OutgoingSendPhase.entries) {
            assertEquals(
                OutgoingBubbleSend(42, phase, false),
                outgoingBubbleSend(message, listOf(row().copy(phase = phase)), emptyList())
            )
        }
        assertNull(
            outgoingBubbleSend(
                message.copy(randomId = 99, id = MessageId(PeerId(5), -2)),
                listOf(row()),
                emptyList()
            )
        )
    }
}
