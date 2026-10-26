package org.monogram.feature.dialog.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OutgoingMediaUpload
import org.monogram.core.database.entity.OutgoingMediaBatchEntity
import org.monogram.core.database.toEntity
import org.monogram.core.models.MessageSendOptions
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.releaseAttachCopy

internal fun mediaSelectionUnchanged(before: DialogStore.State, after: DialogStore.State): Boolean =
    before.attachmentRevision == after.attachmentRevision && before.pendingAttach == after.pendingAttach

internal fun mediaComposerUnchanged(before: DialogStore.State, after: DialogStore.State): Boolean =
    before.composerRevision == after.composerRevision && before.pendingAttach == after.pendingAttach && before.draft == after.draft && before.draftEntities == after.draftEntities && before.draftMentions == after.draftMentions && before.draftFormatting == after.draftFormatting && before.composerReplyId == after.composerReplyId && before.editing == after.editing && before.forwardMessage == after.forwardMessage && before.linkPreviewChoice == after.linkPreviewChoice && before.linkPreviewFixed == after.linkPreviewFixed && before.linkPreviewHidden == after.linkPreviewHidden

internal fun mediaOptionsAllowed(state: DialogStore.State): Boolean {
    if (!state.canView || state.left || state.blockedByMe || state.sending || !state.canSendComposerContent || state.editing != null || state.forwardMessage != null || state.pendingAttach.size !in 1..10) return false
    val kinds = state.pendingAttach.map { it.kind }.toSet()
    return if (kinds.all { it == "photo" || it == "video" }) state.canSendPhotos else kinds == setOf(
        "document"
    ) && state.canSendPlain
}

internal fun DialogExecutor.queueMediaSend(text: String?, options: MessageSendOptions) {
    val cache = warmup ?: return
    val current = snapshot()
    if (!mediaOptionsAllowed(current) || cache.outgoingMediaFiles == null || !validSendSchedule(
            options.scheduleDate, System.currentTimeMillis() / 1000
        )
    ) return
    val epoch = cache.outgoingAccountEpoch()
    val draft = current.cloudDraft(composerCloudDraft)
    emit(Msg.Sending(true))
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            val stored = cache.captureOutgoingTextDraft(chatId, threadTopMsgId, epoch)
            if (stored.draft.hasUnsupportedContent) return@launch
            val wire = prepareDraftPayload(text ?: current.draft, current)
            if (!mediaOptionsAllowed(snapshot().copy(sending = false)) || !mediaComposerUnchanged(
                    current, snapshot()
                )
            ) return@launch
            val groupedId = if (current.pendingAttach.size > 1) pendingRandomId() else null
            val uploads = current.pendingAttach.mapIndexed { index, item ->
                val file = java.io.File(item.path)
                val caption = if (index == 0 && wire.text.isNotEmpty()) wire.text else item.caption
                val entities =
                    if (index == 0 && wire.text.isNotEmpty()) wire.entities else item.captionEntities
                val pending = pendingOutgoing(
                    item.path,
                    item.kind,
                    caption.ifEmpty { null },
                    item.fileName.ifBlank { file.name },
                    null,
                    item.duration,
                    item.width,
                    item.height,
                    pendingRandomId(),
                    groupedId,
                    current.replyTo.takeIf { index == 0 },
                    entities,
                    current.composerReplyId
                )
                OutgoingMediaUpload(item.path, item.mimeType, pending.toEntity())
            }
            val admitted = cache.retainAndEnqueueOutgoingMedia(
                OutgoingMediaBatchEntity(
                    checkNotNull(uploads.first().message.randomId),
                    chatId.value,
                    System.currentTimeMillis() / 1000,
                    options.silent,
                    options.scheduleDate,
                    uploads.size > 1
                ),
                uploads,
                20L * 1024 * 1024 * 1024,
                epoch,
                stored,
                consumeDraft = stored.draft.copy(date = 0) == draft.copy(date = 0) && stored.markdown == current.draftFormatting
            )
            val latest = snapshot()
            val consumeComposer = admitted.draftConsumed && mediaComposerUnchanged(current, latest)
            if (mediaSelectionUnchanged(current, latest)) {
                emit(Msg.PendingAttach(emptyList()))
                current.pendingAttach.forEach { releaseAttachCopy(it.path) }
            }
            if (consumeComposer) {
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
