package org.monogram.domain.repository

interface ClientOptionsRepository {
    suspend fun getContactJoinedNotificationsEnabled(): Boolean
    suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean)

    suspend fun getSentScheduledMessageNotificationsEnabled(): Boolean
    suspend fun setSentScheduledMessageNotificationsEnabled(enabled: Boolean)

    suspend fun getAnimatedEmojiEnabled(): Boolean
    suspend fun setAnimatedEmojiEnabled(enabled: Boolean)

    suspend fun canArchiveAndMuteNewChatsFromUnknownUsers(): Boolean
    suspend fun getArchiveAndMuteNewChatsFromUnknownUsersEnabled(): Boolean
    suspend fun setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled: Boolean)
}
