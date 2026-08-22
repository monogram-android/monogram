package org.monogram.data.mtproto

import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.crypto.MtProtoSecretChatKeyDerivation
import org.monogram.mtproto.secret.MtProtoSecretInbound
import org.monogram.mtproto.secret.MtProtoSecretChatMessenger

class MtProtoRoomSecretChatSessionTest {
    private val chatId = 9
    private val prime = BigInteger("2339584727")
    private val g = BigInteger.TWO
    private val creatorPrivate = BigInteger.valueOf(111_111L)
    private val acceptorPrivate = BigInteger.valueOf(222_222L)

    private fun senderAuthKey(): ByteArray = MtProtoSecretChatKeyDerivation.completeSharedSecret(
        peerPublic = acceptorPublic(),
        privateExponent = creatorPrivate.toByteArray(),
        prime = prime.toByteArray(),
    )

    private fun receiverAuthKey(): ByteArray = MtProtoSecretChatKeyDerivation.completeSharedSecret(
        peerPublic = creatorPublic(),
        privateExponent = acceptorPrivate.toByteArray(),
        prime = prime.toByteArray(),
    )

    private fun acceptorPublic(): ByteArray = g.modPow(acceptorPrivate, prime).toByteArray()

    private fun creatorPublic(): ByteArray = g.modPow(creatorPrivate, prime).toByteArray()

    private class RecordingStore(initial: MtProtoSecretChatState) : MtProtoSecretChatStateStore {
        var state: MtProtoSecretChatState = initial
        var saves = 0

        override suspend fun get(chatId: Int): MtProtoSecretChatState? = state

        override suspend fun save(state: MtProtoSecretChatState) {
            this.state = state
            saves++
        }

        override suspend fun recordSend(chatId: Int): Boolean {
            state = state.copy(maxOutSeq = state.maxOutSeq + 1)
            return true
        }

        override suspend fun stageFutureKey(chatId: Int, futureAuthKey: ByteArray, futureKeyFingerprint: Long) = Unit

        override suspend fun commitFutureKey(chatId: Int) = Unit

        override suspend fun delete(chatId: Int) { state = state.copy(authKey = ByteArray(0)) }

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
            state = state.copy(authKey = ByteArray(0))
        }
    }

    @Test
    fun `onSent routes through recordSend for re-key accounting`() = runBlocking {
        val store = RecordingStore(
            MtProtoSecretChatState(
                chatId = chatId, accessHash = 8L, adminId = 1L, participantId = 2L,
                authKey = senderAuthKey(), keyFingerprint = MtProtoSecretChatKeyDerivation.fingerprint(senderAuthKey()),
                maxInSeq = 0, maxOutSeq = 0,
            ),
        )
        val session = MtProtoRoomSecretChatSession(store)

        session.onSent(chatId)

        // Use accounting advanced without touching sequence counters.
        assertEquals(1, store.state.keyUseCountOut)
        assertEquals(0, store.state.maxOutSeq)
    }

    @Test
    fun `send and receive round trip persists counters through the room store`() = runBlocking {
        val senderStore = RecordingStore(
            MtProtoSecretChatState(
                chatId = chatId, accessHash = 8L, adminId = 1L, participantId = 2L,
                authKey = senderAuthKey(), keyFingerprint = MtProtoSecretChatKeyDerivation.fingerprint(senderAuthKey()),
                maxInSeq = 0, maxOutSeq = 0,
            ),
        )
        val receiverStore = RecordingStore(
            MtProtoSecretChatState(
                chatId = chatId, accessHash = 8L, adminId = 1L, participantId = 2L,
                authKey = receiverAuthKey(), keyFingerprint = MtProtoSecretChatKeyDerivation.fingerprint(receiverAuthKey()),
                maxInSeq = 0, maxOutSeq = 0,
            ),
        )
        val senderMessenger = MtProtoSecretChatMessenger(MtProtoRoomSecretChatSession(senderStore))
        val receiverMessenger = MtProtoSecretChatMessenger(MtProtoRoomSecretChatSession(receiverStore))

        val outbound = senderMessenger.send(chatId, "hello secret".toByteArray())
        assertEquals(1, outbound.seqOut)
        assertEquals(1, senderStore.state.maxOutSeq)
        assertEquals(1, senderStore.state.keyUseCountOut)

        val inbound = receiverMessenger.receive(chatId, outbound.packet, seqOut = outbound.seqOut) as MtProtoSecretInbound.Accepted
        assertEquals(1, inbound.seqOut)
        assertTrue(inbound.paddedPlaintext.copyOf("hello secret".length).contentEquals("hello secret".toByteArray()))
        assertEquals(1, receiverStore.state.maxInSeq)

        // Second message continues the chain on both sides.
        val second = senderMessenger.send(chatId, "again".toByteArray())
        assertEquals(2, second.seqOut)
        // The sender never received anything, so its acknowledged inbound count stays 0.
        assertEquals(0, second.ackedInSeq)

        val acceptedSecond = receiverMessenger.receive(chatId, second.packet, seqOut = 2) as MtProtoSecretInbound.Accepted
        assertTrue(acceptedSecond.paddedPlaintext.copyOf(5).contentEquals("again".toByteArray()))
        assertEquals(2, receiverStore.state.maxInSeq)
        assertTrue(senderStore.saves >= 2 && receiverStore.saves >= 2)
    }
}
