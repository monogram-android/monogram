package org.monogram.data.datasource.remote

import kotlinx.coroutines.flow.Flow
import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource.DownloadType
import org.monogram.domain.models.*
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel
import org.monogram.domain.repository.OlderMessagesPage
import org.monogram.domain.repository.ReadUpdate
import org.monogram.domain.repository.SearchChatMessagesResult

interface MessageRemoteDataSource {
    val newMessageFlow: Flow<MessageModel>
    val messageEditedFlow: Flow<MessageModel>
    val messageReadFlow: Flow<ReadUpdate>
    val messageUploadProgressFlow: Flow<Pair<Long, Float>>
    val messageDownloadProgressFlow: Flow<Pair<Long, Float>>
    val messageDownloadCancelledFlow: Flow<Long>
    val messageDeletedFlow: Flow<Pair<Long, List<Long>>>
    val messageIdUpdateFlow: Flow<Triple<Long, Long, MessageModel>>
    val messageDownloadCompletedFlow: Flow<Pair<Long, String>>
    val pinnedMessageFlow: Flow<Long>
    val mediaUpdateFlow: Flow<Unit>
    fun registerFileForMessage(fileId: Int, chatId: Long, messageId: Long)
    fun isFileQueued(fileId: Int): Boolean
    suspend fun handleUpdate(update: TdApi.Update)
    fun setChatOpened(chatId: Long)
    fun setChatClosed(chatId: Long)
    fun enqueueDownload(fileId: Int, priority: Int = 1, type: DownloadType = DownloadType.DEFAULT, offset: Long = 0, limit: Long = 0, synchronous: Boolean = false)
    suspend fun forwardMessage(toChatId: Long, fromChatId: Long, messageId: Long)
    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message?
    suspend fun getMessages(chatId: Long, fromMessageId: Long, offset: Int, limit: Int, threadId: Long?): TdApi.Messages?
    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int): TdApi.Messages?
    suspend fun searchChatMessages(chatId: Long, query: String, fromMessageId: Long, limit: Int, filter: TdApi.SearchMessagesFilter, threadId: Long?): TdApi.FoundChatMessages?
    suspend fun getChatPinnedMessage(chatId: Long): TdApi.Message?
    suspend fun getPollVoters(chatId: Long, messageId: Long, optionId: Int, offset: Int, limit: Int): TdApi.PollVoters?
    suspend fun getMessageViewers(chatId: Long, messageId: Long): TdApi.MessageViewers?
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyToMsgId: Long?,
        entities: List<MessageEntity>,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message?

    suspend fun sendPhoto(
        chatId: Long,
        photoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message?

    suspend fun sendVideo(
        chatId: Long,
        videoPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message?

    suspend fun sendDocument(
        chatId: Long,
        documentPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message?

    suspend fun sendSticker(chatId: Long, stickerPath: String, replyToMsgId: Long?, threadId: Long?): TdApi.Message?
    suspend fun sendGif(
        chatId: Long,
        gifId: String,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message?

    suspend fun sendGifFile(
        chatId: Long,
        gifPath: String,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Message?

    suspend fun sendAlbum(
        chatId: Long,
        paths: List<String>,
        caption: String,
        captionEntities: List<MessageEntity>,
        replyToMsgId: Long?,
        threadId: Long?,
        sendOptions: MessageSendOptions
    ): TdApi.Messages?
    suspend fun sendVideoNote(chatId: Long, videoPath: String, duration: Int, length: Int): TdApi.Message?
    suspend fun sendVoiceNote(chatId: Long, voicePath: String, duration: Int, waveform: ByteArray): TdApi.Message?
    suspend fun forwardMessages(toChatId: Long, fromChatId: Long, messageIds: LongArray, removeCaption: Boolean, sendCopy: Boolean): TdApi.Messages?
    suspend fun deleteMessages(chatId: Long, messageIds: LongArray, revoke: Boolean): TdApi.Ok?
    suspend fun editMessageText(chatId: Long, messageId: Long, text: String, entities: List<MessageEntity>): TdApi.Message?
    suspend fun viewMessages(chatId: Long, messageIds: LongArray, forceRead: Boolean): TdApi.Ok?
    suspend fun readAllChatMentions(chatId: Long): TdApi.Ok?
    suspend fun readAllChatReactions(chatId: Long): TdApi.Ok?
    suspend fun setChatDraftMessage(chatId: Long, messageThreadId: Long, draftMessage: TdApi.DraftMessage?): TdApi.Ok?
    suspend fun pinChatMessage(chatId: Long, messageId: Long, disableNotification: Boolean, onlyForSelf: Boolean): TdApi.Ok?
    suspend fun unpinChatMessage(chatId: Long, messageId: Long): TdApi.Ok?
    suspend fun addMessageReaction(chatId: Long, messageId: Long, reaction: String): TdApi.Ok?
    suspend fun removeMessageReaction(chatId: Long, messageId: Long, reaction: String): TdApi.Ok?
    suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: IntArray): TdApi.Ok?
    suspend fun stopPoll(chatId: Long, messageId: Long): TdApi.Poll?
    suspend fun sendChatAction(chatId: Long, messageThreadId: Long, action: TdApi.ChatAction): TdApi.Ok?
    suspend fun getCallbackQueryAnswer(chatId: Long, messageId: Long, payload: TdApi.CallbackQueryPayload): TdApi.CallbackQueryAnswer?
    suspend fun openWebApp(chatId: Long, botUserId: Long, url: String, theme: ThemeParams?): WebAppInfoModel?
    suspend fun closeWebApp(webAppLaunchId: Long): TdApi.Ok?
    suspend fun getPaymentForm(inputInvoice: TdApi.InputInvoice): TdApi.PaymentForm?
    suspend fun sendWebAppData(botUserId: Long, buttonText: String, data: String): TdApi.Ok?
    suspend fun markAsRead(chatId: Long, messageId: Long)
    suspend fun markAllReactionsAsRead(chatId: Long)
    suspend fun markAllMentionsAsRead(chatId: Long)
    suspend fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean)
    suspend fun unpinMessage(chatId: Long, messageId: Long)
    suspend fun saveChatDraft(chatId: Long, draft: TdApi.DraftMessage?, replyToMsgId: Long?, threadId: Long? = null)
    suspend fun getChatDraft(chatId: Long, threadId: Long? = null): String?
    fun updateVisibleRange(chatId: Long, visibleIds: List<Long>, nearbyIds: List<Long>)
    suspend fun onCallbackQueryBuy(chatId: Long, messageId: Long)
    suspend fun sendWebAppResult(botUserId: Long, data: String, buttonText: String = "")
    suspend fun onCallbackQuery(chatId: Long, messageId: Long, data: ByteArray)
    suspend fun searchMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long,
        limit: Int,
        threadId: Long?
    ): SearchChatMessagesResult

    suspend fun getPollVotersModels(
        chatId: Long,
        messageId: Long,
        optionId: Int,
        offset: Int,
        limit: Int
    ): List<UserModel>
    suspend fun getMessageViewersModels(chatId: Long, messageId: Long): List<MessageViewerModel>

    suspend fun getMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long? = null
    ): OlderMessagesPage

    suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageModel>

    suspend fun getMessagesAround(
        chatId: Long,
        messageId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageModel>

    suspend fun getPinnedMessageModel(chatId: Long, threadId: Long? = null): MessageModel?
    suspend fun getAllPinnedMessages(chatId: Long, threadId: Long? = null): List<MessageModel>
    suspend fun getPinnedMessageCount(chatId: Long, threadId: Long? = null): Int
    suspend fun getScheduledMessages(chatId: Long): List<MessageModel>
    suspend fun sendScheduledNow(chatId: Long, messageId: Long)
}
