package org.monogram.feature.dialog.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.markup.forSend
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.InlineBotResult
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canShowMessageViewers
import org.monogram.core.models.canShowOutboxReadDate
import org.monogram.core.models.geoPlace
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.playedMediaKind
import org.monogram.core.models.preferredPeerTitle
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerAtToken
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DraftMention
import org.monogram.feature.dialog.InlineBotQuery
import org.monogram.feature.dialog.MentionCandidate
import org.monogram.feature.dialog.PinnedBarMemory
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerPackMemory
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isTransientSendFailure
import org.monogram.feature.dialog.jumpNeedsFetch
import org.monogram.feature.dialog.localMediaCacheKey
import org.monogram.feature.dialog.localMediaPath
import org.monogram.feature.dialog.mergeSenderTags
import org.monogram.feature.dialog.nextRetryText
import org.monogram.feature.dialog.parseUpdateMessageId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlin.time.Duration.Companion.milliseconds

internal fun DialogExecutor.sendUpload(
    path: String,
    kind: String,
    fileName: String = "",
    mimeType: String = "",
    duration: Int = 0,
    width: Int = 0,
    height: Int = 0,
    caption: String? = null,
) {
    if (path.isBlank()) return
    if ((kind == "photo" || kind == "video") && !snapshot().canSendPhotos) return
    val file = java.io.File(path)
    if (!file.isFile || file.length() <= 0L) return
    emit(Msg.ComposerPanel(null))
    val current = snapshot()
    val text = caption ?: current.draft.trim()
    if (caption == null && text.isNotEmpty()) applyDraft("")
    val reply = current.replyTo
    emit(Msg.ReplyTo(null))
    val randomId = pendingRandomId()
    val name = fileName.ifBlank { file.name }.ifBlank { "file" }
    val pending = pendingOutgoing(
        path = path,
        kind = kind,
        caption = text.ifBlank { null },
        fileName = name,
        fileSize = file.length(),
        duration = duration,
        width = width,
        height = height,
        randomId = randomId,
        groupedId = null,
        reply = reply,
        entities = emptyList(),
    )
    emit(Msg.Append(pending))
    work.launch {
        warmup?.upsertMessages(listOf(pending))
        val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, reply?.id?.id)
        val item = UploadItem(
            path = path,
            kind = kind,
            mimeType = mimeType,
            fileName = name,
            caption = text,
            duration = duration,
            width = width,
            height = height,
            randomId = randomId,
        )
        finishOutgoingSend(
            pendingId = pending.id.id,
            result = client.sendUploadedMedia(chatId, item, replyId, topId),
        )
    }
}

internal fun DialogExecutor.sendAlbum(items: List<UploadItem>) {
    if (items.isEmpty() || items.size > 10) return
    val kinds = items.map { it.kind }
    val photos = kinds.all { it == "photo" || it == "video" }
    val documents = kinds.all { it == "document" }
    if (!photos && !documents) return
    if (photos && !snapshot().canSendPhotos) return
    emit(Msg.ComposerPanel(null))
    val current = snapshot()
    val draft = current.draft.trim()
    if (draft.isNotEmpty() && items.all { it.caption.isBlank() }) applyDraft("")
    val reply = current.replyTo
    emit(Msg.ReplyTo(null))
    val groupedId = pendingRandomId()
    val pending = items.mapIndexed { index, raw ->
        val file = java.io.File(raw.path)
        val randomId = if (raw.randomId != 0L) raw.randomId else pendingRandomId()
        val caption = raw.caption.ifBlank {
            if (index == 0) draft else ""
        }
        val name = raw.fileName.ifBlank { file.name }.ifBlank { "file" }
        pendingOutgoing(
            path = raw.path,
            kind = raw.kind,
            caption = caption.ifBlank { null },
            fileName = name,
            fileSize = file.length().takeIf { it > 0L },
            duration = raw.duration,
            width = raw.width,
            height = raw.height,
            randomId = randomId,
            groupedId = groupedId,
            reply = reply.takeIf { index == 0 },
            entities = emptyList(),
        ) to raw.copy(
            fileName = name,
            caption = caption,
            randomId = randomId,
        )
    }.filter { java.io.File(it.second.path).isFile }
    if (pending.isEmpty()) return
    pending.forEach { emit(Msg.Append(it.first)) }
    work.launch {
        warmup?.upsertMessages(pending.map { it.first })
        val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, reply?.id?.id)
        when (val result = client.sendUploadedAlbum(
            chatId,
            pending.map { it.second },
            replyId,
            topId,
        )) {
            is Outcome.Ok -> {
                pending.forEachIndexed { index, (local, _) ->
                    val sent = result.value.getOrNull(index)
                    if (sent != null) {
                        replaceOutgoing(local.id.id, sent)
                    }
                }
                if (result.value.size > pending.size) {
                    result.value.drop(pending.size).forEach { emit(Msg.Append(it)) }
                    warmup?.upsertMessages(result.value.drop(pending.size))
                }
            }
            is Outcome.Err -> {
                pending.forEach { (local, _) -> failOrKeepOutgoing(local.id.id, result.telegramError) }
            }
        }
    }
}

internal fun DialogExecutor.pendingOutgoing(
    path: String,
    kind: String,
    caption: String?,
    fileName: String,
    fileSize: Long?,
    duration: Int,
    width: Int,
    height: Int,
    randomId: Long,
    groupedId: Long?,
    reply: Message?,
    entities: List<org.monogram.core.models.TextEntity>,
): Message = Message(
    id = MessageId(chatId, pendingMessageId()),
    senderId = null,
    text = caption?.takeIf { it.isNotBlank() },
    date = System.currentTimeMillis() / 1000,
    outgoing = true,
    mediaCacheKey = localMediaCacheKey(path),
    mediaKind = kind,
    thumbCacheKey = localMediaCacheKey(path),
    mediaDuration = duration.takeIf { it > 0 },
    mediaWidth = width.takeIf { it > 0 },
    mediaHeight = height.takeIf { it > 0 },
    groupedId = groupedId,
    fileName = fileName.takeIf { it.isNotBlank() },
    fileSize = fileSize?.takeIf { it > 0L },
    pending = true,
    randomId = randomId,
    replyQuote = reply?.text,
    replyToMsgId = reply?.id?.id,
    entities = entities,
)

internal suspend fun DialogExecutor.finishOutgoingSend(pendingId: Int, result: Outcome<Message>) {
    when (result) {
        is Outcome.Ok -> replaceOutgoing(pendingId, result.value)
        is Outcome.Err -> failOrKeepOutgoing(pendingId, result.telegramError)
    }
}

internal suspend fun DialogExecutor.replaceOutgoing(pendingId: Int, sent: Message) {
    if (sent.id.id <= 0) return
    emit(Msg.ReplacePending(pendingId, sent))
    warmup?.deleteMessage(chatId, pendingId)
    warmup?.upsertMessages(listOf(sent))
}

internal fun DialogExecutor.failOrKeepOutgoing(pendingId: Int, error: TelegramError) {
    if (isTransientSendFailure(error)) {
        AppLog.warn("dialog", error.logLine())
        return
    }
    emit(Msg.MarkFailed(pendingId))
    handleError(error, false)
}

internal suspend fun DialogExecutor.absorbIncoming(incoming: Message) {
    val existing = snapshot().messages.firstOrNull { it.id.id == incoming.id.id }
    if (existing != null) {
        if (existing != incoming) {
            emit(Msg.Append(incoming))
            resolveSenders(listOf(incoming))
            sessionStore?.upsertPeerMins(listOf(incoming))
        }
        return
    }
    val pending = matchingPending(snapshot().messages, incoming)
    if (pending != null) {
        PerfLog.event("send", "phase=pending_reconcile via=new_message")
        replaceOutgoing(pending.id.id, incoming.copy(outgoing = true))
        return
    }
    emit(Msg.Append(incoming))
    resolveSenders(listOf(incoming))
    sessionStore?.upsertPeerMins(listOf(incoming))
}

internal fun DialogExecutor.bindPendingId(randomId: Long, messageId: Int) {
    val pending = snapshot().messages.firstOrNull {
        it.randomId == randomId && (it.pending || it.failed || it.id.id < 0)
    } ?: return
    PerfLog.event("send", "phase=update_message_id")
    emit(Msg.BindPending(randomId, messageId))
    work.launch {
        warmup?.deleteMessage(chatId, pending.id.id)
        val bound = pending.copy(
            id = MessageId(pending.id.chatId, messageId),
            pending = false,
            failed = false,
        )
        warmup?.upsertMessages(listOf(bound))
    }
}

internal fun DialogExecutor.sendSavedGif(documentId: Long) {
    if (snapshot().sending) return
    emit(Msg.Sending(true))
    emit(Msg.GifPicker(false))
    emit(Msg.ComposerPanel(null))
    val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, snapshot().replyTo?.id?.id)
    work.launch {
        when (val result = client.sendSavedGif(chatId, documentId, replyId, topId)) {
            is Outcome.Ok -> {
                emit(Msg.Append(result.value))
                warmup?.upsertMessages(listOf(result.value))
                emit(Msg.ReplyTo(null))
            }
            is Outcome.Err -> handleError(result.telegramError, false)
        }
        emit(Msg.Sending(false))
    }
}

internal fun DialogExecutor.serializeSendEntities(
    styled: StyledText,
    mentions: List<DraftMention>,
): String? {
    val extra = mentions.mapNotNull { mention ->
        val end = mention.start + mention.length
        if (mention.start < 0 || end > styled.text.length) return@mapNotNull null
        TextEntity(
            kind = "mention_name",
            offset = mention.start,
            length = mention.length,
            url = mention.peerId.value.toString(),
        )
    }
    return TextEntities.serialize((styled.entities + extra).sortedBy { it.offset })
}

internal suspend fun DialogExecutor.styleForSend(parsed: StyledText): StyledText {
    val documentIds = parsed.entities
        .asSequence()
        .filter { it.kind == "custom_emoji" }
        .mapNotNull { it.url?.toLongOrNull()?.takeIf { id -> id > 0 } }
        .distinct()
        .toList()
    if (documentIds.isEmpty()) return parsed.forSend(isPremium = isPremium())

    val premium = isPremium()
    val maxCustomEmoji = when (val result = client.animatedEmojiMax()) {
        is Outcome.Ok -> result.value.coerceAtLeast(0)
        is Outcome.Err -> null
    }
    val freeUrls = if (premium) {
        emptySet()
    } else {
        documentIds.filter { id ->
            when (val result = client.customEmojiIsFree(id)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> false
            }
        }.mapTo(linkedSetOf()) { it.toString() }
    }
    return parsed.forSend(
        isPremium = premium,
        maxCustomEmoji = maxCustomEmoji,
        freeCustomEmojiUrls = freeUrls,
    )
}

internal fun DialogExecutor.send(overrideText: String? = null) {
    val current = snapshot()
    val raw = overrideText ?: current.draft
    val text = raw.trim()
    val leading = raw.length - raw.trimStart().length
    val attach = current.pendingAttach
    val editing = current.editing
    val mentions = current.draftMentions.map { mention ->
        mention.copy(start = mention.start - leading)
    }
    if (editing != null) {
        if (current.sending || text.isEmpty() || !current.canSendPlain) return
        emit(Msg.Sending(true))
        work.launch {
            try {
                val parsed = withContext(markupContext) { markup.parseTelegramMarkdown(text) }
                val styled = styleForSend(parsed)
                when (val result = client.editText(
                    chatId,
                    editing.id.id,
                    styled.text,
                    serializeSendEntities(styled, mentions),
                )) {
                    is Outcome.Ok -> {
                        emit(Msg.ReplacePending(editing.id.id, result.value))
                        warmup?.upsertMessages(listOf(result.value))
                        emit(Msg.Editing(null))
                        applyDraft("")
                    }
                    is Outcome.Err -> handleError(result.telegramError, false)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Throwable) {
                AppLog.warn("dialog", "edit failed")
            } finally {
                emit(Msg.Sending(false))
            }
        }
        return
    }
    if (text.isEmpty() && attach.isEmpty()) return
    if (attach.isNotEmpty()) {
        val photos = attach.any { it.kind == "photo" || it.kind == "video" }
        if (photos && !current.canSendPhotos) return
        if (!photos && !current.canSendPlain) return
        applyDraft("")
        emit(Msg.PendingAttach(emptyList()))
        if (attach.size > 1) {
            sendAlbum(
                attach.mapIndexed { index, item ->
                    item.copy(caption = if (index == 0) text else "")
                },
            )
        } else {
            val item = attach.first()
            sendUpload(
                path = item.path,
                kind = item.kind,
                fileName = item.fileName,
                mimeType = item.mimeType,
                duration = item.duration,
                width = item.width,
                height = item.height,
                caption = text,
            )
        }
        return
    }
    if (!current.canSendPlain) return
    val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, current.replyTo?.id?.id)
    val pendingId = pendingMessageId()
    val randomId = pendingRandomId()
    val sendOp = sendSeq.incrementAndGet()
    inFlightSends += 1
    applyDraft("")
    emit(Msg.PendingAttach(emptyList()))
    emit(Msg.ReplyTo(null))
    PerfLog.event("send", "id=$sendOp phase=intent")
    val pending = Message(
        id = MessageId(chatId, pendingId),
        senderId = null,
        text = text.ifEmpty { null },
        date = System.currentTimeMillis() / 1000,
        outgoing = true,
        pending = true,
        randomId = randomId,
        replyQuote = current.replyTo?.text,
        replyToMsgId = current.replyTo?.id?.id,
        entities = emptyList(),
    )
    emit(Msg.Append(pending))
    PerfLog.event("send", "id=$sendOp phase=pending_append")
    work.launch {
        val persistAt = PerfLog.nowMs()
        warmup?.upsertMessages(listOf(pending))
        PerfLog.event(
            "send",
            "id=$sendOp phase=pending_persist elapsed_ms=${PerfLog.nowMs() - persistAt}",
        )
    }
    work.launch {
        try {
            val parsed = withContext(markupContext) { markup.parseTelegramMarkdown(text) }
            val wire = styleForSend(parsed)
            val entitiesJson = serializeSendEntities(wire, mentions)
            PerfLog.event("send", "id=$sendOp phase=bridge_send")
            val result = client.sendText(
                chatId,
                wire.text,
                replyId,
                entitiesJson,
                topId,
            )
            PerfLog.event(
                "send",
                "id=$sendOp phase=rpc_done result=${if (result is Outcome.Ok) "ok" else "err"}",
            )
            when (result) {
                is Outcome.Ok -> {
                    val sent = if (result.value.entities.isEmpty() && wire.entities.isNotEmpty()) {
                        result.value.copy(entities = wire.entities)
                    } else {
                        result.value
                    }
                    replaceOutgoing(pendingId, sent)
                    PerfLog.event("send", "id=$sendOp phase=pending_reconcile")
                }
                is Outcome.Err -> failOrKeepOutgoing(pendingId, result.telegramError)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            AppLog.warn("dialog", "send failed")
            if (inFlightSends == 1 && snapshot().draft.isEmpty()) applyDraft(text)
        } finally {
            inFlightSends -= 1
        }
    }
}

internal fun DialogExecutor.delete(messageId: Int, revoke: Boolean) {
    emit(Msg.Drop(messageId))
    work.launch {
        warmup?.deleteMessage(chatId, messageId)
        when (val result = client.deleteMessage(chatId, messageId, revoke)) {
            is Outcome.Ok -> Unit
            is Outcome.Err -> {
                handleError(result.telegramError, false)
                refresh()
            }
        }
    }
}

internal fun DialogExecutor.retryFailed() {
    val failed = snapshot().messages.firstOrNull { it.failed && it.outgoing } ?: return
    val localPath = localMediaPath(failed.mediaCacheKey)
    if (localPath != null && !failed.mediaKind.isNullOrBlank()) {
        val group = failed.groupedId
        val batch = if (group != null) {
            snapshot().messages.filter {
                it.failed && it.outgoing && it.groupedId == group
            }.asReversed()
        } else {
            listOf(failed)
        }
        batch.forEach { emit(Msg.Drop(it.id.id)) }
        if (batch.size > 1) {
            sendAlbum(
                batch.mapNotNull { message ->
                    val path = localMediaPath(message.mediaCacheKey) ?: return@mapNotNull null
                    UploadItem(
                        path = path,
                        kind = message.mediaKind.orEmpty(),
                        fileName = message.fileName.orEmpty(),
                        caption = message.text.orEmpty(),
                        duration = message.mediaDuration ?: 0,
                        width = message.mediaWidth ?: 0,
                        height = message.mediaHeight ?: 0,
                    )
                },
            )
        } else {
            sendUpload(
                path = localPath,
                kind = failed.mediaKind.orEmpty(),
                fileName = failed.fileName.orEmpty(),
                duration = failed.mediaDuration ?: 0,
                width = failed.mediaWidth ?: 0,
                height = failed.mediaHeight ?: 0,
                caption = failed.text.orEmpty(),
            )
        }
        return
    }
    val text = nextRetryText(snapshot().messages) ?: return
    emit(Msg.Drop(failed.id.id))
    applyDraft(text)
    send()
}

