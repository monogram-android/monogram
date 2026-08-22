package org.monogram.data.mtproto

import org.monogram.mtproto.secret.LoadedSecretChat
import org.monogram.mtproto.secret.MtProtoSecretChatSessionState
import org.monogram.mtproto.secret.MtProtoSecretChatState

/** Adapts the Room-backed secret-chat state store to the messenger's session-state interface. */
internal class MtProtoRoomSecretChatSession(
    private val store: MtProtoSecretChatStateStore,
) : MtProtoSecretChatSessionState {
    override suspend fun load(chatId: Int): LoadedSecretChat? =
        store.get(chatId)?.let { state ->
            LoadedSecretChat(
                chatId = state.chatId,
                accessHash = state.accessHash,
                authKey = state.authKey,
                keyFingerprint = state.keyFingerprint,
                maxInSeq = state.maxInSeq,
                maxOutSeq = state.maxOutSeq,
            )
        }

    override suspend fun saveCounters(chatId: Int, maxInSeq: Int, maxOutSeq: Int) {
        val current = store.get(chatId) ?: return
        store.save(current.copy(maxInSeq = maxInSeq, maxOutSeq = maxOutSeq))
    }

    override suspend fun onSent(chatId: Int) {
        // Sequence counters were persisted by saveCounters; onSent tracks use accounting only.
        val current = store.get(chatId) ?: return
        store.save(current.copy(keyUseCountOut = current.keyUseCountOut + 1))
    }
}
