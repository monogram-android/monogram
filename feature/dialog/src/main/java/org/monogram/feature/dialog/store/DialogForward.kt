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
import org.monogram.core.models.canForwardFrom
import org.monogram.core.models.canSelectAsForwardRecipient
import org.monogram.core.models.canShowMessageViewers
import org.monogram.core.models.canShowOutboxReadDate
import org.monogram.core.models.geoPlace
import org.monogram.core.models.requiresForwardPhotoRight
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

internal fun eligibleForwardTargets(
    chats: List<Chat>,
    requiresPhotos: Boolean,
    selfPeerId: PeerId?,
): List<Chat> = chats.filter {
    it.canSelectAsForwardRecipient(requiresPhotos, selfPeerId)
}

internal fun DialogExecutor.openForward(message: Message) {
    emit(Msg.ForwardHint(null))
    emit(Msg.ForwardMessage(message))
    emit(Msg.ForwardQuery(""))
    val needsPhotos = message.requiresForwardPhotoRight()
    work.launch {
        val selfId = sessionStore?.readAuthorizedUserId()
        var targets = eligibleForwardTargets(warmup?.chats().orEmpty(), needsPhotos, selfId)
        if (targets.isEmpty()) {
            when (val result = client.getChats()) {
                is Outcome.Ok -> {
                    warmup?.upsertChats(result.value)
                    targets = eligibleForwardTargets(result.value, needsPhotos, selfId)
                }
                is Outcome.Err -> handleError(result.telegramError, false)
            }
        }
        emit(Msg.ForwardTargets(targets))
    }
}

internal fun DialogExecutor.forwardTo(toChatId: PeerId) {
    val current = snapshot()
    val message = current.forwardMessage ?: return
    if (current.forwarding || !message.canForwardFrom(current.canForward)) return
    val dest = current.forwardTargets.firstOrNull { it.id == toChatId } ?: return
    emit(Msg.Forwarding(true))
    work.launch {
        when (
            val result = client.forwardMessages(
                fromChatId = chatId,
                messageIds = listOf(message.id.id),
                toChatId = dest.id,
                dropAuthor = false,
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
