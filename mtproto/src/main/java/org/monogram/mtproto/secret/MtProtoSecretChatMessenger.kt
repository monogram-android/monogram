package org.monogram.mtproto.secret

import java.security.SecureRandom
import org.monogram.mtproto.crypto.MtProtoSecretChatKeyDerivation
import org.monogram.mtproto.crypto.MtProtoSecretMessageCipher

/** Encrypted outbound packet ready for `messages.sendEncrypted`. */
class MtProtoSecretOutbound(
    val chatId: Int,
    val seqOut: Int,
    val ackedInSeq: Int,
    val packet: ByteArray,
)

sealed interface MtProtoSecretInbound {
    /** In-order message: decrypted plaintext (protocol-padded) with counters advanced durably. */
    data class Accepted(val chatId: Int, val seqOut: Int, val paddedPlaintext: ByteArray) : MtProtoSecretInbound

    /** Already processed; dropped without persisting anything or exposing plaintext. */
    data class Duplicate(val chatId: Int, val seqOut: Int) : MtProtoSecretInbound

    /** Earlier messages are missing; nothing decrypted content is surfaced or persisted. */
    data class Gap(val chatId: Int, val seqOut: Int, val expectedSeqOut: Int) : MtProtoSecretInbound
}

interface MtProtoSecretChatSessionState {
    suspend fun load(chatId: Int): LoadedSecretChat?
    suspend fun saveCounters(chatId: Int, maxInSeq: Int, maxOutSeq: Int)

    /** Called after each outbound message so implementations can track key-use accounting. */
    suspend fun onSent(chatId: Int) {}
}

data class LoadedSecretChat(
    val chatId: Int,
    val accessHash: Long,
    val authKey: ByteArray,
    val keyFingerprint: Long,
    val maxInSeq: Int,
    val maxOutSeq: Int,
)

/**
 * Encrypts and decrypts secret messages over one chat's durable key/sequence state.
 *
 * Outgoing messages allocate the next `seq_out`, encrypt with the sender slice, and persist
 * counters before returning the packet. Incoming packets verify the fingerprint header, decrypt
 * with the receiver slice, and classify `seq_out` (supplied by the caller's TL layer from the
 * decrypted body): counters persist only on in-order acceptance, so duplicates and gaps leave
 * durable state untouched.
 */
class MtProtoSecretChatMessenger(
    private val sessionState: MtProtoSecretChatSessionState,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun send(chatId: Int, plaintext: ByteArray): MtProtoSecretOutbound {
        require(plaintext.isNotEmpty()) { "secret message payload must not be empty" }
        val loaded = requireNotNull(sessionState.load(chatId)) {
            "No established secret chat $chatId for this account"
        }
        val nextOut = loaded.maxOutSeq + 1
        val packet = MtProtoSecretMessageCipher.encrypt(
            authKey = loaded.authKey,
            incoming = false,
            keyFingerprint = loaded.keyFingerprint,
            plaintext = plaintext,
            random = random,
        )
        sessionState.saveCounters(chatId, loaded.maxInSeq, nextOut)
        // Outbound use accounting feeds the re-key initiation thresholds.
        sessionState.onSent(chatId)
        return MtProtoSecretOutbound(chatId, nextOut, loaded.maxInSeq, packet)
    }

    suspend fun receive(chatId: Int, packet: ByteArray, seqOut: Int): MtProtoSecretInbound {
        require(seqOut > 0) { "inbound seq_out must be positive" }
        val loaded = requireNotNull(sessionState.load(chatId)) {
            "No established secret chat $chatId for this account"
        }
        if (packet.size >= FINGERPRINT_BYTES && readLong(packet) != loaded.keyFingerprint) {
            throw IllegalStateException("Secret chat key fingerprint mismatch")
        }
        val sequence = MtProtoSecretChatSequence(initialInSeq = loaded.maxInSeq, initialOutSeq = loaded.maxOutSeq)
        return when (val result = sequence.acceptIncoming(seqOut)) {
            is MtProtoSecretChatSequence.IncomingResult.Accepted -> {
                val decrypted = MtProtoSecretMessageCipher.decryptPacket(loaded.authKey, incoming = true, packet = packet)
                sessionState.saveCounters(chatId, result.seqOut, loaded.maxOutSeq)
                MtProtoSecretInbound.Accepted(chatId, result.seqOut, decrypted.paddedPlaintext)
            }
            is MtProtoSecretChatSequence.IncomingResult.Duplicate ->
                MtProtoSecretInbound.Duplicate(chatId, result.seqOut)
            is MtProtoSecretChatSequence.IncomingResult.Gap ->
                MtProtoSecretInbound.Gap(chatId, result.seqOut, result.expectedSeqOut)
        }
    }

    private fun readLong(source: ByteArray): Long {
        var value = 0L
        repeat(FINGERPRINT_BYTES) { index ->
            value = (value shl 8) or (source[index].toLong() and 0xFF)
        }
        return value
    }

    private companion object {
        const val FINGERPRINT_BYTES = 8
    }
}
