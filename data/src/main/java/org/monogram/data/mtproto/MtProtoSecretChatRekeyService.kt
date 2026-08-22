package org.monogram.data.mtproto

import org.monogram.mtproto.secret.MtProtoSecretChatRekey

/** Outbound action for `decryptedMessageActionRequestKey`. */
data class MtProtoSecretRekeyRequest(val exchangeId: Long, val gA: ByteArray)

/**
 * Drives secret-chat re-key negotiation against the durable state store:
 * initiation follows [MtProtoSecretChatRekey.shouldInitiate], peer acceptance derives and stages
 * the future key, and commit swaps it into the active slot atomically.
 */
internal class MtProtoSecretChatRekeyService(
    private val store: MtProtoSecretChatStateStore,
    private val ownPrivateExponent: ByteArray,
    private val prime: ByteArray,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val exchangeIdAllocator: () -> Long,
) {
    /**
     * Starts a re-key exchange when the stored state warrants one. Returns the request payload
     * for the caller's encrypted service message, or null when no re-key is warranted or an
     * exchange is already in flight.
     */
    suspend fun maybeBeginExchange(chatId: Int, gA: ByteArray): MtProtoSecretRekeyRequest? {
        val current = store.get(chatId) ?: return null
        val warrants = MtProtoSecretChatRekey.shouldInitiate(
            keyUseCount = current.keyUseCountOut,
            keyCreateDateSeconds = current.keyCreateDateSeconds ?: return null,
            nowSeconds = nowSeconds(),
            exchangeId = current.exchangeId,
            hasFutureKey = current.futureAuthKey != null,
        )
        if (!warrants) return null
        val exchangeId = exchangeIdAllocator()
        store.save(current.copy(exchangeId = exchangeId))
        return MtProtoSecretRekeyRequest(exchangeId = exchangeId, gA = gA.copyOf())
    }

    /** Handles the peer's `acceptKey` public part; stages the future key for commit. */
    suspend fun onPeerAccepted(chatId: Int, exchangeId: Long, peerGA: ByteArray): Long {
        val current = store.get(chatId) ?: error("No secret chat $chatId")
        require(current.exchangeId == exchangeId) {
            "Re-key exchange id mismatch for chat $chatId (${current.exchangeId} != $exchangeId)"
        }
        val future = MtProtoSecretChatRekey.deriveFutureKeys(peerGA, ownPrivateExponent, prime)
        store.stageFutureKey(chatId, future.authKey, future.keyFingerprint)
        return future.keyFingerprint
    }

    /** Swaps the staged future key into the active slot once both sides confirmed. */
    suspend fun commit(chatId: Int) {
        store.commitFutureKey(chatId)
    }

    /** Convenience returning the staged future fingerprint as 8 big-endian bytes. */
    fun fingerprintBytes(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr ((7 - index) * 8)) and 0xFF).toByte()
    }

}
