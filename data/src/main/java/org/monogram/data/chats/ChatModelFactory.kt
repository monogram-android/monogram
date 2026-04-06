package org.monogram.data.chats

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.db.dao.UserFullInfoDao
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.*
import org.monogram.data.mapper.user.toEntity
import org.monogram.data.mapper.user.toTdApi
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.UsernamesModel
import org.monogram.domain.repository.AppPreferencesProvider
import java.util.concurrent.ConcurrentHashMap

class ChatModelFactory(
    private val gateway: TelegramGateway,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val cache: ChatCache,
    private val chatMapper: ChatMapper,
    private val fileManager: ChatFileManager,
    private val typingManager: ChatTypingManager,
    private val appPreferences: AppPreferencesProvider,
    private val userFullInfoDao: UserFullInfoDao,
    private val triggerUpdate: (Long?) -> Unit,
    private val fetchUser: (Long) -> Unit
) {
    private val missingUserFullInfoUntilMs = ConcurrentHashMap<Long, Long>()
    private val userFullInfoSemaphore = Semaphore(permits = 3)

    fun mapChatToModel(
        chat: TdApi.Chat,
        order: Long,
        isPinned: Boolean,
        allowMediaDownloads: Boolean = true
    ): ChatModel {
        val cachedCounts = parseCachedCounts(chat.clientData)
        var smallPhoto = chat.photo?.small
        var photoId = smallPhoto?.id ?: 0
        var isSupergroup = false
        var isChannel = false
        var memberCount = cachedCounts?.first ?: 0
        var onlineCount = cache.onlineMemberCount[chat.id] ?: cachedCounts?.second ?: 0
        var isOnline = false
        var userStatus = ""
        var isVerified = isForcedVerifiedChat(chat.id)
        var isScam = false
        var isFake = false
        var botVerificationIconCustomEmojiId = 0L
        var restrictionReason: String? = null
        var hasSensitiveContent = false
        var activeStoryStateType: String? = null
        var activeStoryId = 0
        var boostLevel = 0
        var hasForumTabs = false
        var isAdministeredDirectMessagesGroup = false
        var paidMessageStarCount = 0L
        var isForum = false
        var isBot = false
        var isMember = true
        var isAdmin = false
        var isSponsor = false
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
                    personalAvatarPath = null
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
                val supergroup = cache.supergroups[type.supergroupId]
                supergroup?.let {
                    memberCount = it.memberCount
                    isVerified = (it.verificationStatus?.isVerified ?: false) || isForcedVerifiedChat(chat.id)
                    isScam = it.verificationStatus?.isScam ?: false
                    isFake = it.verificationStatus?.isFake ?: false
                    botVerificationIconCustomEmojiId = it.verificationStatus?.botVerificationIconCustomEmojiId ?: 0L
                    restrictionReason = it.restrictionInfo?.restrictionReason?.ifEmpty { null }
                    hasSensitiveContent = it.restrictionInfo?.hasSensitiveContent ?: false
                    activeStoryStateType = it.activeStoryState.toTypeString()
                    activeStoryId = (it.activeStoryState as? TdApi.ActiveStoryStateLive)?.storyId ?: 0
                    boostLevel = it.boostLevel
                    hasForumTabs = it.hasForumTabs
                    isAdministeredDirectMessagesGroup = it.isAdministeredDirectMessagesGroup
                    paidMessageStarCount = it.paidMessageStarCount
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

                val canLoadSupergroupFullInfo = supergroup?.status?.let {
                    it !is TdApi.ChatMemberStatusLeft && it !is TdApi.ChatMemberStatusBanned
                } == true

                if (canLoadSupergroupFullInfo) {
                    cache.supergroupFullInfoCache[type.supergroupId]?.let { fullInfo ->
                        description = fullInfo.description
                        inviteLink = fullInfo.inviteLink?.inviteLink
                        personalAvatarPath = null
                    } ?: lazyLoad(cache.pendingSupergroupFullInfo, type.supergroupId) {
                        if (type.supergroupId == 0L) return@lazyLoad
                        val result = gateway.execute(TdApi.GetSupergroupFullInfo(type.supergroupId))
                        cache.supergroupFullInfoCache[type.supergroupId] = result
                        triggerUpdate(chat.id)
                    }
                }
            }

            is TdApi.ChatTypePrivate -> {
                val user = cache.usersCache[type.userId]
                user?.let {
                    isBot = user.type is TdApi.UserTypeBot
                    isOnline = !isBot && user.status is TdApi.UserStatusOnline
                    if (isOnline) onlineCount = 1
                    userStatus = chatMapper.formatUserStatus(user.status, isBot)
                    isVerified = (user.verificationStatus?.isVerified ?: false) || isForcedVerifiedUser(user.id)
                    isScam = user.verificationStatus?.isScam ?: false
                    isFake = user.verificationStatus?.isFake ?: false
                    botVerificationIconCustomEmojiId = user.verificationStatus?.botVerificationIconCustomEmojiId ?: 0L
                    restrictionReason = user.restrictionInfo?.restrictionReason?.ifEmpty { null }
                    hasSensitiveContent = user.restrictionInfo?.hasSensitiveContent ?: false
                    activeStoryStateType = user.activeStoryState.toTypeString()
                    activeStoryId = (user.activeStoryState as? TdApi.ActiveStoryStateLive)?.storyId ?: 0
                    paidMessageStarCount = user.paidMessageStarCount
                    isSponsor = isSponsoredUser(user.id)
                    username = user.usernames?.activeUsernames?.firstOrNull()
                    usernames = user.usernames?.toDomain()
                    if (smallPhoto == null) {
                        smallPhoto = user.profilePhoto?.small
                        photoId = smallPhoto?.id ?: 0
                    }
                } ?: run { fetchUser(type.userId) }

                if (user != null) {
                    cache.userFullInfoCache[type.userId]?.let { fullInfo ->
                        description = fullInfo.bio?.text
                        personalAvatarPath = resolvePhotoPath(fullInfo.personalPhoto, chat.id, allowMediaDownloads)
                    } ?: run {
                        if (!isUserFullInfoTemporarilyMissing(type.userId)) {
                            lazyLoad(cache.pendingUserFullInfo, type.userId) {
                                if (type.userId == 0L) return@lazyLoad
                                cache.userFullInfoCache[type.userId]?.let {
                                    triggerUpdate(chat.id)
                                    return@lazyLoad
                                }
                                val cachedInfo = coRunCatching {
                                    userFullInfoDao.getUserFullInfo(type.userId)?.toTdApi()
                                }.getOrNull()
                                if (cachedInfo != null) {
                                    cache.putUserFullInfo(type.userId, cachedInfo)
                                    missingUserFullInfoUntilMs.remove(type.userId)
                                    triggerUpdate(chat.id)
                                    return@lazyLoad
                                }
                                val result = userFullInfoSemaphore.withPermit {
                                    cache.userFullInfoCache[type.userId] ?: coRunCatching {
                                        gateway.execute(TdApi.GetUserFullInfo(type.userId))
                                    }.getOrNull()
                                }
                                if (result != null) {
                                    cache.putUserFullInfo(type.userId, result)
                                    coRunCatching { userFullInfoDao.insertUserFullInfo(result.toEntity(type.userId)) }
                                    missingUserFullInfoUntilMs.remove(type.userId)
                                    triggerUpdate(chat.id)
                                } else {
                                    rememberMissingUserFullInfo(type.userId)
                                }
                            }
                        }
                    }
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

        val finalPath = resolvePhotoPath(smallPhoto, chat.id, allowMediaDownloads)

        val emojiStatusId = (chat.emojiStatus?.type as? TdApi.EmojiStatusTypeCustomEmoji)?.customEmojiId ?: 0L
        var emojiPath: String? = null
        if (emojiStatusId != 0L) {
            emojiPath = fileManager.getEmojiPath(emojiStatusId)
            if (emojiPath == null && allowMediaDownloads) fileManager.loadEmoji(emojiStatusId)
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
            isScam = isScam,
            isFake = isFake,
            botVerificationIconCustomEmojiId = botVerificationIconCustomEmojiId,
            restrictionReason = restrictionReason,
            hasSensitiveContent = hasSensitiveContent,
            activeStoryStateType = activeStoryStateType,
            activeStoryId = activeStoryId,
            boostLevel = boostLevel,
            hasForumTabs = hasForumTabs,
            isAdministeredDirectMessagesGroup = isAdministeredDirectMessagesGroup,
            paidMessageStarCount = paidMessageStarCount,
            isSponsor = isSponsor,
            isForum = isForum,
            isBot = isBot,
            memberCount = memberCount,
            onlineCount = onlineCount,
            emojiPath = emojiPath,
            typingAction = typingManager.formatTypingAction(chat.id),
            lastMessageText = txt,
            lastMessageEntities = entities,
            lastMessageTime = time,
            lastMessageDate = chat.lastMessage?.date ?: 0,
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
                coRunCatching { block() }
                pendingSet.remove(key)
            }
        }
    }

    private fun isUserFullInfoTemporarilyMissing(userId: Long): Boolean {
        if (userId <= 0L) return true
        val until = missingUserFullInfoUntilMs[userId] ?: return false
        if (until > System.currentTimeMillis()) return true
        missingUserFullInfoUntilMs.remove(userId, until)
        return false
    }

    private fun rememberMissingUserFullInfo(userId: Long) {
        if (userId <= 0L) return
        missingUserFullInfoUntilMs[userId] = System.currentTimeMillis() + USER_FULL_INFO_RETRY_TTL_MS
    }

    private fun resolvePhotoPath(photoFile: TdApi.File?, chatId: Long, allowDownload: Boolean): String? {
        if (photoFile == null) return null
        if (photoFile.id != 0) {
            fileManager.registerChatPhoto(photoFile.id, chatId)
        }

        val localPath = photoFile.local.path
        if (isValidFilePath(localPath)) {
            return localPath
        }

        val cachedPath = photoFile.id.takeIf { it != 0 }?.let { fileManager.getFilePath(it) }
        if (isValidFilePath(cachedPath)) {
            return cachedPath
        }

        if (allowDownload && photoFile.id != 0) {
            fileManager.downloadFile(photoFile.id, 24, offset = 0, limit = 0, synchronous = false)
        }
        return null
    }

    private fun resolvePhotoPath(chatPhoto: TdApi.ChatPhoto?, chatId: Long, allowDownload: Boolean): String? {
        if (chatPhoto == null) return null
        return resolvePhotoPath(chatPhoto.animation?.file, chatId, allowDownload)
            ?: resolvePhotoPath(chatPhoto.sizes.lastOrNull()?.photo, chatId, allowDownload)
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

    companion object {
        private const val USER_FULL_INFO_RETRY_TTL_MS = 5 * 60 * 1000L
    }
}

private fun TdApi.ActiveStoryState?.toTypeString(): String? {
    return when (this) {
        is TdApi.ActiveStoryStateLive -> "LIVE"
        is TdApi.ActiveStoryStateUnread -> "UNREAD"
        is TdApi.ActiveStoryStateRead -> "READ"
        else -> null
    }
}
