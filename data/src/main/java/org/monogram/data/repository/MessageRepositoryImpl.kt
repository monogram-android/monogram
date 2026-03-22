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
import org.monogram.data.datasource.remote.MessageRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.MessageMapper
import org.monogram.data.mapper.map
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.*
import org.monogram.domain.models.webapp.InstantViewModel
import org.monogram.domain.models.webapp.InvoiceModel
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel
import org.monogram.domain.repository.InlineBotResultsModel
import org.monogram.domain.repository.MessageRepository
import org.monogram.domain.repository.ProfileMediaFilter
import org.monogram.domain.repository.SearchChatMessagesResult
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
    private val chatLocalDataSource: ChatLocalDataSource
) : MessageRepository {
    private val scope = scopeProvider.appScope

    override val newMessageFlow = messageRemoteDataSource.newMessageFlow
    override val messageEditedFlow = messageRemoteDataSource.messageEditedFlow
    override val messageUploadProgressFlow = messageRemoteDataSource.messageUploadProgressFlow
    override val messageDownloadProgressFlow = messageRemoteDataSource.messageDownloadProgressFlow
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
                    if (update is TdApi.UpdateNewMessage) {
                        chatLocalDataSource.insertMessage(messageMapper.mapToEntity(update.message))
                    }
                }
            } catch (e: Exception) {
                Log.e("TdLibUpdates", "CRITICAL: Update loop died", e)
            }
        }

        scope.launch(dispatcherProvider.io) {
            val oneMonthAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            chatLocalDataSource.deleteExpired(oneMonthAgo)
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
        threadId: Long?
    ) {
        messageRemoteDataSource.sendMessage(chatId, text, replyToMsgId, entities, threadId)
    }

    override suspend fun sendSticker(chatId: Long, stickerPath: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendSticker(chatId, stickerPath, replyToMsgId, threadId)
    }

    override suspend fun sendPhoto(chatId: Long, photoPath: String, caption: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendPhoto(chatId, photoPath, caption, replyToMsgId, threadId)
    }

    override suspend fun sendVideo(chatId: Long, videoPath: String, caption: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendVideo(chatId, videoPath, caption, replyToMsgId, threadId)
    }

    override suspend fun sendDocument(
        chatId: Long,
        documentPath: String,
        caption: String,
        replyToMsgId: Long?,
        threadId: Long?
    ) {
        messageRemoteDataSource.sendDocument(chatId, documentPath, caption, replyToMsgId, threadId)
    }

    override suspend fun sendGif(chatId: Long, gifId: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendGif(chatId, gifId, replyToMsgId, threadId)
    }

    override suspend fun sendGifFile(chatId: Long, gifPath: String, caption: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendGifFile(chatId, gifPath, caption, replyToMsgId, threadId)
    }

    override suspend fun sendAlbum(chatId: Long, paths: List<String>, caption: String, replyToMsgId: Long?, threadId: Long?) {
        messageRemoteDataSource.sendAlbum(chatId, paths, caption, replyToMsgId, threadId)
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
    ): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            val remoteMessages = messageRemoteDataSource.getMessagesOlder(chatId, fromMessageId, limit, threadId)

            scope.launch(dispatcherProvider.io) {
                remoteMessages.forEach { model ->
                    messageRemoteDataSource.getMessage(chatId, model.id)?.let {
                        chatLocalDataSource.insertMessage(messageMapper.mapToEntity(it))
                    }
                }
            }
            remoteMessages
        }

    override suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            val remoteMessages = messageRemoteDataSource.getMessagesNewer(chatId, fromMessageId, limit, threadId)

            scope.launch(dispatcherProvider.io) {
                remoteMessages.forEach { model ->
                    messageRemoteDataSource.getMessage(chatId, model.id)?.let {
                        chatLocalDataSource.insertMessage(messageMapper.mapToEntity(it))
                    }
                }
            }

            remoteMessages
        }

    override suspend fun getMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        threadId: Long?
    ): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            val remoteMessages = messageRemoteDataSource.getMessagesAround(chatId, messageId, limit, threadId)

            scope.launch(dispatcherProvider.io) {
                remoteMessages.forEach { model ->
                    messageRemoteDataSource.getMessage(chatId, model.id)?.let {
                        chatLocalDataSource.insertMessage(messageMapper.mapToEntity(it))
                    }
                }
            }
            remoteMessages
        }

    @Deprecated("Use getMessagesOlder instead")
    override suspend fun getMessages(chatId: Long, fromMessageId: Long, limit: Int): List<MessageModel> =
        withContext(dispatcherProvider.io) {
            getMessagesOlder(chatId, fromMessageId, limit)
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
        draft?.let {
            scope.launch {
                messageRemoteDataSource.saveChatDraft(chatId, it, replyToMsgId, threadId)

                cache.updateChat(chatId) { chat ->
                    chat.draftMessage = it
                }
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

    override fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long, synchronous: Boolean) {
        scope.launch {
            fileDataSource.downloadFile(fileId, priority, offset, limit, synchronous)
        }
    }

    override suspend fun cancelDownloadFile(fileId: Int) {
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
        val result = gateway.execute(TdApi.GetFile(fileId))
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

                        InlineQueryResultModel(
                            id = when (res) {
                                is TdApi.InlineQueryResultArticle -> res.id
                                is TdApi.InlineQueryResultPhoto -> res.id
                                is TdApi.InlineQueryResultVideo -> res.id
                                is TdApi.InlineQueryResultAudio -> res.audio.title
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
                            type = res.javaClass.simpleName,
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
                                is TdApi.InlineQueryResultPhoto -> res.photo.sizes.lastOrNull()?.photo?.id ?: 0
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
                                    val bestSize = photo.sizes.lastOrNull()
                                    val path = bestSize?.photo?.let { file ->
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
                                is TdApi.InlineQueryResultVideo -> getPath(res.video.thumbnail)
                                is TdApi.InlineQueryResultDocument -> getPath(res.document.thumbnail)
                                is TdApi.InlineQueryResultAnimation -> getPath(res.animation.thumbnail)
                                is TdApi.InlineQueryResultSticker -> getPath(res.sticker.thumbnail)
                                else -> null
                            },
                            width = when (res) {
                                is TdApi.InlineQueryResultPhoto -> res.photo.sizes.lastOrNull()?.width
                                    ?: res.photo.minithumbnail?.width ?: 0
                                is TdApi.InlineQueryResultVideo -> res.video.width
                                is TdApi.InlineQueryResultAnimation -> res.animation.width
                                is TdApi.InlineQueryResultSticker -> res.sticker.width
                                else -> 0
                            },
                            height = when (res) {
                                is TdApi.InlineQueryResultPhoto -> res.photo.sizes.lastOrNull()?.height
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

        threadId?.let {
            gateway.execute(
                TdApi.SendInlineQueryResultMessage(
                    chatId,
                    TdApi.MessageTopicForum(it.toInt()),
                    replyTo,
                    null,
                    queryId,
                    resultId,
                    false
                )
            )
        }
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
        val result = gateway.execute(TdApi.GetFile(fileId))
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
        val result = gateway.execute(TdApi.GetMessage(chatId, messageId))
        if (result is TdApi.Message && result.content is TdApi.MessagePhoto) {
            val photo = (result.content as TdApi.MessagePhoto).photo
            return photo.sizes.lastOrNull()?.photo?.id
        } else {
            return null
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
}
