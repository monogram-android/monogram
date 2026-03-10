package org.monogram.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatModel(
    val id: Long,
    val title: String,
    val unreadCount: Int,
    val avatarPath: String? = null,
    val personalAvatarPath: String? = null,
    val lastMessageText: String = "",
    val lastMessageEntities: List<MessageEntity> = emptyList(),
    val lastMessageTime: String = "",
    val order: Long = 0L,
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
    val onlineCount: Int = 0,
    val userStatus: String? = null,
    val photoId: Int = 0,
    val isPinned: Boolean = false,
    val accentColorId: Int = 0,
    val profileAccentColorId: Int = -1,
    val backgroundCustomEmojiId: Long = 0L,
    val emojiStatusPath: String? = null,
    val unreadMentionCount: Int = 0,
    val isChannel: Boolean = false,
    val unreadReactionCount: Int = 0,
    val isMarkedAsUnread: Boolean = false,
    val isMuted: Boolean = false,
    val hasProtectedContent: Boolean = false,
    val isTranslatable: Boolean = false,
    val hasAutomaticTranslation: Boolean = false,
    val messageAutoDeleteTime: Int = 0,
    val canBeDeletedOnlyForSelf: Boolean = false,
    val canBeDeletedForAllUsers: Boolean = false,
    val canBeReported: Boolean = false,
    val lastReadInboxMessageId: Long = 0L,
    val lastReadOutboxMessageId: Long = 0L,
    val lastMessageId: Long = 0L,
    val isLastMessageOutgoing: Boolean = false,
    val replyMarkupMessageId: Long = 0L,
    val messageSenderId: Long? = null,
    val blockList: Boolean = false,
    val emojiStatusId: Long? = null,
    val isSupergroup: Boolean = false,
    val isAdmin: Boolean = false,
    val isOnline: Boolean = false,
    val typingAction: String? = null,
    val draftMessage: String? = null,
    val draftMessageEntities: List<MessageEntity> = emptyList(),
    val isVerified: Boolean = false,
    val viewAsTopics: Boolean = false,
    val isForum: Boolean = false,
    val permissions: ChatPermissionsModel = ChatPermissionsModel(),
    val username: String? = null,
    val usernames: UsernamesModel? = null,
    val description: String? = null,
    val inviteLink: String? = null,
    val type: ChatType = ChatType.PRIVATE,
    val isBot: Boolean = false,
    val isMember: Boolean = true,
    val isArchived: Boolean = false,
)

@Serializable
enum class ChatType {
    PRIVATE, BASIC_GROUP, SUPERGROUP, SECRET
}

@Serializable
data class ChatPermissionsModel(
    val canSendBasicMessages: Boolean = true,
    val canSendAudios: Boolean = true,
    val canSendDocuments: Boolean = true,
    val canSendPhotos: Boolean = true,
    val canSendVideos: Boolean = true,
    val canSendVideoNotes: Boolean = true,
    val canSendVoiceNotes: Boolean = true,
    val canSendPolls: Boolean = true,
    val canSendOtherMessages: Boolean = true,
    val canAddWebPagePreviews: Boolean = true,
    val canChangeInfo: Boolean = false,
    val canInviteUsers: Boolean = false,
    val canPinMessages: Boolean = false,
    val canManageTopics: Boolean = false
)
