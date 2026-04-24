package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarActions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarState
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
internal fun rememberChatInputBarState(
    state: ChatComponent.State,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>
): ChatInputBarState {
    return remember(state, pendingMediaPaths, pendingDocumentPaths) {
        ChatInputBarState(
            replyMessage = state.replyMessage,
            editingMessage = state.editingMessage,
            draftText = state.draftText,
            pendingMediaPaths = pendingMediaPaths,
            pendingDocumentPaths = pendingDocumentPaths,
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
            replyMarkup = state.messages.firstOrNull {
                it.replyMarkup is ReplyMarkupModel.ShowKeyboard
            }?.replyMarkup,
            mentionSuggestions = state.mentionSuggestions,
            inlineBotResults = state.inlineBotResults,
            currentInlineBotUsername = state.currentInlineBotUsername,
            currentInlineQuery = state.currentInlineQuery,
            isInlineBotLoading = state.isInlineBotLoading,
            attachBots = state.attachMenuBots,
            scheduledMessages = state.scheduledMessages,
            isPremiumUser = state.currentUser?.isPremium == true,
            isSecretChat = state.isSecretChat
        )
    }
}

@Composable
internal fun rememberChatInputBarActions(
    component: ChatComponent,
    state: ChatComponent.State,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    onPickMedia: () -> Unit,
    onHideKeyboardAndClearFocus: () -> Unit,
    onStartRecordingVideo: () -> Unit,
    onSetPendingMediaPaths: (List<String>) -> Unit,
    onSetPendingDocumentPaths: (List<String>) -> Unit,
    onEditMediaPath: (String) -> Unit
): ChatInputBarActions {
    return remember(component, state, pendingMediaPaths, pendingDocumentPaths) {
        ChatInputBarActions(
            onSend = { text, entities, options ->
                component.onSendMessage(text, entities, options)
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
            onSaveEdit = component::onSaveEditedMessage,
            onDraftChange = component::onDraftChange,
            onTyping = component::onTyping,
            onCancelMedia = { onSetPendingMediaPaths(emptyList()) },
            onSendMedia = { paths, caption, captionEntities, options ->
                if (options.sendAsDocument) {
                    if (paths.size > 1) {
                        component.onSendAlbum(paths, caption, captionEntities, options)
                    } else {
                        paths.firstOrNull()?.let {
                            component.onSendDocument(it, caption, captionEntities, options)
                        }
                    }
                } else if (paths.size > 1) {
                    component.onSendAlbum(paths, caption, captionEntities, options)
                } else {
                    paths.firstOrNull()?.let {
                        if (it.endsWith(".mp4")) {
                            component.onSendVideo(it, caption, captionEntities, options)
                        } else {
                            component.onSendPhoto(it, caption, captionEntities, options)
                        }
                    }
                }
                onSetPendingMediaPaths(emptyList())
                onSetPendingDocumentPaths(emptyList())
            },
            onSendDocuments = { paths, caption, captionEntities, options ->
                paths.forEachIndexed { index, path ->
                    component.onSendDocument(
                        path,
                        caption = if (index == 0) caption else "",
                        captionEntities = if (index == 0) captionEntities else emptyList(),
                        sendOptions = options
                    )
                }
                onSetPendingDocumentPaths(emptyList())
                onSetPendingMediaPaths(emptyList())
            },
            onMediaOrderChange = {
                onSetPendingMediaPaths(it)
                if (it.isNotEmpty()) {
                    onSetPendingDocumentPaths(emptyList())
                }
            },
            onDocumentOrderChange = {
                onSetPendingDocumentPaths(it)
                if (it.isNotEmpty()) {
                    onSetPendingMediaPaths(emptyList())
                }
            },
            onMediaClick = onEditMediaPath,
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
                component.onLinkClick("https://t.me/$botUsername?start=$encodedParameter")
            },
            onAttachBotClick = { bot ->
                component.onOpenAttachBot(bot.botUserId, bot.name)
            },
            onSendPoll = component::onSendPoll,
            onRefreshScheduledMessages = component::onRefreshScheduledMessages,
            onEditScheduledMessage = component::onEditMessage,
            onDeleteScheduledMessage = component::onDeleteMessage,
            onSendScheduledNow = component::onSendScheduledNow
        )
    }
}

