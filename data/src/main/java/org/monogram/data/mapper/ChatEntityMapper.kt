package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.data.db.model.ChatEntity

fun TdApi.Chat.toEntity(): ChatEntity {
    val isChannel = (type as? TdApi.ChatTypeSupergroup)?.isChannel ?: false
    val isArchived = positions.any { it.list is TdApi.ChatListArchive }
    val permissions = permissions ?: TdApi.ChatPermissions()
    val cachedCounts = parseCachedCounts(clientData)
    val senderId = when (val sender = messageSenderId) {
        is TdApi.MessageSenderUser -> sender.userId
        is TdApi.MessageSenderChat -> sender.chatId
        else -> null
    }
    val privateUserId = (type as? TdApi.ChatTypePrivate)?.userId ?: 0L
    val basicGroupId = (type as? TdApi.ChatTypeBasicGroup)?.basicGroupId ?: 0L
    val supergroupId = (type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: 0L
    val secretChatId = (type as? TdApi.ChatTypeSecret)?.secretChatId ?: 0
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
        isGroup = type is TdApi.ChatTypeBasicGroup || (type is TdApi.ChatTypeSupergroup && !isChannel),
        type = when (type) {
            is TdApi.ChatTypePrivate -> "PRIVATE"
            is TdApi.ChatTypeBasicGroup -> "BASIC_GROUP"
            is TdApi.ChatTypeSupergroup -> "SUPERGROUP"
            is TdApi.ChatTypeSecret -> "SECRET"
            else -> "PRIVATE"
        },
        privateUserId = privateUserId,
        basicGroupId = basicGroupId,
        supergroupId = supergroupId,
        secretChatId = secretChatId,
        positionsCache = encodePositions(positions),
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
        permissionCanSendBasicMessages = permissions.canSendBasicMessages,
        permissionCanSendAudios = permissions.canSendAudios,
        permissionCanSendDocuments = permissions.canSendDocuments,
        permissionCanSendPhotos = permissions.canSendPhotos,
        permissionCanSendVideos = permissions.canSendVideos,
        permissionCanSendVideoNotes = permissions.canSendVideoNotes,
        permissionCanSendVoiceNotes = permissions.canSendVoiceNotes,
        permissionCanSendPolls = permissions.canSendPolls,
        permissionCanSendOtherMessages = permissions.canSendOtherMessages,
        permissionCanAddLinkPreviews = permissions.canAddLinkPreviews,
        permissionCanEditTag = permissions.canEditTag,
        permissionCanChangeInfo = permissions.canChangeInfo,
        permissionCanInviteUsers = permissions.canInviteUsers,
        permissionCanPinMessages = permissions.canPinMessages,
        permissionCanCreateTopics = permissions.canCreateTopics,
        createdAt = System.currentTimeMillis()
    )
}

private fun encodePositions(positions: Array<TdApi.ChatPosition>): String? {
    if (positions.isEmpty()) return null
    val encoded = positions.mapNotNull { pos ->
        if (pos.order == 0L) return@mapNotNull null
        val pinned = if (pos.isPinned) 1 else 0
        when (val list = pos.list) {
            is TdApi.ChatListMain -> "m:${pos.order}:$pinned"
            is TdApi.ChatListArchive -> "a:${pos.order}:$pinned"
            is TdApi.ChatListFolder -> "f:${list.chatFolderId}:${pos.order}:$pinned"
            else -> null
        }
    }
    return if (encoded.isEmpty()) null else encoded.joinToString("|")
}

private fun parseCachedCounts(clientData: String?): Pair<Int, Int> {
    if (clientData.isNullOrBlank()) return 0 to 0
    val memberCount = Regex("""mc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    val onlineCount = Regex("""oc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    return memberCount to onlineCount
}