package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.*
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.chats.BotCommandSuggestions
import org.monogram.presentation.features.stickers.ui.menu.StickerEmojiMenu

@Composable
fun ChatInputBarComposerSection(
    editingMessage: MessageModel?,
    replyMessage: MessageModel?,
    pendingMediaPaths: List<String>,
    mentionSuggestions: List<UserModel>,
    filteredCommands: List<BotCommandModel>,
    currentInlineBotUsername: String?,
    isInlineBotLoading: Boolean,
    inlineBotResults: org.monogram.domain.repository.InlineBotResultsModel?,
    isBot: Boolean,
    botMenuButton: BotMenuButtonModel,
    botCommands: List<BotCommandModel>,
    scheduledMessagesCount: Int,
    textValue: TextFieldValue,
    onTextValueChange: (TextFieldValue) -> Unit,
    knownCustomEmojis: MutableMap<Long, StickerModel>,
    emojiFontFamily: FontFamily,
    focusRequester: FocusRequester,
    canWriteText: Boolean,
    canSendMedia: Boolean,
    canSendStickers: Boolean,
    canSendVoice: Boolean,
    isStickerMenuVisible: Boolean,
    closeStickerMenuWithoutSlide: Boolean,
    isKeyboardVisible: Boolean,
    transitionHoldBottomInset: Dp,
    stickerMenuHeight: Dp,
    voiceRecorder: VoiceRecorderState,
    isGifSearchFocused: Boolean,
    showFullScreenEditor: Boolean,
    currentMessageLength: Int,
    maxMessageLength: Int,
    isOverMessageLimit: Boolean,
    isVideoMessageMode: Boolean,
    replyMarkup: ReplyMarkupModel?,
    showSendOptionsSheet: Boolean,
    stickerRepository: StickerRepository,
    onCancelEdit: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelMedia: () -> Unit,
    onMediaOrderChange: (List<String>) -> Unit,
    onMediaClick: (String) -> Unit,
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
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = transitionHoldBottomInset)
        ) {
            InputPreviewSection(
                editingMessage = editingMessage,
                replyMessage = replyMessage,
                pendingMediaPaths = pendingMediaPaths,
                onCancelEdit = onCancelEdit,
                onCancelReply = onCancelReply,
                onCancelMedia = onCancelMedia,
                onMediaOrderChange = onMediaOrderChange,
                onMediaClick = onMediaClick
            )

            AnimatedVisibility(
                visible = mentionSuggestions.isNotEmpty(),
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
            ) {
                MentionSuggestions(
                    suggestions = mentionSuggestions,
                    onMentionClick = {
                        onMentionClick(it)
                        onMentionQueryClear()
                    }
                )
            }

            AnimatedVisibility(
                visible = filteredCommands.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                BotCommandSuggestions(
                    commands = filteredCommands,
                    onCommandClick = onCommandClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            AnimatedVisibility(
                visible = currentInlineBotUsername != null || isInlineBotLoading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                InlineBotResults(
                    inlineBotResults = inlineBotResults,
                    isInlineMode = currentInlineBotUsername != null,
                    isLoading = isInlineBotLoading,
                    onResultClick = onInlineResultClick,
                    onSwitchPmClick = onInlineSwitchPmClick,
                    onLoadMore = onLoadMoreInlineResults
                )
            }

            AnimatedVisibility(
                visible = !isGifSearchFocused,
                enter = expandVertically(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    AnimatedVisibility(
                        visible = !voiceRecorder.isRecording,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        InputBarLeadingIcons(
                            editingMessage = editingMessage,
                            pendingMediaPaths = pendingMediaPaths,
                            canSendMedia = canSendMedia,
                            onAttachClick = onAttachClick
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
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
                                    textValue = textValue,
                                    onValueChange = {
                                        onTextValueChange(
                                            mergeInputTextValuePreservingAnnotations(
                                                textValue,
                                                it
                                            )
                                        )
                                    },
                                    onRichTextValueChange = onTextValueChange,
                                    isBot = isBot,
                                    botMenuButton = botMenuButton,
                                    botCommands = botCommands,
                                    canSendStickers = canSendStickers,
                                    canWriteText = canWriteText,
                                    isStickerMenuVisible = isStickerMenuVisible,
                                    onStickerMenuToggle = onStickerMenuToggle,
                                    onShowBotCommands = onShowBotCommands,
                                    onOpenMiniApp = onOpenMiniApp,
                                    knownCustomEmojis = knownCustomEmojis,
                                    emojiFontFamily = emojiFontFamily,
                                    focusRequester = focusRequester,
                                    pendingMediaPaths = pendingMediaPaths,
                                    onFocus = onInputFocus,
                                    onOpenFullScreenEditor = onOpenFullScreenEditor,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    if (!voiceRecorder.isLocked) {
                        if (scheduledMessagesCount > 0) {
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
                                textValue = textValue,
                                editingMessage = editingMessage,
                                pendingMediaPaths = pendingMediaPaths,
                                isOverCharLimit = isOverMessageLimit,
                                canWriteText = canWriteText,
                                canSendVoice = canSendVoice,
                                canSendMedia = canSendMedia,
                                isVideoMessageMode = isVideoMessageMode,
                                onSendWithOptions = onSendWithOptions,
                                onShowSendOptionsMenu = onShowSendOptionsMenu,
                                onCameraClick = onCameraClick,
                                onVideoModeToggle = onVideoModeToggle,
                                onVoiceStart = onVoiceStart,
                                onVoiceStop = onVoiceStop,
                                onVoiceLock = onVoiceLock
                            )

                            SendOptionsPopup(
                                expanded = showSendOptionsSheet,
                                scheduledMessagesCount = scheduledMessagesCount,
                                onDismiss = onDismissSendOptions,
                                onSendSilent = onSendSilent,
                                onScheduleMessage = onScheduleMessage,
                                onOpenScheduledMessages = onOpenScheduledMessagesFromPopup
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !voiceRecorder.isRecording && !showFullScreenEditor && currentMessageLength > 1000,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = stringResource(R.string.message_length_counter, currentMessageLength, maxMessageLength),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOverMessageLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = replyMarkup is ReplyMarkupModel.ShowKeyboard && textValue.text.isEmpty() && !isStickerMenuVisible && !isKeyboardVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val markup = replyMarkup as? ReplyMarkupModel.ShowKeyboard ?: return@AnimatedVisibility
                KeyboardMarkupView(
                    markup = markup,
                    onButtonClick = onReplyMarkupButtonClick,
                    onOpenMiniApp = onOpenMiniApp
                )
            }

            AnimatedVisibility(
                visible = isStickerMenuVisible,
                enter = slideInVertically(
                    animationSpec = tween(220),
                    initialOffsetY = { it }) + fadeIn(animationSpec = tween(170)),
                exit = if (closeStickerMenuWithoutSlide) {
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
                                value = textValue,
                                emoji = emoji,
                                sticker = sticker,
                                knownCustomEmojis = knownCustomEmojis
                            )
                        )
                    },
                    onGifSelected = onGifClick,
                    onSearchFocused = onGifSearchFocusedChange,
                    panelHeight = stickerMenuHeight,
                    stickerRepository = stickerRepository
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
