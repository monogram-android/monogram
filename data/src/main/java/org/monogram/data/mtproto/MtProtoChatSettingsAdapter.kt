package org.monogram.data.mtproto

import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.repository.ChatSettingsRepository

internal class MtProtoChatSettingsAdapter(
    private val mtProtoFactory: () -> MtProtoChatSettingsRepository,
) : ChatSettingsRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    override suspend fun setChatPhoto(chatId: Long, photoPath: String) = mtProto.setPhoto(chatId, photoPath)
    override suspend fun setChatTitle(chatId: Long, title: String) = mtProto.setTitle(chatId, title)
    override suspend fun setChatDescription(chatId: Long, description: String) = mtProto.setDescription(chatId, description)
    override suspend fun setChatUsername(chatId: Long, username: String) = mtProto.setUsername(chatId, username)
    override suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel) = mtProto.setPermissions(chatId, permissions)
    override suspend fun setChatHasProtectedContent(chatId: Long, hasProtectedContent: Boolean) = mtProto.setProtectedContent(chatId, hasProtectedContent)
    override suspend fun setChatSignMessages(chatId: Long, signMessages: Boolean) = mtProto.setSignMessages(chatId, signMessages)
    override suspend fun setChatHasHiddenMembers(chatId: Long, hasHiddenMembers: Boolean) = mtProto.setParticipantsHidden(chatId, hasHiddenMembers)
    override suspend fun setChatHasAggressiveAntiSpamEnabled(chatId: Long, enabled: Boolean) = mtProto.setAntiSpamEnabled(chatId, enabled)
    override suspend fun setChatJoinToSendMessages(chatId: Long, joinToSendMessages: Boolean) = mtProto.setJoinToSend(chatId, joinToSendMessages)
    override suspend fun setChatJoinByRequest(chatId: Long, joinByRequest: Boolean) = mtProto.setJoinByRequest(chatId, joinByRequest)
    override suspend fun setChatAvailableReactions(chatId: Long, availableReactions: List<String>) = mtProto.setAvailableReactions(chatId, availableReactions)
    override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) = mtProto.setSlowModeDelay(chatId, slowModeDelay)
    override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) = mtProto.setForumEnabled(chatId, isForum)
}
