package org.monogram.data.mapper

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import org.drinkless.tdlib.TdApi
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.remote.MessageFileApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.models.*
import org.monogram.domain.repository.SettingsRepository
import org.monogram.domain.repository.UserRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MessageMapper(
    private val connectivityManager: ConnectivityManager,
    private val gateway: TelegramGateway,
    private val userRepository: UserRepository,
    private val customEmojiPaths: ConcurrentHashMap<Long, String>,
    private val fileIdToCustomEmojiId: ConcurrentHashMap<Int, Long>,
    private val fileApi: MessageFileApi,
    private val settingsRepository: SettingsRepository,
    private val cache: ChatCache,
    scopeProvider: ScopeProvider
) {
    val scope = scopeProvider.appScope

    private fun getCurrentNetworkType(): TdApi.NetworkType {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        return when {
            capabilities == null -> TdApi.NetworkTypeNone()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (connectivityManager.isDefaultNetworkActive && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
                        .not()
                ) {
                    TdApi.NetworkTypeMobileRoaming()
                } else {
                    TdApi.NetworkTypeMobile()
                }
            }
            else -> TdApi.NetworkTypeNone()
        }
    }

    private fun isNetworkAutoDownloadEnabled(): Boolean {
        return when (getCurrentNetworkType()) {
            is TdApi.NetworkTypeWiFi -> settingsRepository.autoDownloadWifi.value
            is TdApi.NetworkTypeMobile -> settingsRepository.autoDownloadMobile.value
            is TdApi.NetworkTypeMobileRoaming -> settingsRepository.autoDownloadRoaming.value
            else -> settingsRepository.autoDownloadWifi.value
        }
    }

    private fun isValidPath(path: String?): Boolean {
        return !path.isNullOrEmpty() && File(path).exists()
    }

    private fun findBestAvailablePath(mainFile: TdApi.File?, sizes: Array<TdApi.PhotoSize>? = null): String? {
        if (mainFile != null && isValidPath(mainFile.local.path)) {
            return mainFile.local.path
        }

        if (sizes != null) {
            return sizes.sortedByDescending { it.width }
                .map { getUpdatedFile(it.photo) }
                .firstOrNull { isValidPath(it.local.path) }
                ?.local?.path
        }
        return null
    }

    suspend fun mapMessageToModel(
        msg: TdApi.Message,
        isChatOpen: Boolean = false,
        isReply: Boolean = false
    ): MessageModel = coroutineScope {
        var senderName = "User"
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
                val user = try {
                    withTimeout(500) { userRepository.getUser(senderId) }
                } catch (e: Exception) {
                    null
                }
                if (user != null) {
                    senderName = listOfNotNull(
                        user.firstName.takeIf { it.isNotBlank() },
                        user.lastName?.takeIf { it.isNotBlank() }
                    ).joinToString(" ")

                    if (senderName.isBlank()) senderName = "User"

                    senderAvatar = user.avatarPath.takeIf { isValidPath(it) }
                    senderPersonalAvatar = user.personalAvatarPath.takeIf { isValidPath(it) }
                    isSenderVerified = user.isVerified
                    isSenderPremium = user.isPremium
                    senderStatusEmojiId = user.statusEmojiId
                    senderStatusEmojiPath = user.statusEmojiPath
                }

                val chat = cache.getChat(msg.chatId)
                val canGetMember = when (chat?.type) {
                    is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> true
                    is TdApi.ChatTypeBasicGroup -> true
                    is TdApi.ChatTypeSupergroup -> {
                        val supergroup = (chat.type as TdApi.ChatTypeSupergroup)
                        val cachedSupergroup = cache.getSupergroup(supergroup.supergroupId)
                        !(cachedSupergroup?.isChannel ?: false) || (chat.permissions?.canSendBasicMessages ?: false)
                    }
                    else -> false
                }

                if (canGetMember) {
                    val member = try {
                        withTimeout(500) { userRepository.getChatMember(msg.chatId, senderId) }
                    } catch (e: Exception) {
                        null
                    }
                    senderCustomTitle = member?.rank
                }
            }

            is TdApi.MessageSenderChat -> {
                senderId = sender.chatId
                val chat = try {
                    withTimeout(500) {
                        cache.getChat(senderId) ?: gateway.execute(TdApi.GetChat(senderId)).also { cache.putChat(it) }
                    }
                } catch (e: Exception) {
                    null
                }
                if (chat != null) {
                    senderName = chat.title
                    val photo = chat.photo?.small
                    if (photo != null) {
                        senderAvatar = photo.local.path.takeIf { isValidPath(it) }
                        if (senderAvatar.isNullOrEmpty()) {
                            fileApi.enqueueDownload(
                                photo.id,
                                1,
                                TdMessageRemoteDataSource.DownloadType.DEFAULT,
                                0,
                                0,
                                false
                            )
                        }
                    }
                }
            }

            else -> senderId = 0L
        }

        var replyToMsgId: Long? = null
        var replyToMsg: MessageModel? = null

        if (!isReply && msg.replyTo != null) {
            val replyTo = msg.replyTo
            if (replyTo is TdApi.MessageReplyToMessage) {
                replyToMsgId = replyTo.messageId

                val repliedMessage = try {
                    withTimeout(500) {
                        cache.getMessage(msg.chatId, replyToMsgId)
                            ?: gateway.execute(TdApi.GetMessage(msg.chatId, replyToMsgId)).also { cache.putMessage(it) }
                    }
                } catch (e: Exception) {
                    null
                }
                if (repliedMessage != null) {
                    replyToMsg =
                        mapMessageToModel(
                            repliedMessage,
                            isChatOpen,
                            isReply = true
                        ).copy(replyToMsg = null, replyToMsgId = null)
                }
            }
        }

        var forwardInfo: ForwardInfo? = null
        if (msg.forwardInfo != null) {
            val fwd = msg.forwardInfo
            val origin = fwd?.origin
            var originName = "Unknown"
            var originPeerId = 0L
            var originChatId: Long? = null
            var originMessageId: Long? = null

            when (origin) {
                is TdApi.MessageOriginUser -> {
                    originPeerId = origin.senderUserId
                    val user = try {
                        withTimeout(500) { userRepository.getUser(originPeerId) }
                    } catch (e: Exception) {
                        null
                    }

                    if (user != null) {
                        val first = user.firstName.takeIf { it.isNotBlank() }
                        val last = user.lastName?.takeIf { it.isNotBlank() }
                        val username = user.username?.takeIf { it.isNotBlank() }

                        val baseName = listOfNotNull(first, last).joinToString(" ")

                        originName = if (baseName.isNotBlank()) {
                            if (username != null) "$baseName (@$username)" else baseName
                        } else {
                            username?.let { "@$it" } ?: "Unknown"
                        }
                    }
                }

                is TdApi.MessageOriginChat -> {
                    originPeerId = origin.senderChatId
                    val chat = try {
                        withTimeout(500) {
                            cache.getChat(originPeerId) ?: gateway.execute(TdApi.GetChat(originPeerId))
                                .also { cache.putChat(it) }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (chat != null) {
                        originName = chat.title
                    }
                }

                is TdApi.MessageOriginChannel -> {
                    originPeerId = origin.chatId
                    originChatId = origin.chatId
                    originMessageId = origin.messageId
                    val chat = try {
                        withTimeout(500) {
                            cache.getChat(originPeerId) ?: gateway.execute(TdApi.GetChat(originPeerId))
                                .also { cache.putChat(it) }
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (chat != null) {
                        originName = chat.title
                    }
                }

                is TdApi.MessageOriginHiddenUser -> {
                    originName = origin.senderName
                }
            }
            forwardInfo =
                ForwardInfo(fwd?.date ?: 0, originPeerId, originName, originChatId, originMessageId)
        }

        val views = msg.interactionInfo?.viewCount
        val replyCount = msg.interactionInfo?.replyInfo?.replyCount ?: 0

        val sendingState = when (val state = msg.sendingState) {
            is TdApi.MessageSendingStatePending -> MessageSendingState.Pending
            is TdApi.MessageSendingStateFailed -> MessageSendingState.Failed(
                state.error.code,
                state.error.message
            )

            else -> null
        }

        val reactions =
            if (isReply) emptyList() else msg.interactionInfo?.reactions?.reactions?.map { reaction ->
                async {
                    val recentSenders = try {
                        withTimeout(1000) {
                            reaction.recentSenderIds.map { senderId ->
                                async {
                                    when (senderId) {
                                        is TdApi.MessageSenderUser -> {
                                            val user = try {
                                                withTimeout(500) { userRepository.getUser(senderId.userId) }
                                            } catch (e: Exception) {
                                                null
                                            }
                                            ReactionSender(
                                                id = senderId.userId,
                                                name = listOfNotNull(
                                                    user?.firstName,
                                                    user?.lastName
                                                ).joinToString(" "),
                                                avatar = user?.avatarPath.takeIf { isValidPath(it) }
                                            )
                                        }

                                        is TdApi.MessageSenderChat -> {
                                            val chat = try {
                                                withTimeout(500) {
                                                    cache.getChat(senderId.chatId) ?: gateway.execute(
                                                        TdApi.GetChat(
                                                            senderId.chatId
                                                        )
                                                    ).also { cache.putChat(it) }
                                                }
                                            } catch (e: Exception) {
                                                null
                                            }
                                            ReactionSender(
                                                id = senderId.chatId,
                                                name = chat?.title ?: "",
                                                avatar = chat?.photo?.small?.local?.path.takeIf { isValidPath(it) }
                                            )
                                        }

                                        else -> ReactionSender(0)
                                    }
                                }
                            }.awaitAll()
                        }
                    } catch (e: Exception) {
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
                            val path = customEmojiPaths[emojiId].takeIf { isValidPath(it) }
                            if (path == null) {
                                loadCustomEmoji(
                                    emojiId,
                                    msg.chatId,
                                    msg.id,
                                    isChatOpen && isNetworkAutoDownloadEnabled()
                                )
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
            }?.awaitAll()?.filterNotNull() ?: emptyList()

        val threadId = when (val topic = msg.topicId) {
            is TdApi.MessageTopicForum -> topic.forumTopicId
            else -> null
        }

        var viaBotName: String? = null
        if (msg.viaBotUserId != 0L) {
            val bot = try {
                withTimeout(500) { userRepository.getUser(msg.viaBotUserId) }
            } catch (e: Exception) {
                null
            }
            viaBotName = bot?.username ?: bot?.firstName
        }

        createMessageModel(
            msg,
            senderName,
            senderId,
            senderAvatar,
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
            isSenderVerified = isSenderVerified,
            threadId = threadId,
            replyCount = replyCount,
            isReply = isReply,
            viaBotUserId = msg.viaBotUserId,
            viaBotName = viaBotName,
            senderPersonalAvatar = senderPersonalAvatar,
            senderCustomTitle = senderCustomTitle,
            isSenderPremium = isSenderPremium,
            senderStatusEmojiId = senderStatusEmojiId,
            senderStatusEmojiPath = senderStatusEmojiPath
        )
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
        } catch (e: Exception) {
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

    private fun getUpdatedFile(file: TdApi.File): TdApi.File {
        return cache.fileCache[file.id] ?: file
    }

    private fun mapEntities(
        entities: Array<TdApi.TextEntity>,
        chatId: Long,
        messageId: Long,
        networkAutoDownload: Boolean
    ): List<MessageEntity> {
        return entities.map { entity ->
            val type = when (val entityType = entity.type) {
                is TdApi.TextEntityTypeBold -> MessageEntityType.Bold
                is TdApi.TextEntityTypeItalic -> MessageEntityType.Italic
                is TdApi.TextEntityTypeUnderline -> MessageEntityType.Underline
                is TdApi.TextEntityTypeStrikethrough -> MessageEntityType.Strikethrough
                is TdApi.TextEntityTypeSpoiler -> MessageEntityType.Spoiler
                is TdApi.TextEntityTypeCode -> MessageEntityType.Code
                is TdApi.TextEntityTypePre -> MessageEntityType.Pre()
                is TdApi.TextEntityTypePreCode -> MessageEntityType.Pre(entityType.language)
                is TdApi.TextEntityTypeUrl -> MessageEntityType.Url
                is TdApi.TextEntityTypeTextUrl -> MessageEntityType.TextUrl(entityType.url)
                is TdApi.TextEntityTypeMention -> MessageEntityType.Mention
                is TdApi.TextEntityTypeMentionName -> MessageEntityType.Mention
                is TdApi.TextEntityTypeHashtag -> MessageEntityType.Hashtag
                is TdApi.TextEntityTypeBotCommand -> MessageEntityType.BotCommand
                is TdApi.TextEntityTypeEmailAddress -> MessageEntityType.Email
                is TdApi.TextEntityTypePhoneNumber -> MessageEntityType.PhoneNumber
                is TdApi.TextEntityTypeBankCardNumber -> MessageEntityType.BankCardNumber
                is TdApi.TextEntityTypeCustomEmoji -> {
                    val emojiId = entityType.customEmojiId
                    val path = customEmojiPaths[emojiId].takeIf { isValidPath(it) }
                    if (path == null) {
                        scope.launch {
                            loadCustomEmoji(emojiId, chatId, messageId, networkAutoDownload)
                        }
                    }
                    MessageEntityType.CustomEmoji(emojiId, path)
                }

                else -> MessageEntityType.Other
            }
            MessageEntity(entity.offset, entity.length, type)
        }
    }

    private fun mapWebPage(
        webPage: TdApi.LinkPreview?,
        chatId: Long,
        messageId: Long,
        networkAutoDownload: Boolean
    ): WebPage? {
        if (webPage == null) return null

        var photoObj: TdApi.Photo? = null
        var videoObj: TdApi.Video? = null
        var audioObj: TdApi.Audio? = null
        var documentObj: TdApi.Document? = null
        var stickerObj: TdApi.Sticker? = null
        var animationObj: TdApi.Animation? = null
        var duration = 0

        val linkPreviewType = when (val t = webPage.type) {
            is TdApi.LinkPreviewTypePhoto -> {
                photoObj = t.photo
                WebPage.LinkPreviewType.Photo
            }

            is TdApi.LinkPreviewTypeVideo -> {
                videoObj = t.video
                WebPage.LinkPreviewType.Video
            }

            is TdApi.LinkPreviewTypeAnimation -> {
                animationObj = t.animation
                WebPage.LinkPreviewType.Animation
            }

            is TdApi.LinkPreviewTypeAudio -> {
                audioObj = t.audio
                WebPage.LinkPreviewType.Audio
            }

            is TdApi.LinkPreviewTypeDocument -> {
                documentObj = t.document
                WebPage.LinkPreviewType.Document
            }

            is TdApi.LinkPreviewTypeSticker -> {
                stickerObj = t.sticker
                WebPage.LinkPreviewType.Sticker
            }

            is TdApi.LinkPreviewTypeVideoNote -> WebPage.LinkPreviewType.VideoNote
            is TdApi.LinkPreviewTypeVoiceNote -> WebPage.LinkPreviewType.VoiceNote
            is TdApi.LinkPreviewTypeAlbum -> WebPage.LinkPreviewType.Album
            is TdApi.LinkPreviewTypeArticle -> WebPage.LinkPreviewType.Article
            is TdApi.LinkPreviewTypeApp -> WebPage.LinkPreviewType.App
            is TdApi.LinkPreviewTypeExternalVideo -> {
                duration = t.duration
                WebPage.LinkPreviewType.ExternalVideo(t.url)
            }

            is TdApi.LinkPreviewTypeExternalAudio -> {
                duration = t.duration
                WebPage.LinkPreviewType.ExternalAudio(t.url)
            }

            is TdApi.LinkPreviewTypeEmbeddedVideoPlayer -> {
                duration = t.duration
                WebPage.LinkPreviewType.EmbeddedVideo(t.url)
            }

            is TdApi.LinkPreviewTypeEmbeddedAudioPlayer -> {
                duration = t.duration
                WebPage.LinkPreviewType.EmbeddedAudio(t.url)
            }

            is TdApi.LinkPreviewTypeEmbeddedAnimationPlayer -> {
                duration = t.duration
                WebPage.LinkPreviewType.EmbeddedAnimation(t.url)
            }
            is TdApi.LinkPreviewTypeUser -> WebPage.LinkPreviewType.User(0)
            is TdApi.LinkPreviewTypeChat -> WebPage.LinkPreviewType.Chat(0)
            is TdApi.LinkPreviewTypeStory -> WebPage.LinkPreviewType.Story(t.storyPosterChatId, t.storyId)
            is TdApi.LinkPreviewTypeTheme -> WebPage.LinkPreviewType.Theme
            is TdApi.LinkPreviewTypeBackground -> WebPage.LinkPreviewType.Background
            is TdApi.LinkPreviewTypeInvoice -> WebPage.LinkPreviewType.Invoice
            is TdApi.LinkPreviewTypeMessage -> WebPage.LinkPreviewType.Message
            else -> WebPage.LinkPreviewType.Unknown
        }

        fun processTdFile(
            file: TdApi.File,
            downloadType: TdMessageRemoteDataSource.DownloadType,
            supportsStreaming: Boolean = false
        ): TdApi.File {
            val updatedFile = getUpdatedFile(file)
            fileApi.registerFileForMessage(updatedFile.id, chatId, messageId)

            val autoDownload = when (downloadType) {
                TdMessageRemoteDataSource.DownloadType.VIDEO -> supportsStreaming && networkAutoDownload
                TdMessageRemoteDataSource.DownloadType.DEFAULT -> {
                    if (linkPreviewType == WebPage.LinkPreviewType.Document) false else networkAutoDownload
                }
                TdMessageRemoteDataSource.DownloadType.STICKER -> networkAutoDownload && settingsRepository.autoDownloadStickers.value
                TdMessageRemoteDataSource.DownloadType.VIDEO_NOTE -> networkAutoDownload && settingsRepository.autoDownloadVideoNotes.value
                else -> networkAutoDownload
            }

            if (!isValidPath(updatedFile.local.path) && autoDownload) {
                fileApi.enqueueDownload(updatedFile.id, 1, downloadType, 0, 0, false)
            }
            return updatedFile
        }

        val photo = photoObj?.let { p ->
            val size = p.sizes.firstOrNull()
            if (size != null) {
                val f = processTdFile(size.photo, TdMessageRemoteDataSource.DownloadType.DEFAULT)
                val bestPath = findBestAvailablePath(f, p.sizes)

                WebPage.Photo(
                    path = bestPath,
                    width = size.width,
                    height = size.height,
                    fileId = f.id,
                    minithumbnail = p.minithumbnail?.data
                )
            } else null
        }

        val video = videoObj?.let { v ->
            val f = processTdFile(v.video, TdMessageRemoteDataSource.DownloadType.VIDEO, v.supportsStreaming)
            WebPage.Video(f.local.path.takeIf { isValidPath(it) }, v.width, v.height, v.duration, f.id, v.supportsStreaming)
        }

        val audio = audioObj?.let { a ->
            val f = processTdFile(a.audio, TdMessageRemoteDataSource.DownloadType.DEFAULT)
            WebPage.Audio(a.audio.local.path.takeIf { isValidPath(it) }, a.duration, a.title, a.performer, f.id)
        }

        val document = documentObj?.let { d ->
            val f = processTdFile(d.document, TdMessageRemoteDataSource.DownloadType.DEFAULT)
            WebPage.Document(d.document.local.path.takeIf { isValidPath(it) }, d.fileName, d.mimeType, f.size, f.id)
        }

        val sticker = stickerObj?.let { s ->
            val f = processTdFile(s.sticker, TdMessageRemoteDataSource.DownloadType.STICKER)
            WebPage.Sticker(s.sticker.local.path.takeIf { isValidPath(it) }, s.width, s.height, s.emoji, f.id)
        }

        val animation = animationObj?.let { anim ->
            val f = processTdFile(anim.animation, TdMessageRemoteDataSource.DownloadType.GIF)
            WebPage.Animation(anim.animation.local.path.takeIf { isValidPath(it) }, anim.width, anim.height, anim.duration, f.id)
        }

        return WebPage(
            url = webPage.url,
            displayUrl = webPage.displayUrl,
            type = linkPreviewType,
            siteName = webPage.siteName,
            title = webPage.title,
            description = webPage.description?.text,
            photo = photo,
            embedUrl = null,
            embedType = null,
            embedWidth = 0,
            embedHeight = 0,
            duration = duration,
            author = webPage.author,
            video = video,
            audio = audio,
            document = document,
            sticker = sticker,
            animation = animation,
            instantViewVersion = webPage.instantViewVersion
        )
    }

    private fun mapReplyMarkup(markup: TdApi.ReplyMarkup?): ReplyMarkupModel? {
        return when (markup) {
            is TdApi.ReplyMarkupInlineKeyboard -> {
                ReplyMarkupModel.InlineKeyboard(
                    rows = markup.rows.map { row ->
                        row.map { button ->
                            InlineKeyboardButtonModel(
                                text = button.text,
                                type = when (val type = button.type) {
                                    is TdApi.InlineKeyboardButtonTypeUrl -> InlineKeyboardButtonType.Url(
                                        type.url
                                    )

                                    is TdApi.InlineKeyboardButtonTypeCallback -> InlineKeyboardButtonType.Callback(
                                        type.data
                                    )

                                    is TdApi.InlineKeyboardButtonTypeWebApp -> InlineKeyboardButtonType.WebApp(
                                        type.url
                                    )

                                    is TdApi.InlineKeyboardButtonTypeLoginUrl -> InlineKeyboardButtonType.LoginUrl(
                                        type.url,
                                        type.id
                                    )

                                    is TdApi.InlineKeyboardButtonTypeSwitchInline -> InlineKeyboardButtonType.SwitchInline(
                                        query = type.query
                                    )

                                    is TdApi.InlineKeyboardButtonTypeBuy -> InlineKeyboardButtonType.Buy()
                                    is TdApi.InlineKeyboardButtonTypeUser -> InlineKeyboardButtonType.User(
                                        type.userId
                                    )

                                    else -> InlineKeyboardButtonType.Unsupported
                                }
                            )
                        }
                    }
                )
            }

            is TdApi.ReplyMarkupShowKeyboard -> {
                ReplyMarkupModel.ShowKeyboard(
                    rows = markup.rows.map { row ->
                        row.map { button ->
                            KeyboardButtonModel(
                                text = button.text,
                                type = when (val type = button.type) {
                                    is TdApi.KeyboardButtonTypeText -> KeyboardButtonType.Text
                                    is TdApi.KeyboardButtonTypeRequestPhoneNumber -> KeyboardButtonType.RequestPhoneNumber
                                    is TdApi.KeyboardButtonTypeRequestLocation -> KeyboardButtonType.RequestLocation
                                    is TdApi.KeyboardButtonTypeRequestPoll -> KeyboardButtonType.RequestPoll(
                                        type.forceQuiz,
                                        type.forceRegular
                                    )

                                    is TdApi.KeyboardButtonTypeWebApp -> KeyboardButtonType.WebApp(
                                        type.url
                                    )

                                    is TdApi.KeyboardButtonTypeRequestUsers -> KeyboardButtonType.RequestUsers(
                                        type.id
                                    )

                                    is TdApi.KeyboardButtonTypeRequestChat -> KeyboardButtonType.RequestChat(
                                        type.id
                                    )

                                    else -> KeyboardButtonType.Unsupported
                                }
                            )
                        }
                    },
                    isPersistent = markup.isPersistent,
                    resizeKeyboard = markup.resizeKeyboard,
                    oneTime = markup.oneTime,
                    isPersonal = markup.isPersonal,
                    inputFieldPlaceholder = markup.inputFieldPlaceholder
                )
            }

            is TdApi.ReplyMarkupRemoveKeyboard -> ReplyMarkupModel.RemoveKeyboard(markup.isPersonal)
            is TdApi.ReplyMarkupForceReply -> ReplyMarkupModel.ForceReply(
                markup.isPersonal,
                markup.inputFieldPlaceholder
            )

            else -> null
        }
    }

    fun createMessageModel(
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
        threadId: Int? = null,
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
        val networkAutoDownload = isChatOpen && isNetworkAutoDownloadEnabled()
        val isActuallyUploading = msg.sendingState is TdApi.MessageSendingStatePending

        val content = when (val c = msg.content) {
            is TdApi.MessageText -> {
                val entities = mapEntities(c.text.entities, msg.chatId, msg.id, networkAutoDownload)
                val webPage = mapWebPage(c.linkPreview, msg.chatId, msg.id, networkAutoDownload)
                MessageContent.Text(c.text.text, entities, webPage)
            }

            is TdApi.MessagePhoto -> {

                val sizes = c.photo.sizes
                val photoSize = sizes.find { it.type == "x" }
                    ?: sizes.find { it.type == "m" }
                    ?: sizes.getOrNull(sizes.size / 2)
                    ?: sizes.lastOrNull()

                val photoFile = photoSize?.photo?.let { getUpdatedFile(it) }

                val path = findBestAvailablePath(photoFile, sizes)

                if (photoFile != null) {
                    fileApi.registerFileForMessage(photoFile.id, msg.chatId, msg.id)
                    if (path == null && networkAutoDownload) {
                        fileApi.enqueueDownload(photoFile.id, 1, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                    }
                }
                val isDownloading = photoFile?.local?.isDownloadingActive ?: false
                val isQueued = photoFile?.let { fileApi.isFileQueued(it.id) } ?: false
                val downloadProgress = if ((photoFile?.size ?: 0) > 0) {
                    photoFile!!.local.downloadedSize.toFloat() / photoFile.size.toFloat()
                } else 0f

                MessageContent.Photo(
                    path = path,
                    width = photoSize?.width ?: 0,
                    height = photoSize?.height ?: 0,
                    caption = c.caption.text,
                    entities = mapEntities(c.caption.entities, msg.chatId, msg.id, networkAutoDownload),
                    isUploading = isActuallyUploading && (photoFile?.remote?.isUploadingActive ?: false),
                    uploadProgress = if ((photoFile?.size ?: 0) > 0) photoFile!!.remote.uploadedSize.toFloat() / photoFile.size.toFloat() else 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = photoFile?.id ?: 0,
                    minithumbnail = c.photo.minithumbnail?.data
                )
            }

            is TdApi.MessageVideo -> {
                val video = c.video
                val videoFile = getUpdatedFile(video.video)
                val path = videoFile.local.path.takeIf { isValidPath(it) }
                fileApi.registerFileForMessage(videoFile.id, msg.chatId, msg.id)

                val thumbFile = video.thumbnail?.file?.let { getUpdatedFile(it) }

                if (thumbFile != null) {
                    fileApi.registerFileForMessage(thumbFile.id, msg.chatId, msg.id)
                    if (!isValidPath(thumbFile.local.path) && networkAutoDownload) {
                        fileApi.enqueueDownload(thumbFile.id, 1, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                    }
                }

                if (path == null && networkAutoDownload && video.supportsStreaming) {
                    fileApi.enqueueDownload(videoFile.id, 1, TdMessageRemoteDataSource.DownloadType.VIDEO, 0, 0, false)
                }

                val isDownloading = videoFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(videoFile.id)
                val downloadProgress = if (videoFile.size > 0) {
                    videoFile.local.downloadedSize.toFloat() / videoFile.size.toFloat()
                } else 0f

                MessageContent.Video(
                    path = path,
                    width = video.width,
                    height = video.height,
                    duration = video.duration,
                    caption = c.caption.text,
                    entities = mapEntities(c.caption.entities, msg.chatId, msg.id, networkAutoDownload),
                    isUploading = isActuallyUploading && videoFile.remote.isUploadingActive,
                    uploadProgress = if (videoFile.size > 0) videoFile.remote.uploadedSize.toFloat() / videoFile.size.toFloat() else 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = videoFile.id,
                    minithumbnail = video.minithumbnail?.data,
                    supportsStreaming = video.supportsStreaming
                )
            }

            is TdApi.MessageVoiceNote -> {
                val voice = c.voiceNote
                val voiceFile = getUpdatedFile(voice.voice)
                val path = voiceFile.local.path.takeIf { isValidPath(it) }
                fileApi.registerFileForMessage(voiceFile.id, msg.chatId, msg.id)
                if (path == null && networkAutoDownload) {
                    fileApi.enqueueDownload(voiceFile.id, 1, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                }
                val isDownloading = voiceFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(voiceFile.id)
                val downloadProgress = if (voiceFile.size > 0) {
                    voiceFile.local.downloadedSize.toFloat() / voiceFile.size.toFloat()
                } else 0f

                MessageContent.Voice(
                    path = path,
                    duration = voice.duration,
                    waveform = voice.waveform,
                    isUploading = isActuallyUploading && voiceFile.remote.isUploadingActive,
                    uploadProgress = if (voiceFile.size > 0) voiceFile.remote.uploadedSize.toFloat() / voiceFile.size.toFloat() else 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = voiceFile.id
                )
            }

            is TdApi.MessageVideoNote -> {
                val note = c.videoNote
                val videoFile = getUpdatedFile(note.video)
                val videoPath = videoFile.local.path.takeIf { isValidPath(it) }
                fileApi.registerFileForMessage(videoFile.id, msg.chatId, msg.id)

                if (videoPath == null && networkAutoDownload && settingsRepository.autoDownloadVideoNotes.value) {
                    fileApi.enqueueDownload(videoFile.id, 1, TdMessageRemoteDataSource.DownloadType.VIDEO_NOTE, 0, 0, false)
                }

                val thumbFile = note.thumbnail?.file?.let { getUpdatedFile(it) }
                val thumbPath = thumbFile?.local?.path?.takeIf { isValidPath(it) }
                if (thumbFile != null) {
                    fileApi.registerFileForMessage(thumbFile.id, msg.chatId, msg.id)
                    if (thumbPath == null && networkAutoDownload) {
                        fileApi.enqueueDownload(thumbFile.id, 1, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                    }
                }
                val isUploading = isActuallyUploading && videoFile.remote.isUploadingActive
                val progress = if (videoFile.size > 0) {
                    videoFile.remote.uploadedSize.toFloat() / videoFile.size.toFloat()
                } else 0f

                val isDownloading = videoFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(videoFile.id)
                val downloadProgress = if (videoFile.size > 0) {
                    videoFile.local.downloadedSize.toFloat() / videoFile.size.toFloat()
                } else 0f

                MessageContent.VideoNote(
                    path = videoPath,
                    thumbnail = thumbPath,
                    duration = note.duration,
                    length = note.length,
                    isUploading = isUploading,
                    uploadProgress = progress,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = videoFile.id
                )
            }

            is TdApi.MessageSticker -> {
                val sticker = c.sticker
                val stickerFile = getUpdatedFile(sticker.sticker)
                val path = stickerFile.local.path.takeIf { isValidPath(it) }

                fileApi.registerFileForMessage(stickerFile.id, msg.chatId, msg.id)
                if (path == null && networkAutoDownload && settingsRepository.autoDownloadStickers.value) {
                    fileApi.enqueueDownload(stickerFile.id, 1, TdMessageRemoteDataSource.DownloadType.STICKER, 0, 0, false)
                }

                val format = when (sticker.format) {
                    is TdApi.StickerFormatWebp -> StickerFormat.STATIC
                    is TdApi.StickerFormatTgs -> StickerFormat.ANIMATED
                    is TdApi.StickerFormatWebm -> StickerFormat.VIDEO
                    else -> StickerFormat.UNKNOWN
                }

                val isDownloading = stickerFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(stickerFile.id)
                val downloadProgress = if (stickerFile.size > 0) {
                    stickerFile.local.downloadedSize.toFloat() / stickerFile.size.toFloat()
                } else 0f

                MessageContent.Sticker(
                    id = sticker.sticker.id.toLong(),
                    setId = sticker.setId,
                    path = path,
                    width = sticker.width,
                    height = sticker.height,
                    emoji = sticker.emoji,
                    format = format,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = stickerFile.id
                )
            }

            is TdApi.MessageAnimation -> {
                val animation = c.animation
                val animationFile = getUpdatedFile(animation.animation)
                val path = animationFile.local.path.takeIf { isValidPath(it) }
                fileApi.registerFileForMessage(animationFile.id, msg.chatId, msg.id)
                if (path == null && networkAutoDownload) {
                    fileApi.enqueueDownload(animationFile.id, 1, TdMessageRemoteDataSource.DownloadType.GIF, 0, 0, false)
                }

                val thumbFile = animation.thumbnail?.file?.let { getUpdatedFile(it) }
                if (thumbFile != null) {
                    fileApi.registerFileForMessage(thumbFile.id, msg.chatId, msg.id)
                    if (!isValidPath(thumbFile.local.path) && networkAutoDownload) {
                        fileApi.enqueueDownload(thumbFile.id, 1, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                    }
                }

                val isDownloading = animationFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(animationFile.id)
                val downloadProgress = if (animationFile.size > 0) {
                    animationFile.local.downloadedSize.toFloat() / animationFile.size.toFloat()
                } else 0f

                MessageContent.Gif(
                    path = path,
                    width = animation.width,
                    height = animation.height,
                    caption = c.caption.text,
                    entities = mapEntities(c.caption.entities, msg.chatId, msg.id, networkAutoDownload),
                    isUploading = isActuallyUploading && animationFile.remote.isUploadingActive,
                    uploadProgress = if (animationFile.size > 0) animationFile.remote.uploadedSize.toFloat() / animationFile.size.toFloat() else 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = animationFile.id,
                    minithumbnail = animation.minithumbnail?.data
                )
            }

            is TdApi.MessageAnimatedEmoji -> MessageContent.Text(c.emoji)
            is TdApi.MessageDice -> {
                val valueStr = if (c.value != 0) " (Result: ${c.value})" else ""
                MessageContent.Text("${c.emoji}$valueStr")
            }

            is TdApi.MessageDocument -> {
                val doc = c.document
                val docFile = getUpdatedFile(doc.document)
                val path = docFile.local.path.takeIf { isValidPath(it) }
                fileApi.registerFileForMessage(docFile.id, msg.chatId, msg.id)

                val thumbFile = doc.thumbnail?.file?.let { getUpdatedFile(it) }
                if (thumbFile != null) {
                    fileApi.registerFileForMessage(thumbFile.id, msg.chatId, msg.id)
                    if (!isValidPath(thumbFile.local.path) && networkAutoDownload) {
                        fileApi.enqueueDownload(thumbFile.id, 1, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                    }
                }

                val isDownloading = docFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(docFile.id)
                val downloadProgress = if (docFile.size > 0) {
                    docFile.local.downloadedSize.toFloat() / docFile.size.toFloat()
                } else 0f

                MessageContent.Document(
                    path = path,
                    fileName = doc.fileName,
                    mimeType = doc.mimeType,
                    size = docFile.size,
                    caption = c.caption.text,
                    entities = mapEntities(c.caption.entities, msg.chatId, msg.id, networkAutoDownload),
                    isUploading = isActuallyUploading && docFile.remote.isUploadingActive,
                    uploadProgress = if (docFile.size > 0) docFile.remote.uploadedSize.toFloat() / docFile.size.toFloat() else 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = docFile.id
                )
            }

            is TdApi.MessageAudio -> {
                val audio = c.audio
                val audioFile = getUpdatedFile(audio.audio)
                val path = audioFile.local.path.takeIf { isValidPath(it) }
                fileApi.registerFileForMessage(audioFile.id, msg.chatId, msg.id)

                if (path == null && networkAutoDownload) {
                    fileApi.enqueueDownload(
                        audioFile.id,
                        1,
                        TdMessageRemoteDataSource.DownloadType.DEFAULT,
                        0,
                        0,
                        false
                    )
                }

                val isDownloading = audioFile.local.isDownloadingActive
                val isQueued = fileApi.isFileQueued(audioFile.id)
                val downloadProgress = if (audioFile.size > 0) {
                    audioFile.local.downloadedSize.toFloat() / audioFile.size.toFloat()
                } else 0f

                MessageContent.Audio(
                    path = path,
                    duration = audio.duration,
                    title = audio.title ?: "Unknown",
                    performer = audio.performer ?: "Unknown",
                    fileName = audio.fileName ?: "audio.mp3",
                    mimeType = audio.mimeType ?: "audio/mpeg",
                    size = audioFile.size,
                    caption = c.caption.text,
                    entities = mapEntities(c.caption.entities, msg.chatId, msg.id, networkAutoDownload),
                    isUploading = isActuallyUploading && audioFile.remote.isUploadingActive,
                    uploadProgress = if (audioFile.size > 0) audioFile.remote.uploadedSize.toFloat() / audioFile.size.toFloat() else 0f,
                    isDownloading = isDownloading || isQueued,
                    downloadProgress = downloadProgress,
                    fileId = audioFile.id
                )
            }

            is TdApi.MessageCall -> MessageContent.Text("📞 Call (${c.duration}s)")
            is TdApi.MessageContact -> {
                val contact = c.contact
                MessageContent.Contact(
                    phoneNumber = contact.phoneNumber,
                    firstName = contact.firstName,
                    lastName = contact.lastName,
                    vcard = contact.vcard,
                    userId = contact.userId
                )
            }
            is TdApi.MessageLocation -> {
                val loc = c.location
                MessageContent.Location(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    horizontalAccuracy = loc.horizontalAccuracy,
                    livePeriod = c.livePeriod,
                    heading = c.heading,
                    proximityAlertRadius = c.proximityAlertRadius
                )
            }

            is TdApi.MessageVenue -> {
                val v = c.venue
                MessageContent.Venue(
                    latitude = v.location.latitude,
                    longitude = v.location.longitude,
                    title = v.title,
                    address = v.address,
                    provider = v.provider,
                    venueId = v.id,
                    venueType = v.type
                )
            }
            is TdApi.MessagePoll -> {
                val poll = c.poll
                val type = when (val pollType = poll.type) {
                    is TdApi.PollTypeRegular -> PollType.Regular(pollType.allowMultipleAnswers)
                    is TdApi.PollTypeQuiz -> PollType.Quiz(pollType.correctOptionId, pollType.explanation?.text)
                    else -> PollType.Regular(false)
                }
                MessageContent.Poll(
                    id = poll.id,
                    question = poll.question.text,
                    options = poll.options.map { option ->
                        PollOption(
                            text = option.text.text,
                            voterCount = option.voterCount,
                            votePercentage = option.votePercentage,
                            isChosen = option.isChosen,
                            isBeingChosen = false
                        )
                    },
                    totalVoterCount = poll.totalVoterCount,
                    isClosed = poll.isClosed,
                    isAnonymous = poll.isAnonymous,
                    type = type,
                    openPeriod = poll.openPeriod,
                    closeDate = poll.closeDate
                )
            }
            is TdApi.MessageGame -> MessageContent.Text("🎮 Game: ${c.game.title}")
            is TdApi.MessageInvoice -> {
                val productInfo = c.productInfo
                MessageContent.Text("💳 Invoice: ${productInfo.title}")
            }
            is TdApi.MessageStory -> MessageContent.Text("📖 Story")
            is TdApi.MessageExpiredPhoto -> MessageContent.Text("📷 Photo has expired")
            is TdApi.MessageExpiredVideo -> MessageContent.Text("📹 Video has expired")

            is TdApi.MessageChatJoinByLink -> MessageContent.Service("$senderName has joined the group via invite link")
            is TdApi.MessageChatAddMembers -> MessageContent.Service("$senderName added members")
            is TdApi.MessageChatDeleteMember -> MessageContent.Service("$senderName  left the chat")
            is TdApi.MessagePinMessage -> MessageContent.Service("$senderName pinned a message")
            is TdApi.MessageChatChangeTitle -> MessageContent.Service("$senderName changed group name to \"${c.title}\"")
            is TdApi.MessageChatChangePhoto -> MessageContent.Service("$senderName changed group photo")
            is TdApi.MessageChatDeletePhoto -> MessageContent.Service("$senderName removed group photo")
            is TdApi.MessageScreenshotTaken -> MessageContent.Service("$senderName took a screenshot")
            is TdApi.MessageContactRegistered -> MessageContent.Service("$senderName joined Telegram!")
            is TdApi.MessageChatUpgradeTo -> MessageContent.Service("$senderName upgraded to supergroup")
            is TdApi.MessageChatUpgradeFrom -> MessageContent.Service("group created")
            is TdApi.MessageBasicGroupChatCreate -> MessageContent.Service("created the group \"${c.title}\"")
            is TdApi.MessageSupergroupChatCreate -> MessageContent.Service("created the supergroup \"${c.title}\"")
            is TdApi.MessagePaymentSuccessful -> MessageContent.Service("Payment successful: ${c.currency} ${c.totalAmount}")
            is TdApi.MessagePaymentSuccessfulBot -> MessageContent.Service("Payment successful")
            is TdApi.MessagePassportDataSent -> MessageContent.Service("Passport data sent")
            is TdApi.MessagePassportDataReceived -> MessageContent.Service("Passport data received")
            is TdApi.MessageProximityAlertTriggered -> MessageContent.Service("is within ${c.distance}m")
            is TdApi.MessageForumTopicCreated -> MessageContent.Service("$senderName created topic \"${c.name}\"")
            is TdApi.MessageForumTopicEdited -> MessageContent.Service("$senderName edited topic")
            is TdApi.MessageForumTopicIsClosedToggled -> MessageContent.Service("$senderName toggled topic closed status")
            is TdApi.MessageForumTopicIsHiddenToggled -> MessageContent.Service("$senderName toggled topic hidden status")
            is TdApi.MessageSuggestProfilePhoto -> MessageContent.Service("$senderName suggested a profile photo")
            is TdApi.MessageCustomServiceAction -> MessageContent.Service(c.text)
            is TdApi.MessageChatBoost -> MessageContent.Service("Chat boost: ${c.boostCount}")
            is TdApi.MessageChatSetTheme -> MessageContent.Service("Chat theme changed to ${c.theme}")
            is TdApi.MessageGameScore -> MessageContent.Service("Game score: ${c.score}")
            is TdApi.MessageVideoChatScheduled -> MessageContent.Service("Video chat scheduled for ${c.startDate}")
            is TdApi.MessageVideoChatStarted -> MessageContent.Service("Video chat started")
            is TdApi.MessageVideoChatEnded -> MessageContent.Service("Video chat ended")
            is TdApi.MessageChatSetBackground -> MessageContent.Service("Chat background changed")
            else -> MessageContent.Text("ℹ️ Unsupported message type: ${c.javaClass.simpleName}")
        }

        val isServiceMessage = content is MessageContent.Service

        val canEdit = msg.isOutgoing && !isServiceMessage
        val canForward = !isServiceMessage
        val canSave = !isServiceMessage

        val hasInteraction = msg.interactionInfo != null

        return MessageModel(
            id = msg.id,
            date = msg.date,
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
            viewCount = views,
            mediaAlbumId = msg.mediaAlbumId,
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
            replyMarkup = if (isReply) null else mapReplyMarkup(msg.replyMarkup),
            viaBotUserId = viaBotUserId,
            viaBotName = viaBotName,
            isSenderPremium = isSenderPremium,
            senderStatusEmojiId = senderStatusEmojiId,
            senderStatusEmojiPath = senderStatusEmojiPath
        )
    }

    fun mapToEntity(msg: TdApi.Message): org.monogram.data.db.model.MessageEntity {
        return org.monogram.data.db.model.MessageEntity(
            id = msg.id,
            chatId = msg.chatId,
            senderId = when (val sender = msg.senderId) {
                is TdApi.MessageSenderUser -> sender.userId
                is TdApi.MessageSenderChat -> sender.chatId
                else -> 0L
            },
            content = (msg.content as? TdApi.MessageText)?.text?.text ?: "",
            date = msg.date,
            isOutgoing = msg.isOutgoing,
            isRead = false,
            createdAt = System.currentTimeMillis()
        )
    }

    private suspend fun loadCustomEmoji(emojiId: Long, chatId: Long, messageId: Long, autoDownload: Boolean) {
        val result = gateway.execute(TdApi.GetCustomEmojiStickers(longArrayOf(emojiId)))

        if (result is TdApi.Stickers && result.stickers.isNotEmpty()) {
            val fileToUse = result.stickers.first().sticker

            fileIdToCustomEmojiId[fileToUse.id] = emojiId
            fileApi.registerFileForMessage(fileToUse.id, chatId, messageId)

            if (!isValidPath(fileToUse.local.path)) {
                if (autoDownload) {
                    fileApi.enqueueDownload(fileToUse.id, 32, TdMessageRemoteDataSource.DownloadType.DEFAULT, 0, 0, false)
                }
            } else {
                customEmojiPaths[emojiId] = fileToUse.local.path
            }
        }
    }
}