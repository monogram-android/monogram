package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoClientOptionsRepository
import org.monogram.domain.repository.ClientOptionsRepository

internal class TelegramBackendClientOptionsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ClientOptionsRepository,
    private val mtProtoFactory: () -> MtProtoClientOptionsRepository = {
        throw UnsupportedOperationException("MTProto client options are not configured")
    },
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ClientOptionsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }

    override suspend fun getContactJoinedNotificationsEnabled() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getContactJoinedNotificationsEnabled()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getContactJoinedNotificationsEnabled()
    }

    override suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setContactJoinedNotificationsEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setContactJoinedNotificationsEnabled(enabled)
    }

    override suspend fun getSentScheduledMessageNotificationsEnabled() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getSentScheduledMessageNotificationsEnabled()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getSentScheduledMessageNotificationsEnabled()
    }

    override suspend fun setSentScheduledMessageNotificationsEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setSentScheduledMessageNotificationsEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setSentScheduledMessageNotificationsEnabled(enabled)
    }

    override suspend fun getAnimatedEmojiEnabled() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getAnimatedEmojiEnabled()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getAnimatedEmojiEnabled()
    }

    override suspend fun setAnimatedEmojiEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setAnimatedEmojiEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setAnimatedEmojiEnabled(enabled)
    }

    override suspend fun canArchiveAndMuteNewChatsFromUnknownUsers() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.canArchiveAndMuteNewChatsFromUnknownUsers()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.canArchiveAndMuteNewChatsFromUnknownUsers()
    }

    override suspend fun getArchiveAndMuteNewChatsFromUnknownUsersEnabled() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getArchiveAndMuteNewChatsFromUnknownUsersEnabled()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getArchiveAndMuteNewChatsFromUnknownUsersEnabled()
    }

    override suspend fun setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled)
    }

    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto client options are not available")
    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
