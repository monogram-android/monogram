package org.monogram.data.mapper.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.SenderNameResolver
import org.monogram.data.mapper.TdFileHelper
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.UserRepository
import java.util.concurrent.ConcurrentHashMap

internal data class ResolvedSender(
    val senderId: Long,
    val senderName: String,
    val senderAvatar: String? = null,
    val senderPersonalAvatar: String? = null,
    val senderCustomTitle: String? = null,
    val isSenderVerified: Boolean = false,
    val isSenderPremium: Boolean = false,
    val senderStatusEmojiId: Long = 0L,
    val senderStatusEmojiPath: String? = null
)

internal class MessageSenderResolver(
    private val gateway: TelegramGateway,
    private val userRepository: UserRepository,
    private val chatInfoRepository: ChatInfoRepository,
    private val cache: ChatCache,
    private val fileHelper: TdFileHelper,
    private val stringProvider: StringProvider
) {
    private data class SenderUserSnapshot(
        val name: String,
        val avatar: String?,
        val personalAvatar: String?,
        val isVerified: Boolean,
        val isPremium: Boolean,
        val statusEmojiId: Long,
        val statusEmojiPath: String?
    )

    private data class SenderChatSnapshot(
        val name: String,
        val avatar: String?
    )

    private val senderUserSnapshotCache = ConcurrentHashMap<Long, SenderUserSnapshot>()
    private val senderChatSnapshotCache = ConcurrentHashMap<Long, SenderChatSnapshot>()
    private val senderRankCache = ConcurrentHashMap<String, String>()
    private val queuedAvatarDownloads = ConcurrentHashMap.newKeySet<Int>()
    private val unknownUserName: String
        get() = stringProvider.getString("unknown_user")

    val senderUpdateFlow: Flow<Long>
        get() = userRepository.anyUserUpdateFlow

    fun invalidateCache(userId: Long) {
        if (userId <= 0L) return
        senderUserSnapshotCache.remove(userId)
        senderChatSnapshotCache.remove(userId)
        senderRankCache.entries.removeIf { it.key.endsWith(":$userId") }
    }

    fun resolveNameFromCache(senderId: Long, fallback: String): String {
        val user = cache.getUser(senderId)
        if (user != null) {
            return SenderNameResolver.fromParts(
                firstName = user.firstName,
                lastName = user.lastName,
                fallback = fallback.ifBlank { unknownUserName }
            )
        }

        val chat = cache.getChat(senderId)
        if (chat != null) {
            return chat.title.takeIf { it.isNotBlank() } ?: fallback.ifBlank { unknownUserName }
        }

        return fallback.ifBlank { unknownUserName }
    }

    fun resolveFallbackSender(msg: TdApi.Message): ResolvedSender {
        return when (val sender = msg.senderId) {
            is TdApi.MessageSenderUser -> {
                val senderId = sender.userId
                val snapshot = senderUserSnapshotCache[senderId]
                if (snapshot != null) {
                    ResolvedSender(
                        senderId = senderId,
                        senderName = snapshot.name.ifBlank { unknownUserName },
                        senderAvatar = snapshot.avatar ?: snapshot.personalAvatar,
                        senderPersonalAvatar = snapshot.personalAvatar,
                        isSenderVerified = snapshot.isVerified,
                        isSenderPremium = snapshot.isPremium,
                        senderStatusEmojiId = snapshot.statusEmojiId,
                        senderStatusEmojiPath = snapshot.statusEmojiPath
                    )
                } else {
                    val user = cache.getUser(senderId)
                    val fallbackName = if (user != null) {
                        SenderNameResolver.fromParts(user.firstName, user.lastName, unknownUserName)
                    } else {
                        unknownUserName
                    }
                    val avatar = user?.profilePhoto?.small?.local?.path?.takeIf { fileHelper.isValidPath(it) }
                        ?: user?.profilePhoto?.big?.local?.path?.takeIf { fileHelper.isValidPath(it) }
                    ResolvedSender(senderId = senderId, senderName = fallbackName, senderAvatar = avatar)
                }
            }

            is TdApi.MessageSenderChat -> {
                val senderId = sender.chatId
                val snapshot = senderChatSnapshotCache[senderId]
                if (snapshot != null) {
                    ResolvedSender(
                        senderId = senderId,
                        senderName = snapshot.name.ifBlank { unknownUserName },
                        senderAvatar = snapshot.avatar
                    )
                } else {
                    val chat = cache.getChat(senderId)
                    val fallbackName = chat?.title?.takeIf { it.isNotBlank() } ?: unknownUserName
                    val avatar = chat?.photo?.small?.local?.path?.takeIf { fileHelper.isValidPath(it) }
                    ResolvedSender(senderId = senderId, senderName = fallbackName, senderAvatar = avatar)
                }
            }

            else -> ResolvedSender(senderId = 0L, senderName = unknownUserName)
        }
    }

    suspend fun resolveSender(msg: TdApi.Message): ResolvedSender {
        var senderName = unknownUserName
        var senderAvatar: String? = null
        var senderPersonalAvatar: String? = null
        var senderCustomTitle: String? = null
        var isSenderVerified = false
        var isSenderPremium = false
        var senderStatusEmojiId = 0L
        var senderStatusEmojiPath: String? = null
        val senderId: Long

        when (val sender = msg.senderId) {
            is TdApi.MessageSenderUser -> {
                senderId = sender.userId
                val cachedSnapshot = senderUserSnapshotCache[senderId]
                if (cachedSnapshot != null) {
                    senderName = cachedSnapshot.name
                    senderAvatar = cachedSnapshot.avatar
                    senderPersonalAvatar = cachedSnapshot.personalAvatar
                    isSenderVerified = cachedSnapshot.isVerified
                    isSenderPremium = cachedSnapshot.isPremium
                    senderStatusEmojiId = cachedSnapshot.statusEmojiId
                    senderStatusEmojiPath = cachedSnapshot.statusEmojiPath
                } else {
                    val user = try {
                        withTimeout(500) { userRepository.getUser(senderId) }
                    } catch (_: Exception) {
                        null
                    }

                    if (user != null) {
                        senderName = SenderNameResolver.fromParts(
                            firstName = user.firstName,
                            lastName = user.lastName,
                            fallback = unknownUserName
                        )

                        senderAvatar = user.avatarPath.takeIf { fileHelper.isValidPath(it) }
                        senderPersonalAvatar = user.personalAvatarPath.takeIf { fileHelper.isValidPath(it) }
                        isSenderVerified = user.isVerified
                        isSenderPremium = user.isPremium
                        senderStatusEmojiId = user.statusEmojiId
                        senderStatusEmojiPath = user.statusEmojiPath

                        senderUserSnapshotCache[senderId] = SenderUserSnapshot(
                            name = senderName,
                            avatar = senderAvatar,
                            personalAvatar = senderPersonalAvatar,
                            isVerified = isSenderVerified,
                            isPremium = isSenderPremium,
                            statusEmojiId = senderStatusEmojiId,
                            statusEmojiPath = senderStatusEmojiPath
                        )
                    }
                }

                val chat = cache.getChat(msg.chatId)
                val canGetMember = when (chat?.type) {
                    is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> true
                    is TdApi.ChatTypeBasicGroup -> true
                    is TdApi.ChatTypeSupergroup -> {
                        val supergroup = chat.type as TdApi.ChatTypeSupergroup
                        val cachedSupergroup = cache.getSupergroup(supergroup.supergroupId)
                        !(cachedSupergroup?.isChannel ?: false) || (chat.permissions?.canSendBasicMessages ?: false)
                    }

                    else -> false
                }

                if (canGetMember) {
                    val rankKey = "${msg.chatId}:$senderId"
                    val cachedRank = senderRankCache[rankKey]
                    if (cachedRank != null) {
                        senderCustomTitle = cachedRank.takeUnless { it == NO_RANK_SENTINEL }
                    } else {
                        val member = try {
                            withTimeout(500) { chatInfoRepository.getChatMember(msg.chatId, senderId) }
                        } catch (_: Exception) {
                            null
                        }

                        senderCustomTitle = member?.rank
                        senderRankCache[rankKey] = senderCustomTitle ?: NO_RANK_SENTINEL
                    }
                }
            }

            is TdApi.MessageSenderChat -> {
                senderId = sender.chatId
                val cachedSnapshot = senderChatSnapshotCache[senderId]
                if (cachedSnapshot != null) {
                    senderName = cachedSnapshot.name
                    senderAvatar = cachedSnapshot.avatar
                } else {
                    val chat = try {
                        withTimeout(500) {
                            cache.getChat(senderId)
                                ?: gateway.execute(TdApi.GetChat(senderId)).also { cache.putChat(it) }
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (chat != null) {
                        senderName = chat.title
                        val photo = chat.photo?.small
                        if (photo != null) {
                            senderAvatar = photo.local.path.takeIf { fileHelper.isValidPath(it) }
                            if (senderAvatar.isNullOrEmpty() && queuedAvatarDownloads.add(photo.id)) {
                                fileHelper.enqueueDownload(
                                    photo.id,
                                    16,
                                    TdMessageRemoteDataSource.DownloadType.DEFAULT,
                                    0,
                                    0,
                                    false
                                )
                            }
                        }

                        senderChatSnapshotCache[senderId] = SenderChatSnapshot(
                            name = senderName,
                            avatar = senderAvatar
                        )
                    }
                }
            }

            else -> senderId = 0L
        }

        return ResolvedSender(
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            senderPersonalAvatar = senderPersonalAvatar,
            senderCustomTitle = senderCustomTitle,
            isSenderVerified = isSenderVerified,
            isSenderPremium = isSenderPremium,
            senderStatusEmojiId = senderStatusEmojiId,
            senderStatusEmojiPath = senderStatusEmojiPath
        )
    }

    private companion object {
        private const val NO_RANK_SENTINEL = "__NO_RANK__"
    }
}