package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoClientOptionsRepository
import org.monogram.domain.repository.ClientOptionsRepository

internal class MtProtoClientOptionsAdapter(
    private val mtProtoFactory: () -> MtProtoClientOptionsRepository,
) : ClientOptionsRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    override suspend fun getContactJoinedNotificationsEnabled() = mtProto.getContactJoinedNotificationsEnabled()

    override suspend fun setContactJoinedNotificationsEnabled(enabled: Boolean) = mtProto.setContactJoinedNotificationsEnabled(enabled)

    override suspend fun getSentScheduledMessageNotificationsEnabled() = mtProto.getSentScheduledMessageNotificationsEnabled()

    override suspend fun setSentScheduledMessageNotificationsEnabled(enabled: Boolean) = mtProto.setSentScheduledMessageNotificationsEnabled(enabled)

    override suspend fun getAnimatedEmojiEnabled() = mtProto.getAnimatedEmojiEnabled()

    override suspend fun setAnimatedEmojiEnabled(enabled: Boolean) = mtProto.setAnimatedEmojiEnabled(enabled)

    override suspend fun canArchiveAndMuteNewChatsFromUnknownUsers() = mtProto.canArchiveAndMuteNewChatsFromUnknownUsers()

    override suspend fun getArchiveAndMuteNewChatsFromUnknownUsersEnabled() = mtProto.getArchiveAndMuteNewChatsFromUnknownUsersEnabled()

    override suspend fun setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled: Boolean) = mtProto.setArchiveAndMuteNewChatsFromUnknownUsersEnabled(enabled)

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
