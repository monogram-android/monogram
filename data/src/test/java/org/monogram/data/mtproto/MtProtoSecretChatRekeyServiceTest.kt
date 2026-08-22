package org.monogram.data.mtproto

import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.monogram.mtproto.secret.MtProtoSecretChatRekey
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoSecretChatRekeyServiceTest {
    private val chatId = 9
    private val prime = BigInteger("2339584727")
    private val g = BigInteger.TWO
    private val ownPrivate = BigInteger.valueOf(314_159L)
    private val ownPublic: ByteArray by lazy { g.modPow(ownPrivate, prime).toByteArray() }
    private val peerPrivate = BigInteger.valueOf(271_828L)

    private fun state(
        useOut: Int = 100,
        created: Long = 0L,
        exchangeId: Long = 0L,
    ) = MtProtoSecretChatState(
        chatId = chatId, accessHash = 8L, adminId = 1L, participantId = 2L,
        authKey = ByteArray(256) { (it * 3).toByte() },
        keyFingerprint = 42L,
        maxInSeq = 4, maxOutSeq = 6,
        exchangeId = exchangeId,
        keyCreateDateSeconds = created,
        keyUseCountIn = 130, keyUseCountOut = useOut,
    )

    private class RecordingStore(initial: MtProtoSecretChatState) : MtProtoSecretChatStateStore {
        var state: MtProtoSecretChatState = initial
        var stagedFutureKey: ByteArray? = null
        var stagedFingerprint: Long? = null
        var committed = false

        override suspend fun get(chatId: Int) = state
        override suspend fun save(state: MtProtoSecretChatState) { this.state = state }
        override suspend fun recordSend(chatId: Int): Boolean {
            state = state.copy(maxOutSeq = state.maxOutSeq + 1)
            return true
        }
        override suspend fun stageFutureKey(chatId: Int, futureAuthKey: ByteArray, futureKeyFingerprint: Long) {
            stagedFutureKey = futureAuthKey
            stagedFingerprint = futureKeyFingerprint
            state = state.copy(futureAuthKey = futureAuthKey, futureKeyFingerprint = futureKeyFingerprint)
        }
        override suspend fun commitFutureKey(chatId: Int) {
            val future = state.futureAuthKey ?: return
            val fp = state.futureKeyFingerprint ?: return
            state = state.copy(
                authKey = future,
                keyFingerprint = fp,
                futureAuthKey = null,
                futureKeyFingerprint = null,
                exchangeId = 0L,
                keyUseCountIn = 0, keyUseCountOut = 0,
            )
            committed = true
        }
        override suspend fun delete(chatId: Int) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }

    @Suppress("SameParameterValue")
    private fun service(
        store: RecordingStore,
        now: Long = 1_000_000L + 604_801L,
        allocator: () -> Long = { 77L },
    ): MtProtoSecretChatRekeyService =
        MtProtoSecretChatRekeyService(
            store = store,
            ownPrivateExponent = ownPrivate.toByteArray(),
            prime = prime.toByteArray(),
            nowSeconds = { now }, // one second past 7 days from creation=1_000_000 by default
            exchangeIdAllocator = allocator,
        )

    @Test
    fun `begins exchange when aged out and persists the exchange id`() = runBlocking {
        val store = RecordingStore(state(created = 1_000_000L))
        val service = service(store) { 77L }

        val request = service.maybeBeginExchange(chatId, gA = byteArrayOf(9))

        request as MtProtoSecretRekeyRequest
        assertEquals(77L, request.exchangeId)
        assertEquals(77L, store.state.exchangeId)
        assertEquals(listOf(9), request.gA.map { it.toInt() })
    }

    @Test
    fun `returns null before thresholds or with an exchange in flight`() = runBlocking {
        val fresh = RecordingStore(state(useOut = 99, created = 1_000_000L))
        // 99 uses and only one day of age: neither threshold met.
        assertNull(service(fresh, now = 1_000_000L + 86_400L) { 77L }.maybeBeginExchange(chatId, byteArrayOf(9)))

        val inFlight = RecordingStore(state(useOut = 500, created = 0L, exchangeId = 12L))
        assertNull(service(inFlight) { 77L }.maybeBeginExchange(chatId, byteArrayOf(9)))
    }

    @Test
    fun `peer acceptance stages the mirrored future key and commit swaps it`() = runBlocking {
        val peerPublic = g.modPow(peerPrivate, prime).toByteArray()
        val store = RecordingStore(state(exchangeId = 77L))
        val service = service(store) { 77L }

        // Mirror derivation: peer computes the same secret against our public part.
        val mirror = MtProtoSecretChatRekey.deriveFutureKeys(
            ownPublic, peerPrivate.toByteArray(), prime.toByteArray(),
        )

        val fingerprint = service.onPeerAccepted(chatId, exchangeId = 77L, peerGA = peerPublic)

        assertEquals(mirror.keyFingerprint, fingerprint)
        assertTrue(store.stagedFutureKey!!.contentEquals(mirror.authKey))
        assertEquals(mirror.keyFingerprint, store.state.futureKeyFingerprint)

        service.commit(chatId)

        assertTrue(store.committed)
        assertTrue(store.state.authKey.contentEquals(mirror.authKey))
        assertEquals(mirror.keyFingerprint, store.state.keyFingerprint)
        assertNull(store.state.futureAuthKey)
        assertEquals(0L, store.state.exchangeId)
        assertEquals(0, store.state.keyUseCountOut)
    }

    @Test
    fun `rejects acceptance for a mismatched exchange id`() = runBlocking {
        val store = RecordingStore(state(exchangeId = 77L))
        val service = service(store) { 77L }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service.onPeerAccepted(chatId, exchangeId = 88L, peerGA = byteArrayOf(1)) }
        }
        assertTrue(thrown.message!!.contains("exchange id mismatch"))
    }
}
