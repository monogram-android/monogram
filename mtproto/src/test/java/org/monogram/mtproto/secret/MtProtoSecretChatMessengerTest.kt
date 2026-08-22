package org.monogram.mtproto.secret

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.crypto.MtProtoSecretChatKeyDerivation

class MtProtoSecretChatMessengerTest {
    private val chatId = 9
    private val prime = BigInteger("2339584727")
    private val g = BigInteger.TWO
    private val creatorPrivate = BigInteger.valueOf(111_111L)
    private val acceptorPrivate = BigInteger.valueOf(222_222L)
    private val creatorPublic: ByteArray by lazy { g.modPow(creatorPrivate, prime).toByteArray() }

    private fun senderAuthKey(): ByteArray =
        MtProtoSecretChatKeyDerivation.completeSharedSecret(
            peerPublic = acceptorPublic(),
            privateExponent = creatorPrivate.toByteArray(),
            prime = prime.toByteArray(),
        )

    private fun receiverAuthKey(): ByteArray =
        MtProtoSecretChatKeyDerivation.completeSharedSecret(
            peerPublic = creatorPublic,
            privateExponent = acceptorPrivate.toByteArray(),
            prime = prime.toByteArray(),
        )

    private fun acceptorPublic(): ByteArray = g.modPow(acceptorPrivate, prime).toByteArray()

    @Suppress("MemberVisibilityCanBePrivate")
    private class InMemoryState : MtProtoSecretChatSessionState {
        val chats = ConcurrentHashMap<Int, LoadedSecretChat>()
        var saves = 0
        var sentCount = 0

        override suspend fun load(chatId: Int) = chats[chatId]

        override suspend fun saveCounters(chatId: Int, maxInSeq: Int, maxOutSeq: Int) {
            chats.computeIfPresent(chatId) { _, loaded -> loaded.copy(maxInSeq = maxInSeq, maxOutSeq = maxOutSeq) }
            saves++
        }

        override suspend fun onSent(chatId: Int) { sentCount++ }
    }

    private fun seed(state: InMemoryState, authKey: ByteArray) {
        state.chats[chatId] = LoadedSecretChat(
            chatId = chatId, accessHash = 8L, authKey = authKey,
            keyFingerprint = MtProtoSecretChatKeyDerivation.fingerprint(authKey),
            maxInSeq = 0, maxOutSeq = 0,
        )
    }

    private suspend fun sealedPacket(authKey: ByteArray, seqOut: Int): ByteArray {
        val state = InMemoryState()
        seed(state, authKey)
        val messenger = MtProtoSecretChatMessenger(state)
        return messenger.send(chatId, "body-$seqOut".toByteArray()).packet
    }

    @Test
    fun `send allocates sequences and persists counters`() = runBlocking {
        val state = InMemoryState()
        seed(state, senderAuthKey())
        val messenger = MtProtoSecretChatMessenger(state)

        val first = messenger.send(chatId, "one".toByteArray())
        assertEquals(1, first.seqOut)
        assertEquals(1, state.chats.getValue(chatId).maxOutSeq)
        assertEquals(0, first.ackedInSeq)

        val second = messenger.send(chatId, "two".toByteArray())
        assertEquals(2, second.seqOut)
        assertEquals(2, state.chats.getValue(chatId).maxOutSeq)
        assertTrue(second.packet.size > 24)
        // Outbound use accounting fired for each send (feeds re-key thresholds).
        assertEquals(2, state.sentCount)
    }

    @Test
    fun `receive accepts in order persists and drops duplicates without persisting`() = runBlocking {
        val receiverKey = receiverAuthKey()
        val state = InMemoryState()
        seed(state, receiverKey)
        val messenger = MtProtoSecretChatMessenger(state)

        val accepted = messenger.receive(
            chatId,
            sealedPacket(receiverKey, seqOut = 1),
            seqOut = 1,
        ) as MtProtoSecretInbound.Accepted

        assertEquals(1, accepted.seqOut)
        assertEquals(1, state.chats.getValue(chatId).maxInSeq)
        val savesAfterAccept = state.saves

        val duplicate = messenger.receive(
            chatId,
            sealedPacket(receiverKey, seqOut = 1),
            seqOut = 1,
        ) as MtProtoSecretInbound.Duplicate

        assertEquals(1, duplicate.seqOut)
        assertEquals(savesAfterAccept, state.saves)
        assertEquals(1, state.chats.getValue(chatId).maxInSeq)
    }

    @Test
    fun `gaps surface expected sequence and leave state untouched until filled`() = runBlocking {
        val receiverKey = receiverAuthKey()
        val state = InMemoryState()
        seed(state, receiverKey)
        val messenger = MtProtoSecretChatMessenger(state)

        val gap = messenger.receive(
            chatId,
            sealedPacket(receiverKey, seqOut = 5),
            seqOut = 5,
        ) as MtProtoSecretInbound.Gap

        assertEquals(5, gap.seqOut)
        assertEquals(1, gap.expectedSeqOut)
        assertEquals(0, state.chats.getValue(chatId).maxInSeq)
        assertEquals(0, state.saves)

        // The chain stays alive for in-order delivery after a skipped future message.
        val inOrder = messenger.receive(
            chatId,
            sealedPacket(receiverKey, seqOut = 1),
            seqOut = 1,
        ) as MtProtoSecretInbound.Accepted
        assertEquals(1, inOrder.seqOut)
        assertEquals(1, state.chats.getValue(chatId).maxInSeq)
    }

    @Test
    fun `rejects packets sealed under a different key`() = runBlocking {
        val state = InMemoryState()
        seed(state, senderAuthKey())
        val messenger = MtProtoSecretChatMessenger(state)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                messenger.receive(chatId, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 3), seqOut = 1)
            }
        }
        Unit
    }
}
