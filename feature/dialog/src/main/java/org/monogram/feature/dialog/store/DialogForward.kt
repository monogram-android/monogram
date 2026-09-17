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

internal fun DialogExecutor.openForward(message: Message) {
    emit(Msg.ForwardHint(null))
    emit(Msg.ForwardMessage(message))
    emit(Msg.ForwardQuery(""))
    work.launch {
        var targets = warmup?.chats().orEmpty()
            .filter { it.canView && it.canSendPlain }
        if (targets.isEmpty()) {
            when (val result = client.getChats()) {
                is Outcome.Ok -> {
                    warmup?.upsertChats(result.value)
                    targets = result.value.filter { it.canView && it.canSendPlain }
                }
                is Outcome.Err -> handleError(result.telegramError, false)
            }
        }
        emit(Msg.ForwardTargets(targets))
    }
}

internal fun DialogExecutor.forwardTo(toChatId: PeerId) {
    val message = snapshot().forwardMessage ?: return
    if (snapshot().forwarding || message.id.id <= 0) return
    emit(Msg.Forwarding(true))
    work.launch {
        when (
            val result = client.forwardMessage(
                fromChatId = chatId,
                messageId = message.id.id,
                toChatId = toChatId,
            )
        ) {
            is Outcome.Ok -> {
                if (toChatId == chatId) {
                    result.value.forEach { emit(Msg.Append(it)) }
                    warmup?.upsertMessages(result.value)
                }
                val title = snapshot().forwardTargets.firstOrNull { it.id == toChatId }?.title
                    ?: toChatId.value.toString()
                emit(Msg.ForwardHint(title))
                emit(Msg.ForwardMessage(null))
                emit(Msg.ForwardQuery(""))
            }
            is Outcome.Err -> handleError(result.telegramError, false)
        }
        emit(Msg.Forwarding(false))
    }
}
