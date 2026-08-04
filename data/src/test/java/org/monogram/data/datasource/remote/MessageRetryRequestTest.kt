package org.monogram.data.datasource.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRetryRequestTest {

    @Test
    fun `restart retry resends the restored temporary id`() {
        val request = resendTemporaryMessageRequest(chatId = 7L, temporaryMessageId = -101L)

        assertEquals(7L, request.chatId)
        assertArrayEquals(longArrayOf(-101L), request.messageIds)
    }
}
