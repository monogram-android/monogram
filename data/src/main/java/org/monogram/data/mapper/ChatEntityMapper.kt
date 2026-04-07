package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity

fun TdApi.Chat.toEntity(): ChatEntity {
    val isChannel = type.isChannelType()
    val isArchived = positions.any { it.list is TdApi.ChatListArchive }
    val cachedCounts = parseCachedCounts(clientData)
    val typeIds = type.extractTypeIds()
    val chatPermissions = permissions.toDomainChatPermissions()
    val senderId = when (val sender = messageSenderId) {
        is TdApi.MessageSenderUser -> sender.userId
        is TdApi.MessageSenderChat -> sender.chatId
        else -> null
    }

    return ChatEntity(
        id = id,
        title = title,
        unreadCount = unreadCount,
        avatarPath = photo?.small?.local?.path,
        lastMessageText = (lastMessage?.content as? TdApi.MessageText)?.text?.text ?: "",
        lastMessageTime = (lastMessage?.date?.toLong() ?: 0L).toString(),
        lastMessageDate = lastMessage?.date ?: 0,
        order = positions.firstOrNull()?.order ?: 0L,
        isPinned = positions.firstOrNull()?.isPinned ?: false,
        isMuted = notificationSettings.muteFor > 0,
        isChannel = isChannel,
        isGroup = type.isGroupType(),
        type = type.toEntityChatType(),
        privateUserId = typeIds.privateUserId,
        basicGroupId = typeIds.basicGroupId,
        supergroupId = typeIds.supergroupId,
        secretChatId = typeIds.secretChatId,
        positionsCache = encodeChatPositions(positions),
        isArchived = isArchived,
        memberCount = cachedCounts.first,
        onlineCount = cachedCounts.second,
        unreadMentionCount = unreadMentionCount,
        unreadReactionCount = unreadReactionCount,
        isMarkedAsUnread = isMarkedAsUnread,
        hasProtectedContent = hasProtectedContent,
        isTranslatable = isTranslatable,
        hasAutomaticTranslation = false,
        messageAutoDeleteTime = messageAutoDeleteTime,
        canBeDeletedOnlyForSelf = canBeDeletedOnlyForSelf,
        canBeDeletedForAllUsers = canBeDeletedForAllUsers,
        canBeReported = canBeReported,
        lastReadInboxMessageId = lastReadInboxMessageId,
        lastReadOutboxMessageId = lastReadOutboxMessageId,
        lastMessageId = lastMessage?.id ?: 0L,
        isLastMessageOutgoing = lastMessage?.isOutgoing ?: false,
        replyMarkupMessageId = replyMarkupMessageId,
        messageSenderId = senderId,
        blockList = blockList != null,
        emojiStatusId = (emojiStatus?.type as? TdApi.EmojiStatusTypeCustomEmoji)?.customEmojiId,
        accentColorId = accentColorId,
        profileAccentColorId = profileAccentColorId,
        backgroundCustomEmojiId = backgroundCustomEmojiId,
        photoId = photo?.small?.id ?: 0,
        isSupergroup = type is TdApi.ChatTypeSupergroup,
        isAdmin = false,
        isOnline = false,
        typingAction = null,
        draftMessage = (draftMessage?.inputMessageText as? TdApi.InputMessageText)?.text?.text,
        isVerified = false,
        viewAsTopics = viewAsTopics,
        isForum = false,
        isBot = false,
        isMember = true,
        username = null,
        description = null,
        inviteLink = null,
        createdAt = System.currentTimeMillis()
    ).withPermissions(chatPermissions)
}

private fun parseCachedCounts(clientData: String?): Pair<Int, Int> {
    if (clientData.isNullOrBlank()) return 0 to 0
    val memberCount = Regex("""mc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val onlineCount = Regex("""oc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    return memberCount to onlineCount
}