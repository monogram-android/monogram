package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow

internal fun interface MtProtoStoryStealthModeReader {
    suspend fun observe(): Flow<MtProtoStoryStealthMode?>
}

internal class MtProtoStoryStealthModeReaderImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val store: MtProtoStoryStealthModeStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoStoryStealthModeReader {
    override suspend fun observe(): Flow<MtProtoStoryStealthMode?> {
        val config = configSource.createForAccount(accountSlot)
        return store.observe(MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId))
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
