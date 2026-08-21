package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarActions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarState
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
internal fun rememberChatInputBarState(
    state: ChatComponent.State,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>
): ChatInputBarState {
    val replyMarkup = remember(state.messages) {
        state.messages.firstOrNull {
            it.replyMarkup is ReplyMarkupModel.ShowKeyboard
        }?.replyMarkup
    }

    return remember(
        state.replyMessage,
        state.editingMessage,
        state.draftText,
        state.draftLinkTargets,
        state.selectedDraftLinkPreviewUrl,
        state.resolvedDraftLinkPreviewUrl,
        state.dismissedDraftLinkPreviewUrls,
        state.draftLinkPreview,
        state.isDraftLinkPreviewLoading,
        state.draftLinkPreviewError,
        state.isDraftLinkPreviewDisabledForSend,
        pendingMediaPaths,
        pendingDocumentPaths,
        state.topics,
        state.currentTopicId,
        state.effectiveInputPermissions,
        state.permissions,
        state.slowModeDelay,
        state.slowModeDelayExpiresIn,
        state.isCurrentUserRestricted,
        state.restrictedUntilDate,
        state.isAdmin,
        state.isChannel,
        state.isBot,
        state.botCommands,
        state.botMenuButton,
        replyMarkup,
        state.mentionSuggestions,
        state.inlineBotResults,
        state.currentInlineBotUsername,
        state.currentInlineQuery,
        state.isInlineBotLoading,
        state.attachMenuBots,
        state.scheduledMessages,
        state.currentUser?.isPremium,
        state.isSecretChat,
        state.telegramLimits
    ) {
        ChatInputBarState(
            replyMessage = state.replyMessage,
            editingMessage = state.editingMessage,
            draftText = state.draftText,
            draftLinkTargets = state.draftLinkTargets,
            selectedDraftLinkPreviewUrl = state.selectedDraftLinkPreviewUrl,
            resolvedDraftLinkPreviewUrl = state.resolvedDraftLinkPreviewUrl,
            dismissedDraftLinkPreviewUrls = state.dismissedDraftLinkPreviewUrls,
            draftLinkPreview = state.draftLinkPreview,
            isDraftLinkPreviewLoading = state.isDraftLinkPreviewLoading,
            draftLinkPreviewError = state.draftLinkPreviewError,
            isDraftLinkPreviewDisabledForSend = state.isDraftLinkPreviewDisabledForSend,
            pendingAttachments = state.stagedAttachments,
            pendingMediaPaths = state.pendingMediaPaths,
            pendingDocumentPaths = state.pendingDocumentPaths,
            isClosed = state.topics.find { it.id.toLong() == state.currentTopicId }?.isClosed
                ?: false,
            permissions = state.effectiveInputPermissions ?: state.permissions,
            slowModeDelay = state.slowModeDelay,
            slowModeDelayExpiresIn = state.slowModeDelayExpiresIn,
            isCurrentUserRestricted = state.isCurrentUserRestricted,
            restrictedUntilDate = state.restrictedUntilDate,
            isAdmin = state.isAdmin,
            isChannel = state.isChannel,
            isBot = state.isBot,
            botCommands = state.botCommands,
            botMenuButton = state.botMenuButton,
            replyMarkup = replyMarkup,
            mentionSuggestions = state.mentionSuggestions,
            inlineBotResults = state.inlineBotResults,
            currentInlineBotUsername = state.currentInlineBotUsername,
            currentInlineQuery = state.currentInlineQuery,
            isInlineBotLoading = state.isInlineBotLoading,
            attachBots = state.attachMenuBots,
            scheduledMessages = state.scheduledMessages,
            isPremiumUser = state.currentUser?.isPremium == true,
            isSecretChat = state.isSecretChat,
            telegramLimits = state.telegramLimits
        )
    }
}

@Composable
internal fun rememberChatInputBarActions(
    component: ChatComponent,
    state: ChatComponent.State,
    onPickMedia: () -> Unit,
    onHideKeyboardAndClearFocus: () -> Unit,
    onStartRecordingVideo: () -> Unit,
    onSetPendingMediaPaths: (List<String>) -> Unit,
    onSetPendingDocumentPaths: (List<String>) -> Unit,
    onEditMediaPath: (String) -> Unit,
    onDraftLinkPreviewAction: (LinkPreviewAction) -> Unit
): ChatInputBarActions {
    val telegramLinkRepository: TelegramLinkRepository = koinInject()
    val scope = rememberCoroutineScope()
    return remember(
        component,
        state.chatId,
        state.isBot,
        onPickMedia,
        onHideKeyboardAndClearFocus,
        onStartRecordingVideo,
        onSetPendingMediaPaths,
        onSetPendingDocumentPaths,
        onEditMediaPath,
        onDraftLinkPreviewAction
    ) {
        ChatInputBarActions(
            onSend = { text, entities, options, parseMode ->
                component.onSendMessage(text, entities, options, parseMode)
            },
            onStickerClick = component::onSendSticker,
            onGifClick = component::onSendGif,
            onAttachClick = onPickMedia,
            onCameraClick = {
                onHideKeyboardAndClearFocus()
                onStartRecordingVideo()
            },
            onSendVoice = component::onSendVoice,
            onCancelReply = component::onCancelReply,
            onCancelEdit = component::onCancelEdit,
            onSaveEdit = { text, entities, parseMode ->
                component.onSaveEditedMessage(text, entities, parseMode)
            },
            onDraftChange = component::onDraftChange,
            onSelectDraftLinkPreview = component::onSelectDraftLinkPreview,
            onDismissDraftLinkPreview = component::onDismissDraftLinkPreview,
            onRestoreDraftLinkPreview = component::onRestoreDraftLinkPreview,
            onTyping = component::onTyping,
            onCancelMedia = component::onClearPendingAttachments,
            onSendAttachments = { attachments, caption, captionEntities, options ->
                component.onSendPendingAttachments(attachments, caption, captionEntities, options)
            },
            onPendingAttachmentsChange = { attachments ->
                component.onStageAttachments(attachments)
            },
            onMediaClick = onEditMediaPath,
            onDraftLinkPreviewAction = onDraftLinkPreviewAction,
            onShowBotCommands = {
                onHideKeyboardAndClearFocus()
                component.onShowBotCommands()
            },
            onReplyMarkupButtonClick = {
                component.onReplyMarkupButtonClick(
                    0,
                    it,
                    if (state.isBot) state.chatId else 0L
                )
            },
            onOpenMiniApp = { url, name ->
                component.onOpenMiniApp(
                    url,
                    name,
                    if (state.isBot) state.chatId else 0L
                )
            },
            onMentionQueryChange = component::onMentionQueryChange,
            onInlineQueryChange = component::onInlineQueryChange,
            onLoadMoreInlineResults = component::onLoadMoreInlineResults,
            onSendInlineResult = component::onSendInlineResult,
            onInlineSwitchPm = { botUsername, parameter ->
                val encodedParameter = URLEncoder.encode(
                    parameter,
                    StandardCharsets.UTF_8.name()
                )
                scope.launch {
                    val link =
                        telegramLinkRepository.buildUrl("$botUsername?start=$encodedParameter")
                    component.onLinkClick(link)
                }
            },
            onAttachBotClick = { bot ->
                component.onOpenAttachBot(bot.botUserId, bot.name)
            },
            onSendPoll = component::onSendPoll,
            onCreateChecklist = { component.onOpenChecklistEditor(null) },
            onRefreshScheduledMessages = component::onRefreshScheduledMessages,
            onEditScheduledMessage = component::onEditMessage,
            onDeleteScheduledMessage = component::onDeleteMessage,
            onSendScheduledNow = component::onSendScheduledNow
        )
    }
}

