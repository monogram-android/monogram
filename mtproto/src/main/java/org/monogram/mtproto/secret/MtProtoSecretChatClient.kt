package org.monogram.mtproto.secret

import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChatDiscarded
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChatRequested
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChatWaiting
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChat_8f2eebf4e2
import org.monogram.mtproto.tl.generated.cloud.layer223.EncryptedChat_88a799033b
import org.monogram.mtproto.tl.generated.cloud.layer223.InputEncryptedChat_d36f957924
import org.monogram.mtproto.tl.generated.cloud.layer223.InputEncryptedChat_056d66869b
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_0bd9c3151c
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AcceptEncryption
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DiscardEncryption
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.RequestEncryption
import org.monogram.mtproto.tl.runtime.TlBytes

sealed interface MtProtoSecretChatState {
    data class Waiting(val chatId: Int, val accessHash: Long) : MtProtoSecretChatState
    data class Requested(val folderId: Int?, val chatId: Int, val accessHash: Long, val gA: ByteArray) : MtProtoSecretChatState
    data class Established(val chatId: Int, val accessHash: Long, val peerPublic: ByteArray, val keyFingerprint: Long) : MtProtoSecretChatState
    data class Discarded(val chatId: Int, val historyDeleted: Boolean) : MtProtoSecretChatState
}

fun interface MtProtoSecretChatExecutor {
    suspend fun execute(method: org.monogram.mtproto.tl.runtime.TlMethod<*>): org.monogram.mtproto.tl.runtime.TlObject
}

/** Secret-chat establishment lifecycle (`messages.request/accept/discardEncryption`). */
class MtProtoSecretChatClient(
    private val executor: MtProtoSecretChatExecutor,
) {
    /** Requests a new secret chat with [userId]; the returned `gA` must be kept for key completion. */
    suspend fun request(userId: Long?, randomId: Int, gA: ByteArray): MtProtoSecretChatState =
        @Suppress("UNCHECKED_CAST")
        classify(
            executor.execute(
                RequestEncryption(
                    userId = userId?.let { InputUser_4020eae812(it, 0L) } ?: InputUserSelf,
                    randomId = randomId,
                    gA = TlBytes.copyOf(gA),
                ),
            ),
        )

    suspend fun accept(chatId: Int, accessHash: Long, gB: ByteArray, keyFingerprint: Long): MtProtoSecretChatState =
        @Suppress("UNCHECKED_CAST")
        classify(
            executor.execute(
                AcceptEncryption(
                    peer = InputEncryptedChat_056d66869b(chatId, accessHash),
                    gB = TlBytes.copyOf(gB),
                    keyFingerprint = keyFingerprint,
                ),
            ),
        )

    suspend fun discard(chatId: Int, accessHash: Long, deleteHistory: Boolean): MtProtoSecretChatState {
        executor.execute(DiscardEncryption(deleteHistory = deleteHistory, chatId = chatId))
        return MtProtoSecretChatState.Discarded(chatId, deleteHistory)
    }

    private fun classify(result: org.monogram.mtproto.tl.runtime.TlObject): MtProtoSecretChatState = when (result) {
        is EncryptedChatWaiting ->
            MtProtoSecretChatState.Waiting(result.id, result.accessHash)
        is EncryptedChatRequested ->
            MtProtoSecretChatState.Requested(result.folderId, result.id, result.accessHash, result.gA.toByteArray())
        is EncryptedChat_8f2eebf4e2 ->
            MtProtoSecretChatState.Established(result.id, result.accessHash, result.gAOrB.toByteArray(), result.keyFingerprint)
        is EncryptedChatDiscarded ->
            MtProtoSecretChatState.Discarded(result.id, result.historyDeleted)
        else ->
            throw IllegalStateException("Unsupported encrypted chat constructor ${result.constructorId}")
    }
}
