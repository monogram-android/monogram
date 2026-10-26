package org.monogram.feature.dialog.store

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.CloudDraftState
import org.monogram.core.models.Message
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.feature.dialog.DialogStore

internal val DialogStore.State.canSendComposerContent: Boolean
    get() = !draftUnsupported || editing != null

internal val DialogStore.State.composerReplyId: Int?
    get() = replyTo?.id?.id ?: draftReplyId.takeIf { it > 0 }

internal fun DialogStore.State.cloudDraft(base: CloudDraft): CloudDraft = base.copy(
    text = draft,
    entitiesJson = TextEntities.serialize((draftEntities + draftMentions.map {
        TextEntity("mention_name", it.start, it.length, it.peerId.value.toString())
    }).distinct().sortedWith(compareBy<TextEntity> { it.offset }.thenByDescending { it.length })),
    replyToMessageId = composerReplyId ?: 0,
    noWebpage = linkPreviewHidden,
)

private fun DialogExecutor.acceptComposerDraft(stored: CloudDraftState) {
    if (!work.isActive || draftWritesPending > 0 || stored.revision < draftSavedRevision) return
    composerCloudDraft = stored.draft
    composerMarkdown = stored.markdown

    if (snapshot().editing != null) return

    emit(Msg.CloudDraftRestored(stored.draft, stored.markdown && isPremium()))

    scheduleComposerAt(stored.draft.text)
    scheduleDraftPreview()
    scheduleLinkPreview(stored.draft.text)
}

internal fun DialogExecutor.observeComposerDraft() {
    warmup?.draftSync?.observe(chatId, threadTopMsgId)?.onEach { emit(Msg.DraftSync(it)) }
        ?.launchIn(work)
    warmup?.observeCloudDraft(chatId, threadTopMsgId)?.onEach { acceptComposerDraft(it) }
        ?.launchIn(work)
}

private fun DialogExecutor.writeComposerDraft(write: suspend () -> CloudDraftState) {
    draftWritesPending++
    // Finish short local writes in edit order even when the editor is disposed.
    work.launch(start = CoroutineStart.UNDISPATCHED) {
        val stored = try {
            withContext(NonCancellable) {
                write().also { draftSavedRevision = maxOf(draftSavedRevision, it.revision) }
            }
        } finally {
            draftWritesPending--
        }
        acceptComposerDraft(stored)
    }
}

internal fun DialogExecutor.persistComposerDraft() {
    if (snapshot().editing != null) return
    val draft = snapshot().cloudDraft(composerCloudDraft)
    composerCloudDraft = draft
    val markdown = snapshot().draftFormatting
    composerMarkdown = markdown
    val cache = warmup ?: return
    writeComposerDraft { cache.saveLocalCloudDraft(draft, markdown) }
}

internal fun DialogExecutor.discardComposerDraft() {
    if (snapshot().editing != null) return
    composerCloudDraft = CloudDraft(chatId, threadTopMsgId)
    composerMarkdown = false
    emit(Msg.CloudDraftRestored(composerCloudDraft))
    val cache = warmup ?: return
    writeComposerDraft { cache.discardLocalCloudDraft(chatId, threadTopMsgId) }
}

internal fun DialogExecutor.setComposerReply(message: Message?) {
    emit(Msg.ReplyTo(message))
    persistComposerDraft()
}

internal fun DialogExecutor.finishComposerEdit() {
    val staged = snapshot().pendingAttach.takeUnless { snapshot().sending }.orEmpty()
    emit(Msg.PendingAttach(emptyList()))
    work.launch(kotlinx.coroutines.Dispatchers.IO) {
        staged.forEach { org.monogram.feature.dialog.releaseAttachCopy(it.path) }
    }
    emit(Msg.Editing(null))
    emit(Msg.CloudDraftRestored(composerCloudDraft, composerMarkdown && isPremium()))
    scheduleDraftPreview()
    scheduleComposerAt(composerCloudDraft.text)
    scheduleLinkPreview(composerCloudDraft.text)
}
