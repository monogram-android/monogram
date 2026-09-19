package org.monogram.network.bridge.message

import org.monogram.core.common.Outcome
import org.monogram.core.models.DiscussionRef
import org.monogram.core.models.Message
import org.monogram.core.models.MessageViewer
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.SavedGif
import org.monogram.core.models.UploadItem

interface MessageOps {
    suspend fun getHistory(chatId: PeerId, limit: Int = 50): Outcome<List<Message>>
    suspend fun getHistoryPage(
        chatId: PeerId,
        limit: Int = 40,
        offsetId: Int = 0,
        offsetDate: Int = 0,
        addOffset: Int = 0,
    ): Outcome<List<Message>>

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

    suspend fun getPinnedMessages(chatId: PeerId, limit: Int = 40): Outcome<List<Message>>

    /** Per-user viewers of an outgoing group message. Gated by the caller. */
    suspend fun getMessageViewers(
        chatId: PeerId,
        messageId: Int,
        played: Boolean,
        memberScanLimit: Int,
    ): Outcome<MessageViewers> = Outcome.Err("message viewers unavailable")

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

    suspend fun sendText(
        chatId: PeerId,
        text: String,
        replyToMsgId: Int = 0,
        entitiesJson: String? = null,
        topMsgId: Int = 0,
    ): Outcome<Message>

    suspend fun sendPhoto(
        chatId: PeerId,
        path: String,
        caption: String,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
        entitiesJson: String? = null,
    ): Outcome<Message>

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

    suspend fun toggleTodoCompleted(
        chatId: PeerId,
        messageId: Int,
        completed: List<Int>,
        incompleted: List<Int>,
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
