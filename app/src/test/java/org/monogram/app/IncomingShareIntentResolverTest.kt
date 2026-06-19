package org.monogram.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingShareIntentResolverTest {
    @Test
    fun `normalizeIncomingSharePayload returns text only share`() {
        val payload = normalizeIncomingSharePayload(
            IncomingSharePayloadInput(
                action = "android.intent.action.SEND",
                text = " hello "
            )
        )

        assertEquals("hello", payload?.text)
        assertEquals(emptyList<String>(), payload?.uriStrings)
    }

    @Test
    fun `normalizeIncomingSharePayload merges clipData and extra stream without duplicates`() {
        val payload = normalizeIncomingSharePayload(
            IncomingSharePayloadInput(
                action = "android.intent.action.SEND_MULTIPLE",
                text = "payload",
                clipDataUris = listOf("content://share/1", "content://share/2"),
                extraStreamUris = listOf("content://share/1", "content://share/2")
            )
        )

        assertEquals("payload", payload?.text)
        assertEquals(listOf("content://share/1", "content://share/2"), payload?.uriStrings)
    }

    @Test
    fun `normalizeIncomingSharePayload returns null for unsupported intent`() {
        val payload = normalizeIncomingSharePayload(
            IncomingSharePayloadInput(
                action = "android.intent.action.VIEW",
                text = "ignored"
            )
        )

        assertNull(payload)
    }

    @Test
    fun `normalizeIncomingSharePayload returns null for empty share`() {
        val payload = normalizeIncomingSharePayload(
            IncomingSharePayloadInput(
                action = "android.intent.action.SEND",
                text = null
            )
        )

        assertNull(payload)
    }
}
