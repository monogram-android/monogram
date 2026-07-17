package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.domain.repository.ClientOptionsRepository

class ClientOptionsRepositoryImpl(
    private val remote: SettingsRemoteDataSource
) : ClientOptionsRepository {
    override suspend fun getContactJoinedNotificationsEnabled(): Boolean =
        getInverseBooleanOption("disable_contact_registered_notifications")

    override suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean) {
        setInverseBooleanOption("disable_contact_registered_notifications", enabled)
    }

    override suspend fun getSentScheduledMessageNotificationsEnabled(): Boolean =
        getInverseBooleanOption("disable_sent_scheduled_message_notifications")

    override suspend fun setSentScheduledMessageNotificationsEnabled(enabled: Boolean) {
        setInverseBooleanOption("disable_sent_scheduled_message_notifications", enabled)
    }

    override suspend fun getAnimatedEmojiEnabled(): Boolean =
        getInverseBooleanOption("disable_animated_emoji")

    override suspend fun setAnimatedEmojiEnabled(enabled: Boolean) {
        setInverseBooleanOption("disable_animated_emoji", enabled)
    }

    override suspend fun canArchiveAndMuteNewChatsFromUnknownUsers(): Boolean {
        val result = remote.getOption("can_archive_and_mute_new_chats_from_unknown_users")
        return (result as? TdApi.OptionValueBoolean)?.value ?: false
    }

    override suspend fun getArchiveAndMuteNewChatsFromUnknownUsersEnabled(): Boolean {
        return remote.getArchiveChatListSettings()?.archiveAndMuteNewChatsFromUnknownUsers ?: false
    }

    override suspend fun setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled: Boolean) {
        val current = remote.getArchiveChatListSettings() ?: TdApi.ArchiveChatListSettings()
        remote.setArchiveChatListSettings(
            TdApi.ArchiveChatListSettings(
                enabled,
                current.keepUnmutedChatsArchived,
                current.keepChatsFromFoldersArchived
            )
        )
    }

    private suspend fun getInverseBooleanOption(name: String): Boolean {
        return when (val result = remote.getOption(name)) {
            is TdApi.OptionValueBoolean -> !result.value
            else -> true
        }
    }

    private suspend fun setInverseBooleanOption(name: String, enabled: Boolean) {
        remote.setOption(name, TdApi.OptionValueBoolean(!enabled))
    }
}
