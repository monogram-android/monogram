package org.monogram.feature.dialog.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.encodeContactSend
import org.monogram.core.database.entity.OutgoingTextSendEntity
import org.monogram.core.database.toEntity
import org.monogram.core.models.ChecklistSendSpec
import org.monogram.core.models.ContactSendSpec
import org.monogram.core.models.ForumIo
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.MessageSendOptions
import org.monogram.core.models.PollSendSpec

internal fun validSendSchedule(date: Int?, now: Long): Boolean =
    date == null || date.toLong() in (now + 60)..minOf(
        now + 365L * 24 * 60 * 60, Int.MAX_VALUE.toLong() - 2
    )

internal fun DialogExecutor.queueChecklistSend(
    spec: ChecklistSendSpec, options: MessageSendOptions
) {
    val cache = warmup ?: return
    val current = snapshot()
    if (!validSendSchedule(options.scheduleDate, System.currentTimeMillis() / 1000)) return
    try {
        spec.requireValid()
    } catch (_: IllegalArgumentException) {
        return
    }
    val epoch = cache.outgoingAccountEpoch()
    emit(Msg.Sending(true))
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, current.composerReplyId)
            val randomId = pendingRandomId()
            val pending = Message(
                id = MessageId(chatId, pendingMessageId()),
                senderId = null,
                text = spec.title,
                date = System.currentTimeMillis() / 1000,
                outgoing = true,
                pending = true,
                randomId = randomId,
                mediaKind = "todo",
                checklist = spec.pendingChecklist(),
                replyToMsgId = replyId.takeIf { it > 0 },
                replyToTopId = topId.takeIf { it > 0 },
                forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0
            )
            withContext(NonCancellable) {
                cache.enqueueOutgoingTextSend(
                    OutgoingTextSendEntity(
                        randomId,
                        pending.toEntity(),
                        options.silent,
                        false,
                        options.scheduleDate,
                        null
                    ), epoch, consumeDraft = false
                )
                emit(Msg.ChecklistAdmitted(randomId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            handleError(TelegramError.parse("RPC 500: INTERNAL"), false)
        } finally {
            emit(Msg.Sending(false))
        }
    }
}

internal fun DialogExecutor.queueContactSend(
    spec: ContactSendSpec, options: MessageSendOptions
) {
    val cache = warmup ?: return
    val current = snapshot()
    if (current.sending || !current.canView || !current.canSendComposerContent || !current.canSendPlain || current.editing != null || current.pendingAttach.isNotEmpty() || current.forwardMessage != null || options.noWebpage) return
    if (!validSendSchedule(options.scheduleDate, System.currentTimeMillis() / 1000)) return
    try {
        spec.requireValid()
    } catch (_: IllegalArgumentException) {
        return
    }
    val epoch = cache.outgoingAccountEpoch()
    emit(Msg.Sending(true))
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, current.composerReplyId)
            val randomId = pendingRandomId()
            val pending = Message(
                id = MessageId(chatId, pendingMessageId()),
                senderId = null,
                text = spec.displayName,
                date = System.currentTimeMillis() / 1000,
                outgoing = true,
                pending = true,
                mediaKind = "contact",
                fileName = encodeContactSend(spec),
                replyToMsgId = replyId.takeIf { it > 0 },
                replyToTopId = topId.takeIf { it > 0 },
                forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0,
                randomId = randomId
            )
            withContext(NonCancellable) {
                cache.enqueueOutgoingTextSend(
                    OutgoingTextSendEntity(
                        randomId,
                        pending.toEntity(),
                        options.silent,
                        false,
                        options.scheduleDate,
                        null
                    ), epoch, consumeDraft = false
                )
                emit(Msg.ContactAdmitted(randomId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            handleError(TelegramError.parse("RPC 500: INTERNAL"), false)
        } finally {
            emit(Msg.Sending(false))
        }
    }
}

internal fun DialogExecutor.queuePollSend(
    spec: PollSendSpec, options: MessageSendOptions
) {
    val cache = warmup ?: return
    val current = snapshot()
    if (current.sending || !current.canCreatePoll || !current.canSendComposerContent || current.editing != null || current.pendingAttach.isNotEmpty() || current.forwardMessage != null || options.noWebpage) return
    if (!validSendSchedule(options.scheduleDate, System.currentTimeMillis() / 1000)) return
    try {
        spec.requireValid()
    } catch (_: IllegalArgumentException) {
        return
    }
    val epoch = cache.outgoingAccountEpoch()
    emit(Msg.Sending(true))
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, current.composerReplyId)
            val randomId = pendingRandomId()
            val pending = Message(
                id = MessageId(chatId, pendingMessageId()),
                senderId = null,
                text = spec.question,
                date = System.currentTimeMillis() / 1000,
                outgoing = true,
                pending = true,
                randomId = randomId,
                mediaKind = "poll",
                fileName = org.monogram.core.database.encodePollSend(spec),
                replyToMsgId = replyId.takeIf { it > 0 },
                replyToTopId = topId.takeIf { it > 0 },
                forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0
            )
            withContext(NonCancellable) {
                cache.enqueueOutgoingTextSend(
                    OutgoingTextSendEntity(
                        randomId,
                        pending.toEntity(),
                        options.silent,
                        false,
                        options.scheduleDate,
                        null
                    ), epoch, consumeDraft = false
                )
                emit(Msg.PollAdmitted(randomId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            handleError(TelegramError.parse("RPC 500: INTERNAL"), false)
        } finally {
            emit(Msg.Sending(false))
        }
    }
}

internal fun DialogExecutor.queueTextSend(text: String?, options: MessageSendOptions) {
    val cache = warmup ?: return
    val current = snapshot()
    val raw = text ?: current.draft
    if (current.sending || !current.canSendComposerContent || !current.canSendPlain || current.editing != null || current.pendingAttach.isNotEmpty() || current.forwardMessage != null || raw.isBlank()) return
    if (!validSendSchedule(options.scheduleDate, System.currentTimeMillis() / 1000)) return
    val epoch = cache.outgoingAccountEpoch()
    val draft = current.cloudDraft(composerCloudDraft)
    emit(Msg.Sending(true))
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val storedDraft = cache.captureOutgoingTextDraft(chatId, threadTopMsgId, epoch)
            if (storedDraft.draft.hasUnsupportedContent) return@launch
            val consumeDraft =
                storedDraft.draft.copy(date = 0) == draft.copy(date = 0) && storedDraft.markdown == current.draftFormatting
            val wire = prepareDraftPayload(raw, current)
            if (!snapshot().canSendComposerContent || !snapshot().canSendPlain) return@launch
            val (replyId, topId) = ForumIo.sendReplyIds(threadTopMsgId, current.composerReplyId)
            val randomId = pendingRandomId()
            val dice = org.monogram.core.models.Dice.emoticonForSend(wire.text)
                .takeIf { wire.entities.isEmpty() && options.scheduleDate == null }
            val pending = Message(
                id = MessageId(chatId, pendingMessageId()),
                senderId = null,
                text = wire.text.takeIf { dice == null },
                mediaKind = if (dice != null) "dice" else null,
                fileName = dice?.let { "{\"e\":\"$it\",\"v\":0}" },
                date = System.currentTimeMillis() / 1000,
                outgoing = true,
                pending = true,
                randomId = randomId,
                entities = wire.entities,
                replyQuote = current.replyTo?.text,
                replyToMsgId = replyId.takeIf { it > 0 },
                replyToTopId = topId.takeIf { it > 0 },
                forumTopic = ForumIo.historyThreadId(threadTopMsgId) > 0,
            )
            val consumed = withContext(NonCancellable) {
                cache.enqueueOutgoingTextSend(
                    OutgoingTextSendEntity(
                        randomId,
                        pending.toEntity(),
                        options.silent,
                        dice == null && (options.noWebpage || current.linkPreviewHidden),
                        options.scheduleDate,
                        if (dice == null) webpageUrlForSend(wire.text, current) else null,
                    ), epoch, storedDraft, consumeDraft
                )
            }
            val latest = snapshot()
            if (consumed && latest.draft == current.draft && latest.draftEntities == current.draftEntities && latest.draftMentions == current.draftMentions && latest.draftFormatting == current.draftFormatting && latest.composerReplyId == current.composerReplyId && latest.pendingAttach == current.pendingAttach && latest.editing == current.editing && latest.linkPreviewChoice == current.linkPreviewChoice && latest.linkPreviewFixed == current.linkPreviewFixed && latest.linkPreviewHidden == current.linkPreviewHidden) {
                applyDraft("")
                setComposerReply(null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            handleError(TelegramError.parse("RPC 500: INTERNAL"), false)
        } finally {
            emit(Msg.Sending(false))
        }
    }
}
