package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchDateJumpPolicyTest {
    @Test
    fun `single day root chat without query resolves date jump`() {
        val request = resolveSearchDateJumpRequest(
            query = "",
            senderId = null,
            fromEpochSeconds = 1_700_000_000,
            toEpochSeconds = 1_700_086_399,
            isThreadScoped = false
        )

        requireNotNull(request)
        assertEquals(1_700_000_000, request.fromEpochSeconds)
        assertEquals(1_700_086_399, request.targetEpochSeconds)
    }

    @Test
    fun `multi day range keeps regular search`() {
        assertNull(
            resolveSearchDateJumpRequest(
                query = "",
                senderId = null,
                fromEpochSeconds = 1_700_000_000,
                toEpochSeconds = 1_700_172_799,
                isThreadScoped = false
            )
        )
    }

    @Test
    fun `query sender or thread scope disables date jump`() {
        assertNull(
            resolveSearchDateJumpRequest(
                query = "hello",
                senderId = null,
                fromEpochSeconds = 1,
                toEpochSeconds = 10,
                isThreadScoped = false
            )
        )
        assertNull(
            resolveSearchDateJumpRequest(
                query = "",
                senderId = 42L,
                fromEpochSeconds = 1,
                toEpochSeconds = 10,
                isThreadScoped = false
            )
        )
        assertNull(
            resolveSearchDateJumpRequest(
                query = "",
                senderId = null,
                fromEpochSeconds = 1,
                toEpochSeconds = 10,
                isThreadScoped = true
            )
        )
    }
}
