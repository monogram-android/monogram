package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.*
import org.monogram.domain.models.webapp.InstantViewModel
import org.monogram.domain.models.webapp.InvoiceModel
import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel

sealed interface ReadUpdate {
    val chatId: Long
    val messageId: Long
    data class Inbox(override val chatId: Long, override val messageId: Long) : ReadUpdate
    data class Outbox(override val chatId: Long, override val messageId: Long) : ReadUpdate
}
enum class ProfileMediaFilter {
    MEDIA,
    FILES,
    AUDIO,
    VOICE,
    LINKS,
    GIFS
}
data class SearchChatMessagesResult(
    val messages: List<MessageModel>,
    val totalCount: Int,
    val nextFromMessageId: Long
)

data class OlderMessagesPage(
    val messages: List<MessageModel>,
    val reachedOldest: Boolean,
    val isRemote: Boolean
)

interface MessageRepository {
    val newMessageFlow: Flow<MessageModel>
    val senderUpdateFlow: Flow<Long>
    val messageReadFlow: Flow<ReadUpdate>
    val messageUploadProgressFlow: Flow<Pair<Long, Float>>
    val messageDownloadProgressFlow: Flow<Pair<Long, Float>>
    val messageDownloadCancelledFlow: Flow<Long>
    val messageDownloadCompletedFlow: Flow<Triple<Long, Int, String>>
    val messageDeletedFlow: Flow<Pair<Long, List<Long>>>
    val messageEditedFlow: Flow<MessageModel>
    val messageIdUpdateFlow: Flow<Triple<Long, Long, MessageModel>>
    val pinnedMessageFlow: Flow<Long>
    val mediaUpdateFlow: Flow<Unit>
    suspend fun getHighResFileId(chatId: Long, messageId: Long): Int?
    suspend fun getFileInfo(fileId: Int): FileModel?
    suspend fun getProfileMedia(
        chatId: Long,
        filter: ProfileMediaFilter,
        fromMessageId: Long,
        limit: Int
    ): List<MessageModel>
    suspend fun openChat(chatId: Long)
    suspend fun closeChat(chatId: Long)

    suspend fun sendVideoNote(chatId: Long, videoPath: String, duration: Int, length: Int)
    suspend fun sendVoiceNote(chatId: Long, voicePath: String, duration: Int, waveform: ByteArray)

    suspend fun getMessagesOlder(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long? = null
    ): OlderMessagesPage

    suspend fun getCachedMessages(chatId: Long, limit: Int): List<MessageModel>

    suspend fun getMessagesNewer(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
        threadId: Long? = null
    ): List<MessageModel>

    suspend fun getMessagesAround(chatId: Long, messageId: Long, limit: Int, threadId: Long? = null): List<MessageModel>

    @Deprecated("Use getMessagesOlder instead")
    suspend fun getMessages(chatId: Long, fromMessageId: Long, limit: Int): List<MessageModel>

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyToMsgId: Long? = null,
        entities: List<MessageEntity> = emptyList(),
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendSticker(chatId: Long, stickerPath: String, replyToMsgId: Long? = null, threadId: Long? = null)
    suspend fun sendPhoto(
        chatId: Long,
        photoPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendVideo(
        chatId: Long,
        videoPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendDocument(
        chatId: Long,
        documentPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendGif(
        chatId: Long,
        gifId: String,
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendGifFile(
        chatId: Long,
        gifPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendChatAction(chatId: Long, action: ChatAction, threadId: Long? = null)
    suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int
    suspend fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerModel>
    suspend fun summarizeMessage(chatId: Long, messageId: Long, toLanguageCode: String = ""): String?
    suspend fun translateMessage(chatId: Long, messageId: Long, toLanguageCode: String): String?
    suspend fun addMessageReaction(chatId: Long, messageId: Long, reaction: String)
    suspend fun removeMessageReaction(chatId: Long, messageId: Long, reaction: String)
    suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: List<Int>)
    suspend fun stopPoll(chatId: Long, messageId: Long)
    suspend fun getPollVoters(
        chatId: Long,
        messageId: Long,
        optionId: Int,
        offset: Int,
        limit: Int
    ): List<UserModel>

    suspend fun getWebPageInstantView(url: String, forceFull: Boolean = false): InstantViewModel?

    suspend fun searchMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long = 0,
        limit: Int = 50,
        threadId: Long? = null
    ): SearchChatMessagesResult

    fun updateVisibleRange(chatId: Long, visibleMessageIds: List<Long>, nearbyMessageIds: List<Long>)

    suspend fun onCallbackQuery(chatId: Long, messageId: Long, data: ByteArray)

    suspend fun openWebApp(
        chatId: Long,
        botUserId: Long,
        url: String,
        themeParams: ThemeParams? = null
    ): WebAppInfoModel?

    suspend fun closeWebApp(launchId: Long)

    suspend fun getInvoice(slug: String? = null, chatId: Long? = null, messageId: Long? = null): InvoiceModel?

    suspend fun payInvoice(slug: String? = null, chatId: Long? = null, messageId: Long? = null): Boolean

    suspend fun getFilePath(fileId: Int): String?

    suspend fun onCallbackQueryBuy(chatId: Long, messageId: Long)

    suspend fun sendWebAppResult(launchId: Long, queryId: String)

    sealed interface ChatAction {
        data object Typing : ChatAction
        data object RecordingVideo : ChatAction
        data object RecordingVoice : ChatAction
        data object UploadingPhoto : ChatAction
        data object UploadingVideo : ChatAction
        data object UploadingDocument : ChatAction
        data object ChoosingSticker : ChatAction
        data object Cancel : ChatAction
    }

    suspend fun sendAlbum(
        chatId: Long,
        paths: List<String>,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun getScheduledMessages(chatId: Long): List<MessageModel>
    suspend fun sendScheduledNow(chatId: Long, messageId: Long)

    suspend fun forwardMessage(toChatId: Long, fromChatId: Long, messageId: Long)
    suspend fun deleteMessage(chatId: Long, messageIds: List<Long>, revoke: Boolean = false)
    suspend fun editMessage(chatId: Long, messageId: Long, newText: String, entities: List<MessageEntity> = emptyList())
    suspend fun markAsRead(chatId: Long, messageId: Long)
    suspend fun markAllMentionsAsRead(chatId: Long)
    suspend fun markAllReactionsAsRead(chatId: Long)
    suspend fun getChatDraft(chatId: Long, threadId: Long? = null): String?
    suspend fun saveChatDraft(chatId: Long, text: String, replyToMsgId: Long?, threadId: Long? = null)
    suspend fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean = false)
    suspend fun unpinMessage(chatId: Long, messageId: Long)
    suspend fun getPinnedMessage(chatId: Long, threadId: Long? = null): MessageModel?
    suspend fun getAllPinnedMessages(chatId: Long, threadId: Long? = null): List<MessageModel>
    suspend fun getPinnedMessageCount(chatId: Long, threadId: Long? = null): Int
    fun downloadFile(
        fileId: Int,
        priority: Int = 1,
        offset: Long = 0,
        limit: Long = 0,
        synchronous: Boolean = false
    )
    fun invalidateSenderCache(userId: Long)
    suspend fun cancelDownloadFile(fileId: Int)
    suspend fun joinChat(chatId: Long)
    suspend fun restrictChatMember(chatId: Long, userId: Long, permissions: ChatPermissionsModel, untilDate: Int = 0)

    suspend fun getInlineBotResults(
        botUserId: Long,
        chatId: Long,
        query: String,
        offset: String = ""
    ): InlineBotResultsModel?

    suspend fun sendInlineBotResult(
        chatId: Long,
        queryId: Long,
        resultId: String,
        replyToMsgId: Long? = null,
        threadId: Long? = null
    )

    suspend fun getChatEventLog(
        chatId: Long,
        query: String = "",
        fromEventId: Long = 0,
        limit: Int = 50,
        filters: ChatEventLogFiltersModel = ChatEventLogFiltersModel(),
        userIds: List<Long> = emptyList()
    ): List<ChatEventModel>

    fun clearMessages(chatId: Long)
    fun clearAllCache()
}

data class InlineBotResultsModel(
    val queryId: Long,
    val nextOffset: String,
    val results: List<InlineQueryResultModel>,
    val switchPmText: String? = null,
    val switchPmParameter: String? = null
)
