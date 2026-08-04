package org.monogram.presentation.features.chats.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState

class OutgoingMessageReducerTest {
    private val key = OutgoingMessageReducer.Key(chatId = 7L, temporaryMessageId = -10L)

    @Test
    fun `pending acknowledgement and success form a terminal lifecycle`() {
        val pending = OutgoingMessageReducer.pending(emptyMap(), pendingMessage())
        val acknowledged = OutgoingMessageReducer.acknowledged(pending, key)
        val succeeded = OutgoingMessageReducer.succeeded(acknowledged, key, finalMessageId = 101L)

        assertEquals(OutgoingMessageReducer.State.PendingLocal, pending[key])
        assertEquals(OutgoingMessageReducer.State.Acknowledged, acknowledged[key])
        assertEquals(OutgoingMessageReducer.State.Succeeded(101L), succeeded[key])
    }

    @Test
    fun `duplicate terminal updates do not replace the first terminal state`() {
        val succeeded = OutgoingMessageReducer.succeeded(emptyMap(), key, finalMessageId = 101L)
        val duplicateSuccess =
            OutgoingMessageReducer.succeeded(succeeded, key, finalMessageId = 102L)
        val lateFailure = OutgoingMessageReducer.failed(duplicateSuccess, key, errorCode = 500)

        assertEquals(OutgoingMessageReducer.State.Succeeded(101L), lateFailure[key])
    }

    @Test
    fun `failure is retryable only for transient TDLib errors`() {
        val retryable = OutgoingMessageReducer.failed(emptyMap(), key, errorCode = 429)
        val permanent = OutgoingMessageReducer.failed(emptyMap(), key, errorCode = 400)

        assertEquals(OutgoingMessageReducer.State.Failed(429, true), retryable[key])
        assertEquals(OutgoingMessageReducer.State.Failed(400, false), permanent[key])
    }

    @Test
    fun `recovery recreates pending and failed operations from TDLib message state`() {
        val restored = OutgoingMessageReducer.recover(
            listOf(
                pendingMessage(),
                pendingMessage(id = -11L, state = MessageSendingState.Failed(500, "temporary"))
            )
        )

        assertEquals(OutgoingMessageReducer.State.PendingLocal, restored[key])
        assertTrue(
            restored[OutgoingMessageReducer.Key(7L, -11L)] is OutgoingMessageReducer.State.Failed
        )
    }

    private fun pendingMessage(
        id: Long = -10L,
        state: MessageSendingState = MessageSendingState.Pending
    ) = MessageModel(
        id = id,
        date = 0,
        isOutgoing = true,
        senderName = "",
        chatId = 7L,
        content = MessageContent.Text(""),
        sendingState = state
    )
}
