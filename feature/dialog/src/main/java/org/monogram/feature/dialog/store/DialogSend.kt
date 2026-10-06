package org.monogram.feature.dialog.store

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.markup.forSend
import org.monogram.core.models.FixedLinkPreviewRules
import org.monogram.core.models.ForumIo
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.UploadItem
import org.monogram.core.models.prepareComposerText
import org.monogram.core.models.remapTextEntities
import org.monogram.core.ui.AppearanceSettings
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.isTransientSendFailure
import org.monogram.feature.dialog.localMediaCacheKey
import org.monogram.feature.dialog.localMediaPath
import org.monogram.feature.dialog.nextRetryText
import org.monogram.network.bridge.MtprotoClient
import kotlin.coroutines.cancellation.CancellationException

internal fun DialogExecutor.sendUpload(
    path: String,
    kind: String,
    fileName: String = "",
    mimeType: String = "",
    duration: Int = 0,
    width: Int = 0,
    height: Int = 0,
    caption: String? = null,
    captionEntities: List<TextEntity> = emptyList(),
) {
    if (path.isBlank()) return
    if ((kind == "photo" || kind == "video") && !snapshot().canSendPhotos) return
    val file = java.io.File(path)
    if (!file.isFile || file.length() <= 0L) return
    emit(Msg.ComposerPanel(null))
    val current = snapshot()
    if (caption == null && current.draft.isNotEmpty()) {
        work.launch {
            val wire = prepareDraftPayload(current.draft, current)
            if (snapshot().draft == current.draft) applyDraft("")
            sendUpload(
                path,
                kind,
                fileName,
                mimeType,
                duration,
                width,
                height,
                wire.text,
                wire.entities
            )
        }
        return
    }
    val text = caption.orEmpty()
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
        entities = captionEntities,
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
            captionEntities = captionEntities,
            duration = duration,
            width = width,
            height = height,
            randomId = randomId,
        )
        finishOutgoingSend(
            pendingId = pending.id.id,
            result = client.sendUploadedMedia(
                chatId,
                item,
                replyId,
                topId,
                TextEntities.serialize(captionEntities)
            ),
        )
    }
}

internal fun DialogExecutor.sendAlbum(items: List<UploadItem>, useDraft: Boolean = true) {
    if (items.isEmpty() || items.size > 10) return
    val kinds = items.map { it.kind }
    val photos = kinds.all { it == "photo" || it == "video" }
    val documents = kinds.all { it == "document" }
    if (!photos && !documents) return
    if (photos && !snapshot().canSendPhotos) return
    emit(Msg.ComposerPanel(null))
    val current = snapshot()
    val draft = if (useDraft) current.draft.trim() else ""
    if (draft.isNotEmpty() && items.first().caption.isBlank()) {
        work.launch {
            val wire = prepareDraftPayload(current.draft, current)
            if (snapshot().draft == current.draft) applyDraft("")
            sendAlbum(items.mapIndexed { index, item ->
                if (index == 0) item.copy(
                    caption = wire.text,
                    captionEntities = wire.entities
                ) else item
            })
        }
        return
    }
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
            entities = raw.captionEntities,
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
                pending.forEach { (local, _) ->
                    failOrKeepOutgoing(
                        local.id.id,
                        result.telegramError
                    )
                }
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
    entities: List<TextEntity>,
): Message {
    val replyIds = ForumIo.sendReplyIds(threadTopMsgId, reply?.id?.id)
    return Message(
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
        replyToMsgId = replyIds.first.takeIf { it > 0 },
        replyToTopId = replyIds.second.takeIf { it > 0 },
        forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0,
        entities = entities,
    )
}

internal suspend fun DialogExecutor.finishOutgoingSend(pendingId: Int, result: Outcome<Message>) {
    when (result) {
        is Outcome.Ok -> replaceOutgoing(pendingId, result.value)
        is Outcome.Err -> failOrKeepOutgoing(pendingId, result.telegramError)
    }
}

internal suspend fun DialogExecutor.replaceOutgoing(pendingId: Int, sent: Message) {
    if (sent.id.id <= 0) return
    val pending = snapshot().messages.firstOrNull { it.id.id == pendingId }
    val resolved = if (sent.entities.isEmpty() && sent.text == pending?.text) {
        sent.copy(entities = pending?.entities.orEmpty())
    } else sent
    emit(Msg.ReplacePending(pendingId, resolved))
    warmup?.deleteMessage(chatId, pendingId)
    warmup?.upsertMessages(listOf(resolved))
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

/** Native text sends may mint a different random_id. Bind only when this chat has one pending. */
internal fun DialogExecutor.uniqueUnmatchedPending(): Message? {
    val unmatched = snapshot().messages.filter {
        it.outgoing &&
                it.pending &&
                !it.failed &&
                (it.randomId ?: 0L) != 0L &&
                it.id.id < 0
    }
    return unmatched.singleOrNull()
}

internal fun DialogExecutor.bindPendingId(randomId: Long, messageId: Int) {
    val pending = snapshot().messages.firstOrNull {
        it.randomId == randomId && (it.pending || it.failed || it.id.id < 0)
    } ?: uniqueUnmatchedPending() ?: return
    val mappedRandomId = pending.randomId ?: randomId
    PerfLog.event("send", "phase=update_message_id")
    emit(Msg.BindPending(mappedRandomId, messageId))
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

internal suspend fun DialogExecutor.styleForSend(parsed: StyledText): StyledText =
    filterComposerPayload(parsed, client, isPremium())

internal suspend fun filterComposerPayload(
    parsed: StyledText,
    client: MtprotoClient,
    premium: Boolean
): StyledText {
    val documentIds = parsed.entities
        .asSequence()
        .filter { it.kind == "custom_emoji" }
        .mapNotNull { it.url?.toLongOrNull()?.takeIf { id -> id > 0 } }
        .distinct()
        .toList()
    if (documentIds.isEmpty()) return parsed.forSend(isPremium = premium)

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

internal fun webpageUrlForSend(text: String, current: DialogStore.State): String? {
    if (current.linkPreviewHidden) return null
    val urls = FixedLinkPreviewRules.urls(text)
    val selected =
        current.linkPreviewChoice?.takeIf { it in urls } ?: urls.firstOrNull() ?: return null
    val fixed = if (AppearanceSettings.state.value.fixLinkPreviews) {
        FixedLinkPreviewRules.previewUrlFor(
            selected,
            current.linkPreviewUrl.takeIf { current.linkPreviewFixed },
        )
    } else {
        null
    }
    val page = fixed ?: selected
    return page.takeIf { urls.size > 1 || page != urls.first() }
}

internal suspend fun DialogExecutor.prepareDraftPayload(
    raw: String,
    current: DialogStore.State
): StyledText {
    val mentions = current.draftMentions.map { mention ->
        TextEntity("mention_name", mention.start, mention.length, mention.peerId.value.toString())
    }
    val parsed = withContext(markupContext) {
        prepareComposerText(
            raw, current.draftFormatting && isPremium(),
            remapTextEntities(current.draft, raw, current.draftEntities + mentions)
        )
    }
    return styleForSend(parsed)
}

internal fun DialogExecutor.send(
    overrideText: String? = null,
    prepared: StyledText? = null,
    captured: DialogStore.State? = null,
    consumeDraft: Boolean = true,
) {
    val current = captured ?: snapshot()
    val raw = overrideText ?: current.draft
    if (prepared == null) {
        if (current.sending) return
        emit(Msg.Sending(true))
        work.launch {
            try {
                val wire =
                    current.draftPreview?.takeIf { raw == current.draft && (!current.draftFormatting || isPremium()) }
                        ?: prepareDraftPayload(raw, current)
                emit(Msg.Sending(false))
                send(raw, wire, current, consumeDraft)
            } catch (error: CancellationException) {
                emit(Msg.Sending(false))
                throw error
            } catch (_: Throwable) {
                emit(Msg.Sending(false))
                AppLog.warn("dialog", "formatting failed")
            }
        }
        return
    }
    val text = raw.trim()
    val attach = current.pendingAttach
    val editing = current.editing
    if (editing != null) {
        if (current.sending || text.isEmpty() || !current.canSendPlain) return
        emit(Msg.Sending(true))
        work.launch {
            try {
                val styled = prepared
                when (val result = client.editText(
                    chatId,
                    editing.id.id,
                    styled.text,
                    TextEntities.serialize(styled.entities),
                )) {
                    is Outcome.Ok -> {
                        emit(Msg.ReplacePending(editing.id.id, result.value))
                        warmup?.upsertMessages(listOf(result.value))
                        emit(Msg.Editing(null))
                        if (consumeDraft && snapshot().draft == current.draft) applyDraft("")
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
        if (consumeDraft && snapshot().draft == current.draft) applyDraft("")
        emit(Msg.PendingAttach(emptyList()))
        if (attach.size > 1) {
            sendAlbum(
                attach.mapIndexed { index, item ->
                    if (index == 0 && prepared.text.isNotEmpty()) item.copy(
                        caption = prepared.text,
                        captionEntities = prepared.entities
                    ) else item
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
                caption = prepared.text.ifEmpty { item.caption },
                captionEntities = if (prepared.text.isNotEmpty()) prepared.entities else item.captionEntities,
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
    if (consumeDraft && snapshot().draft == current.draft) applyDraft("")
    if (consumeDraft) {
        emit(Msg.PendingAttach(emptyList()))
        emit(Msg.ReplyTo(null))
    }
    PerfLog.event("send", "id=$sendOp phase=intent")
    val pending = Message(
        id = MessageId(chatId, pendingId),
        senderId = null,
        text = prepared.text.ifEmpty { null },
        date = System.currentTimeMillis() / 1000,
        outgoing = true,
        pending = true,
        randomId = randomId,
        replyQuote = current.replyTo?.text,
        replyToMsgId = replyId.takeIf { it > 0 },
        replyToTopId = topId.takeIf { it > 0 },
        forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0,
        entities = prepared.entities,
    )
    emit(Msg.Append(pending))
    PerfLog.event("send", "id=$sendOp phase=pending_append")
    work.launch {
        try {
            warmup?.upsertMessages(listOf(pending))
            val wire = prepared
            val entitiesJson = TextEntities.serialize(wire.entities)
            PerfLog.event("send", "id=$sendOp phase=bridge_send")
            val webpageUrl = webpageUrlForSend(wire.text, current)
            val result = client.sendText(
                chatId,
                wire.text,
                replyId,
                entitiesJson,
                topId,
                webpageUrl,
                randomId,
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
            if (consumeDraft && inFlightSends == 1 && snapshot().draft.isEmpty()) {
                applyDraft(raw)
                emit(Msg.DraftFormatting(current.draftFormatting))
                emit(Msg.DraftEntities(current.draftEntities))
                scheduleDraftPreview()
            }
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
                        captionEntities = message.entities,
                        duration = message.mediaDuration ?: 0,
                        width = message.mediaWidth ?: 0,
                        height = message.mediaHeight ?: 0,
                    )
                },
                useDraft = false,
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
                captionEntities = failed.entities,
            )
        }
        return
    }
    val text = nextRetryText(snapshot().messages) ?: return
    emit(Msg.Drop(failed.id.id))
    send(
        text,
        StyledText(text, failed.entities),
        snapshot().copy(editing = null, pendingAttach = emptyList()),
        consumeDraft = false
    )
}

