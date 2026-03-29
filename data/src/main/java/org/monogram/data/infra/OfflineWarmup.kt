package org.monogram.data.infra

import org.monogram.data.core.coRunCatching
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.db.dao.*
import org.monogram.data.db.model.ChatEntity
import org.monogram.data.db.model.UserEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.user.toEntity
import org.monogram.domain.repository.StickerRepository

private const val TAG = "OfflineWarmup"

class OfflineWarmup(
    private val scopeProvider: ScopeProvider,
    private val dispatchers: DispatcherProvider,
    private val gateway: TelegramGateway,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val userFullInfoDao: UserFullInfoDao,
    private val chatFullInfoDao: ChatFullInfoDao,
    private val messageMapper: MessageMapper,
    private val chatCache: ChatCache,
    private val stickerRepository: StickerRepository
) {
    private val scope = scopeProvider.appScope

    init {
        scope.launch(dispatchers.io) {
            delay(1800)
            coRunCatching { warmup() }
                .onFailure { Log.e(TAG, "Offline warmup failed", it) }
        }
    }

    private suspend fun warmup() {
        val topChats = chatDao.getTopChats(limit = 100)
        if (topChats.isEmpty()) return

        warmupStickers()
        warmupUsers(topChats)
        warmupChatFullInfo(topChats)
        warmupMessages(topChats)
    }

    private suspend fun warmupStickers() {
        coRunCatching { stickerRepository.loadInstalledStickerSets() }
        coRunCatching { stickerRepository.loadCustomEmojiStickerSets() }
    }

    private suspend fun warmupUsers(chats: List<ChatEntity>) {
        val userIds = LinkedHashSet<Long>()
        chats.forEach { chat ->
            if (chat.privateUserId != 0L) userIds.add(chat.privateUserId)
            chat.messageSenderId?.takeIf { it > 0 }?.let { userIds.add(it) }
            messageDao.getLatestMessages(chat.id, 20)
                .asSequence()
                .map { it.senderId }
                .filter { it > 0L }
                .forEach { userIds.add(it) }
        }
        if (userIds.isEmpty()) return

        val limited = userIds.take(80)
        val existingUsers = userDao.getUsersByIds(limited).associateBy { it.id }
        val existingFullInfos = userFullInfoDao.getUserFullInfos(limited).associateBy { it.userId }
        val now = System.currentTimeMillis()

        for (userId in limited) {
            val cachedUser = existingUsers[userId]
            var hasUser = cachedUser != null
            if (cachedUser == null || now - cachedUser.createdAt > ONE_DAY_MS) {
                val user = coRunCatching { gateway.execute(TdApi.GetUser(userId)) as? TdApi.User }.getOrNull()
                if (user != null) {
                    val personalAvatarPath = existingFullInfos[userId]?.personalPhotoPath?.ifBlank { null }
                    userDao.insertUser(user.toUserEntity(personalAvatarPath))
                    chatCache.putUser(user)
                    hasUser = true
                } else if (cachedUser == null) {
                    hasUser = false
                }
            }

            if (!hasUser) {
                delay(25)
                continue
            }

            val cachedFullInfo = existingFullInfos[userId]
            if (cachedFullInfo == null || now - cachedFullInfo.createdAt > SEVEN_DAYS_MS) {
                val fullInfo = coRunCatching {
                    gateway.execute(TdApi.GetUserFullInfo(userId)) as? TdApi.UserFullInfo
                }.getOrNull()
                if (fullInfo != null) {
                    userFullInfoDao.insertUserFullInfo(fullInfo.toEntity(userId))
                    val personalAvatarPath = fullInfo.extractPersonalAvatarPath()
                    if (!personalAvatarPath.isNullOrBlank()) {
                        userDao.getUser(userId)?.let { existing ->
                            if (existing.personalAvatarPath != personalAvatarPath) {
                                userDao.insertUser(existing.copy(personalAvatarPath = personalAvatarPath))
                            }
                        }
                    }
                    chatCache.putUserFullInfo(userId, fullInfo)
                }
            }
            delay(25)
        }
    }

    private suspend fun warmupChatFullInfo(chats: List<ChatEntity>) {
        val targetChats = chats.take(50)
        val existing = chatFullInfoDao.getChatFullInfos(targetChats.map { it.id }).associateBy { it.chatId }
        val now = System.currentTimeMillis()

        for (chat in targetChats) {
            val cached = existing[chat.id]
            if (cached != null && now - cached.createdAt <= SEVEN_DAYS_MS) {
                continue
            }

            when {
                chat.supergroupId != 0L -> {
                    val supergroupInfo = gateway.execute(TdApi.GetSupergroupFullInfo(chat.supergroupId)) as? TdApi.SupergroupFullInfo
                    if (supergroupInfo != null) {
                        chatFullInfoDao.insertChatFullInfo(supergroupInfo.toEntity(chat.id))
                        val supergroup = gateway.execute(TdApi.GetSupergroup(chat.supergroupId)) as? TdApi.Supergroup
                        if (supergroup != null) {
                            chatCache.putSupergroup(supergroup)
                            chatCache.putSupergroupFullInfo(chat.supergroupId, supergroupInfo)
                        }
                    }
                }

                chat.basicGroupId != 0L -> {
                    val basicGroupInfo = gateway.execute(TdApi.GetBasicGroupFullInfo(chat.basicGroupId)) as? TdApi.BasicGroupFullInfo
                    if (basicGroupInfo != null) {
                        chatFullInfoDao.insertChatFullInfo(basicGroupInfo.toEntity(chat.id))
                        val basicGroup = gateway.execute(TdApi.GetBasicGroup(chat.basicGroupId)) as? TdApi.BasicGroup
                        if (basicGroup != null) {
                            chatCache.putBasicGroup(basicGroup)
                            chatCache.putBasicGroupFullInfo(chat.basicGroupId, basicGroupInfo)
                        }
                    }
                }
            }
            delay(30)
        }
    }

    private suspend fun warmupMessages(chats: List<ChatEntity>) {
        val targetChats = chats.take(30)
        for (chat in targetChats) {
            val alreadyCachedCount = messageDao.getLatestMessages(chat.id, 25).size
            if (alreadyCachedCount >= 25) {
                continue
            }

            val history = gateway.execute(
                TdApi.GetChatHistory(
                    chat.id,
                    0L,
                    0,
                    60,
                    false
                )
            ) as? TdApi.Messages ?: continue

            if (history.messages.isEmpty()) {
                continue
            }

            history.messages.forEach { message -> chatCache.putMessage(message) }
            val entities = history.messages.map { message ->
                messageMapper.mapToEntity(message) { senderId ->
                    chatCache.getUser(senderId)?.let { cachedUser ->
                        listOfNotNull(
                            cachedUser.firstName.takeIf { it.isNotBlank() },
                            cachedUser.lastName.takeIf { !it.isNullOrBlank() }
                        ).joinToString(" ")
                    }
                }
            }
            messageDao.insertMessages(entities)
            delay(40)
        }
    }

    private fun TdApi.User.toUserEntity(personalAvatarPath: String?): UserEntity {
        val usernamesData = buildString {
            append(usernames?.activeUsernames?.joinToString("|").orEmpty())
            append('\n')
            append(usernames?.disabledUsernames?.joinToString("|").orEmpty())
            append('\n')
            append(usernames?.editableUsername.orEmpty())
            append('\n')
            append(usernames?.collectibleUsernames?.joinToString("|").orEmpty())
        }

        val statusType = when (status) {
            is TdApi.UserStatusOnline -> "ONLINE"
            is TdApi.UserStatusRecently -> "RECENTLY"
            is TdApi.UserStatusLastWeek -> "LAST_WEEK"
            is TdApi.UserStatusLastMonth -> "LAST_MONTH"
            else -> "OFFLINE"
        }

        val statusEmojiId = when (val type = emojiStatus?.type) {
            is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
            is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
            else -> 0L
        }

        return UserEntity(
            id = id,
            firstName = firstName,
            lastName = lastName.ifEmpty { null },
            phoneNumber = phoneNumber.ifEmpty { null },
            avatarPath = profilePhoto?.small?.local?.path?.ifEmpty { null },
            personalAvatarPath = personalAvatarPath,
            isPremium = isPremium,
            isVerified = verificationStatus?.isVerified ?: false,
            isSupport = isSupport,
            isContact = isContact,
            isMutualContact = isMutualContact,
            isCloseFriend = isCloseFriend,
            haveAccess = haveAccess,
            username = usernames?.activeUsernames?.firstOrNull(),
            usernamesData = usernamesData,
            statusType = statusType,
            accentColorId = accentColorId,
            profileAccentColorId = profileAccentColorId,
            statusEmojiId = statusEmojiId,
            languageCode = languageCode.ifEmpty { null },
            lastSeen = (status as? TdApi.UserStatusOffline)?.wasOnline?.toLong() ?: 0L,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun TdApi.UserFullInfo.extractPersonalAvatarPath(): String? {
        val bestPhotoSize = personalPhoto?.sizes?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: personalPhoto?.sizes?.lastOrNull()
        return personalPhoto?.animation?.file?.local?.path?.ifEmpty { null }
            ?: bestPhotoSize?.photo?.local?.path?.ifEmpty { null }
    }

    private companion object {
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        private const val SEVEN_DAYS_MS = 7L * ONE_DAY_MS
    }
}
