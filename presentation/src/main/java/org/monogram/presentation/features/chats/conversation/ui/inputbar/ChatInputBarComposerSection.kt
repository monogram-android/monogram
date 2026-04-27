package org.monogram.presentation.features.chats.conversation.ui.inputbar

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.conversation.ui.message.BotCommandSuggestions
import org.monogram.presentation.features.stickers.ui.menu.StickerEmojiMenu

@Composable
internal fun ChatInputBarComposerSection(
    modifier: Modifier = Modifier,
    editingMessage: MessageModel?,
    replyMessage: MessageModel?,
    attachments: ComposerAttachmentState,
    suggestions: ComposerSuggestionState,
    botState: ComposerBotState,
    rowState: ComposerRowState,
    onTextValueChange: (TextFieldValue) -> Unit,
    knownCustomEmojis: MutableMap<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    capabilities: ChatInputBarCapabilities,
    canSendAttachments: Boolean,
    canPasteMediaFromClipboard: Boolean,
    voiceRecorder: VoiceRecorderState,
    stickerRepository: StickerRepository,
    isTablet: Boolean = false,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelMedia: () -> Unit,
    onCancelDocuments: () -> Unit,
    onAddMedia: () -> Unit,
    onAddDocuments: () -> Unit,
    onMediaOrderChange: (List<String>) -> Unit,
    onDocumentOrderChange: (List<String>) -> Unit,
    onMediaClick: (String) -> Unit,
    onPasteImages: (List<Uri>) -> Unit,
    onMentionClick: (UserModel) -> Unit,
    onMentionQueryClear: () -> Unit,
    onInlineResultClick: (String) -> Unit,
    onInlineSwitchPmClick: (String) -> Unit,
    onLoadMoreInlineResults: (String) -> Unit,
    onCommandClick: (String) -> Unit,
    onAttachClick: () -> Unit,
    onStickerMenuToggle: () -> Unit,
    onShowBotCommands: () -> Unit,
    onOpenMiniApp: (String, String) -> Unit,
    onInputFocus: () -> Unit,
    onOpenFullScreenEditor: () -> Unit,
    onOpenScheduledMessages: () -> Unit,
    onSendWithOptions: (MessageSendOptions) -> Unit,
    onShowSendOptionsMenu: () -> Unit,
    onSendAsDocument: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoModeToggle: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: (Boolean) -> Unit,
    onVoiceLock: () -> Unit,
    onSendSilent: () -> Unit,
    onScheduleMessage: () -> Unit,
    onOpenScheduledMessagesFromPopup: () -> Unit,
    onDismissSendOptions: () -> Unit,
    onStickerClick: (String) -> Unit,
    onGifClick: (GifModel) -> Unit,
    onGifSearchFocusedChange: (Boolean) -> Unit,
    onReplyMarkupButtonClick: (KeyboardButtonModel) -> Unit
) {
    val inputUiState = remember(
        attachments.pendingMediaPaths,
        attachments.pendingDocumentPaths,
        botState,
        rowState.textValue,
        rowState.editingMessage,
        rowState.isStickerMenuVisible,
        capabilities.canSendStickers,
        capabilities.canWriteText,
        capabilities.canOpenAttachSheet,
        canPasteMediaFromClipboard
    ) {
        InputTextFieldUiState(
            textValue = rowState.textValue,
            isBot = botState.isBot,
            botMenuButton = botState.botMenuButton,
            botCommands = botState.botCommands,
            canSendStickers = capabilities.canSendStickers,
            canWriteText = capabilities.canWriteText,
            canShowBotActions = capabilities.canWriteText,
            isStickerMenuVisible = rowState.isStickerMenuVisible,
            canAttachMedia = rowState.editingMessage == null &&
                    attachments.pendingMediaPaths.isEmpty() &&
                    attachments.pendingDocumentPaths.isEmpty() &&
                    capabilities.canOpenAttachSheet,
            canPasteMediaFromClipboard = canPasteMediaFromClipboard,
            pendingMediaPaths = attachments.pendingMediaPaths,
            pendingDocumentPaths = attachments.pendingDocumentPaths,
            showExpandEditorAction = rowState.textValue.text.contains('\n') || rowState.textValue.text.length > 150
        )
    }
    val sendButtonState = remember(
        rowState.textValue.text,
        rowState.editingMessage,
        attachments.pendingMediaPaths,
        attachments.pendingDocumentPaths,
        rowState.isOverMessageLimit,
        capabilities.canWriteText,
        canSendAttachments,
        capabilities.canSendVoice,
        capabilities.canSendVideoNotes,
        rowState.isVideoMessageMode,
        rowState.isSlowModeActive,
        rowState.slowModeRemainingSeconds
    ) {
        InputBarSendButtonState(
            isTextEmpty = rowState.textValue.text.isBlank(),
            isEditing = rowState.editingMessage != null,
            hasPendingAttachments = attachments.pendingMediaPaths.isNotEmpty() || attachments.pendingDocumentPaths.isNotEmpty(),
            isOverCharLimit = rowState.isOverMessageLimit,
            canWriteText = capabilities.canWriteText,
            canSendAttachments = canSendAttachments,
            canSendVoice = capabilities.canSendVoice,
            canSendVideoNotes = capabilities.canSendVideoNotes,
            isVideoMessageMode = rowState.isVideoMessageMode,
            isSlowModeActive = rowState.isSlowModeActive,
            slowModeRemainingSeconds = rowState.slowModeRemainingSeconds
        )
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = if (isTablet) {
            RoundedCornerShape(16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = rowState.bottomInset)
        ) {
            InputPreviewSection(
                editingMessage = editingMessage,
                replyMessage = replyMessage,
                pendingMediaPaths = attachments.pendingMediaPaths,
                pendingDocumentPaths = attachments.pendingDocumentPaths,
                onCancelEdit = onCancelEdit,
                onCancelReply = onCancelReply,
                onCancelMedia = onCancelMedia,
                onCancelDocuments = onCancelDocuments,
                onAddMedia = onAddMedia,
                onAddDocuments = onAddDocuments,
                onMediaOrderChange = onMediaOrderChange,
                onDocumentOrderChange = onDocumentOrderChange,
                onMediaClick = onMediaClick
            )

            AnimatedVisibility(
                visible = suggestions.mentionSuggestions.isNotEmpty(),
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
            ) {
                MentionSuggestions(
                    suggestions = suggestions.mentionSuggestions,
                    onMentionClick = {
                        onMentionClick(it)
                        onMentionQueryClear()
                    }
                )
            }

            AnimatedVisibility(
                visible = suggestions.filteredCommands.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                BotCommandSuggestions(
                    commands = suggestions.filteredCommands,
                    onCommandClick = onCommandClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = suggestions.currentInlineBotUsername != null || suggestions.isInlineBotLoading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                InlineBotResults(
                    inlineBotResults = suggestions.inlineBotResults,
                    isInlineMode = suggestions.currentInlineBotUsername != null,
                    isLoading = suggestions.isInlineBotLoading,
                    onResultClick = onInlineResultClick,
                    onSwitchPmClick = onInlineSwitchPmClick,
                    onLoadMore = onLoadMoreInlineResults
                )
            }

            AnimatedVisibility(
                visible = !suggestions.isGifSearchFocused,
                enter = expandVertically(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200))
            ) {
                ComposerMainRow(
                    inputUiState = inputUiState,
                    sendButtonState = sendButtonState,
                    attachments = attachments,
                    voiceRecorder = voiceRecorder,
                    knownCustomEmojis = knownCustomEmojis,
                    emojiFontFamily = emojiFontFamily,
                    focusRequester = focusRequester,
                    onTextValueChange = onTextValueChange,
                    onStickerMenuToggle = onStickerMenuToggle,
                    onAttachClick = onAttachClick,
                    onShowBotCommands = onShowBotCommands,
                    onOpenMiniApp = onOpenMiniApp,
                    onPasteImages = onPasteImages,
                    onInputFocus = onInputFocus,
                    onOpenFullScreenEditor = onOpenFullScreenEditor,
                    onOpenScheduledMessages = onOpenScheduledMessages,
                    onSendWithOptions = onSendWithOptions,
                    onShowSendOptionsMenu = onShowSendOptionsMenu,
                    onCameraClick = onCameraClick,
                    onVideoModeToggle = onVideoModeToggle,
                    onVoiceStart = onVoiceStart,
                    onVoiceStop = onVoiceStop,
                    onVoiceLock = onVoiceLock
                )

                ComposerSendOptionsPopup(
                    expanded = rowState.showSendOptionsSheet,
                    scheduledMessagesCount = attachments.scheduledMessagesCount,
                    showSendAsDocument = attachments.pendingMediaPaths.isNotEmpty(),
                    onDismiss = onDismissSendOptions,
                    onSendAsDocument = onSendAsDocument,
                    onSendSilent = onSendSilent,
                    onScheduleMessage = onScheduleMessage,
                    onOpenScheduledMessages = onOpenScheduledMessagesFromPopup
                )
            }

            AnimatedVisibility(
                visible = !voiceRecorder.isRecording && !rowState.showFullScreenEditor && rowState.currentMessageLength > 1000,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = stringResource(
                        R.string.message_length_counter,
                        rowState.currentMessageLength,
                        rowState.maxMessageLength
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rowState.isOverMessageLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = suggestions.replyMarkup is ReplyMarkupModel.ShowKeyboard && rowState.textValue.text.isEmpty() && !rowState.isStickerMenuVisible && !rowState.isKeyboardVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val markup = suggestions.replyMarkup as? ReplyMarkupModel.ShowKeyboard
                    ?: return@AnimatedVisibility
                KeyboardMarkupView(
                    markup = markup,
                    onButtonClick = onReplyMarkupButtonClick,
                    onOpenMiniApp = onOpenMiniApp
                )
            }

            AnimatedVisibility(
                visible = rowState.isStickerMenuVisible,
                enter = slideInVertically(
                    animationSpec = tween(220),
                    initialOffsetY = { it }) + fadeIn(animationSpec = tween(170)),
                exit = if (rowState.closeStickerMenuWithoutSlide) {
                    fadeOut(animationSpec = tween(90))
                } else {
                    slideOutVertically(
                        animationSpec = tween(170),
                        targetOffsetY = { it }) + fadeOut(animationSpec = tween(120))
                }
            ) {
                StickerEmojiMenu(
                    onStickerSelected = onStickerClick,
                    onEmojiSelected = { emoji, sticker ->
                        onTextValueChange(
                            insertEmojiAtSelection(
                                value = rowState.textValue,
                                emoji = emoji,
                                sticker = sticker,
                                knownCustomEmojis = knownCustomEmojis
                            )
                        )
                    },
                    onGifSelected = onGifClick,
                    onSearchFocused = onGifSearchFocusedChange,
                    panelHeight = rowState.stickerMenuHeight,
                    canSendStickers = capabilities.canSendStickers,
                    stickerRepository = stickerRepository
                )
            }
        }
    }
}

@Composable
private fun ComposerMainRow(
    inputUiState: InputTextFieldUiState,
    sendButtonState: InputBarSendButtonState,
    attachments: ComposerAttachmentState,
    voiceRecorder: VoiceRecorderState,
    knownCustomEmojis: MutableMap<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    onTextValueChange: (TextFieldValue) -> Unit,
    onStickerMenuToggle: () -> Unit,
    onAttachClick: () -> Unit,
    onShowBotCommands: () -> Unit,
    onOpenMiniApp: (String, String) -> Unit,
    onPasteImages: (List<Uri>) -> Unit,
    onInputFocus: () -> Unit,
    onOpenFullScreenEditor: () -> Unit,
    onOpenScheduledMessages: () -> Unit,
    onSendWithOptions: (MessageSendOptions) -> Unit,
    onShowSendOptionsMenu: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoModeToggle: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: (Boolean) -> Unit,
    onVoiceLock: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ComposerInputSlot(
            modifier = Modifier.weight(1f),
            uiState = inputUiState,
            attachments = attachments,
            voiceRecorder = voiceRecorder,
            knownCustomEmojis = knownCustomEmojis,
            emojiFontFamily = emojiFontFamily,
            focusRequester = focusRequester,
            onTextValueChange = onTextValueChange,
            onStickerMenuToggle = onStickerMenuToggle,
            onAttachClick = onAttachClick,
            onShowBotCommands = onShowBotCommands,
            onOpenMiniApp = onOpenMiniApp,
            onPasteImages = onPasteImages,
            onInputFocus = onInputFocus,
            onOpenFullScreenEditor = onOpenFullScreenEditor,
            onVoiceStop = onVoiceStop
        )

        ComposerActionsSlot(
            attachments = attachments,
            sendButtonState = sendButtonState,
            voiceRecorder = voiceRecorder,
            onOpenScheduledMessages = onOpenScheduledMessages,
            onSendWithOptions = onSendWithOptions,
            onShowSendOptionsMenu = onShowSendOptionsMenu,
            onCameraClick = onCameraClick,
            onVideoModeToggle = onVideoModeToggle,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
            onVoiceLock = onVoiceLock
        )
    }
}

@Composable
private fun ComposerInputSlot(
    modifier: Modifier = Modifier,
    uiState: InputTextFieldUiState,
    attachments: ComposerAttachmentState,
    voiceRecorder: VoiceRecorderState,
    knownCustomEmojis: MutableMap<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    onTextValueChange: (TextFieldValue) -> Unit,
    onStickerMenuToggle: () -> Unit,
    onAttachClick: () -> Unit,
    onShowBotCommands: () -> Unit,
    onOpenMiniApp: (String, String) -> Unit,
    onPasteImages: (List<Uri>) -> Unit,
    onInputFocus: () -> Unit,
    onOpenFullScreenEditor: () -> Unit,
    onVoiceStop: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            .padding(start = if (voiceRecorder.isRecording) 0.dp else 4.dp)
    ) {
        AnimatedContent(
            targetState = voiceRecorder.isRecording,
            transitionSpec = { (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut()) },
            label = "InputContent"
        ) { isRecording ->
            if (isRecording) {
                RecordingUI(
                    voiceRecorderState = voiceRecorder,
                    onStop = { onVoiceStop(false) },
                    onCancel = { onVoiceStop(true) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                InputTextFieldContainer(
                    uiState = uiState,
                    onValueChange = {
                        onTextValueChange(
                            mergeInputTextValuePreservingAnnotations(
                                uiState.textValue,
                                it
                            )
                        )
                    },
                    onRichTextValueChange = onTextValueChange,
                    onStickerMenuToggle = onStickerMenuToggle,
                    onAttachClick = onAttachClick,
                    onShowBotCommands = onShowBotCommands,
                    onOpenMiniApp = onOpenMiniApp,
                    knownCustomEmojis = knownCustomEmojis,
                    emojiFontFamily = emojiFontFamily,
                    focusRequester = focusRequester,
                    pendingMediaPaths = attachments.pendingMediaPaths,
                    pendingDocumentPaths = attachments.pendingDocumentPaths,
                    onPasteImages = onPasteImages,
                    onFocus = onInputFocus,
                    onOpenFullScreenEditor = onOpenFullScreenEditor,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ComposerActionsSlot(
    attachments: ComposerAttachmentState,
    sendButtonState: InputBarSendButtonState,
    voiceRecorder: VoiceRecorderState,
    onOpenScheduledMessages: () -> Unit,
    onSendWithOptions: (MessageSendOptions) -> Unit,
    onShowSendOptionsMenu: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoModeToggle: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: (Boolean) -> Unit,
    onVoiceLock: () -> Unit
) {
    if (!voiceRecorder.isLocked) {
        if (attachments.scheduledMessagesCount > 0) {
            IconButton(onClick = onOpenScheduledMessages) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = stringResource(R.string.action_scheduled_messages),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(contentAlignment = Alignment.CenterEnd) {
            InputBarSendButton(
                state = sendButtonState,
                onSendWithOptions = onSendWithOptions,
                onShowSendOptionsMenu = onShowSendOptionsMenu,
                onCameraClick = onCameraClick,
                onVideoModeToggle = onVideoModeToggle,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                onVoiceLock = onVoiceLock
            )
        }
    }
}

@Composable
private fun ComposerSendOptionsPopup(
    expanded: Boolean,
    scheduledMessagesCount: Int,
    showSendAsDocument: Boolean,
    onDismiss: () -> Unit,
    onSendAsDocument: () -> Unit,
    onSendSilent: () -> Unit,
    onScheduleMessage: () -> Unit,
    onOpenScheduledMessages: () -> Unit
) {
    SendOptionsPopup(
        expanded = expanded,
        scheduledMessagesCount = scheduledMessagesCount,
        showSendAsDocument = showSendAsDocument,
        onDismiss = onDismiss,
        onSendAsDocument = onSendAsDocument,
        onSendSilent = onSendSilent,
        onScheduleMessage = onScheduleMessage,
        onOpenScheduledMessages = onOpenScheduledMessages
    )
}
