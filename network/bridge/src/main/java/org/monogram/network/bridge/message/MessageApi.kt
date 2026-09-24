package org.monogram.network.bridge.message

import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
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
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.profile.ProfileOps
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.bridge.session.SessionCore

internal class MessageApi(
    private val core: SessionCore,
    private val profile: ProfileOps,
) : MessageOps {
    override suspend fun getHistory(chatId: PeerId, limit: Int): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        val historyAt = if (PerfLog.isEnabled()) System.nanoTime() else 0L
        val outcome = withTimeoutOrNull(core.historyTimeoutMs) {
            core.rpc("getHistory failed") { activeHandle ->
                core.native.getHistory(activeHandle, chatId.value, limit).map { it.toModel() }
            }
        } ?: core.fail(Exception("RPC timeout"), "getHistory failed")
        if (historyAt != 0L) {
            PerfLog.trace(
                op = "getHistory",
                phase = "page",
                elapsedMs = (System.nanoTime() - historyAt) / 1_000_000,
                handle = core.activeHandleOrZero(),
                dispatchClass = DispatchClass.INTERACTIVE_READ,
                result = if (outcome is Outcome.Ok) "ok" else "err",
            )
        }
        return outcome
    }

    override suspend fun getHistoryPage(
        chatId: PeerId,
        limit: Int,
        offsetId: Int,
        offsetDate: Int,
        addOffset: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getHistoryPage failed") { activeHandle ->
            core.native.getHistoryPage(
                activeHandle,
                chatId.value,
                limit,
                offsetId,
                offsetDate,
                addOffset,
            ).map { it.toModel() }
        }
    }

    override suspend fun searchMessages(
        chatId: PeerId,
        query: String,
        limit: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("searchMessages failed") { activeHandle ->
            core.native.searchMessages(activeHandle, chatId.value, query, limit)
                .map { it.toModel() }
        }
    }

    override suspend fun searchMessagesFiltered(
        chatId: PeerId,
        query: String,
        filter: String,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("searchMessagesFiltered failed") { activeHandle ->
            core.native.searchMessagesFiltered(
                activeHandle,
                chatId.value,
                query,
                filter,
                offsetId,
                addOffset,
                limit,
            ).map { it.toModel() }
        }
    }

    override suspend fun getPinnedMessages(
        chatId: PeerId,
        limit: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getPinnedMessages failed") { activeHandle ->
            core.native.getPinnedMessages(activeHandle, chatId.value, limit).map { it.toModel() }
        }
    }

    override suspend fun getMessageViewers(
        chatId: PeerId,
        messageId: Int,
        played: Boolean,
        memberScanLimit: Int,
    ): Outcome<MessageViewers> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        val dto = core.rpcBackground("getMessageReadParticipants failed") { activeHandle ->
            core.native.getMessageReadParticipants(activeHandle, chatId.value, messageId)
        }
        val viewers = when (dto) {
            is Outcome.Err -> return dto
            is Outcome.Ok -> readParticipantsOutcome(dto.value, played)
        }
        if (viewers !is MessageViewers.Ready || viewers.viewers.isEmpty()) return Outcome.Ok(viewers)
        // Names come from one member page: read receipts only exist for groups
        // at or below the threshold, so a single page covers every reader.
        val members = when (
            val page = profile.getProfileMembers(chatId, limit = memberScanLimit.coerceIn(1, 200))
        ) {
            is Outcome.Ok -> page.value.members
            is Outcome.Err -> emptyList()
        }
        return Outcome.Ok(viewers.copy(viewers = attachViewerProfiles(viewers.viewers, members)))
    }

    override suspend fun getReactionUsers(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<List<MessageViewer>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getMessageReactionsList failed") { activeHandle ->
            core.native.getMessageReactionsList(
                activeHandle,
                chatId.value,
                messageId
            ).peers.map { row -> row.toViewer() }
        }
    }

    override suspend fun getPollVoters(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<List<MessageViewer>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getPollVotes failed") { activeHandle ->
            core.native.getPollVotes(activeHandle, chatId.value, messageId).voters.map { row ->
                row.toViewer()
            }
        }
    }

    override suspend fun getOutboxReadState(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<OutboxReadState> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getOutboxReadDate failed") { activeHandle ->
            core.native.getOutboxReadDate(activeHandle, chatId.value, messageId)
                .let(::outboxReadOutcome)
        }
    }

    override suspend fun readReceiptConfig(): Outcome<ReadReceiptConfig> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getReadReceiptConfig failed") { activeHandle ->
            core.native.getReadReceiptConfig(activeHandle).toModel()
        }
    }

    override suspend fun sendText(
        chatId: PeerId,
        text: String,
        replyToMsgId: Int,
        entitiesJson: String?,
        topMsgId: Int,
        webpageUrl: String?,
    ): Outcome<Message> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("sendText", "start entities=${entitiesJson?.length ?: 0}")
        val sendAt = if (PerfLog.isEnabled()) System.nanoTime() else 0L
        PerfLog.trace(
            op = "sendText",
            phase = "bridge_send",
            handle = core.activeHandleOrZero(),
            dispatchClass = DispatchClass.INTERACTIVE_WRITE,
        )
        val outcome = core.rpcWrite("sendText failed") { activeHandle ->
            val sent = core.native.sendTextMessage(
                activeHandle,
                chatId.value,
                text,
                replyToMsgId,
                entitiesJson,
                topMsgId,
                webpageUrl,
            ).toModel()
            AppLog.api("sendText", "ok id=${sent.id.id}")
            sent
        }
        if (sendAt != 0L) {
            PerfLog.trace(
                op = "sendText",
                phase = "rpc_done",
                elapsedMs = (System.nanoTime() - sendAt) / 1_000_000,
                handle = core.activeHandleOrZero(),
                dispatchClass = DispatchClass.INTERACTIVE_WRITE,
                result = if (outcome is Outcome.Ok) "ok" else "err",
            )
        }
        return outcome
    }

    override suspend fun sendPhoto(
        chatId: PeerId,
        path: String,
        caption: String,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): Outcome<Message> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("sendPhoto", "start")
        return core.rpcWrite("sendPhoto failed") { activeHandle ->
            val sent = core.native.sendPhotoMessage(
                activeHandle,
                chatId.value,
                path,
                caption,
                replyToMsgId,
                topMsgId,
                entitiesJson,
            ).toModel()
            AppLog.api("sendPhoto", "ok id=${sent.id.id}")
            sent
        }
    }

    override suspend fun sendUploadedMedia(
        chatId: PeerId,
        item: UploadItem,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): Outcome<Message> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("sendUploadedMedia failed") { activeHandle ->
            core.native.sendUploadedMedia(
                activeHandle,
                chatId.value,
                item.toDto(),
                replyToMsgId,
                topMsgId,
                entitiesJson,
            ).toModel()
        }
    }

    override suspend fun sendUploadedAlbum(
        chatId: PeerId,
        items: List<UploadItem>,
        replyToMsgId: Int,
        topMsgId: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("sendUploadedAlbum failed") { activeHandle ->
            core.native.sendUploadedAlbum(
                activeHandle,
                chatId.value,
                items.map { it.toDto() },
                replyToMsgId,
                topMsgId,
            ).map { it.toModel() }
        }
    }

    override suspend fun editText(
        chatId: PeerId,
        messageId: Int,
        text: String,
        entitiesJson: String?,
    ): Outcome<Message> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("editText", "start")
        return core.rpcWrite("editText failed") { activeHandle ->
            val sent = core.native.editTextMessage(
                activeHandle,
                chatId.value,
                messageId,
                text,
                entitiesJson,
            ).toModel()
            AppLog.api("editText", "ok id=${sent.id.id}")
            sent
        }
    }

    override suspend fun forwardMessage(
        fromChatId: PeerId,
        messageId: Int,
        toChatId: PeerId,
    ): Outcome<List<Message>> = forwardMessages(
        fromChatId = fromChatId,
        messageIds = listOf(messageId),
        toChatId = toChatId,
        dropAuthor = false,
    )

    override suspend fun forwardMessages(
        fromChatId: PeerId,
        messageIds: List<Int>,
        toChatId: PeerId,
        dropAuthor: Boolean,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("forwardMessages", "start count=${messageIds.size} dropAuthor=$dropAuthor")
        return core.rpcWrite("forwardMessages failed") { activeHandle ->
            val sent = core.native.forwardMessages(
                activeHandle,
                fromChatId.value,
                messageIds,
                toChatId.value,
                dropAuthor,
            ).map { it.toModel() }
            AppLog.api("forwardMessages", "ok count=${sent.size}")
            sent
        }
    }

    override suspend fun deleteMessage(
        chatId: PeerId,
        messageId: Int,
        revoke: Boolean,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        AppLog.api("deleteMessage", "start")
        return core.rpcWrite("deleteMessage failed") { activeHandle ->
            core.native.deleteMessage(activeHandle, chatId.value, messageId, revoke)
            AppLog.api("deleteMessage", "ok id=$messageId")
        }
    }

    override suspend fun toggleTodoCompleted(
        chatId: PeerId,
        messageId: Int,
        completed: List<Int>,
        incompleted: List<Int>,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("toggleTodoCompleted failed") { activeHandle ->
            core.native.toggleTodoCompleted(
                activeHandle,
                chatId.value,
                messageId,
                completed,
                incompleted
            )
        }
    }

    override suspend fun sendLocation(
        chatId: PeerId,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int,
        heading: Int,
        replyToMsgId: Int?,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("sendLocation failed") { activeHandle ->
            core.native.sendLocation(
                activeHandle,
                chatId.value,
                latitude,
                longitude,
                livePeriodSeconds,
                heading,
                replyToMsgId ?: 0,
            )
        }
    }

    override suspend fun sendPollVote(
        chatId: PeerId,
        messageId: Int,
        options: List<ByteArray>,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("sendPollVote failed") { activeHandle ->
            core.native.sendPollVote(activeHandle, chatId.value, messageId, options)
        }
    }

    override suspend fun appendChecklistItems(
        chatId: PeerId,
        messageId: Int,
        firstId: Int,
        titles: List<String>,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("appendChecklistItems failed") { activeHandle ->
            core.native.appendTodoItems(activeHandle, chatId.value, messageId, firstId, titles)
        }
    }

    override suspend fun getSavedGifs(): Outcome<List<SavedGif>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getSavedGifs failed") { activeHandle ->
            core.native.getSavedGifs(activeHandle).map {
                SavedGif(
                    documentId = it.documentId,
                    cacheKey = it.cacheKey,
                    thumbCacheKey = it.thumbCacheKey,
                )
            }
        }
    }

    override suspend fun sendSavedGif(
        chatId: PeerId,
        documentId: Long,
        replyToMsgId: Int,
        topMsgId: Int,
    ): Outcome<Message> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("sendSavedGif failed") { activeHandle ->
            core.native.sendSavedGif(activeHandle, chatId.value, documentId, replyToMsgId, topMsgId)
                .toModel()
        }
    }

    override suspend fun sendReaction(
        chatId: PeerId,
        messageId: Int,
        emoticon: String,
        documentId: Long,
    ): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcWrite("sendReaction failed") { activeHandle ->
            core.native.sendReaction(activeHandle, chatId.value, messageId, emoticon, documentId)
        }
    }

    override suspend fun getDiscussionMessage(
        chatId: PeerId,
        messageId: Int,
    ): Outcome<DiscussionRef> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getDiscussionMessage failed") { activeHandle ->
            val dto = core.native.getDiscussionMessage(activeHandle, chatId.value, messageId)
            DiscussionRef(
                chatId = PeerId(dto.chatId),
                messageId = dto.messageId,
            )
        }
    }

    override suspend fun getReplies(
        chatId: PeerId,
        msgId: Int,
        limit: Int,
        offsetId: Int,
        addOffset: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getReplies failed") { activeHandle ->
            core.native.getReplies(activeHandle, chatId.value, msgId, limit, offsetId, addOffset)
                .map { it.toModel() }
        }
    }

    override suspend fun getRecentReactions(): Outcome<List<ReactionChoice>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getRecentReactions failed") { activeHandle ->
            core.native.getRecentReactions(activeHandle).map {
                ReactionChoice(
                    emoticon = it.emoticon,
                    documentId = it.documentId,
                )
            }
        }
    }

    override suspend fun getUnreadMentions(
        chatId: PeerId,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
        topMsgId: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getUnreadMentions failed") { activeHandle ->
            core.native.getUnreadMentions(
                activeHandle,
                chatId.value,
                offsetId,
                addOffset,
                limit.coerceIn(1, 100),
                topMsgId,
            ).map { it.toModel() }
        }
    }

    override suspend fun readMentions(chatId: PeerId, topMsgId: Int): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("readMentions failed") { activeHandle ->
            core.native.readMentions(activeHandle, chatId.value, topMsgId)
        }.also { result ->
            if (result is Outcome.Ok && topMsgId <= 0) {
                core.localUpdates.emit(MtprotoUpdate.UnreadMentions(chatId, stillUnread = 0))
            }
        }
    }

    override suspend fun readMessageContents(
        chatId: PeerId,
        messageIds: List<Int>,
    ): Outcome<Unit> {
        val ids = messageIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Outcome.Ok(Unit)
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("readMessageContents failed") { activeHandle ->
            core.native.readMessageContents(activeHandle, chatId.value, ids)
        }
    }

    override suspend fun getUnreadReactions(
        chatId: PeerId,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
        topMsgId: Int,
    ): Outcome<List<Message>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getUnreadReactions failed") { activeHandle ->
            core.native.getUnreadReactions(
                activeHandle,
                chatId.value,
                offsetId,
                addOffset,
                limit.coerceIn(1, 100),
                topMsgId,
            ).map { it.toModel() }
        }
    }

    override suspend fun readReactions(chatId: PeerId, topMsgId: Int): Outcome<Unit> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("readReactions failed") { activeHandle ->
            core.native.readReactions(activeHandle, chatId.value, topMsgId)
        }.also { result ->
            if (result is Outcome.Ok && topMsgId <= 0) {
                core.localUpdates.emit(MtprotoUpdate.UnreadReactions(chatId, stillUnread = 0))
            }
        }
    }
}
