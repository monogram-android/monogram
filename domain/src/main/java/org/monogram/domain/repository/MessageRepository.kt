package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.ConversationUpdate
import org.monogram.domain.models.DraftLinkPreview
import org.monogram.domain.models.DraftLinkPreviewRequest
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageDeletedEvent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageIdUpdatedEvent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendAcknowledgedEvent
import org.monogram.domain.models.MessageSendFailedEvent
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.MessageUploadProgressEvent
import org.monogram.domain.models.MessageViewerModel
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.SponsoredMessagesFeedModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.webapp.InstantViewModel

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

data class MediaAutoDownloadPolicy(
    val enabled: Boolean,
    val allowFiles: Boolean
) {
    companion object {
        val Disabled = MediaAutoDownloadPolicy(enabled = false, allowFiles = false)
    }
}

sealed interface ConversationScope {
    data object Main : ConversationScope
    data class ForumTopic(val topicId: Long) : ConversationScope
    data class MessageThread(val threadId: Long) : ConversationScope
}

data class ConversationKey(
    val chatId: Long,
    val scope: ConversationScope = ConversationScope.Main
) {
    /** TDLib still addresses topic/thread history through a shared numeric argument. */
    val threadId: Long?
        get() = when (val value = scope) {
            ConversationScope.Main -> null
            is ConversationScope.ForumTopic -> value.topicId
            is ConversationScope.MessageThread -> value.threadId
        }

    val scopeType: String
        get() = when (scope) {
            ConversationScope.Main -> "main"
            is ConversationScope.ForumTopic -> "forum_topic"
            is ConversationScope.MessageThread -> "message_thread"
        }

    val scopeId: Long
        get() = when (val value = scope) {
            ConversationScope.Main -> 0L
            is ConversationScope.ForumTopic -> value.topicId
            is ConversationScope.MessageThread -> value.threadId
        }
}

sealed interface HistoryAnchor {
    data object Latest : HistoryAnchor
    data class Message(val id: Long) : HistoryAnchor
}

enum class HistoryDirection { Initial, Older, Newer, Around }
enum class HistorySource { RoomSnapshot, TdlibLocal, TdlibNetwork, CacheFallback }

sealed interface BoundaryState {
    data object Reached : BoundaryState
    data object Open : BoundaryState
    data class Gap(val anchorId: Long) : BoundaryState
}

data class HistoryRequest(
    val key: ConversationKey,
    val anchor: HistoryAnchor,
    val direction: HistoryDirection,
    val limit: Int,
    val source: HistorySource = HistorySource.TdlibNetwork
)

data class HistoryPage(
    val messages: List<MessageModel>,
    val olderBoundary: BoundaryState,
    val newerBoundary: BoundaryState,
    val source: HistorySource
)

data class MessageThreadContext(
    val chatId: Long,
    val threadId: Long
)

data class ForwardTarget(
    val chatId: Long,
    val forumTopicId: Int? = null
)

data class ForwardOptions(
    val sendCopy: Boolean = false,
    val removeCaption: Boolean = false,
    val commentText: String = "",
    val commentEntities: List<MessageEntity> = emptyList()
)

data class ForwardRequest(
    val fromChatId: Long,
    val messageIds: List<Long>,
    val targets: List<ForwardTarget>,
    val options: ForwardOptions = ForwardOptions()
)

data class ChecklistDraft(
    val title: String,
    val titleEntities: List<MessageEntity> = emptyList(),
    val tasks: List<ChecklistTaskDraft>,
    val othersCanAddTasks: Boolean = false,
    val othersCanMarkTasksAsDone: Boolean = false
)

data class ChecklistTaskDraft(
    val id: Int,
    val text: String,
    val entities: List<MessageEntity> = emptyList()
)

interface MessageRepository :
    FileRepository,
    InlineBotRepository,
    ChatEventLogRepository,
    MessageAiRepository,
    PaymentRepository,
    WebAppRepository {
    suspend fun getHistoryPage(request: HistoryRequest): HistoryPage
    val newMessageFlow: Flow<MessageModel>
    val conversationUpdates: Flow<ConversationUpdate>
        get() = emptyFlow()
    val senderUpdateFlow: Flow<Long>
    val messageReadFlow: Flow<ReadUpdate>
    val messageUploadProgressFlow: Flow<MessageUploadProgressEvent>
    val messageDeletedFlow: Flow<MessageDeletedEvent>
    val messageEditedFlow: Flow<MessageModel>
    val messageIdUpdateFlow: Flow<MessageIdUpdatedEvent>
    val messageAcknowledgedFlow: Flow<MessageSendAcknowledgedEvent>
        get() = emptyFlow()
    val messageSendFailedFlow: Flow<MessageSendFailedEvent>
        get() = emptyFlow()
    val pinnedMessageFlow: Flow<Long>
    val mediaUpdateFlow: Flow<Unit>
    suspend fun getHighResFileId(chatId: Long, messageId: Long): Int?
    suspend fun getProfileMedia(
        chatId: Long,
        filter: ProfileMediaFilter,
        fromMessageId: Long,
        limit: Int
    ): List<MessageModel>
    suspend fun openChat(chatId: Long, ownerTag: String = "unknown")
    suspend fun closeChat(chatId: Long, ownerTag: String = "unknown")

    suspend fun sendVideoNote(
        chatId: Long,
        videoPath: String,
        duration: Int,
        length: Int,
        replyToMsgId: Long? = null,
        threadId: Long? = null
    )

    suspend fun sendVoiceNote(
        chatId: Long,
        voicePath: String,
        duration: Int,
        waveform: ByteArray,
        replyToMsgId: Long? = null,
        threadId: Long? = null
    )

    suspend fun getChatMessageByDate(chatId: Long, dateEpochSeconds: Int): MessageModel?

    suspend fun getMessageThreadContext(chatId: Long, messageId: Long): MessageThreadContext?

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        replyToMsgId: Long? = null,
        entities: List<MessageEntity> = emptyList(),
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    /** Retries a TDLib-owned failed outgoing operation without creating another local bubble. */
    suspend fun retryFailedMessage(chatId: Long, temporaryMessageId: Long) = Unit

    suspend fun sendRichMessage(
        chatId: Long,
        markdown: String,
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions(),
        isRtl: Boolean? = null,
        detectAutomaticBlocks: Boolean = true,
        parseMode: RichTextParseMode = RichTextParseMode.Markdown
    )

    suspend fun sendSticker(chatId: Long, stickerPath: String, replyToMsgId: Long? = null, threadId: Long? = null)
    suspend fun sendPhoto(
        chatId: Long,
        photoPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        showCaptionAboveMedia: Boolean = false,
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendVideo(
        chatId: Long,
        videoPath: String,
        caption: String = "",
        captionEntities: List<MessageEntity> = emptyList(),
        showCaptionAboveMedia: Boolean = false,
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

    suspend fun sendPoll(
        chatId: Long,
        poll: PollDraft,
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendChecklist(
        chatId: Long,
        checklistDraft: ChecklistDraft,
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
        showCaptionAboveMedia: Boolean = false,
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun sendChatAction(chatId: Long, action: ChatAction, threadId: Long? = null)
    suspend fun getMessageReadDate(chatId: Long, messageId: Long, messageDate: Int): Int
    suspend fun getMessageViewers(chatId: Long, messageId: Long): List<MessageViewerModel>
    suspend fun getRawMessageJson(chatId: Long, messageId: Long): String?
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
    suspend fun getFullRichMessage(chatId: Long, messageId: Long): MessageContent.RichMessage?
    suspend fun getDraftLinkPreview(request: DraftLinkPreviewRequest): DraftLinkPreview?
    suspend fun getChannelSponsoredMessages(chatId: Long): SponsoredMessagesFeedModel?
    suspend fun clickChannelSponsoredMessage(
        chatId: Long,
        messageId: Long,
        isMediaClick: Boolean,
        fromFullscreen: Boolean = false
    )

    suspend fun searchMessages(
        chatId: Long,
        query: String,
        fromMessageId: Long = 0,
        limit: Int = 50,
        threadId: Long? = null,
        senderId: Long? = null
    ): SearchChatMessagesResult

    fun updateVisibleRange(
        chatId: Long,
        visibleMessageIds: List<Long>,
        nearbyMessageIds: List<Long>,
        policy: MediaAutoDownloadPolicy = MediaAutoDownloadPolicy.Disabled
    )

    /** Prevents bounded Room cleanup from removing the persisted viewport anchor. */
    fun updateCachedViewportAnchor(key: ConversationKey, messageId: Long?)

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
        showCaptionAboveMedia: Boolean = false,
        replyToMsgId: Long? = null,
        threadId: Long? = null,
        sendOptions: MessageSendOptions = MessageSendOptions()
    )

    suspend fun getScheduledMessages(chatId: Long): List<MessageModel>
    suspend fun sendScheduledNow(chatId: Long, messageId: Long)

    suspend fun forwardMessage(
        toChatId: Long,
        fromChatId: Long,
        messageId: Long,
        sendCopy: Boolean = false
    )
    suspend fun forwardMessages(request: ForwardRequest)
    suspend fun deleteMessage(chatId: Long, messageIds: List<Long>, revoke: Boolean = false)
    suspend fun editMessage(chatId: Long, messageId: Long, newText: String, entities: List<MessageEntity> = emptyList())
    suspend fun editRichMessage(
        chatId: Long,
        messageId: Long,
        markdown: String,
        isRtl: Boolean? = null,
        detectAutomaticBlocks: Boolean = true,
        parseMode: RichTextParseMode = RichTextParseMode.Markdown
    )
    suspend fun editMessageCaption(chatId: Long, messageId: Long, newCaption: String, entities: List<MessageEntity> = emptyList())
    suspend fun editChecklistMessage(chatId: Long, messageId: Long, checklistDraft: ChecklistDraft)
    suspend fun markChecklistTasksAsDone(
        chatId: Long,
        messageId: Long,
        doneIds: List<Int>,
        undoneIds: List<Int>
    )
    suspend fun markAsRead(chatId: Long, messageId: Long) =
        markMessagesAsRead(chatId, listOf(messageId))

    suspend fun markMessagesAsRead(
        chatId: Long,
        messageIds: List<Long>,
        threadId: Long? = null
    )
    suspend fun markAllMentionsAsRead(chatId: Long)
    suspend fun markAllReactionsAsRead(chatId: Long)
    suspend fun getChatDraft(chatId: Long, threadId: Long? = null): String?
    suspend fun saveChatDraft(chatId: Long, text: String, replyToMsgId: Long?, threadId: Long? = null)
    suspend fun pinMessage(chatId: Long, messageId: Long, disableNotification: Boolean = false)
    suspend fun unpinMessage(chatId: Long, messageId: Long)
    suspend fun getPinnedMessage(chatId: Long, threadId: Long? = null): MessageModel?
    suspend fun getAllPinnedMessages(chatId: Long, threadId: Long? = null): List<MessageModel>
    suspend fun getPinnedMessageCount(chatId: Long, threadId: Long? = null): Int
    fun invalidateSenderCache(userId: Long)
    suspend fun joinChat(chatId: Long)
    suspend fun restrictChatMember(chatId: Long, userId: Long, permissions: ChatPermissionsModel, untilDate: Int = 0)

    fun clearMessages(chatId: Long)
    fun clearAllCache()
}
