package org.monogram.mtproto.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.crypto.EntropySource
import org.monogram.mtproto.crypto.MtProtoKeyDerivation
import org.monogram.mtproto.handshake.MtProtoAuthKey

class MtProtoEncryptedSessionTest {
    @Test
    fun assignsMonotonicMessageIdsAndContentSequenceNumbers() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        try {
            val firstPacket = session.encode(BODY, contentRelated = true)
            val secondPacket = session.encode(BODY, contentRelated = false)
            assertThrows(IllegalArgumentException::class.java) {
                session.encode(ByteArray(3), contentRelated = true)
            }
            val thirdPacket = session.encode(BODY, contentRelated = true)
            var first: MtProtoEncryptedMessage? = null
            var second: MtProtoEncryptedMessage? = null
            var third: MtProtoEncryptedMessage? = null
            try {
                first = EncryptedMessageCodec.decode(authKey, session.sessionId, firstPacket, EncryptedMessageCodec.CLIENT_X)
                second = EncryptedMessageCodec.decode(authKey, session.sessionId, secondPacket, EncryptedMessageCodec.CLIENT_X)
                third = EncryptedMessageCodec.decode(authKey, session.sessionId, thirdPacket, EncryptedMessageCodec.CLIENT_X)
                assertEquals(1, first.metadata.sequenceNumber)
                assertEquals(2, second.metadata.sequenceNumber)
                assertEquals(3, third.metadata.sequenceNumber)
                assertEquals(4L, second.metadata.messageId - first.metadata.messageId)
                assertEquals(8L, third.metadata.messageId - second.metadata.messageId)
                val body = first.copyBody()
                try {
                    assertArrayEquals(BODY, body)
                } finally {
                    body.fill(0)
                }
            } finally {
                first?.close()
                second?.close()
                third?.close()
                firstPacket.fill(0)
                secondPacket.fill(0)
                thirdPacket.fill(0)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun authenticatesAndDecodesServerDirectionPacket() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        val packet = EncryptedMessageCodec.encode(
            authKey,
            MtProtoEncryptedMessageMetadata(91L, session.sessionId, (1_700_000_000L shl 32) or 1L, 1),
            BODY,
            CounterEntropy(),
            EncryptedMessageCodec.SERVER_X,
        )
        try {
            val decoded = session.decode(packet)
            decoded.use {
                assertEquals(91L, decoded.metadata.serverSalt)
                assertEquals(session.sessionId, decoded.metadata.sessionId)
                val body = decoded.copyBody()
                try {
                    assertArrayEquals(BODY, body)
                } finally {
                    body.fill(0)
                }
            }
            assertThrows(IllegalStateException::class.java) { decoded.copyBody() }
            assertThrows(IllegalArgumentException::class.java) { session.decode(packet) }
            packet[packet.lastIndex] = (packet.last().toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { session.decode(packet) }
        } finally {
            packet.fill(0)
            session.close()
        }
    }

    @Test
    fun rejectsAuthenticatedPacketsOutsideServerTimeWindow() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        val packet = EncryptedMessageCodec.encode(
            authKey,
            MtProtoEncryptedMessageMetadata(91L, session.sessionId, (1_699_999_699L shl 32) or 1L, 1),
            BODY,
            CounterEntropy(),
            EncryptedMessageCodec.SERVER_X,
        )
        try {
            assertThrows(IllegalArgumentException::class.java) { session.decode(packet) }
        } finally {
            packet.fill(0)
            session.close()
        }
    }

    @Test
    fun appliesUpdatedSaltAndRejectsUseAfterClose() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        var packet: ByteArray? = null
        try {
            session.updateServerSalt(123L)
            packet = session.encode(BODY, contentRelated = false)
            EncryptedMessageCodec.decode(authKey, session.sessionId, packet, EncryptedMessageCodec.CLIENT_X).use {
                assertEquals(123L, it.metadata.serverSalt)
            }
        } finally {
            packet?.fill(0)
            session.close()
        }
        assertThrows(IllegalStateException::class.java) { session.encode(BODY, false) }
        assertThrows(IllegalStateException::class.java) { session.decode(ByteArray(72)) }
        assertThrows(IllegalStateException::class.java) { session.updateServerSalt(456L) }
        assertThrows(IllegalStateException::class.java) { authKey.toByteArray() }
        session.close()
    }

    @Test
    fun validatesBodyAlignmentAndServerMessageIds() {
        val authKey = authKey()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                EncryptedMessageCodec.encode(
                    authKey,
                    MtProtoEncryptedMessageMetadata(0L, 1L, 4L, 0),
                    ByteArray(3),
                    CounterEntropy(),
                    EncryptedMessageCodec.CLIENT_X,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                EncryptedMessageCodec.encode(
                    authKey,
                    MtProtoEncryptedMessageMetadata(0L, 1L, 4L, 0),
                    BODY,
                    CounterEntropy(),
                    EncryptedMessageCodec.SERVER_X,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                EncryptedMessageCodec.encode(
                    authKey,
                    MtProtoEncryptedMessageMetadata(0L, 1L, 4L, 0),
                    ByteArray(16_777_144),
                    CounterEntropy(),
                    EncryptedMessageCodec.CLIENT_X,
                )
            }
        } finally {
            authKey.close()
        }
    }

    private class CounterEntropy : EntropySource {
        private var call = 0
        override fun nextBytes(destination: ByteArray) {
            call++
            destination.fill(call.toByte())
        }
    }

    private fun authKey(): MtProtoAuthKey {
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        val idBytes = MtProtoKeyDerivation.authKeyIdBytes(material)
        return try {
            val id = ByteBuffer.wrap(idBytes).order(ByteOrder.LITTLE_ENDIAN).long
            MtProtoAuthKey.restore(material, id, 73L, 1_700_000_000)
        } finally {
            idBytes.fill(0)
            material.fill(0)
        }
    }

    private companion object {
        val BODY = byteArrayOf(0x78, 0x56, 0x34, 0x12)
    }
}
