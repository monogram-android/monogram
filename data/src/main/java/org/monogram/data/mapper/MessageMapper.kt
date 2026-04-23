package org.monogram.data.mapper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.message.ContentMappingContext
import org.monogram.data.mapper.message.MessageContentMapper
import org.monogram.data.mapper.message.MessagePersistenceMapper
import org.monogram.data.mapper.message.MessageSenderResolver
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.ForwardOriginType
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.domain.models.ReactionSender
import org.monogram.domain.repository.StringProvider
import org.monogram.domain.repository.UserRepository

class MessageMapper internal constructor(
    private val gateway: TelegramGateway,
    private val userRepository: UserRepository,
    private val cache: ChatCache,
    private val fileHelper: TdFileHelper,
    private val senderResolver: MessageSenderResolver,
    private val contentMapper: MessageContentMapper,
    private val persistenceMapper: MessagePersistenceMapper,
    private val customEmojiLoader: CustomEmojiLoader,
    private val stringProvider: StringProvider
) {
    private val unknownUserName: String
        get() = stringProvider.getString("unknown_user")

    val senderUpdateFlow: Flow<Long>
        get() = senderResolver.senderUpdateFlow

    fun invalidateSenderCache(userId: Long) {
        senderResolver.invalidateCache(userId)
    }

    suspend fun mapMessageToModel(
        msg: TdApi.Message,
        isChatOpen: Boolean = false,
        isReply: Boolean = false
    ): MessageModel = coroutineScope {
        withTimeoutOrNull(MESSAGE_MAP_TIMEOUT_MS) {
            val sender = senderResolver.resolveSender(msg)

            val (replyToMsgId, replyToMsg) = resolveReplyInfo(
                msg = msg,
                isChatOpen = isChatOpen,
                isReply = isReply
            )

            val forwardInfo = resolveForwardInfo(msg)
            val views = msg.interactionInfo?.viewCount
            val replyCount = msg.interactionInfo?.replyInfo?.replyCount ?: 0
            val sendingState = resolveSendingState(msg)
            val reactions = resolveReactions(msg, isReply, isChatOpen)
            val threadId = resolveThreadId(msg)
            val viaBotName = resolveViaBotName(msg)

            createMessageModel(
                msg = msg,
                senderName = sender.senderName,
                senderId = sender.senderId,
                senderAvatar = sender.senderAvatar,
                isReadOverride = false,
                replyToMsgId = replyToMsgId,
                replyToMsg = replyToMsg,
                forwardInfo = forwardInfo,
                views = views,
                viewCount = views,
                mediaAlbumId = msg.mediaAlbumId,
                sendingState = sendingState,
                isChatOpen = isChatOpen,
                readDate = 0,
                reactions = reactions,
                isSenderVerified = sender.isSenderVerified,
                threadId = threadId,
                replyCount = replyCount,
                isReply = isReply,
                viaBotUserId = msg.viaBotUserId,
                viaBotName = viaBotName,
                senderPersonalAvatar = sender.senderPersonalAvatar,
                senderCustomTitle = sender.senderCustomTitle,
                isSenderPremium = sender.isSenderPremium,
                senderStatusEmojiId = sender.senderStatusEmojiId,
                senderStatusEmojiPath = sender.senderStatusEmojiPath
            )
        } ?: mapMessageToModelFallback(msg, isChatOpen, isReply)
    }

    suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int {
        val chat = cache.getChat(chatId)
        if (chat?.type !is TdApi.ChatTypePrivate) {
            return 0
        }

        val sevenDaysAgo = (System.currentTimeMillis() / 1000) - (7 * 24 * 60 * 60)
        if (messageDate < sevenDaysAgo) {
            return 0
        }

        return try {
            val result = gateway.execute(TdApi.GetMessageReadDate(chatId, messageId))
            if (result is TdApi.MessageReadDateRead) {
                result.readDate
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    suspend fun mapMessageToModelSync(
        msg: TdApi.Message,
        inboxLimit: Long,
        outboxLimit: Long,
        isChatOpen: Boolean = false,
        isReply: Boolean = false
    ): MessageModel {
        val isRead = if (msg.isOutgoing) msg.id <= outboxLimit else msg.id <= inboxLimit
        val baseModel = mapMessageToModel(msg, isChatOpen, isReply)
        return baseModel.copy(isRead = isRead)
    }

    internal fun createMessageModel(
        msg: TdApi.Message,
        senderName: String,
        senderId: Long,
        senderAvatar: String?,
        isReadOverride: Boolean = false,
        replyToMsgId: Long? = null,
        replyToMsg: MessageModel? = null,
        forwardInfo: ForwardInfo? = null,
        views: Int? = null,
        viewCount: Int? = null,
        mediaAlbumId: Long = 0L,
        sendingState: MessageSendingState? = null,
        isChatOpen: Boolean = false,
        readDate: Int = 0,
        reactions: List<MessageReactionModel> = emptyList(),
        isSenderVerified: Boolean = false,
        threadId: Long? = null,
        replyCount: Int = 0,
        isReply: Boolean = false,
        viaBotUserId: Long = 0L,
        viaBotName: String? = null,
        senderPersonalAvatar: String? = null,
        senderCustomTitle: String? = null,
        isSenderPremium: Boolean = false,
        senderStatusEmojiId: Long = 0L,
        senderStatusEmojiPath: String? = null
    ): MessageModel {
        val networkAutoDownload = isChatOpen && fileHelper.isNetworkAutoDownloadEnabled()
        val isActuallyUploading = msg.sendingState is TdApi.MessageSendingStatePending

        val content = contentMapper.mapContent(
            msg = msg,
            context = ContentMappingContext(
                chatId = msg.chatId,
                messageId = msg.id,
                senderId = senderId,
                senderName = senderName,
                networkAutoDownload = networkAutoDownload,
                isActuallyUploading = isActuallyUploading
            )
        )

        val isServiceMessage = content is MessageContent.Service
        val canEdit = msg.isOutgoing && !isServiceMessage
        val canForward = !isServiceMessage
        val canSave = !isServiceMessage
        val hasInteraction = msg.interactionInfo != null

        return MessageModel(
            id = msg.id,
            date = contentMapper.resolveMessageDate(msg),
            isOutgoing = msg.isOutgoing,
            senderName = senderName,
            chatId = msg.chatId,
            content = content,
            senderId = senderId,
            senderAvatar = senderAvatar,
            senderPersonalAvatar = senderPersonalAvatar,
            senderCustomTitle = senderCustomTitle,
            isRead = isReadOverride,
            replyToMsgId = replyToMsgId,
            replyToMsg = replyToMsg,
            forwardInfo = forwardInfo,
            views = views,
            viewCount = viewCount,
            mediaAlbumId = mediaAlbumId,
            editDate = msg.editDate,
            sendingState = sendingState,
            readDate = readDate,
            reactions = reactions,
            isSenderVerified = isSenderVerified,
            threadId = threadId,
            replyCount = replyCount,
            canBeEdited = canEdit,
            canBeForwarded = canForward,
            canBeDeletedOnlyForSelf = true,
            canBeDeletedForAllUsers = msg.isOutgoing,
            canBeSaved = canSave,
            canGetMessageThread = msg.interactionInfo?.replyInfo != null,
            canGetStatistics = hasInteraction,
            canGetReadReceipts = hasInteraction,
            canGetViewers = hasInteraction,
            replyMarkup = if (isReply) null else msg.replyMarkup.toDomainReplyMarkup(),
            viaBotUserId = viaBotUserId,
            viaBotName = viaBotName,
            isSenderPremium = isSenderPremium,
            senderStatusEmojiId = senderStatusEmojiId,
            senderStatusEmojiPath = senderStatusEmojiPath
        )
    }

    fun mapToEntity(
        msg: TdApi.Message,
        getSenderName: ((Long) -> String?)? = null
    ): org.monogram.data.db.model.MessageEntity {
        return persistenceMapper.mapToEntity(msg, getSenderName)
    }

    internal fun extractCachedContent(content: TdApi.MessageContent): MessagePersistenceMapper.CachedMessageContent {
        return persistenceMapper.extractCachedContent(content)
    }

    fun mapEntityToModel(entity: org.monogram.data.db.model.MessageEntity): MessageModel {
        return persistenceMapper.mapEntityToModel(entity)
    }

    private suspend fun resolveReplyInfo(
        msg: TdApi.Message,
        isChatOpen: Boolean,
        isReply: Boolean
    ): Pair<Long?, MessageModel?> {
        if (isReply || msg.replyTo == null) return null to null
        val replyTo = msg.replyTo
        if (replyTo !is TdApi.MessageReplyToMessage) return null to null

        val replyToMsgId = replyTo.messageId
        val repliedMessage = try {
            withTimeout(500) {
                cache.getMessage(msg.chatId, replyToMsgId)
                    ?: gateway.execute(TdApi.GetMessage(msg.chatId, replyToMsgId)).also { cache.putMessage(it) }
            }
        } catch (_: Exception) {
            null
        }

        val replyToMsg = repliedMessage?.let {
            mapMessageToModel(
                msg = it,
                isChatOpen = isChatOpen,
                isReply = true
            ).copy(replyToMsg = null, replyToMsgId = null)
        }

        return replyToMsgId to replyToMsg
    }

    private suspend fun resolveForwardInfo(msg: TdApi.Message): ForwardInfo? {
        val fwd = msg.forwardInfo ?: return null
        val origin = fwd.origin
        var originName = unknownUserName
        var originPeerId = 0L
        var originChatId: Long? = null
        var originMessageId: Long? = null
        var originType = ForwardOriginType.UNKNOWN
        var avatarPath: String? = null
        var personalAvatarPath: String? = null

        when (origin) {
            is TdApi.MessageOriginUser -> {
                originType = ForwardOriginType.USER
                originPeerId = origin.senderUserId
                val user = try {
                    withTimeout(500) { userRepository.getUser(originPeerId) }
                } catch (_: Exception) {
                    null
                }

                if (user != null) {
                    avatarPath = user.avatarPath
                    personalAvatarPath = user.personalAvatarPath
                    val username = user.username?.takeIf { it.isNotBlank() }
                    val baseName = SenderNameResolver.fromPartsOrBlank(user.firstName, user.lastName)
                    originName = if (baseName.isNotBlank()) {
                        if (username != null) "$baseName (@$username)" else baseName
                    } else {
                        username?.let { "@$it" } ?: unknownUserName
                    }
                }
            }

            is TdApi.MessageOriginChat -> {
                originType = ForwardOriginType.CHAT
                originPeerId = origin.senderChatId
                val chat = try {
                    withTimeout(500) {
                        cache.getChat(originPeerId) ?: gateway.execute(TdApi.GetChat(originPeerId))
                            .also { cache.putChat(it) }
                    }
                } catch (_: Exception) {
                    null
                }
                if (chat != null) {
                    avatarPath = chat.photo?.small?.local?.path.takeIf(fileHelper::isValidPath)
                    originName = chat.title
                }
            }

            is TdApi.MessageOriginChannel -> {
                originType = ForwardOriginType.CHANNEL
                originPeerId = origin.chatId
                originChatId = origin.chatId
                originMessageId = origin.messageId
                val chat = try {
                    withTimeout(500) {
                        cache.getChat(originPeerId) ?: gateway.execute(TdApi.GetChat(originPeerId))
                            .also { cache.putChat(it) }
                    }
                } catch (_: Exception) {
                    null
                }
                if (chat != null) {
                    avatarPath = chat.photo?.small?.local?.path.takeIf(fileHelper::isValidPath)
                    originName = chat.title
                }
            }

            is TdApi.MessageOriginHiddenUser -> {
                originType = ForwardOriginType.HIDDEN_USER
                originName = origin.senderName
            }

            null -> Unit
        }

        return ForwardInfo(
            date = fwd.date,
            fromId = originPeerId,
            fromName = originName,
            originChatId = originChatId,
            originMessageId = originMessageId,
            originType = originType,
            avatarPath = avatarPath,
            personalAvatarPath = personalAvatarPath
        )
    }

    private suspend fun resolveReactions(
        msg: TdApi.Message,
        isReply: Boolean,
        isChatOpen: Boolean
    ): List<MessageReactionModel> {
        if (isReply) return emptyList()
        val reactionItems = msg.interactionInfo?.reactions?.reactions ?: return emptyList()

        return coroutineScope {
            reactionItems.map { reaction ->
                async {
                    val recentSenders = try {
                        withTimeout(1000) {
                            reaction.recentSenderIds.map { senderId ->
                                async {
                                    when (senderId) {
                                        is TdApi.MessageSenderUser -> {
                                            val user = try {
                                                withTimeout(500) { userRepository.getUser(senderId.userId) }
                                            } catch (_: Exception) {
                                                null
                                            }
                                            ReactionSender(
                                                id = senderId.userId,
                                                name = SenderNameResolver.fromPartsOrBlank(
                                                    firstName = user?.firstName,
                                                    lastName = user?.lastName
                                                ),
                                                avatar = user?.avatarPath.takeIf { fileHelper.isValidPath(it) }
                                            )
                                        }

                                        is TdApi.MessageSenderChat -> {
                                            val chat = try {
                                                withTimeout(500) {
                                                    cache.getChat(senderId.chatId)
                                                        ?: gateway.execute(TdApi.GetChat(senderId.chatId))
                                                            .also { cache.putChat(it) }
                                                }
                                            } catch (_: Exception) {
                                                null
                                            }
                                            ReactionSender(
                                                id = senderId.chatId,
                                                name = chat?.title ?: "",
                                                avatar = chat?.photo?.small?.local?.path.takeIf {
                                                    fileHelper.isValidPath(
                                                        it
                                                    )
                                                }
                                            )
                                        }

                                        else -> ReactionSender(0)
                                    }
                                }
                            }.awaitAll()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    when (val type = reaction.type) {
                        is TdApi.ReactionTypeEmoji -> {
                            MessageReactionModel(
                                emoji = type.emoji,
                                count = reaction.totalCount,
                                isChosen = reaction.isChosen,
                                recentSenders = recentSenders
                            )
                        }

                        is TdApi.ReactionTypeCustomEmoji -> {
                            val emojiId = type.customEmojiId
                            var path = customEmojiLoader.getPathIfValid(emojiId)
                            if (path == null) {
                                customEmojiLoader.loadIfNeeded(
                                    emojiId = emojiId,
                                    chatId = msg.chatId,
                                    messageId = msg.id,
                                    autoDownload = isChatOpen && fileHelper.isNetworkAutoDownloadEnabled()
                                )
                                path = customEmojiLoader.getPathIfValid(emojiId)
                            }

                            MessageReactionModel(
                                customEmojiId = emojiId,
                                customEmojiPath = path,
                                count = reaction.totalCount,
                                isChosen = reaction.isChosen,
                                recentSenders = recentSenders
                            )
                        }

                        else -> null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun resolveThreadId(msg: TdApi.Message): Long? {
        return when (val topic = msg.topicId) {
            is TdApi.MessageTopicForum -> topic.forumTopicId.toLong()
            is TdApi.MessageTopicThread -> topic.messageThreadId
            else -> null
        }
    }

    private suspend fun resolveViaBotName(msg: TdApi.Message): String? {
        if (msg.viaBotUserId == 0L) return null
        val bot = try {
            withTimeout(500) { userRepository.getUser(msg.viaBotUserId) }
        } catch (_: Exception) {
            null
        }
        return bot?.username ?: bot?.firstName
    }

    private fun resolveSendingState(msg: TdApi.Message): MessageSendingState? {
        return when (val state = msg.sendingState) {
            is TdApi.MessageSendingStatePending -> MessageSendingState.Pending
            is TdApi.MessageSendingStateFailed -> MessageSendingState.Failed(state.error.code, state.error.message)
            else -> null
        }
    }

    private fun mapMessageToModelFallback(
        msg: TdApi.Message,
        isChatOpen: Boolean,
        isReply: Boolean
    ): MessageModel {
        val sender = senderResolver.resolveFallbackSender(msg)
        return createMessageModel(
            msg = msg,
            senderName = sender.senderName,
            senderId = sender.senderId,
            senderAvatar = sender.senderAvatar,
            isChatOpen = isChatOpen,
            isReply = isReply,
            senderPersonalAvatar = sender.senderPersonalAvatar,
            senderCustomTitle = sender.senderCustomTitle,
            isSenderVerified = sender.isSenderVerified,
            isSenderPremium = sender.isSenderPremium,
            senderStatusEmojiId = sender.senderStatusEmojiId,
            senderStatusEmojiPath = sender.senderStatusEmojiPath
        )
    }

    private companion object {
        private const val MESSAGE_MAP_TIMEOUT_MS = 2500L
    }
}