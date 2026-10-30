package org.monogram.network.bridge.message

import org.monogram.core.common.Outcome
import org.monogram.core.models.ChecklistSendSpec
import org.monogram.core.models.ChecklistTextEdit
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.ContactImportResult
import org.monogram.core.models.ContactImportSpec
import org.monogram.core.models.ContactSendSpec
import org.monogram.core.models.DiscussionRef
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.Message
import org.monogram.core.models.MessageSendOptions
import org.monogram.core.models.MessageViewer
import org.monogram.core.models.MessageViewerPage
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.OutgoingMessageResult
import org.monogram.core.models.PeerId
import org.monogram.core.models.PollSendSpec
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.SavedGif
import org.monogram.core.models.ScheduledMessage
import org.monogram.core.models.UploadItem

data class MessagePage(
    val messages: List<Message>,
    val totalCount: Int,
)

interface MessageOps {
    suspend fun getHistory(chatId: PeerId, limit: Int = 50): Outcome<List<Message>>
    suspend fun getMessage(chatId: PeerId, messageId: Int): Outcome<Message?> =
        Outcome.Err("message lookup unavailable")

    suspend fun getHistoryPage(
        chatId: PeerId,
        limit: Int = 40,
        offsetId: Int = 0,
        offsetDate: Int = 0,
        addOffset: Int = 0,
    ): Outcome<List<Message>>

    suspend fun getHistoryPageBackground(
        chatId: PeerId,
        limit: Int = 40,
        offsetId: Int = 0,
        offsetDate: Int = 0,
        addOffset: Int = 0,
    ): Outcome<List<Message>> = getHistoryPage(chatId, limit, offsetId, offsetDate, addOffset)

    suspend fun searchMessages(
        chatId: PeerId,
        query: String,
        limit: Int = 40
    ): Outcome<List<Message>>

    suspend fun searchMessagesFiltered(
        chatId: PeerId,
        query: String = "",
        filter: String,
        offsetId: Int = 0,
        addOffset: Int = 0,
        limit: Int = 40,
    ): Outcome<List<Message>> = Outcome.Err("unsupported")

    suspend fun searchMessagesPage(
        chatId: PeerId,
        query: String = "",
        filter: String,
        offsetId: Int = 0,
        addOffset: Int = 0,
        limit: Int = 40,
    ): Outcome<MessagePage> = Outcome.Err("unsupported")

    suspend fun getPinnedMessages(chatId: PeerId, limit: Int = 40): Outcome<List<Message>>

    /** Per-user viewers of an outgoing group message. Gated by the caller. */
    suspend fun getMessageViewers(
        chatId: PeerId,
        messageId: Int,
        played: Boolean,
        memberScanLimit: Int,
    ): Outcome<MessageViewers> = Outcome.Err("message viewers unavailable")

    suspend fun getReactionUsersPage(
        chatId: PeerId,
        messageId: Int,
        offset: String?
    ): Outcome<MessageViewerPage> =
        Outcome.Err("reaction pages unavailable")

    suspend fun getPollVotersPage(
        chatId: PeerId,
        messageId: Int,
        offset: String?
    ): Outcome<MessageViewerPage> = Outcome.Err("poll pages unavailable")

    suspend fun getReactionUsers(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<List<MessageViewer>> = Outcome.Err("reaction users unavailable")

    suspend fun getPollVoters(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<List<MessageViewer>> = Outcome.Err("poll voters unavailable")

    /** Outbox read date of an outgoing private message. Gated by the caller. */
    suspend fun getOutboxReadState(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<OutboxReadState> = Outcome.Err("outbox read date unavailable")

    /** `chat_read_mark_size_threshold` and the two receipt expire periods. */
    suspend fun readReceiptConfig(): Outcome<ReadReceiptConfig> =
        Outcome.Ok(ReadReceiptConfig.Fallback)

    suspend fun getScheduledHistory(chatId: PeerId): Outcome<List<ScheduledMessage>> =
        Outcome.Err("scheduled messages unavailable")

    suspend fun getScheduledMessages(
        chatId: PeerId,
        ids: List<Int>
    ): Outcome<List<ScheduledMessage>> =
        Outcome.Err("scheduled messages unavailable")

    suspend fun sendScheduledMessages(chatId: PeerId, ids: List<Int>): Outcome<Unit> =
        Outcome.Err("scheduled messages unavailable")

    suspend fun deleteScheduledMessages(chatId: PeerId, ids: List<Int>): Outcome<Unit> =
        Outcome.Err("scheduled messages unavailable")

    suspend fun editScheduledMessage(
        chatId: PeerId, messageId: Int, scheduleDate: Int,
        text: String? = null, entitiesJson: String? = null, noWebpage: Boolean = false,
    ): Outcome<Unit> = Outcome.Err("scheduled messages unavailable")

    suspend fun getCloudDrafts(): Outcome<List<CloudDraft>> =
        Outcome.Err("cloud drafts unavailable")

    suspend fun saveCloudDraft(draft: CloudDraft): Outcome<Unit> =
        Outcome.Err("cloud drafts unavailable")

    suspend fun importContacts(contacts: List<ContactImportSpec>): Outcome<ContactImportResult> =
        Outcome.Err("contact import unavailable")

    suspend fun sendLocationWithOptions(
        chatId: PeerId,
        place: GeoPlace,
        randomId: Long,
        options: MessageSendOptions,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0
    ): Outcome<OutgoingMessageResult> =
        Outcome.Err("location send unavailable")

    suspend fun sendContactWithOptions(
        chatId: PeerId,
        spec: ContactSendSpec,
        randomId: Long,
        options: MessageSendOptions,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<OutgoingMessageResult> =
        Outcome.Err("contact send unavailable")

    suspend fun sendChecklistWithOptions(
        chatId: PeerId,
        spec: ChecklistSendSpec,
        randomId: Long,
        options: MessageSendOptions,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<OutgoingMessageResult> =
        Outcome.Err("checklist send unavailable")

    suspend fun sendPollWithOptions(
        chatId: PeerId,
        spec: PollSendSpec,
        randomId: Long,
        options: MessageSendOptions,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<OutgoingMessageResult> =
        Outcome.Err("poll send unavailable")

    suspend fun sendDiceWithOptions(
        chatId: PeerId,
        emoticon: String,
        randomId: Long,
        options: MessageSendOptions,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<OutgoingMessageResult> =
        Outcome.Err("dice send unavailable")

    suspend fun sendTextWithOptions(
        chatId: PeerId, text: String, options: MessageSendOptions,
        randomId: Long, replyToMsgId: Int = 0, entitiesJson: String? = null, topMsgId: Int = 0,
        webpageUrl: String? = null,
    ): Outcome<OutgoingMessageResult> =
        Outcome.Err("send options unavailable")

    suspend fun sendText(
        chatId: PeerId,
        text: String,
        replyToMsgId: Int = 0,
        entitiesJson: String? = null,
        topMsgId: Int = 0,
        webpageUrl: String? = null,
        randomId: Long = 0,
    ): Outcome<Message>

    suspend fun sendPhoto(
        chatId: PeerId,
        path: String,
        caption: String,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
        entitiesJson: String? = null,
    ): Outcome<Message>

    suspend fun replaceUploadedMedia(
        chatId: PeerId,
        messageId: Int,
        expectedKey: String,
        item: UploadItem
    ): Outcome<Message> = Outcome.Err("unsupported")

    suspend fun sendUploadedMedia(
        chatId: PeerId,
        item: UploadItem,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
        entitiesJson: String? = null,
    ): Outcome<Message> = Outcome.Err("unsupported")

    suspend fun sendUploadedAlbum(
        chatId: PeerId,
        items: List<UploadItem>,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<List<Message>> = Outcome.Err("unsupported")

    suspend fun sendUploadedMediaWithOptions(
        chatId: PeerId, item: UploadItem, options: MessageSendOptions,
        replyToMsgId: Int = 0, topMsgId: Int = 0, entitiesJson: String? = null,
    ): Outcome<OutgoingMessageResult> = Outcome.Err("unsupported")

    suspend fun sendUploadedAlbumWithOptions(
        chatId: PeerId,
        items: List<UploadItem>,
        options: MessageSendOptions,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<List<OutgoingMessageResult>> = Outcome.Err("unsupported")

    suspend fun editText(
        chatId: PeerId,
        messageId: Int,
        text: String,
        entitiesJson: String? = null,
    ): Outcome<Message>

    suspend fun deleteMessage(chatId: PeerId, messageId: Int, revoke: Boolean): Outcome<Unit>
    suspend fun forwardMessage(
        fromChatId: PeerId,
        messageId: Int,
        toChatId: PeerId,
    ): Outcome<List<Message>>

    suspend fun forwardMessages(
        fromChatId: PeerId,
        messageIds: List<Int>,
        toChatId: PeerId,
        dropAuthor: Boolean,
    ): Outcome<List<Message>> = Outcome.Err("unsupported")

    suspend fun toggleTodoCompleted(
        chatId: PeerId,
        messageId: Int,
        completed: List<Int>,
        incompleted: List<Int>,
    ): Outcome<Unit> = Outcome.Err("unsupported")

    suspend fun editLiveLocation(
        chatId: PeerId,
        messageId: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        heading: Int? = null,
        stopped: Boolean = false
    ): Outcome<Unit> = Outcome.Err("unsupported")

    /** Sends a static pin, or a live location when [livePeriodSeconds] is positive. */
    suspend fun sendLocation(
        chatId: PeerId,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int = 0,
        heading: Int = 0,
        replyToMsgId: Int? = null,
    ): Outcome<Unit> = Outcome.Err("unsupported")

    /** Votes in a poll; [options] are the raw option bytes from the poll payload. */
    suspend fun removeChecklistItem(
        chatId: PeerId,
        messageId: Int,
        edit: ChecklistTextEdit
    ): Outcome<Unit> = Outcome.Err("unsupported")

    suspend fun editChecklistText(
        chatId: PeerId,
        messageId: Int,
        edit: ChecklistTextEdit
    ): Outcome<Unit> =
        Outcome.Err("unsupported")

    suspend fun editChecklistTitle(chatId: PeerId, messageId: Int, title: String): Outcome<Unit> =
        Outcome.Err("checklist editing unavailable")

    suspend fun closePoll(chatId: PeerId, messageId: Int): Outcome<Unit> =
        Outcome.Err("poll close unavailable")

    suspend fun sendPollVote(
        chatId: PeerId,
        messageId: Int,
        options: List<ByteArray>,
    ): Outcome<Unit> = Outcome.Err("unsupported")

    /** Appends checklist items, continuing ids from [firstId]. */
    suspend fun appendChecklistItems(
        chatId: PeerId,
        messageId: Int,
        firstId: Int,
        titles: List<String>,
    ): Outcome<Unit> = Outcome.Err("unsupported")

    suspend fun getSavedGifs(): Outcome<List<SavedGif>> =
        Outcome.Err("unsupported")

    suspend fun sendSavedGif(
        chatId: PeerId,
        documentId: Long,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<Message> = Outcome.Err("unsupported")

    suspend fun sendReaction(
        chatId: PeerId,
        messageId: Int,
        emoticon: String = "",
        documentId: Long = 0L,
    ): Outcome<Unit> = Outcome.Err("unsupported")

    suspend fun getDiscussionMessage(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<DiscussionRef> = Outcome.Err("unsupported")

    suspend fun getRecentReactions(): Outcome<List<ReactionChoice>> =
        Outcome.Err("unsupported")

    suspend fun getReplies(
        chatId: PeerId,
        msgId: Int,
        limit: Int,
        offsetId: Int = 0,
        addOffset: Int = 0,
    ): Outcome<List<Message>> = Outcome.Err("unsupported")

    /** https://core.telegram.org/method/messages.getUnreadMentions */
    suspend fun getUnreadMentions(
        chatId: PeerId,
        offsetId: Int = 0,
        addOffset: Int = 0,
        limit: Int = 1,
        topMsgId: Int = 0,
    ): Outcome<List<Message>> = Outcome.Err("unsupported")

    /** https://core.telegram.org/method/messages.readMentions */
    suspend fun readMentions(chatId: PeerId, topMsgId: Int = 0): Outcome<Unit> =
        Outcome.Err("unsupported")

    /** Acknowledges only the supplied mention/reaction messages. */
    suspend fun readMessageContents(chatId: PeerId, messageIds: List<Int>): Outcome<Unit> =
        Outcome.Err("unsupported")

    /** https://core.telegram.org/method/messages.getUnreadReactions */
    suspend fun getUnreadReactions(
        chatId: PeerId,
        offsetId: Int = 0,
        addOffset: Int = 0,
        limit: Int = 1,
        topMsgId: Int = 0,
    ): Outcome<List<Message>> = Outcome.Err("unsupported")

    /** https://core.telegram.org/method/messages.readReactions */
    suspend fun readReactions(chatId: PeerId, topMsgId: Int = 0): Outcome<Unit> =
        Outcome.Err("unsupported")
}
