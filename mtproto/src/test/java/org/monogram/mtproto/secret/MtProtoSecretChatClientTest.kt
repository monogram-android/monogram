package org.monogram.mtproto.secret

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChatDiscarded
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChatRequested
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChatWaiting
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChat_8f2eebf4e2
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AcceptEncryption
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DiscardEncryption
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.RequestEncryption
import org.monogram.mtproto.tl.runtime.TlBytes
import org.monogram.mtproto.tl.runtime.TlMethod

class MtProtoSecretChatClientTest {
    @Test
    fun `requests chats against a user id and classifies waiting`() = runBlocking {
        val requests = mutableListOf<TlMethod<*>>()
        val client = MtProtoSecretChatClient(
            executor = { method ->
                requests += method
                EncryptedChatWaiting(id = 9, accessHash = 77L, date = 0, adminId = 1L, participantId = 2L)
            },
        )

        val state = client.request(userId = 5L, randomId = 123, gA = byteArrayOf(1))

        state as MtProtoSecretChatState.Waiting
        assertEquals(9, state.chatId)
        val request = requests.single() as RequestEncryption
        assertEquals(123, request.randomId)
    }

    @Test
    fun `classifies incoming requests established chats and discards`() = runBlocking {
        val responses = ArrayDeque<org.monogram.mtproto.tl.runtime.TlObject>().apply {
            add(
                EncryptedChatRequested(
                    folderId = null, id = 3, accessHash = 8L, date = 0,
                    adminId = 1L, participantId = 2L, gA = TlBytes.copyOf(byteArrayOf(4)),
                ),
            )
            add(
                EncryptedChat_8f2eebf4e2(
                    id = 3, accessHash = 8L, date = 0, adminId = 1L,
                    participantId = 2L, gAOrB = TlBytes.copyOf(byteArrayOf(6)), keyFingerprint = 99L,
                ),
            )
        }
        val client = MtProtoSecretChatClient(
            executor = { responses.removeFirst() },
        )
        var discarded: MtProtoSecretChatState? = null
        val clientWithDiscard = MtProtoSecretChatClient(
            executor = { method ->
                val result: org.monogram.mtproto.tl.runtime.TlObject = when (method) {
                    is AcceptEncryption -> responses.removeFirst()
                    is DiscardEncryption -> {
                        discarded = MtProtoSecretChatState.Discarded(method.chatId, method.deleteHistory)
                        org.monogram.mtproto.tl.generated.cloud.layer223.BoolTrue
                    }
                    else -> error("unexpected")
                }
                result
            },
        )

        // First response: an incoming request carrying the peer's gA.
        val requested = client.request(userId = 5L, randomId = 1, gA = byteArrayOf(0))
        requested as MtProtoSecretChatState.Requested
        assertEquals(3, requested.chatId)
        assertTrue(requested.gA.contentEquals(byteArrayOf(4)))

        // Second response: establishment with the peer's gB and fingerprint.
        val established = client.accept(3, 8L, byteArrayOf(6), 99L)
        established as MtProtoSecretChatState.Established
        assertEquals(99L, established.keyFingerprint)

        clientWithDiscard.discard(3, 8L, deleteHistory = true)
        discarded as MtProtoSecretChatState.Discarded
        assertTrue(discarded.historyDeleted)
    }

    @Test
    fun `fails closed on unsupported encrypted chat constructors`() {
        val client = MtProtoSecretChatClient(
            executor = { object : org.monogram.mtproto.tl.runtime.TlObject { override val constructorId: UInt = 0u } },
        )

        assertThrows(IllegalStateException::class.java) { runBlocking { client.request(null, 1, byteArrayOf()) } }
    }
}
