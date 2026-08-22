package org.monogram.mtproto.secret

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.crypto.MtProtoSecretChatKeyDerivation

class MtProtoSecretChatRekeyTest {
    private val prime = BigInteger("2339584727")
    private val g = BigInteger.TWO
    private val ownPrivate = BigInteger.valueOf(314_159L)
    private val ownPublic: ByteArray by lazy { g.modPow(ownPrivate, prime).toByteArray() }

    @Test
    fun `initiates after one hundred uses or seven days when idle`() {
        val created = 1_000_000L
        assertTrue(
            MtProtoSecretChatRekey.shouldInitiate(
                keyUseCount = 100, keyCreateDateSeconds = created, nowSeconds = created,
                exchangeId = 0L, hasFutureKey = false,
            ),
        )
        assertFalse(
            MtProtoSecretChatRekey.shouldInitiate(
                keyUseCount = 99, keyCreateDateSeconds = created, nowSeconds = created + 86_399,
                exchangeId = 0L, hasFutureKey = false,
            ),
        )
        // Aged out one second past 7 days (reference compares strictly).
        assertTrue(
            MtProtoSecretChatRekey.shouldInitiate(
                keyUseCount = 0, keyCreateDateSeconds = created, nowSeconds = created + 604_801,
                exchangeId = 0L, hasFutureKey = false,
            ),
        )
        assertFalse(
            MtProtoSecretChatRekey.shouldInitiate(
                keyUseCount = 0, keyCreateDateSeconds = created, nowSeconds = created + 604_800,
                exchangeId = 0L, hasFutureKey = false,
            ),
        )
    }

    @Test
    fun `never initiates with an exchange in flight or a pending future key`() {
        assertFalse(
            MtProtoSecretChatRekey.shouldInitiate(500, 0L, 999_999_999L, exchangeId = 4L, hasFutureKey = false),
        )
        assertFalse(
            MtProtoSecretChatRekey.shouldInitiate(500, 0L, 999_999_999L, exchangeId = 0L, hasFutureKey = true),
        )
    }

    @Test
    fun `accepts peer requests only past the inbound use threshold`() {
        assertTrue(MtProtoSecretChatRekey.shouldAccept(peerRequested = true, keyUseCountIn = 120))
        assertFalse(MtProtoSecretChatRekey.shouldAccept(peerRequested = true, keyUseCountIn = 119))
        assertFalse(MtProtoSecretChatRekey.shouldAccept(peerRequested = false, keyUseCountIn = 500))
    }

    @Test
    fun `derives future keys identified like original keys`() {
        val peerNewPublic = g.modPow(BigInteger.valueOf(9_876L), prime).toByteArray()
        val future = MtProtoSecretChatRekey.deriveFutureKeys(peerNewPublic, ownPrivate.toByteArray(), prime.toByteArray())
        val mirror = MtProtoSecretChatKeyDerivation.derive(
            ownPublic,
            BigInteger.valueOf(9_876L).toByteArray(),
            prime.toByteArray(),
        )

        assertEquals(future.authKey.toList(), mirror.authKey.toList())
        assertEquals(future.keyFingerprint, mirror.keyFingerprint)
        assertEquals(256, future.authKey.size)
    }

    @Test
    fun `shared secret guard rejects degenerate values`() {
        assertTrue(MtProtoSecretChatRekey.isGoodSharedSecret(BigInteger.valueOf(123).toByteArray(), prime.toByteArray()))
        assertFalse(MtProtoSecretChatRekey.isGoodSharedSecret(ByteArray(32), prime.toByteArray()))
        assertFalse(MtProtoSecretChatRekey.isGoodSharedSecret(prime.toByteArray(), prime.toByteArray()))
    }
}
