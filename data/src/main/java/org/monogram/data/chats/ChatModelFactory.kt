package org.monogram.data.chats

import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.ChatMapper
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.UsernamesModel
import org.monogram.domain.repository.AppPreferencesProvider

class ChatModelFactory(
    private val gateway: TelegramGateway,
    private val dispatchers: DispatcherProvider,
    scopeProvider: ScopeProvider,
    private val cache: ChatCache,
    private val chatMapper: ChatMapper,
    private val fileManager: ChatFileManager,
    private val typingManager: ChatTypingManager,
    private val appPreferences: AppPreferencesProvider,
    private val triggerUpdate: (Long?) -> Unit,
    private val fetchUser: (Long) -> Unit
) {
    private val scope = scopeProvider.appScope

    fun mapChatToModel(chat: TdApi.Chat, order: Long, isPinned: Boolean): ChatModel {
        val cachedCounts = parseCachedCounts(chat.clientData)
        var smallPhoto = chat.photo?.small
        var photoId = smallPhoto?.id ?: 0
        var isSupergroup = false
        var isChannel = false
        var memberCount = cachedCounts?.first ?: 0
        var onlineCount = cache.onlineMemberCount[chat.id] ?: cachedCounts?.second ?: 0
        var isOnline = false
        var userStatus = ""
        var isVerified = false
        var isForum = false
        var isBot = false
        var isMember = true
        var isAdmin = false
        var username: String? = null
        var usernames: UsernamesModel? = null
        var description: String? = null
        var inviteLink: String? = null
        var hasAutomaticTranslation = false
        var personalAvatarPath: String? = null

        val isArchived = chat.positions.any { it.list is TdApi.ChatListArchive }

        when (val type = chat.type) {
            is TdApi.ChatTypeBasicGroup -> {
                cache.basicGroups[type.basicGroupId]?.let {
                    memberCount = it.memberCount
                    isMember = it.status !is TdApi.ChatMemberStatusLeft
                    isAdmin = it.status is TdApi.ChatMemberStatusAdministrator ||
                            it.status is TdApi.ChatMemberStatusCreator
                } ?: lazyLoad(cache.pendingBasicGroups, type.basicGroupId) {
                    if (type.basicGroupId == 0L) return@lazyLoad
                    val result = gateway.execute(TdApi.GetBasicGroup(type.basicGroupId))
                    cache.basicGroups[result.id] = result
                    triggerUpdate(chat.id)
                }

                cache.basicGroupFullInfoCache[type.basicGroupId]?.let { fullInfo ->
                    description = fullInfo.description
                    inviteLink = fullInfo.inviteLink?.inviteLink
                    personalAvatarPath = resolvePhotoPath(fullInfo.photo?.sizes?.lastOrNull()?.photo, chat.id)
                } ?: lazyLoad(cache.pendingBasicGroupFullInfo, type.basicGroupId) {
                    if (type.basicGroupId == 0L) return@lazyLoad
                    val result = gateway.execute(TdApi.GetBasicGroupFullInfo(type.basicGroupId))
                    cache.basicGroupFullInfoCache[type.basicGroupId] = result
                    triggerUpdate(chat.id)
                }
            }

            is TdApi.ChatTypeSupergroup -> {
                isSupergroup = true
                isChannel = type.isChannel
                cache.supergroups[type.supergroupId]?.let {
                    memberCount = it.memberCount
                    isVerified = it.verificationStatus?.isVerified ?: false
                    isForum = it.isForum
                    isMember = it.status !is TdApi.ChatMemberStatusLeft
                    isAdmin = it.status is TdApi.ChatMemberStatusAdministrator ||
                            it.status is TdApi.ChatMemberStatusCreator
                    username = it.usernames?.activeUsernames?.firstOrNull()
                    usernames = it.usernames?.toDomain()
                    hasAutomaticTranslation = it.hasAutomaticTranslation
                } ?: lazyLoad(cache.pendingSupergroups, type.supergroupId) {
                    if (type.supergroupId == 0L) return@lazyLoad
                    val result = gateway.execute(TdApi.GetSupergroup(type.supergroupId))
                    cache.supergroups[result.id] = result
                    triggerUpdate(chat.id)
                }

                cache.supergroupFullInfoCache[type.supergroupId]?.let { fullInfo ->
                    description = fullInfo.description
                    inviteLink = fullInfo.inviteLink?.inviteLink
                    personalAvatarPath = resolvePhotoPath(fullInfo.photo?.sizes?.lastOrNull()?.photo, chat.id)
                } ?: lazyLoad(cache.pendingSupergroupFullInfo, type.supergroupId) {
                    if (type.supergroupId == 0L) return@lazyLoad
                    val result = gateway.execute(TdApi.GetSupergroupFullInfo(type.supergroupId))
                    cache.supergroupFullInfoCache[type.supergroupId] = result
                    triggerUpdate(chat.id)
                }
            }

            is TdApi.ChatTypePrivate -> {
                cache.usersCache[type.userId]?.let { user ->
                    isBot = user.type is TdApi.UserTypeBot
                    isOnline = !isBot && user.status is TdApi.UserStatusOnline
                    if (isOnline) onlineCount = 1
                    userStatus = chatMapper.formatUserStatus(user.status, isBot)
                    isVerified = user.verificationStatus?.isVerified ?: false
                    username = user.usernames?.activeUsernames?.firstOrNull()
                    usernames = user.usernames?.toDomain()
                    if (smallPhoto == null) {
                        smallPhoto = user.profilePhoto?.small
                        photoId = smallPhoto?.id ?: 0
                    }
                } ?: run { fetchUser(type.userId) }

                cache.userFullInfoCache[type.userId]?.let { fullInfo ->
                    description = fullInfo.bio?.text
                    personalAvatarPath = resolvePhotoPath(fullInfo.photo?.sizes?.lastOrNull()?.photo, chat.id)
                } ?: lazyLoad(cache.pendingUserFullInfo, type.userId) {
                    if (type.userId == 0L) return@lazyLoad
                    val result = gateway.execute(TdApi.GetUserFullInfo(type.userId))
                    cache.userFullInfoCache[type.userId] = result
                    triggerUpdate(chat.id)
                }
            }

            else -> {}
        }

        if (cache.chatPermissionsCache[chat.id] == null) {
            lazyLoad(cache.pendingChatPermissions, chat.id) {
                val result = gateway.execute(TdApi.GetChat(chat.id))
                cache.chatPermissionsCache[chat.id] = result.permissions
                triggerUpdate(chat.id)
            }
        }

        if (cache.myChatMemberCache[chat.id] == null) {
            val canGetMember = when (val type = chat.type) {
                is TdApi.ChatTypePrivate, is TdApi.ChatTypeBasicGroup -> true
                is TdApi.ChatTypeSupergroup -> !type.isChannel ||
                        cache.supergroups[type.supergroupId]?.status.let {
                            it is TdApi.ChatMemberStatusAdministrator || it is TdApi.ChatMemberStatusCreator
                        }
                else -> false
            }
            if (canGetMember) {
                lazyLoad(cache.pendingMyChatMember, chat.id) {
                    val me = gateway.execute(TdApi.GetMe())
                    val member = gateway.execute(
                        TdApi.GetChatMember(chat.id, TdApi.MessageSenderUser(me.id))
                    )
                    cache.myChatMemberCache[chat.id] = member
                    triggerUpdate(chat.id)
                }
            }
        }

        val finalPath = resolvePhotoPath(smallPhoto, chat.id)

        val emojiStatusId = (chat.emojiStatus?.type as? TdApi.EmojiStatusTypeCustomEmoji)?.customEmojiId ?: 0L
        var emojiPath: String? = null
        if (emojiStatusId != 0L) {
            emojiPath = fileManager.getEmojiPath(emojiStatusId)
            if (emojiPath == null) fileManager.loadEmoji(emojiStatusId)
        }

        val (txt, entities, time) = chatMapper.formatMessageInfo(chat.lastMessage, chat) { userId ->
            cache.usersCache[userId]?.firstName ?: run { fetchUser(userId); null }
        }

        val isMuted = when {
            chat.notificationSettings.muteFor > 0 -> true
            chat.notificationSettings.useDefaultMuteFor -> when {
                isChannel -> !appPreferences.channelsNotifications.value
                isSupergroup || chat.type is TdApi.ChatTypeBasicGroup -> !appPreferences.groupsNotifications.value
                else -> !appPreferences.privateChatsNotifications.value
            }
            else -> false
        }

        return chatMapper.mapChatToModel(
            chat = chat,
            order = order,
            isPinned = isPinned,
            isArchived = isArchived,
            smallPhotoPath = finalPath,
            photoId = photoId,
            isOnline = isOnline,
            userStatus = userStatus,
            isVerified = isVerified,
            isForum = isForum,
            isBot = isBot,
            memberCount = memberCount,
            onlineCount = onlineCount,
            emojiPath = emojiPath,
            typingAction = typingManager.formatTypingAction(chat.id),
            lastMessageText = txt,
            lastMessageEntities = entities,
            lastMessageTime = time,
            isMuted = isMuted,
            isAdmin = isAdmin,
            isMember = isMember,
            username = username,
            usernames = usernames,
            description = description,
            inviteLink = inviteLink,
            hasAutomaticTranslation = hasAutomaticTranslation,
            personalAvatarPath = personalAvatarPath
        )
    }

    private fun <K> lazyLoad(pendingSet: MutableSet<K>, key: K, block: suspend () -> Unit) {
        if (pendingSet.add(key)) {
            scope.launch(dispatchers.io) {
                runCatching { block() }
                pendingSet.remove(key)
            }
        }
    }

    private fun resolvePhotoPath(photoFile: TdApi.File?, chatId: Long): String? {
        if (photoFile == null) return null
        fileManager.registerChatPhoto(photoFile.id, chatId)
        val path = photoFile.local.path.ifEmpty { fileManager.getFilePath(photoFile.id) ?: "" }
        return path.ifEmpty {
            fileManager.downloadFile(photoFile.id, 1, offset = 0, limit = 0, synchronous = true)
            null
        }
    }

    private fun TdApi.Usernames.toDomain() = UsernamesModel(
        activeUsernames = activeUsernames.toList(),
        disabledUsernames = disabledUsernames.toList(),
        collectibleUsernames = collectibleUsernames.toList()
    )

    private fun parseCachedCounts(clientData: String?): Pair<Int, Int>? {
        if (clientData.isNullOrBlank()) return null
        val memberCount = Regex("""mc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val onlineCount = Regex("""oc:(\d+)""").find(clientData)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (memberCount == null && onlineCount == null) return null
        return (memberCount ?: 0) to (onlineCount ?: 0)
    }
}
