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
    fun calibratesServerTimeFromFirstAuthenticatedPacketBeforeEnforcingReplayWindow() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        val firstPacket = EncryptedMessageCodec.encode(
            authKey,
            MtProtoEncryptedMessageMetadata(91L, session.sessionId, (1_699_999_699L shl 32) or 1L, 1),
            BODY,
            CounterEntropy(),
            EncryptedMessageCodec.SERVER_X,
        )
        val stalePacket = EncryptedMessageCodec.encode(
            authKey,
            MtProtoEncryptedMessageMetadata(91L, session.sessionId, (1_699_998_699L shl 32) or 1L, 1),
            BODY,
            CounterEntropy(),
            EncryptedMessageCodec.SERVER_X,
        )
        var outboundPacket: ByteArray? = null
        var outbound: MtProtoEncryptedMessage? = null
        try {
            session.decode(firstPacket).close()
            outboundPacket = session.encode(BODY, contentRelated = true)
            outbound = EncryptedMessageCodec.decode(
                authKey,
                session.sessionId,
                outboundPacket,
                EncryptedMessageCodec.CLIENT_X,
            )
            assertEquals(1_699_999_699L, outbound.metadata.messageId ushr 32)
            assertThrows(IllegalArgumentException::class.java) { session.decode(stalePacket) }
        } finally {
            outbound?.close()
            outboundPacket?.fill(0)
            firstPacket.fill(0)
            stalePacket.fill(0)
            session.close()
        }
    }

    @Test
    fun rollsInboundReplayWindowWithoutRejectingLongLivedSession() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        try {
            repeat(REPLAY_WINDOW_CAPACITY + 1) { index ->
                assertEquals(true, session.admitNested(serverMetadata(index)))
            }

            assertEquals(false, session.admitNested(serverMetadata(REPLAY_WINDOW_CAPACITY)))
            assertEquals(true, session.admitNested(serverMetadata(0)))
        } finally {
            session.close()
        }
    }

    @Test
    fun selectsOnlyCurrentlyValidFutureServerSalt() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        var packet: ByteArray? = null
        try {
            session.updateFutureSalts(
                listOf(
                    MtProtoFutureSalt(1_699_999_000, 1_699_999_999, 11L),
                    MtProtoFutureSalt(1_699_999_999, 1_700_000_001, 22L),
                    MtProtoFutureSalt(1_700_000_001, 1_700_001_000, 33L),
                ),
            )
            packet = session.encode(BODY, contentRelated = false)
            EncryptedMessageCodec.decode(authKey, session.sessionId, packet, EncryptedMessageCodec.CLIENT_X).use {
                assertEquals(22L, it.metadata.serverSalt)
            }
            packet.fill(0)
            packet = null

            session.updateFutureSalts(listOf(MtProtoFutureSalt(1_699_999_000, 1_699_999_999, 11L)))
            packet = session.encode(BODY, contentRelated = false)
            EncryptedMessageCodec.decode(authKey, session.sessionId, packet, EncryptedMessageCodec.CLIENT_X).use {
                assertEquals(73L, it.metadata.serverSalt)
            }
        } finally {
            packet?.fill(0)
            session.close()
        }
    }

    @Test
    fun authoritativeServerTimeCanCorrectAnOverestimatedClock() {
        val authKey = authKey(createdAt = 1_700_000_600)
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        var packet: ByteArray? = null
        try {
            session.synchronizeServerTime(serverMetadata(0).messageId, allowDecrease = true)
            packet = session.encode(BODY, contentRelated = true)
            EncryptedMessageCodec.decode(authKey, session.sessionId, packet, EncryptedMessageCodec.CLIENT_X).use {
                assertEquals(1_700_000_000L, it.metadata.messageId ushr 32)
            }
        } finally {
            packet?.fill(0)
            session.close()
        }
    }

    @Test
    fun classifiesInboundMessageStateForProtocolStatusQueries() {
        val authKey = authKey()
        val session = MtProtoEncryptedSession(authKey, CounterEntropy(), { 1_700_000_000_000L })
        try {
            val contentRelated = serverMetadata(5).copy(sequenceNumber = 1)
            val irrelevant = serverMetadata(7).copy(sequenceNumber = 0)
            assertEquals(true, session.admitNested(contentRelated))
            assertEquals(true, session.admitNested(irrelevant))

            assertEquals(12, session.inboundMessageStatus(contentRelated.messageId))
            assertEquals(20, session.inboundMessageStatus(irrelevant.messageId))
            assertEquals(1, session.inboundMessageStatus(serverMetadata(0).messageId))
            assertEquals(2, session.inboundMessageStatus(serverMetadata(6).messageId))
            assertEquals(3, session.inboundMessageStatus(serverMetadata(8).messageId))
        } finally {
            session.close()
        }
    }

    @Test
    fun schedulesSaltRefreshFromLatestExpiryWithFloor() {
        val nowSeconds = 1_700_000_000L
        val session = MtProtoEncryptedSession(authKey(), CounterEntropy(), { nowSeconds * 1_000L })
        try {
            // No salts fetched yet: fall back to the retry interval.
            assertEquals(MtProtoEncryptedSession.FUTURE_SALT_REFRESH_RETRY_MILLIS, session.futureSaltRefreshDelayMillis())

            // Coverage ending far in the future: refresh one lead interval before the last salt expires.
            session.updateFutureSalts(
                listOf(
                    MtProtoFutureSalt((nowSeconds - 60).toInt(), (nowSeconds + 300).toInt(), 11L),
                    MtProtoFutureSalt((nowSeconds + 300).toInt(), (nowSeconds + 3_600).toInt(), 22L),
                ),
            )
            assertEquals(
                (3_600L - MtProtoEncryptedSession.FUTURE_SALT_REFRESH_LEAD_SECONDS) * 1_000L,
                session.futureSaltRefreshDelayMillis(),
            )

            // Inside the lead window or already expired: never spin; clamp to the minimum delay.
            session.updateFutureSalts(listOf(MtProtoFutureSalt((nowSeconds - 120).toInt(), (nowSeconds + 60).toInt(), 33L)))
            assertEquals(MtProtoEncryptedSession.FUTURE_SALT_REFRESH_MIN_DELAY_MILLIS, session.futureSaltRefreshDelayMillis())
            session.updateFutureSalts(listOf(MtProtoFutureSalt((nowSeconds - 600).toInt(), (nowSeconds - 10).toInt(), 44L)))
            assertEquals(MtProtoEncryptedSession.FUTURE_SALT_REFRESH_MIN_DELAY_MILLIS, session.futureSaltRefreshDelayMillis())
        } finally {
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

    private fun serverMetadata(index: Int) = MtProtoEncryptedMessageMetadata(
        serverSalt = 0L,
        sessionId = 1L,
        messageId = (1_700_000_000L shl 32) or (index.toLong() shl 1) or 1L,
        sequenceNumber = 0,
    )

    private class CounterEntropy : EntropySource {
        private var call = 0
        override fun nextBytes(destination: ByteArray) {
            call++
            destination.fill(call.toByte())
        }
    }

    private fun authKey(createdAt: Int = 1_700_000_000): MtProtoAuthKey {
        val material = ByteArray(MtProtoAuthKey.MATERIAL_BYTES) { it.toByte() }
        val idBytes = MtProtoKeyDerivation.authKeyIdBytes(material)
        return try {
            val id = ByteBuffer.wrap(idBytes).order(ByteOrder.LITTLE_ENDIAN).long
            MtProtoAuthKey.restore(material, id, 73L, createdAt)
        } finally {
            idBytes.fill(0)
            material.fill(0)
        }
    }

    private companion object {
        const val REPLAY_WINDOW_CAPACITY = 65_536
        val BODY = byteArrayOf(0x78, 0x56, 0x34, 0x12)
    }
}
