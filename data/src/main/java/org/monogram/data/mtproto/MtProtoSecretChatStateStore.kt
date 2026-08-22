package org.monogram.data.mtproto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monogram.data.db.dao.MtProtoSecretChatStateDao
import org.monogram.data.db.model.MtProtoSecretChatStateEntity

internal data class MtProtoSecretChatState(
    val chatId: Int,
    val accessHash: Long,
    val adminId: Long,
    val participantId: Long,
    val authKey: ByteArray,
    val keyFingerprint: Long,
    val maxInSeq: Int,
    val maxOutSeq: Int,
    /** In-flight re-key exchange id; 0 means no exchange is pending. */
    val exchangeId: Long = 0L,
    val futureAuthKey: ByteArray? = null,
    val futureKeyFingerprint: Long? = null,
    val keyCreateDateSeconds: Long? = null,
    val keyUseCountIn: Int = 0,
    val keyUseCountOut: Int = 0,
)

/** Durable secret-chat key and sequence state; keys never leave the account scope. */
internal interface MtProtoSecretChatStateStore {
    suspend fun get(chatId: Int): MtProtoSecretChatState?

    suspend fun save(state: MtProtoSecretChatState)

    suspend fun delete(chatId: Int)

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)

    /**
     * Atomically advances sequence counters and outbound use accounting after a send.
     * Returns false when the chat no longer exists.
     */
    suspend fun recordSend(chatId: Int): Boolean

    /** Persists a negotiated future key for the in-flight exchange. */
    suspend fun stageFutureKey(chatId: Int, futureAuthKey: ByteArray, futureKeyFingerprint: Long)

    /** Swaps the staged future key into the active key and clears exchange state. */
    suspend fun commitFutureKey(chatId: Int)
}

internal object NoOpMtProtoSecretChatStateStore : MtProtoSecretChatStateStore {
    override suspend fun get(chatId: Int): MtProtoSecretChatState? = null
    override suspend fun save(state: MtProtoSecretChatState) = Unit
    override suspend fun delete(chatId: Int) = Unit
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    override suspend fun recordSend(chatId: Int) = false
    override suspend fun stageFutureKey(chatId: Int, futureAuthKey: ByteArray, futureKeyFingerprint: Long) = Unit
    override suspend fun commitFutureKey(chatId: Int) = Unit
}

internal class MtProtoRoomSecretChatStateStore(
    private val dao: MtProtoSecretChatStateDao,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
    private val environment: MtProtoEnvironment = MtProtoEnvironment.PRODUCTION,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MtProtoSecretChatStateStore {
    override suspend fun get(chatId: Int): MtProtoSecretChatState? =
        dao.get(accountSlot, environment.storageName, chatId)?.toState()

    override suspend fun save(state: MtProtoSecretChatState) = withContext(Dispatchers.IO) {
        dao.upsert(
            MtProtoSecretChatStateEntity(
                accountSlot = accountSlot,
                environment = environment.storageName,
                chatId = state.chatId,
                accessHash = state.accessHash,
                adminId = state.adminId,
                participantId = state.participantId,
                authKey = state.authKey,
                keyFingerprint = state.keyFingerprint,
                maxInSeq = state.maxInSeq,
                maxOutSeq = state.maxOutSeq,
                exchangeId = state.exchangeId,
                futureAuthKey = state.futureAuthKey,
                futureKeyFingerprint = state.futureKeyFingerprint,
                keyCreateDateSeconds = state.keyCreateDateSeconds,
                keyUseCountIn = state.keyUseCountIn,
                keyUseCountOut = state.keyUseCountOut,
                updatedAt = nowMillis(),
            ),
        )
        Unit
    }

    override suspend fun delete(chatId: Int) {
        dao.delete(accountSlot, environment.storageName, chatId)
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        dao.deleteAccount(accountSlot, environment.storageName)
    }

    override suspend fun recordSend(chatId: Int): Boolean {
        val current = dao.get(accountSlot, environment.storageName, chatId) ?: return false
        dao.upsert(
            current.copy(
                maxOutSeq = current.maxOutSeq + 1,
                keyUseCountOut = current.keyUseCountOut + 1,
                updatedAt = nowMillis(),
            ),
        )
        return true
    }

    override suspend fun stageFutureKey(chatId: Int, futureAuthKey: ByteArray, futureKeyFingerprint: Long) {
        val current = dao.get(accountSlot, environment.storageName, chatId) ?: return
        dao.upsert(current.copy(futureAuthKey = futureAuthKey, futureKeyFingerprint = futureKeyFingerprint, updatedAt = nowMillis()))
    }

    override suspend fun commitFutureKey(chatId: Int) {
        val current = dao.get(accountSlot, environment.storageName, chatId) ?: return
        val future = current.futureAuthKey ?: return
        val futureFingerprint = current.futureKeyFingerprint ?: return
        dao.upsert(
            current.copy(
                authKey = future,
                keyFingerprint = futureFingerprint,
                futureAuthKey = null,
                futureKeyFingerprint = null,
                exchangeId = 0L,
                keyCreateDateSeconds = nowMillis() / 1000,
                keyUseCountIn = 0,
                keyUseCountOut = 0,
                updatedAt = nowMillis(),
            ),
        )
    }

    private fun MtProtoSecretChatStateEntity.toState() = MtProtoSecretChatState(
        chatId = chatId,
        accessHash = accessHash,
        adminId = adminId,
        participantId = participantId,
        authKey = authKey,
        keyFingerprint = keyFingerprint,
        maxInSeq = maxInSeq,
        maxOutSeq = maxOutSeq,
        exchangeId = exchangeId,
        futureAuthKey = futureAuthKey,
        futureKeyFingerprint = futureKeyFingerprint,
        keyCreateDateSeconds = keyCreateDateSeconds,
        keyUseCountIn = keyUseCountIn,
        keyUseCountOut = keyUseCountOut,
    )

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
