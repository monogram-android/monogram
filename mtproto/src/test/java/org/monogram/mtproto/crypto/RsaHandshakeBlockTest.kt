package org.monogram.mtproto.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RsaHandshakeBlockTest {
    @Test
    fun buildsDeterministicFixedWidthEncryptedBlock() {
        val input = ByteArray(96) { it.toByte() }
        val original = input.copyOf()
        val key = RsaPublicKey.fromPkcs1Pem(RsaPublicKeyTest.PEM)
        val first = RsaHandshakeBlock.encrypt(input, key, CounterEntropy())
        val second = RsaHandshakeBlock.encrypt(input, key, CounterEntropy())
        assertEquals(256, first.size)
        assertArrayEquals(first, second)
        assertArrayEquals(original, input)
        assertFalse(first.all { it == 0.toByte() })
    }

    @Test
    fun rejectsOversizedInnerDataAndInvalidAttemptBudgets() {
        val key = RsaPublicKey.fromPkcs1Pem(RsaPublicKeyTest.PEM)
        assertThrows(IllegalArgumentException::class.java) {
            RsaHandshakeBlock.encrypt(ByteArray(145), key, CounterEntropy())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RsaHandshakeBlock.encrypt(ByteArray(1), key, CounterEntropy(), maxAttempts = 0)
        }
    }

    private class CounterEntropy : EntropySource {
        private var value = 1
        override fun nextBytes(destination: ByteArray) {
            destination.indices.forEach { index ->
                destination[index] = value.toByte()
                value = value * 1103515245 + 12345
            }
        }
    }
}
