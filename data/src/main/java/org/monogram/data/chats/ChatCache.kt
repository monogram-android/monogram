package org.monogram.data.chats

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.cache.ChatsCacheDataSource
import org.monogram.data.datasource.cache.UserCacheDataSource
import java.util.concurrent.ConcurrentHashMap

class ChatCache : ChatsCacheDataSource, UserCacheDataSource {
    // Chats and their positions in lists
    val allChats = ConcurrentHashMap<Long, TdApi.Chat>()
    val activeListPositions = ConcurrentHashMap<Long, TdApi.ChatPosition>()
    val onlineMemberCount = ConcurrentHashMap<Long, Int>()

    // Messages: ChatId -> (MessageId -> Message)
    private val messages = ConcurrentHashMap<Long, ConcurrentHashMap<Long, TdApi.Message>>()

    // Users and their full info
    val usersCache = ConcurrentHashMap<Long, TdApi.User>()
    val userFullInfoCache = ConcurrentHashMap<Long, TdApi.UserFullInfo>()

    // Groups and Supergroups
    val basicGroups = ConcurrentHashMap<Long, TdApi.BasicGroup>()
    val basicGroupFullInfoCache = ConcurrentHashMap<Long, TdApi.BasicGroupFullInfo>()
    val supergroups = ConcurrentHashMap<Long, TdApi.Supergroup>()
    val supergroupFullInfoCache = ConcurrentHashMap<Long, TdApi.SupergroupFullInfo>()
    val secretChats = ConcurrentHashMap<Int, TdApi.SecretChat>()

    // Files
    val fileCache = ConcurrentHashMap<Int, TdApi.File>()

    // Permissions and Member Status
    val chatPermissionsCache = ConcurrentHashMap<Long, TdApi.ChatPermissions>()
    val myChatMemberCache = ConcurrentHashMap<Long, TdApi.ChatMember>()

    // Pending requests tracking
    val pendingChats = ConcurrentHashMap.newKeySet<Long>()
    val pendingUsers = ConcurrentHashMap.newKeySet<Long>()
    val pendingUserFullInfo = ConcurrentHashMap.newKeySet<Long>()
    val pendingBasicGroups = ConcurrentHashMap.newKeySet<Long>()
    val pendingBasicGroupFullInfo = ConcurrentHashMap.newKeySet<Long>()
    val pendingSupergroups = ConcurrentHashMap.newKeySet<Long>()
    val pendingSupergroupFullInfo = ConcurrentHashMap.newKeySet<Long>()
    val pendingSecretChats = ConcurrentHashMap.newKeySet<Int>()
    val pendingChatPermissions = ConcurrentHashMap.newKeySet<Long>()
    val pendingMyChatMember = ConcurrentHashMap.newKeySet<Long>()

    override fun getChat(chatId: Long): TdApi.Chat? = allChats[chatId]
    override fun putChat(chat: TdApi.Chat) {
        val existing = allChats[chat.id]
        if (existing != null) {
            synchronized(existing) {
                existing.title = chat.title
                existing.photo = chat.photo
                existing.permissions = chat.permissions
                existing.lastMessage = chat.lastMessage

                val newPositions = chat.positions.toMutableList()
                existing.positions.forEach { oldPos ->
                    if (newPositions.none { isSameChatList(it.list, oldPos.list) }) {
                        newPositions.add(oldPos)
                    }
                }
                existing.positions = newPositions.toTypedArray()
                
                existing.unreadCount = chat.unreadCount
                existing.unreadMentionCount = chat.unreadMentionCount
                existing.unreadReactionCount = chat.unreadReactionCount
                existing.notificationSettings = chat.notificationSettings
                existing.draftMessage = chat.draftMessage
                existing.clientData = chat.clientData
                existing.isMarkedAsUnread = chat.isMarkedAsUnread
                existing.isTranslatable = chat.isTranslatable
                existing.hasProtectedContent = chat.hasProtectedContent
                existing.viewAsTopics = chat.viewAsTopics
                existing.accentColorId = chat.accentColorId
                existing.backgroundCustomEmojiId = chat.backgroundCustomEmojiId
                existing.profileAccentColorId = chat.profileAccentColorId
                existing.profileBackgroundCustomEmojiId = chat.profileBackgroundCustomEmojiId
                existing.emojiStatus = chat.emojiStatus
                existing.messageAutoDeleteTime = chat.messageAutoDeleteTime
                existing.videoChat = chat.videoChat
                existing.pendingJoinRequests = chat.pendingJoinRequests
                existing.replyMarkupMessageId = chat.replyMarkupMessageId
                existing.messageSenderId = chat.messageSenderId
                existing.blockList = chat.blockList
                existing.canBeDeletedOnlyForSelf = chat.canBeDeletedOnlyForSelf
                existing.canBeDeletedForAllUsers = chat.canBeDeletedForAllUsers
                existing.canBeReported = chat.canBeReported
                existing.defaultDisableNotification = chat.defaultDisableNotification
                existing.lastReadInboxMessageId = chat.lastReadInboxMessageId
                existing.lastReadOutboxMessageId = chat.lastReadOutboxMessageId
            }
        } else {
            allChats[chat.id] = chat
        }
    }

    private fun isSameChatList(a: TdApi.ChatList?, b: TdApi.ChatList?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.constructor != b.constructor) return false
        if (a is TdApi.ChatListFolder && b is TdApi.ChatListFolder) {
            return a.chatFolderId == b.chatFolderId
        }
        return true
    }

    override fun getUser(userId: Long): TdApi.User? = usersCache[userId]
    override fun putUser(user: TdApi.User) {
        val existing = usersCache[user.id]
        if (existing != null) {
            synchronized(existing) {
                existing.firstName = user.firstName
                existing.lastName = user.lastName
                existing.usernames = user.usernames
                existing.phoneNumber = user.phoneNumber
                existing.status = user.status
                existing.profilePhoto = user.profilePhoto
                existing.emojiStatus = user.emojiStatus
                existing.isPremium = user.isPremium
                existing.verificationStatus = user.verificationStatus
                existing.isSupport = user.isSupport
                existing.haveAccess = user.haveAccess
                existing.type = user.type
                existing.languageCode = user.languageCode
                existing.addedToAttachmentMenu = user.addedToAttachmentMenu
                existing.isContact = user.isContact
                existing.isMutualContact = user.isMutualContact
                existing.isCloseFriend = user.isCloseFriend
            }
        } else {
            usersCache[user.id] = user
        }
    }

    override fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? = userFullInfoCache[userId]
    override fun putUserFullInfo(userId: Long, userFullInfo: TdApi.UserFullInfo) {
        userFullInfoCache[userId] = userFullInfo
    }

    override fun getSupergroup(supergroupId: Long): TdApi.Supergroup? = supergroups[supergroupId]
    override fun putSupergroup(supergroup: TdApi.Supergroup) {
        supergroups[supergroup.id] = supergroup
    }

    override fun getBasicGroup(basicGroupId: Long): TdApi.BasicGroup? = basicGroups[basicGroupId]
    override fun putBasicGroup(basicGroup: TdApi.BasicGroup) {
        basicGroups[basicGroup.id] = basicGroup
    }

    override fun getSupergroupFullInfo(supergroupId: Long): TdApi.SupergroupFullInfo? =
        supergroupFullInfoCache[supergroupId]

    override fun putSupergroupFullInfo(supergroupId: Long, supergroupFullInfo: TdApi.SupergroupFullInfo) {
        supergroupFullInfoCache[supergroupId] = supergroupFullInfo
    }

    override fun getBasicGroupFullInfo(basicGroupId: Long): TdApi.BasicGroupFullInfo? =
        basicGroupFullInfoCache[basicGroupId]

    override fun putBasicGroupFullInfo(basicGroupId: Long, basicGroupFullInfo: TdApi.BasicGroupFullInfo) {
        basicGroupFullInfoCache[basicGroupId] = basicGroupFullInfo
    }

    override fun getChatPermissions(chatId: Long): TdApi.ChatPermissions? = chatPermissionsCache[chatId]
    override fun putChatPermissions(chatId: Long, permissions: TdApi.ChatPermissions) {
        chatPermissionsCache[chatId] = permissions
    }

    override fun getMyChatMember(chatId: Long): TdApi.ChatMember? = myChatMemberCache[chatId]
    override fun putMyChatMember(chatId: Long, chatMember: TdApi.ChatMember) {
        myChatMemberCache[chatId] = chatMember
    }

    override fun getOnlineMemberCount(chatId: Long): Int? = onlineMemberCount[chatId]
    override fun putOnlineMemberCount(chatId: Long, count: Int) {
        onlineMemberCount[chatId] = count
    }

    override fun getSecretChat(secretChatId: Long): TdApi.SecretChat? = secretChats[secretChatId.toInt()]
    override fun putSecretChat(secretChat: TdApi.SecretChat) {
        secretChats[secretChat.id] = secretChat
    }

    override fun getMessage(chatId: Long, messageId: Long): TdApi.Message? = messages[chatId]?.get(messageId)
    override fun putMessage(message: TdApi.Message) {
        val chatMessages = messages.getOrPut(message.chatId) { ConcurrentHashMap() }
        val existing = chatMessages[message.id]
        if (existing != null) {
            synchronized(existing) {
                existing.senderId = message.senderId
                existing.date = message.date
                existing.editDate = message.editDate
                existing.forwardInfo = message.forwardInfo
                existing.interactionInfo = message.interactionInfo
                existing.replyTo = message.replyTo
                existing.selfDestructIn = message.selfDestructIn
                existing.content = message.content
                existing.replyMarkup = message.replyMarkup
                existing.isOutgoing = message.isOutgoing
                existing.hasTimestampedMedia = message.hasTimestampedMedia
                existing.isChannelPost = message.isChannelPost
                existing.containsUnreadMention = message.containsUnreadMention
                existing.sendingState = message.sendingState
                existing.schedulingState = message.schedulingState
            }
        } else {
            chatMessages[message.id] = message
        }
    }

    override fun removeMessage(chatId: Long, messageId: Long) {
        messages[chatId]?.remove(messageId)
    }

    fun updateChat(chatId: Long, action: (TdApi.Chat) -> Unit) {
        allChats[chatId]?.let { synchronized(it) { action(it) } }
    }

    fun updateUser(userId: Long, action: (TdApi.User) -> Unit) {
        usersCache[userId]?.let { synchronized(it) { action(it) } }
    }

    fun removeMessages(chatId: Long, messageIds: List<Long>) {
        val chatMessages = messages[chatId] ?: return
        messageIds.forEach { chatMessages.remove(it) }
    }

    fun clearMessages(chatId: Long) {
        messages.remove(chatId)
    }

    fun updateMessageId(chatId: Long, oldId: Long, newId: Long) {
        val chatMessages = messages[chatId] ?: return
        val message = chatMessages.remove(oldId) ?: return
        message.id = newId
        chatMessages[newId] = message
    }

    override fun clearAll() {
        allChats.clear()
        activeListPositions.clear()
        onlineMemberCount.clear()
        messages.clear()
        usersCache.clear()
        userFullInfoCache.clear()
        basicGroups.clear()
        basicGroupFullInfoCache.clear()
        supergroups.clear()
        supergroupFullInfoCache.clear()
        secretChats.clear()
        fileCache.clear()
        chatPermissionsCache.clear()
        myChatMemberCache.clear()

        pendingChats.clear()
        pendingUsers.clear()
        pendingUserFullInfo.clear()
        pendingBasicGroups.clear()
        pendingBasicGroupFullInfo.clear()
        pendingSupergroups.clear()
        pendingSupergroupFullInfo.clear()
        pendingSecretChats.clear()
        pendingChatPermissions.clear()
        pendingMyChatMember.clear()
    }

    fun putChatFromEntity(entity: org.monogram.data.db.model.ChatEntity) {
        val chatList = if (entity.isArchived) TdApi.ChatListArchive() else TdApi.ChatListMain()
        val chat = TdApi.Chat().apply {
            id = entity.id
            title = entity.title
            unreadCount = entity.unreadCount
            photo = entity.avatarPath?.let { path ->
                TdApi.ChatPhotoInfo().apply {
                    small = TdApi.File().apply { local = TdApi.LocalFile().apply { this.path = path } }
                }
            }
            lastMessage = TdApi.Message().apply {
                content = TdApi.MessageText().apply { text = TdApi.FormattedText(entity.lastMessageText, emptyArray()) }
                date = 0
            }
            positions = arrayOf(TdApi.ChatPosition(chatList, entity.order, entity.isPinned, null))
            notificationSettings = TdApi.ChatNotificationSettings().apply {
                muteFor = if (entity.isMuted) Int.MAX_VALUE else 0
            }
            type = when (entity.type) {
                "PRIVATE" -> TdApi.ChatTypePrivate()
                "BASIC_GROUP" -> TdApi.ChatTypeBasicGroup()
                "SUPERGROUP" -> TdApi.ChatTypeSupergroup(0, entity.isChannel)
                "SECRET" -> TdApi.ChatTypeSecret()
                else -> TdApi.ChatTypePrivate()
            }
        }
        putChat(chat)
    }
}
