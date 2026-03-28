package org.monogram.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.datasource.FileDataSource
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.datasource.remote.MessageRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileUpdateHandler
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.map
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.*
import org.monogram.domain.models.webapp.InstantViewModel
import org.monogram.domain.models.webapp.InvoiceModel
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel
import org.monogram.domain.repository.*
import java.io.File

class MessageRepositoryImpl(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val messageMapper: MessageMapper,
    private val messageRemoteDataSource: MessageRemoteDataSource,
    private val cache: ChatCache,
    private val fileDataSource: FileDataSource,
    private val dispatcherProvider: DispatcherProvider,
    scopeProvider: ScopeProvider,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val userLocalDataSource: UserLocalDataSource,
    private val fileUpdateHandler: FileUpdateHandler
) : MessageRepository {
    private val scope = scopeProvider.appScope

    override val newMessageFlow = messageRemoteDataSource.newMessageFlow
    override val senderUpdateFlow = messageMapper.senderUpdateFlow
    override val messageEditedFlow = messageRemoteDataSource.messageEditedFlow
    override val messageUploadProgressFlow = messageRemoteDataSource.messageUploadProgressFlow
    override val messageDownloadProgressFlow = messageRemoteDataSource.messageDownloadProgressFlow
    override val messageDownloadCancelledFlow = messageRemoteDataSource.messageDownloadCancelledFlow
    override val messageReadFlow = messageRemoteDataSource.messageReadFlow
    override val messageDownloadCompletedFlow = messageRemoteDataSource.messageDownloadCompletedFlow
    override val messageDeletedFlow = messageRemoteDataSource.messageDeletedFlow
    override val messageIdUpdateFlow = messageRemoteDataSource.messageIdUpdateFlow
    override val pinnedMessageFlow = messageRemoteDataSource.pinnedMessageFlow
    override val mediaUpdateFlow = messageRemoteDataSource.mediaUpdateFlow

    init {
        scope.launch {
            try {
                gateway.updates.collect { update ->
                    messageRemoteDataSource.handleUpdate(update)
                    when (update) {
                        is TdApi.UpdateNewMessage -> {
                            val entity = messageMapper.mapToEntity(update.message, ::resolveSenderName)
                            chatLocalDataSource.insertMessage(entity)
                        }

                        is TdApi.UpdateMessageContent -> {
                            val extracted = messageMapper.extractCachedContent(update.newContent)
                            chatLocalDataSource.updateMessageContent(
                                messageId = update.messageId,
                                content = extracted.text,
                                contentType = extracted.type,
                                contentMeta = extracted.meta,
                                mediaFileId = extracted.fileId,
                                mediaPath = extracted.path,
                                editDate = 0
                            )
                        }

                        is TdApi.UpdateMessageEdited -> {
                            val updated = messageRemoteDataSource.getMessage(update.chatId, update.messageId)
                            if (updated != null) {
                                chatLocalDataSource.insertMessage(
                                    messageMapper.mapToEntity(
                                        updated,
                                        ::resolveSenderName
                                    )
                                )
                            }
                        }

                        is TdApi.UpdateMessageInteractionInfo -> {
                            chatLocalDataSource.updateInteractionInfo(
                                messageId = update.messageId,
                                viewCount = update.interactionInfo?.viewCount ?: 0,
                                forwardCount = update.interactionInfo?.forwardCount ?: 0,
                                replyCount = update.interactionInfo?.replyInfo?.replyCount ?: 0
                            )
                        }

                        is TdApi.UpdateChatReadInbox -> {
                            chatLocalDataSource.markAsRead(update.chatId, update.lastReadInboxMessageId)
                        }

                        is TdApi.UpdateDeleteMessages -> {
                            if (update.isPermanent) {
                                update.messageIds.forEach { messageId ->
                                    chatLocalDataSource.deleteMessage(messageId)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TdLibUpdates", "CRITICAL: Update loop died", e)
            }
        }

        scope.launch(dispatcherProvider.io) {
            val ninetyDaysAgo = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
            chatLocalDataSource.deleteExpired(ninetyDaysAgo)
        }

        scope.launch {
            fileUpdateHandler.fileDownloadCompleted.collect { (fileIdLong, path) ->
                val fileId = fileIdLong.toInt()
                if (fileId != 0 && path.isNotBlank()) {
                    chatLocalDataSource.updateMediaPath(fileId, path)
                }
            }
        }
    }

    override suspend fun openChat(chatId: Long) {
        messageRemoteDataSource.setChatOpened(chatId)
        try {
            gateway.execute(TdApi.OpenChat(chatId))
        } catch (e: Exception) {
            Log.e("MessageRepository", "Error opening chat $chatId", e)
            if (chatId > 0) {
                try {
                    gateway.execute(TdApi.CreatePrivateChat(chatId, false))
                    gateway.execute(TdApi.OpenChat(chatId))
                } catch (e2: Exception) {
                    Log.e("MessageRepository", "Failed to create and open private chat $chatId", e2)
                }
            }
        }
    }

    override suspend fun closeChat(chatId: Long) {
        messageRemoteDataSource.setChatClosed(chatId)
        try {
            gateway.execute(TdApi.CloseChat(chatId))
        } catch (e: Exception) {
            Log.e("MessageRepository", "Error closing chat $chatId", e)
        }
    }

    override suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyToMsgId: Long?,
        entities: List<MessageEntity>,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendMessage(chatId, text, replyToMsgId, entities, threadId, sendOptions)
    }

    override suspend fun sendSticker(chatId: Long, stickerPath: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendSticker(chatId, stickerPath, replyToMsgId, threadId)
    }

    override suspend fun sendPhoto(
        chatId: Long,
        photoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendPhoto(
            chatId = chatId,
            photoPath = photoPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyToMsgId,
            threadId = threadId,
            sendOptions = sendOptions
        )
    }

    override suspend fun sendVideo(
        chatId: Long,
        videoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendVideo(
            chatId = chatId,
            videoPath = videoPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyToMsgId,
            threadId = threadId,
            sendOptions = sendOptions
        )
    }

    override suspend fun sendDocument(
        chatId: Long,
        documentPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendDocument(
            chatId = chatId,
            documentPath = documentPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyToMsgId,
            threadId = threadId,
            sendOptions = sendOptions
        )
    }

    override suspend fun sendGif(
        chatId: Long,
        gifId: String,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendGif(chatId, gifId, replyToMsgId, threadId, sendOptions)
    }

    override suspend fun sendGifFile(
        chatId: Long,
        gifPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendGifFile(
            chatId = chatId,
            gifPath = gifPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyToMsgId,
            threadId = threadId,
            sendOptions = sendOptions
        )
    }

    override suspend fun sendAlbum(
        chatId: Long,
        paths: List<String>,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ) {
        messageRemoteDataSource.sendAlbum(
            chatId = chatId,
            paths = paths,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyToMsgId,
            threadId = threadId,
            sendOptions = sendOptions
        )
    }

    override suspend fun sendVideoNote(chatId: Long, videoPath: String, duration: Int, length: Int) {
        messageRemoteDataSource.sendVideoNote(chatId, videoPath, duration, length)
    }

    override suspend fun sendVoiceNote(chatId: Long, voicePath: String, duration: Int, waveform: ByteArray) {
        messageRemoteDataSource.sendVoiceNote(chatId, voicePath, duration, waveform)
    }

    override suspend fun forwardMessage(toChatId: Long, fromChatId: Long, messageId: Long) {
        messageRemoteDataSource.forwardMessages(toChatId, fromChatId, longArrayOf(messageId), false, false)
    }

    override suspend fun deleteMessage(chatId: Long, messageIds: List<Long>, revoke: Boolean) {
        messageRemoteDataSource.deleteMessages(chatId, messageIds.toLongArray(), revoke)
        messageIds.forEach { chatLocalDataSource.deleteMessage(it) }
    }

    override suspend fun editMessage(chatId: Long, messageId: Long, newText: String, entities: List<MessageEntity>) {
        messageRemoteDataSource.editMessageText(chatId, messageId, newText, entities)
    }

    override suspend fun markAsRead(chatId: Long, messageId: Long) {
        messageRemoteDataSource.markAsRead(chatId, messageId)
    }

    override suspend fun markAllMentionsAsRead(chatId: Long) {
        messageRemoteDataSource.markAllMentionsAsRead(chatId)
    }

    override suspend fun markAllReactionsAsRead(chatId: Long) {
        messageRemoteDataSource.markAllReactionsAsRead(chatId)
    }


    override suspend fun getMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): OlderMessagesPage =
        withContext(dispatcherProvider.io) {
            val cached = if (fromMessageId == 0L) {
                val cachedEntities = chatLocalDataSource.getLatestMessages(chatId, limit)
                mapLocalMessages(cachedEntities)
            } else {
                emptyList()
            }

            try {
                val remotePage = messageRemoteDataSource.getMessagesOlder(chatId, fromMessageId, limit, threadId)
                persistRemoteMessages(chatId, remotePage.messages)
                remotePage
            } catch (e: Exception) {
                val fallbackMessages = if (cached.isNotEmpty()) {
                    cached
                } else {
                    val local = chatLocalDataSource.getMessagesOlder(chatId, fromMessageId, limit)
                    mapLocalMessages(local)
                }
                OlderMessagesPage(
                    messages = fallbackMessages,
                    reachedOldest = false,
                    isRemote = false
                )
            }
        }

    override suspend fun getCachedMessages(chatId: Long, limit: Int): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            val local = chatLocalDataSource.getLatestMessages(chatId, limit)
            mapLocalMessages(local)
        }

    override suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            try {
                val remoteMessages = messageRemoteDataSource.getMessagesNewer(chatId, fromMessageId, limit, threadId)
                persistRemoteMessages(chatId, remoteMessages)
                remoteMessages
            } catch (e: Exception) {
                chatLocalDataSource.getMessagesNewer(chatId, fromMessageId, limit)
                    .let { mapLocalMessages(it) }
            }
        }

    override suspend fun getMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            try {
                val remoteMessages = messageRemoteDataSource.getMessagesAround(chatId, messageId, limit, threadId)
                persistRemoteMessages(chatId, remoteMessages)
                remoteMessages
            } catch (e: Exception) {
                val local = chatLocalDataSource.getLatestMessages(chatId, limit)
                mapLocalMessages(local)
            }
        }

    @Deprecated("Use getMessagesOlder instead")
    override suspend fun getMessages(chatId: Long, fromMessageId: Long, limit: Int): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            getMessagesOlder(chatId, fromMessageId, limit).messages
        }

    override suspend fun getChatDraft(chatId: Long, threadId: Long?): String? =
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.getChatDraft(chatId, threadId)
        }

    override suspend fun saveChatDraft(chatId: Long, text: String, replyToMsgId: Long?, threadId: Long?) {
        val draft = if (text.isNotEmpty()) {
            val inputMessageText = TdApi.InputMessageText().apply {
                this.text = TdApi.FormattedText(text, null)
            }
            TdApi.DraftMessage().apply {
                this.inputMessageText = inputMessageText
                this.date = (System.currentTimeMillis() / 1000).toInt()

                if (replyToMsgId != null && replyToMsgId != 0L) {
                    this.replyTo = TdApi.InputMessageReplyToMessage().apply {
                        this.messageId = replyToMsgId
                        this.quote = null
                        this.checklistTaskId = 0
                    }
                }
            }
        } else {
            null
        }
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.saveChatDraft(chatId, draft, replyToMsgId, threadId)

            cache.updateChat(chatId) { chat ->
                chat.draftMessage = draft
            }
        }
    }

    override suspend fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean) {
        messageRemoteDataSource.pinMessage(chatId, messageId, disableNotification)
    }

    override suspend fun unpinMessage(chatId: Long, messageId: Long) {
        messageRemoteDataSource.unpinMessage(chatId, messageId)
    }

    override suspend fun getPinnedMessage(chatId: Long, threadId: Long?): MessageModel? =
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.getPinnedMessageModel(chatId, threadId)
        }

    override suspend fun getAllPinnedMessages(chatId: Long, threadId: Long?): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.getAllPinnedMessages(chatId, threadId)
        }

    override suspend fun getPinnedMessageCount(chatId: Long, threadId: Long?): Int =
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.getPinnedMessageCount(chatId, threadId)
        }

    override suspend fun getScheduledMessages(chatId: Long): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.getScheduledMessages(chatId)
        }

    override suspend fun sendScheduledNow(chatId: Long, messageId: Long) {
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.sendScheduledNow(chatId, messageId)
        }
    }

    override fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long, synchronous: Boolean) {
        scope.launch {
            Log.d(
                "DownloadDebug",
                "repo.downloadFile: fileId=$fileId priority=$priority offset=$offset limit=$limit sync=$synchronous"
            )
            fileDataSource.downloadFile(fileId, priority, offset, limit, synchronous)
        }
    }

    override fun invalidateSenderCache(userId: Long) {
        messageMapper.invalidateSenderCache(userId)
    }

    override suspend fun cancelDownloadFile(fileId: Int) {
        Log.d("DownloadDebug", "repo.cancelDownloadFile: fileId=$fileId")
        fileDataSource.cancelDownload(fileId)
    }

    override suspend fun sendChatAction(chatId: Long, action: MessageRepository.ChatAction, threadId: Long?) {
        val tdAction = when (action) {
            MessageRepository.ChatAction.Typing -> TdApi.ChatActionTyping()
            MessageRepository.ChatAction.RecordingVideo -> TdApi.ChatActionRecordingVideo()
            MessageRepository.ChatAction.RecordingVoice -> TdApi.ChatActionRecordingVoiceNote()
            MessageRepository.ChatAction.UploadingPhoto -> TdApi.ChatActionUploadingPhoto(0)
            MessageRepository.ChatAction.UploadingVideo -> TdApi.ChatActionUploadingVideo(0)
            MessageRepository.ChatAction.UploadingDocument -> TdApi.ChatActionUploadingDocument(0)
            MessageRepository.ChatAction.ChoosingSticker -> TdApi.ChatActionChoosingSticker()
            MessageRepository.ChatAction.Cancel -> TdApi.ChatActionCancel()
        }
        messageRemoteDataSource.sendChatAction(chatId, threadId?: 0L, tdAction)
    }

    override suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int = withContext(dispatcherProvider.io) {
        messageMapper.getMessageReadDate(chatId, messageId, messageDate)
    }

    override suspend fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerModel> =
        withContext(dispatcherProvider.io) {
            messageRemoteDataSource.getMessageViewersModels(chatId, messageId)
        }

    override suspend fun summarizeMessage(chatId: Long, messageId: Long, toLanguageCode: String): String? =
        withContext(dispatcherProvider.io) {
            when (val result = gateway.execute(TdApi.SummarizeMessage(chatId, messageId, toLanguageCode))) {
                is TdApi.FormattedText -> result.text
                else -> null
            }
        }

    override suspend fun translateMessage(chatId: Long, messageId: Long, toLanguageCode: String): String? =
        withContext(dispatcherProvider.io) {
            when (val result = gateway.execute(TdApi.TranslateMessageText(chatId, messageId, toLanguageCode))) {
                is TdApi.FormattedText -> result.text
                else -> null
            }
        }

    override suspend fun addMessageReaction(chatId: Long, messageId: Long, reaction: String) {
        messageRemoteDataSource.addMessageReaction(chatId, messageId, reaction)
    }

    override suspend fun removeMessageReaction(chatId: Long, messageId: Long, reaction: String) {
        messageRemoteDataSource.removeMessageReaction(chatId, messageId, reaction)
    }

    override suspend fun stopPoll(chatId: Long, messageId: Long) {
        messageRemoteDataSource.stopPoll(chatId, messageId)
    }

    override suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>) {
        messageRemoteDataSource.setPollAnswer(chatId, messageId, optionIds.toIntArray())
    }

    override suspend fun getPollVoters(
        chatId: Long,
        messageId: Long,
        optionId: Int,
        offset: Int,
        limit: Int
    ): List<UserModel> = withContext(dispatcherProvider.io) {
        messageRemoteDataSource.getPollVotersModels(chatId, messageId, optionId, offset, limit)
    }

    override suspend fun getWebPageInstantView(url: String, forceFull: Boolean): InstantViewModel? {
        val result = gateway.execute(TdApi.GetWebPageInstantView(url, forceFull))
        return if (result is TdApi.WebPageInstantView) {
            map(result, url)
        } else {
            null
        }
    }

    override suspend fun searchMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): SearchChatMessagesResult = withContext(dispatcherProvider.io) {
        messageRemoteDataSource.searchMessages(chatId, query, fromMessageId, limit, threadId)
    }

    override fun updateVisibleRange(chatId: Long, visibleMessageIds: List<Long>, nearbyMessageIds: List<Long>) {
        messageRemoteDataSource.updateVisibleRange(chatId, visibleMessageIds, nearbyMessageIds)
    }

    override suspend fun onCallbackQuery(chatId: Long, messageId: Long, data: ByteArray) {
        messageRemoteDataSource.onCallbackQuery(chatId, messageId, data)
    }

    override suspend fun openWebApp(
        chatId: Long,
        botUserId: Long,
        url: String,
        themeParams: ThemeParams?
    ): WebAppInfoModel? = withContext(dispatcherProvider.io) {
        messageRemoteDataSource.openWebApp(chatId, botUserId, url, themeParams)
    }

    override suspend fun closeWebApp(launchId: Long) {
        messageRemoteDataSource.closeWebApp(launchId)
    }

    override suspend fun getInvoice(slug: String?, chatId: Long?, messageId: Long?): InvoiceModel? {
        val inputInvoice = when {
            slug != null -> TdApi.InputInvoiceName(slug)
            chatId != null && messageId != null -> TdApi.InputInvoiceMessage(chatId, messageId)
            else -> {
                return null
            }
        }

        val result = gateway.execute(TdApi.GetPaymentForm(inputInvoice, TdApi.ThemeParameters()))
        if (result is TdApi.PaymentForm) {
            val productInfo = result.productInfo

            val formType = result.type

            if (formType is TdApi.PaymentFormTypeRegular) {
                val invoice = formType.invoice

                val photo = productInfo.photo?.sizes?.lastOrNull()?.photo
                if (photo != null) {
                    // No cache access here, this is a temporary solution
                }

                return InvoiceModel(
                    title = productInfo.title,
                    description = productInfo.description.text,
                    currency = invoice.currency,
                    totalAmount = invoice.priceParts.sumOf { it.amount },
                    photoUrl = photo?.id?.toString(),
                    isTest = invoice.isTest,
                    slug = slug
                )

            } else {
                return null
            }
        } else {
            return null
        }
    }

    override suspend fun payInvoice(slug: String?, chatId: Long?, messageId: Long?): Boolean {
        val inputInvoice = when {
            slug != null -> TdApi.InputInvoiceName(slug)
            chatId != null && messageId != null -> TdApi.InputInvoiceMessage(chatId, messageId)
            else -> {
                return false
            }
        }
        val result = gateway.execute(TdApi.GetPaymentForm(inputInvoice, TdApi.ThemeParameters()))
        return result is TdApi.PaymentForm
    }

    override suspend fun getFilePath(fileId: Int): String? {
        val result = runCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull()
        return if (result is TdApi.File) {
            result.local.path.ifEmpty { null }
        } else {
            null
        }
    }

    override suspend fun onCallbackQueryBuy(chatId: Long, messageId: Long) {
        messageRemoteDataSource.onCallbackQueryBuy(chatId, messageId)
    }

    override suspend fun sendWebAppResult(launchId: Long, queryId: String) {
        messageRemoteDataSource.sendWebAppResult(launchId, queryId)
    }

    override suspend fun getProfileMedia(
        chatId: Long,
        filter: ProfileMediaFilter,
        fromMessageId: Long,
        limit: Int
    ): List<MessageModel> = withContext(dispatcherProvider.io) {
        val tdFilter = when (filter) {
            ProfileMediaFilter.MEDIA -> TdApi.SearchMessagesFilterPhotoAndVideo()
            ProfileMediaFilter.FILES -> TdApi.SearchMessagesFilterDocument()
            ProfileMediaFilter.AUDIO -> TdApi.SearchMessagesFilterAudio()
            ProfileMediaFilter.VOICE -> TdApi.SearchMessagesFilterVoiceAndVideoNote()
            ProfileMediaFilter.LINKS -> TdApi.SearchMessagesFilterUrl()
            ProfileMediaFilter.GIFS -> TdApi.SearchMessagesFilterAnimation()
        }

        val request = TdApi.SearchChatMessages()
        request.chatId = chatId
        request.query = ""
        request.senderId = null
        request.fromMessageId = fromMessageId
        request.offset = 0
        request.limit = limit
        request.filter = tdFilter

        try {
            val result = gateway.execute(request)
            result.messages.mapNotNull { msg ->
                cache.putMessage(msg)
                triggerFileDownload(msg)
                runCatching {
                    messageMapper.mapMessageToModel(msg)
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun triggerFileDownload(msg: TdApi.Message) {
        val file: TdApi.File? = when (val content = msg.content) {
            is TdApi.MessagePhoto -> {
                content.photo.sizes.firstOrNull()?.photo
            }
            is TdApi.MessageVideo -> {
                content.video.thumbnail?.file
            }
            is TdApi.MessageDocument -> {
                content.document.thumbnail?.file
            }
            is TdApi.MessageAnimation -> content.animation.thumbnail?.file
            else -> null
        }
        if (file != null && file.local.path.isEmpty()) {
            fileDataSource.downloadFile(file.id, 32, 0, 0, false)
        }
    }

    override suspend fun joinChat(chatId: Long) {
        gateway.execute(TdApi.JoinChat(chatId))
    }

    override suspend fun restrictChatMember(
        chatId: Long,
        userId: Long,
        permissions: ChatPermissionsModel,
        untilDate: Int
    ) {
        val tdPermissions = TdApi.ChatPermissions(
            permissions.canSendBasicMessages,
            permissions.canSendAudios,
            permissions.canSendDocuments,
            permissions.canSendPhotos,
            permissions.canSendVideos,
            permissions.canSendVideoNotes,
            permissions.canSendVoiceNotes,
            permissions.canSendPolls,
            permissions.canSendOtherMessages,
            permissions.canAddLinkPreviews,
            permissions.canEditTag,
            permissions.canChangeInfo,
            permissions.canInviteUsers,
            permissions.canPinMessages,
            permissions.canCreateTopics
        )
        gateway.execute(
            TdApi.SetChatMemberStatus(
                chatId,
                TdApi.MessageSenderUser(userId),
                TdApi.ChatMemberStatusRestricted(false, untilDate, tdPermissions)
            )
        )
        return
    }

    override suspend fun getInlineBotResults(
        botUserId: Long,
        chatId: Long,
        query: String,
        offset: String
    ): InlineBotResultsModel? {
        val result = gateway.execute(TdApi.GetInlineQueryResults(botUserId, chatId, null, query, offset))
        if (result is TdApi.InlineQueryResults) {
            return InlineBotResultsModel(
                    queryId = result.inlineQueryId,
                    nextOffset = result.nextOffset,
                    results = result.results.map { res ->
                        fun getThumbFileId(thumbnail: TdApi.Thumbnail?): Int {
                            return thumbnail?.file?.id ?: 0
                        }

                        fun getPath(thumbnail: TdApi.Thumbnail?): String? {
                            if (thumbnail == null) return null
                            val file = thumbnail.file
                            val updated = cache.fileCache[file.id] ?: file
                            if (updated.local.path.isNotEmpty()) return updated.local.path
                            scope.launch {
                                fileDataSource.downloadFile(updated.id, 32, 0, 0, false)
                            }
                            return null
                        }

                        fun makePath(thumbnail: TdApi.Minithumbnail?): String? {
                            val data = thumbnail?.data ?: return null
                            return try {
                                val file = File.createTempFile(
                                    "mini_${System.currentTimeMillis()}",
                                    ".webp",
                                    context.cacheDir
                                )
                                file.writeBytes(data)
                                file.absolutePath
                            } catch (e: Exception) {
                                null
                            }
                        }

                        val photoResult = res as? TdApi.InlineQueryResultPhoto
                        val selectedPhotoSize = photoResult
                            ?.photo
                            ?.sizes
                            ?.sortedBy { it.width * it.height }
                            ?.lastOrNull { size -> maxOf(size.width, size.height) <= 1280 }
                            ?: photoResult?.photo?.sizes?.lastOrNull()

                        InlineQueryResultModel(
                            id = when (res) {
                                is TdApi.InlineQueryResultArticle -> res.id
                                is TdApi.InlineQueryResultPhoto -> res.id
                                is TdApi.InlineQueryResultVideo -> res.id
                                is TdApi.InlineQueryResultAudio -> res.id
                                is TdApi.InlineQueryResultDocument -> res.id
                                is TdApi.InlineQueryResultLocation -> res.id
                                is TdApi.InlineQueryResultVenue -> res.id
                                is TdApi.InlineQueryResultGame -> res.id
                                is TdApi.InlineQueryResultAnimation -> res.id
                                is TdApi.InlineQueryResultSticker -> res.id
                                is TdApi.InlineQueryResultVoiceNote -> res.id
                                is TdApi.InlineQueryResultContact -> res.id
                                else -> ""
                            },
                            type = when (res) {
                                is TdApi.InlineQueryResultArticle -> "article"
                                is TdApi.InlineQueryResultPhoto -> "photo"
                                is TdApi.InlineQueryResultVideo -> "video"
                                is TdApi.InlineQueryResultAudio -> "audio"
                                is TdApi.InlineQueryResultDocument -> "document"
                                is TdApi.InlineQueryResultLocation -> "location"
                                is TdApi.InlineQueryResultVenue -> "venue"
                                is TdApi.InlineQueryResultGame -> "game"
                                is TdApi.InlineQueryResultAnimation -> "gif"
                                is TdApi.InlineQueryResultSticker -> "sticker"
                                is TdApi.InlineQueryResultVoiceNote -> "voice"
                                is TdApi.InlineQueryResultContact -> "contact"
                                else -> res.javaClass.simpleName
                            },
                            title = when (res) {
                                is TdApi.InlineQueryResultArticle -> res.title
                                is TdApi.InlineQueryResultPhoto -> res.title
                                is TdApi.InlineQueryResultVideo -> res.title
                                is TdApi.InlineQueryResultAudio -> res.audio.title
                                is TdApi.InlineQueryResultDocument -> res.title
                                is TdApi.InlineQueryResultLocation -> res.title
                                is TdApi.InlineQueryResultVenue -> res.venue.title
                                is TdApi.InlineQueryResultGame -> res.game.title
                                else -> null
                            },
                            description = when (res) {
                                is TdApi.InlineQueryResultArticle -> res.description
                                is TdApi.InlineQueryResultPhoto -> res.description
                                is TdApi.InlineQueryResultVideo -> res.description
                                is TdApi.InlineQueryResultDocument -> res.description
                                else -> null
                            },
                            thumbFileId = when (res) {
                                is TdApi.InlineQueryResultArticle -> getThumbFileId(res.thumbnail)
                                is TdApi.InlineQueryResultPhoto -> selectedPhotoSize?.photo?.id ?: 0
                                is TdApi.InlineQueryResultVideo -> getThumbFileId(res.video.thumbnail)
                                is TdApi.InlineQueryResultDocument -> getThumbFileId(res.document.thumbnail)
                                is TdApi.InlineQueryResultAnimation -> getThumbFileId(res.animation.thumbnail)
                                is TdApi.InlineQueryResultSticker -> getThumbFileId(res.sticker.thumbnail)
                                else -> 0
                            },
                            thumbUrl = when (res) {
                                is TdApi.InlineQueryResultArticle -> getPath(res.thumbnail)
                                is TdApi.InlineQueryResultPhoto -> {
                                    val photo = res.photo
                                    val path = selectedPhotoSize?.photo?.let { file ->
                                        val updated = cache.fileCache[file.id] ?: file
                                        if (updated.local.path.isNotEmpty()) {
                                            updated.local.path
                                        } else {
                                            scope.launch {
                                                fileDataSource.downloadFile(updated.id, 32, 0, 0, false)
                                            }
                                            null
                                        }
                                    }
                                    path ?: makePath(photo.minithumbnail)
                                }

                                is TdApi.InlineQueryResultVideo ->
                                    getPath(res.video.thumbnail) ?: makePath(res.video.minithumbnail)

                                is TdApi.InlineQueryResultDocument ->
                                    getPath(res.document.thumbnail) ?: makePath(res.document.minithumbnail)

                                is TdApi.InlineQueryResultAnimation ->
                                    getPath(res.animation.thumbnail) ?: makePath(res.animation.minithumbnail)
                                is TdApi.InlineQueryResultSticker -> getPath(res.sticker.thumbnail)
                                else -> null
                            },
                            width = when (res) {
                                is TdApi.InlineQueryResultPhoto -> selectedPhotoSize?.width
                                    ?: res.photo.minithumbnail?.width ?: 0
                                is TdApi.InlineQueryResultVideo -> res.video.width
                                is TdApi.InlineQueryResultAnimation -> res.animation.width
                                is TdApi.InlineQueryResultSticker -> res.sticker.width
                                else -> 0
                            },
                            height = when (res) {
                                is TdApi.InlineQueryResultPhoto -> selectedPhotoSize?.height
                                    ?: res.photo.minithumbnail?.height ?: 0
                                is TdApi.InlineQueryResultVideo -> res.video.height
                                is TdApi.InlineQueryResultAnimation -> res.animation.height
                                is TdApi.InlineQueryResultSticker -> res.sticker.height
                                else -> 0
                            }
                        )
                    },
                    switchPmText = null,
                    switchPmParameter = null
            )
        } else {
            return null
        }
    }

    override suspend fun sendInlineBotResult(
        chatId: Long,
        queryId: Long,
        resultId: String,
        replyToMsgId: Long?,
        threadId: Long?
    ) {
        val replyTo = if (replyToMsgId != null)
            TdApi.InputMessageReplyToMessage(replyToMsgId, null, 0)
        else null

        val topicId = if (threadId != null) {
            TdApi.MessageTopicForum(threadId.toInt())
        } else {
            null
        }

        gateway.execute(
            TdApi.SendInlineQueryResultMessage(
                chatId,
                topicId,
                replyTo,
                null,
                queryId,
                resultId,
                false
            )
        )
    }

    override suspend fun getChatEventLog(
        chatId: Long,
        query: String,
        fromEventId: Long,
        limit: Int,
        filters: ChatEventLogFiltersModel,
        userIds: List<Long>
    ): List<ChatEventModel> =
        withContext(dispatcherProvider.io) {
            val tdFilters = TdApi.ChatEventLogFilters(
                filters.messageEdits,
                filters.messageDeletions,
                filters.messagePins,
                filters.memberJoins,
                filters.memberLeaves,
                filters.memberInvites,
                filters.memberPromotions,
                filters.memberRestrictions,
                false,
                filters.infoChanges,
                filters.settingChanges,
                filters.inviteLinkChanges,
                filters.videoChatChanges,
                filters.forumChanges,
                filters.subscriptionExtensions
            )
            val result = gateway.execute(
                TdApi.GetChatEventLog(
                    chatId,
                    query,
                    fromEventId,
                    limit,
                    tdFilters,
                    userIds.toLongArray()
                )
            )
            if (result is TdApi.ChatEvents) {
                val events = result.events.map { event ->
                    val senderId = when (val sender = event.memberId) {
                        is TdApi.MessageSenderUser -> sender.userId
                        else -> 0L
                    }
                    ChatEventModel(
                        id = event.id,
                        date = event.date,
                        memberId = when (val sender = event.memberId) {
                            is TdApi.MessageSenderUser -> MessageSenderModel.User(sender.userId)
                            is TdApi.MessageSenderChat -> MessageSenderModel.Chat(sender.chatId)
                            else -> MessageSenderModel.User(0)
                        },
                        action = when (val action = event.action) {
                            is TdApi.ChatEventMessageEdited -> ChatEventActionModel.MessageEdited(
                                oldMessage = messageMapper.mapMessageToModel(action.oldMessage),
                                newMessage = messageMapper.mapMessageToModel(action.newMessage)
                            )

                            is TdApi.ChatEventMessageDeleted -> ChatEventActionModel.MessageDeleted(
                                message = messageMapper.mapMessageToModel(action.message)
                            )

                            is TdApi.ChatEventMessagePinned -> ChatEventActionModel.MessagePinned(
                                message = messageMapper.mapMessageToModel(action.message)
                            )

                            is TdApi.ChatEventMessageUnpinned -> ChatEventActionModel.MessageUnpinned(
                                message = messageMapper.mapMessageToModel(action.message)
                            )

                            is TdApi.ChatEventMemberJoined -> ChatEventActionModel.MemberJoined(
                                senderId
                            )

                            is TdApi.ChatEventMemberLeft -> ChatEventActionModel.MemberLeft(
                                senderId
                            )

                            is TdApi.ChatEventMemberInvited -> ChatEventActionModel.MemberInvited(
                                action.userId,
                                action.status.toString()
                            )

                            is TdApi.ChatEventMemberPromoted -> ChatEventActionModel.MemberPromoted(
                                action.userId,
                                action.oldStatus.toString(),
                                action.newStatus.toString()
                            )

                            is TdApi.ChatEventMemberRestricted -> {
                                val oldStatus = action.oldStatus
                                val newStatus = action.newStatus
                                ChatEventActionModel.MemberRestricted(
                                    userId = when (val m = action.memberId) {
                                        is TdApi.MessageSenderUser -> m.userId
                                        else -> 0
                                    },
                                    oldStatus = oldStatus.toString(),
                                    newStatus = newStatus.toString(),
                                    untilDate = (newStatus as? TdApi.ChatMemberStatusRestricted)?.restrictedUntilDate
                                        ?: 0,
                                    oldPermissions = (oldStatus as? TdApi.ChatMemberStatusRestricted)?.permissions?.let { p ->
                                        ChatPermissionsModel(
                                            canSendBasicMessages = p.canSendBasicMessages,
                                            canSendAudios = p.canSendAudios,
                                            canSendDocuments = p.canSendDocuments,
                                            canSendPhotos = p.canSendPhotos,
                                            canSendVideos = p.canSendVideos,
                                            canSendVideoNotes = p.canSendVideoNotes,
                                            canSendVoiceNotes = p.canSendVoiceNotes,
                                            canSendPolls = p.canSendPolls,
                                            canSendOtherMessages = p.canSendOtherMessages,
                                            canAddLinkPreviews = p.canAddLinkPreviews,
                                            canEditTag = p.canEditTag,
                                            canChangeInfo = p.canChangeInfo,
                                            canInviteUsers = p.canInviteUsers,
                                            canPinMessages = p.canPinMessages,
                                            canCreateTopics = p.canCreateTopics
                                        )
                                    },
                                    newPermissions = (newStatus as? TdApi.ChatMemberStatusRestricted)?.permissions?.let { p ->
                                        ChatPermissionsModel(
                                            canSendBasicMessages = p.canSendBasicMessages,
                                            canSendAudios = p.canSendAudios,
                                            canSendDocuments = p.canSendDocuments,
                                            canSendPhotos = p.canSendPhotos,
                                            canSendVideos = p.canSendVideos,
                                            canSendVideoNotes = p.canSendVideoNotes,
                                            canSendVoiceNotes = p.canSendVoiceNotes,
                                            canSendPolls = p.canSendPolls,
                                            canSendOtherMessages = p.canSendOtherMessages,
                                            canAddLinkPreviews = p.canAddLinkPreviews,
                                            canEditTag = p.canEditTag,
                                            canChangeInfo = p.canChangeInfo,
                                            canInviteUsers = p.canInviteUsers,
                                            canPinMessages = p.canPinMessages,
                                            canCreateTopics = p.canCreateTopics
                                        )
                                    }
                                )
                            }

                            is TdApi.ChatEventTitleChanged -> ChatEventActionModel.TitleChanged(
                                action.oldTitle,
                                action.newTitle
                            )

                            is TdApi.ChatEventDescriptionChanged -> ChatEventActionModel.DescriptionChanged(
                                action.oldDescription,
                                action.newDescription
                            )

                            is TdApi.ChatEventUsernameChanged -> ChatEventActionModel.UsernameChanged(
                                action.oldUsername,
                                action.newUsername
                            )

                            is TdApi.ChatEventPhotoChanged -> ChatEventActionModel.PhotoChanged(
                                null,
                                null
                            )

                            is TdApi.ChatEventInviteLinkEdited -> ChatEventActionModel.InviteLinkEdited(
                                action.oldInviteLink.inviteLink,
                                action.newInviteLink.inviteLink
                            )

                            is TdApi.ChatEventInviteLinkRevoked -> ChatEventActionModel.InviteLinkRevoked(
                                action.inviteLink.inviteLink
                            )

                            is TdApi.ChatEventInviteLinkDeleted -> ChatEventActionModel.InviteLinkDeleted(
                                action.inviteLink.inviteLink
                            )

                            is TdApi.ChatEventVideoChatCreated -> ChatEventActionModel.VideoChatCreated(
                                action.groupCallId
                            )

                            is TdApi.ChatEventVideoChatEnded -> ChatEventActionModel.VideoChatEnded(
                                action.groupCallId
                            )

                            else -> ChatEventActionModel.Unknown(action.javaClass.simpleName)
                        }
                    )
                }
                return@withContext events
            }
            else return@withContext emptyList()
        }

    override suspend fun getFileInfo(fileId: Int): FileModel? {
        val result = runCatching { gateway.execute(TdApi.GetFile(fileId)) }.getOrNull()
        if (result is TdApi.File) {
            val model = FileModel(
                id = result.id,
                size = result.size,
                expectedSize = result.expectedSize,
                local = result.local.toDomain(),
                remote = result.remote.toDomain()
            )
            return model
        } else {
            return null
        }
    }

    override suspend fun getHighResFileId(chatId: Long, messageId: Long): Int? {
        return try {
            val result = gateway.execute(TdApi.GetMessage(chatId, messageId))
            if (result is TdApi.Message && result.content is TdApi.MessagePhoto) {
                val photo = (result.content as TdApi.MessagePhoto).photo
                val bestSize = photo.sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                    ?: photo.sizes.lastOrNull()
                val fileId = bestSize?.photo?.id ?: return null
                messageRemoteDataSource.registerFileForMessage(fileId, chatId, messageId)
                fileId
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(
                "MessageRepositoryImpl",
                "Failed to resolve high-res file id for chatId=$chatId, messageId=$messageId: ${e.message}"
            )
            null
        }
    }

    override fun clearMessages(chatId: Long) {
        cache.clearMessages(chatId)
        scope.launch(dispatcherProvider.io) {
            chatLocalDataSource.clearMessagesForChat(chatId)
        }
    }

    override fun clearAllCache() {
        cache.clearAll()
        scope.launch(dispatcherProvider.io) {
            chatLocalDataSource.clearAll()
        }
    }

    private fun persistRemoteMessages(chatId: Long, remoteMessages: List<MessageModel>) {
        scope.launch(dispatcherProvider.io) {
            val entities = remoteMessages.mapNotNull { model ->
                messageRemoteDataSource.getMessage(chatId, model.id)?.let { message ->
                    messageMapper.mapToEntity(message, ::resolveSenderName)
                }
            }
            if (entities.isNotEmpty()) {
                chatLocalDataSource.insertMessages(entities)
            }
        }
    }

    private fun resolveSenderName(senderId: Long): String? {
        val user = cache.getUser(senderId)
        if (user != null) {
            return listOfNotNull(user.firstName.takeIf { it.isNotBlank() }, user.lastName?.takeIf { it.isNotBlank() })
                .joinToString(" ")
                .ifBlank { null }
        }
        return cache.getChat(senderId)?.title
    }

    private suspend fun mapLocalMessages(
        entities: List<org.monogram.data.db.model.MessageEntity>
    ): List<MessageModel> {
        prewarmCachedSenders(entities)
        return entities.map { entity ->
            val model = messageMapper.mapEntityToModel(entity)
            enrichSenderFromCache(model)
        }
    }

    private suspend fun prewarmCachedSenders(
        entities: List<org.monogram.data.db.model.MessageEntity>
    ) {
        val senderIds = entities.asSequence()
            .map { it.senderId }
            .filter { it > 0L }
            .distinct()
            .toList()

        senderIds.forEach { senderId ->
            if (cache.getUser(senderId) != null) return@forEach
            val localUser = runCatching { userLocalDataSource.getUser(senderId) }.getOrNull() ?: return@forEach
            cache.putUser(localUser)
        }
    }

    private fun enrichSenderFromCache(model: MessageModel): MessageModel {
        val senderId = model.senderId
        if (senderId <= 0L) return model

        val cachedUser = cache.getUser(senderId)
        if (cachedUser != null) {
            val resolvedName = listOfNotNull(
                cachedUser.firstName.takeIf { it.isNotBlank() },
                cachedUser.lastName?.takeIf { it.isNotBlank() }
            ).joinToString(" ").ifBlank { model.senderName }

            val resolvedAvatar = resolveFilePath(cachedUser.profilePhoto?.small)
            if (resolvedAvatar == null) {
                cachedUser.profilePhoto?.small?.id?.takeIf { it != 0 }?.let { avatarFileId ->
                    messageRemoteDataSource.enqueueDownload(avatarFileId, priority = 16)
                }
            }
            val emojiId = when (val type = cachedUser.emojiStatus?.type) {
                is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
                is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
                else -> model.senderStatusEmojiId
            }

            return model.copy(
                senderName = resolvedName,
                senderAvatar = resolvedAvatar ?: model.senderAvatar,
                isSenderVerified = cachedUser.verificationStatus?.isVerified ?: model.isSenderVerified,
                isSenderPremium = cachedUser.isPremium || model.isSenderPremium,
                senderStatusEmojiId = emojiId
            )
        }

        val cachedChat = cache.getChat(senderId)
        if (cachedChat != null) {
            val resolvedName = cachedChat.title.takeIf { it.isNotBlank() } ?: model.senderName
            val resolvedAvatar = resolveFilePath(cachedChat.photo?.small)
            return model.copy(
                senderName = resolvedName,
                senderAvatar = resolvedAvatar ?: model.senderAvatar
            )
        }

        return model
    }

    private fun resolveFilePath(file: TdApi.File?): String? {
        if (file == null) return null
        val directPath = file.local.path.takeIf { it.isNotBlank() && File(it).exists() }
        if (directPath != null) return directPath

        val cachedPath = cache.fileCache[file.id]?.local?.path
        return cachedPath?.takeIf { it.isNotBlank() && File(it).exists() }
    }
}
