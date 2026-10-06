package org.monogram.feature.dialog.store

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.PeerId
import org.monogram.core.models.prepareComposerText
import org.monogram.core.models.remapTextEntities
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.DraftMention
import kotlin.time.Duration.Companion.milliseconds

internal fun contactTypingNeeded(
    isGroup: Boolean,
    isChannel: Boolean,
    peerStatus: String?,
    peerStatusAt: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (isGroup || isChannel) return true
    return when (peerStatus) {
        null, "recently" -> true
        "online" -> {
            val expires = peerStatusAt ?: return true
            expires > nowMillis / 1000L - 30L
        }

        else -> false
    }
}

internal fun DialogExecutor.publishTyping(typing: Boolean) {
    if (inFlightSends > 0) return
    if (!typing) {
        typingJob?.cancel()
        typingJob = null
        if (lastTypingSent != false) {
            lastTypingSent = false
            work.launch { client.setTyping(chatId, false) }
        }
        return
    }
    val state = snapshot()
    if (!contactTypingNeeded(
            state.isGroup,
            state.isChannel,
            state.peerStatus,
            state.peerStatusAt
        )
    ) {
        return
    }
    if (lastTypingSent == true || typingJob?.isActive == true) return
    typingJob = work.launch {
        delay(400)
        lastTypingSent = true
        client.setTyping(chatId, true)
    }
}

internal fun DialogExecutor.applyDraft(text: String, mentions: List<DraftMention>? = null) {
    val nextMentions = when {
        text.isEmpty() -> emptyList()
        mentions != null -> mentions
        else -> ComposerAt.remapMentions(snapshot().draft, text, snapshot().draftMentions)
    }
    val entities = remapTextEntities(snapshot().draft, text, snapshot().draftEntities)
    emit(Msg.Draft(text))
    emit(Msg.DraftEntities(entities))
    emit(Msg.DraftMentions(nextMentions))
    if (text.isEmpty()) {
        mentionJob?.cancel()
        inlineJob?.cancel()
        clearMentions()
        clearInline()
        clearLinkPreview()
    }
    scheduleDraftPreview()
    work.launch { warmup?.setDraft(chatId, threadTopMsgId, text) }
}

internal fun DialogExecutor.applyTyping(userId: PeerId, typing: Boolean, action: String) {
    typingJobs.remove(userId.value)?.cancel()
    if (!typing) {
        if (userId in snapshot().typingUsers) {
            emit(Msg.Typing(userId, null, false))
        }
        return
    }
    val kind = action.ifBlank { ChatActionKind.Typing.wire }
    val known = snapshot().senders[userId]?.title
    val current = snapshot().typingUsers[userId]
    if (current?.name != known.orEmpty() || current.action != kind) {
        emit(Msg.Typing(userId, known, true, kind))
    }
    if (known.isNullOrBlank()) {
        work.launch {
            val name = sessionStore?.readProfile(userId.value)?.title
            if (!name.isNullOrBlank() && userId in snapshot().typingUsers) {
                emit(
                    Msg.Typing(
                        userId,
                        name,
                        true,
                        snapshot().typingUsers[userId]?.action ?: kind,
                    ),
                )
            }
        }
    }
    typingJobs[userId.value] = work.launch {
        delay(6_000.milliseconds)
        emit(Msg.Typing(userId, null, false))
        typingJobs.remove(userId.value)
    }
}


internal fun DialogExecutor.scheduleDraftPreview() {
    draftPreviewJob?.cancel()
    val current = snapshot()
    draftPreviewJob = work.launch {
        delay(150)
        val candidate = withContext(markupContext) {
            prepareComposerText(current.draft, true, current.draftEntities)
        }
        val literal = prepareComposerText(current.draft, false, current.draftEntities)
        val preview =
            if (current.draftFormatting || current.draftEntities.isNotEmpty() || current.draftMentions.isNotEmpty()) {
                prepareDraftPayload(current.draft, current)
            } else literal
        if (snapshot().draft == current.draft && snapshot().draftFormatting == current.draftFormatting &&
            snapshot().draftEntities == current.draftEntities
        ) {
            emit(Msg.DraftPreview(preview, candidate != literal))
        }
    }
}