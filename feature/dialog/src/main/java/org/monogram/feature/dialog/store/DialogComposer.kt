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
    emit(Msg.Draft(text))
    emit(Msg.DraftMentions(nextMentions))
    if (text.isEmpty()) {
        mentionJob?.cancel()
        inlineJob?.cancel()
        clearMentions()
        clearInline()
        clearLinkPreview()
    }
    work.launch { warmup?.setDraft(chatId, threadTopMsgId, text) }
}

internal fun DialogExecutor.applyTyping(userId: PeerId, typing: Boolean, action: String) {
    typingJobs.remove(userId.value)?.cancel()
    val active = typing
    if (!active) {
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

