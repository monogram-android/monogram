package org.monogram.presentation.features.chats.currentChat.components

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.monogram.domain.models.*
import org.monogram.domain.repository.InlineBotResultsModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.camera.CameraScreen
import org.monogram.presentation.features.chats.currentChat.components.chats.BotCommandSuggestions
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.chats.currentChat.components.inputbar.*
import org.monogram.presentation.features.gallery.GalleryScreen
import org.monogram.presentation.features.stickers.ui.menu.StickerEmojiMenu
import java.io.File
import java.io.FileOutputStream
import java.util.*

@Immutable
data class ChatInputBarState(
    val replyMessage: MessageModel? = null,
    val editingMessage: MessageModel? = null,
    val draftText: String = "",
    val pendingMediaPaths: List<String> = emptyList(),
    val isClosed: Boolean = false,
    val permissions: ChatPermissionsModel = ChatPermissionsModel(),
    val isAdmin: Boolean = false,
    val isChannel: Boolean = false,
    val isBot: Boolean = false,
    val botCommands: List<BotCommandModel> = emptyList(),
    val botMenuButton: BotMenuButtonModel = BotMenuButtonModel.Default,
    val replyMarkup: ReplyMarkupModel? = null,
    val mentionSuggestions: List<UserModel> = emptyList(),
    val inlineBotResults: InlineBotResultsModel? = null,
    val currentInlineBotUsername: String? = null,
    val currentInlineQuery: String? = null,
    val isInlineBotLoading: Boolean = false,
    val attachBots: List<AttachMenuBotModel> = emptyList(),
    val scheduledMessages: List<MessageModel> = emptyList(),
    val isPremiumUser: Boolean = false,
)

@Immutable
data class ChatInputBarActions(
    val onSend: (String, List<MessageEntity>, MessageSendOptions) -> Unit,
    val onStickerClick: (String) -> Unit = {},
    val onGifClick: (GifModel) -> Unit = {},
    val onAttachClick: () -> Unit = {},
    val onCameraClick: () -> Unit = {},
    val onSendVoice: (String, Int, ByteArray) -> Unit = { _, _, _ -> },
    val onCancelReply: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onSaveEdit: (String, List<MessageEntity>) -> Unit = { _, _ -> },
    val onDraftChange: (String) -> Unit = {},
    val onTyping: () -> Unit = {},
    val onCancelMedia: () -> Unit = {},
    val onSendMedia: (List<String>, String, List<MessageEntity>, MessageSendOptions) -> Unit = { _, _, _, _ -> },
    val onMediaOrderChange: (List<String>) -> Unit = {},
    val onMediaClick: (String) -> Unit = {},
    val onShowBotCommands: () -> Unit = {},
    val onReplyMarkupButtonClick: (KeyboardButtonModel) -> Unit = {},
    val onOpenMiniApp: (String, String) -> Unit = { _, _ -> },
    val onMentionQueryChange: (String?) -> Unit = {},
    val onInlineQueryChange: (String, String) -> Unit = { _, _ -> },
    val onLoadMoreInlineResults: (String) -> Unit = {},
    val onSendInlineResult: (String) -> Unit = {},
    val onInlineSwitchPm: (String, String) -> Unit = { _, _ -> },
    val onAttachBotClick: (AttachMenuBotModel) -> Unit = {},
    val onGalleryClick: () -> Unit = {},
    val onRefreshScheduledMessages: () -> Unit = {},
    val onEditScheduledMessage: (MessageModel) -> Unit = {},
    val onDeleteScheduledMessage: (MessageModel) -> Unit = {},
    val onSendScheduledNow: (MessageModel) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    state: ChatInputBarState,
    actions: ChatInputBarActions,
    appPreferences: AppPreferences,
    videoPlayerPool: VideoPlayerPool,
    stickerRepository: StickerRepository
) {
    if (state.isClosed) {
        ClosedTopicBar()
        return
    }

    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }

    val canWriteText = remember(state.isChannel, state.isAdmin, state.permissions.canSendBasicMessages) {
        if (state.isChannel) true else (state.isAdmin || state.permissions.canSendBasicMessages)
    }
    val canSendMedia = remember(
        state.isChannel,
        state.isAdmin,
        state.permissions.canSendPhotos,
        state.permissions.canSendVideos,
        state.permissions.canSendDocuments
    ) {
        if (state.isChannel) true else (state.isAdmin || (state.permissions.canSendPhotos || state.permissions.canSendVideos || state.permissions.canSendDocuments))
    }
    val canSendStickers = remember(state.isChannel, state.isAdmin, state.permissions.canSendOtherMessages) {
        if (state.isChannel) true else (state.isAdmin || state.permissions.canSendOtherMessages)
    }
    val canSendVoice = remember(state.isChannel, state.isAdmin, state.permissions.canSendVoiceNotes) {
        if (state.isChannel) true else (state.isAdmin || state.permissions.canSendVoiceNotes)
    }

    var textValue by remember { mutableStateOf(TextFieldValue(state.draftText)) }
    var isStickerMenuVisible by remember { mutableStateOf(false) }
    var isVideoMessageMode by remember { mutableStateOf(false) }
    var isGifSearchFocused by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) } // New state for gallery visibility
    var showCamera by remember { mutableStateOf(false) } // New state for camera visibility
    var showFullScreenEditor by remember { mutableStateOf(false) }
    var showFullScreenEmojiPicker by remember { mutableStateOf(false) }
    var showFullScreenLinkDialog by remember { mutableStateOf(false) }
    var fullScreenLinkValue by remember { mutableStateOf("https://") }
    var showFullScreenLanguageDialog by remember { mutableStateOf(false) }
    var fullScreenLanguageValue by remember { mutableStateOf("") }
    var showSendOptionsSheet by remember { mutableStateOf(false) }
    var showScheduledMessagesSheet by remember { mutableStateOf(false) }

    val knownCustomEmojis = remember { mutableStateMapOf<Long, StickerModel>() }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val fullScreenFocusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    var lastEditingMessageId by remember { mutableStateOf<Long?>(null) }

    val voiceRecorder = rememberVoiceRecorder(onRecordingFinished = actions.onSendVoice)
    val maxMessageLength = remember(state.pendingMediaPaths, state.isPremiumUser) {
        if (state.pendingMediaPaths.isNotEmpty() && !state.isPremiumUser) 1024 else 4096
    }
    val currentMessageLength = textValue.text.length
    val isOverMessageLimit = currentMessageLength > maxMessageLength

    val sendWithOptions: (MessageSendOptions) -> Unit = sendWithOptions@{
        if (isOverMessageLimit) return@sendWithOptions
        val isTextEmpty = textValue.text.isBlank()
        val captionEntities = extractEntities(textValue.annotatedString, knownCustomEmojis)

        if (state.pendingMediaPaths.isNotEmpty() && canSendMedia) {
            actions.onSendMedia(state.pendingMediaPaths, textValue.text, captionEntities, it)
            textValue = TextFieldValue("")
            knownCustomEmojis.clear()
        } else if (state.editingMessage != null && canWriteText) {
            if (!isTextEmpty) {
                actions.onSaveEdit(textValue.text, captionEntities)
            }
        } else if (canWriteText && !isTextEmpty) {
            actions.onSend(textValue.text, captionEntities, it)
            textValue = TextFieldValue("")
            knownCustomEmojis.clear()
        }

        if (it.scheduleDate != null) {
            actions.onRefreshScheduledMessages()
        }
    }

    val filteredCommands = remember(textValue.text, state.botCommands) {
        if (textValue.text.startsWith("/")) {
            val query = textValue.text.substring(1).lowercase()
            state.botCommands.filter { it.command.lowercase().startsWith(query) }
        } else {
            emptyList()
        }
    }

    val currentOnMentionQueryChange by rememberUpdatedState(actions.onMentionQueryChange)
    LaunchedEffect(textValue.text, textValue.selection) {
        val text = textValue.text
        val selection = textValue.selection
        if (selection.collapsed && selection.start > 0) {
            val lastAt = text.lastIndexOf('@', selection.start - 1)
            if (lastAt != -1) {
                val isStartOfWord = lastAt == 0 || text[lastAt - 1].isWhitespace()
                if (isStartOfWord) {
                    val query = text.substring(lastAt + 1, selection.start)
                    if (!query.contains(' ')) {
                        currentOnMentionQueryChange(query)
                    } else {
                        currentOnMentionQueryChange(null)
                    }
                } else {
                    currentOnMentionQueryChange(null)
                }
            } else {
                currentOnMentionQueryChange(null)
            }
        } else {
            currentOnMentionQueryChange(null)
        }
    }

    val currentOnInlineQueryChange by rememberUpdatedState(actions.onInlineQueryChange)
    LaunchedEffect(textValue.text, textValue.selection) {
        val inlineQuery = parseInlineQueryInput(
            text = textValue.text,
            selection = textValue.selection
        )

        if (inlineQuery != null) {
            currentOnInlineQueryChange(inlineQuery.botUsername, inlineQuery.query)
        } else {
            currentOnInlineQueryChange("", "")
        }
    }

    LaunchedEffect(state.draftText) {
        val shouldApplyInlinePrefill =
            state.editingMessage == null &&
                    state.draftText.isInlineBotPrefillText() &&
                    state.draftText != textValue.text

        if (shouldApplyInlinePrefill || (textValue.text.isEmpty() && state.draftText.isNotEmpty())) {
            textValue = TextFieldValue(state.draftText, TextRange(state.draftText.length))
            if (shouldApplyInlinePrefill) {
                focusRequester.requestFocus()
            }
        }
    }

    val currentOnDraftChange by rememberUpdatedState(actions.onDraftChange)
    val currentOnTyping by rememberUpdatedState(actions.onTyping)
    LaunchedEffect(textValue.text) {
        if (state.editingMessage == null && textValue.text != state.draftText) {
            currentOnDraftChange(textValue.text)
        }
        if (textValue.text.isNotEmpty()) {
            currentOnTyping()
        }
    }

    LaunchedEffect(state.editingMessage) {
        val editingMessage = state.editingMessage
        if (editingMessage != null) {
            if (editingMessage.id != lastEditingMessageId) {
                val content = editingMessage.content
                if (content is MessageContent.Text) {
                    knownCustomEmojis.clear()
                    content.entities.forEach { entity ->
                        if (entity.type is MessageEntityType.CustomEmoji) {
                            val type = entity.type as MessageEntityType.CustomEmoji
                            if (type.path != null) {
                                knownCustomEmojis[type.emojiId] = StickerModel(
                                    id = type.emojiId,
                                    customEmojiId = type.emojiId,
                                    width = 0,
                                    height = 0,
                                    emoji = "",
                                    path = type.path,
                                    format = StickerFormat.UNKNOWN
                                )
                            }
                        }
                    }

                    val annotatedText = buildAnnotatedString {
                        append(content.text)
                        content.entities.forEach { entity ->
                            when (val type = entity.type) {
                                is MessageEntityType.CustomEmoji -> {
                                    addStringAnnotation(
                                        CUSTOM_EMOJI_TAG,
                                        type.emojiId.toString(),
                                        entity.offset,
                                        entity.offset + entity.length
                                    )
                                }

                                is MessageEntityType.TextMention -> {
                                    addStringAnnotation(
                                        MENTION_TAG,
                                        type.userId.toString(),
                                        entity.offset,
                                        entity.offset + entity.length
                                    )
                                }

                                else -> {
                                    val richEntity = richEntityToAnnotation(type)
                                    if (richEntity != null) {
                                        addStringAnnotation(
                                            RICH_ENTITY_TAG,
                                            richEntity,
                                            entity.offset,
                                            entity.offset + entity.length
                                        )
                                    }
                                }
                            }
                        }
                    }

                    textValue = TextFieldValue(annotatedText, TextRange(content.text.length))
                    focusRequester.requestFocus()
                }
                lastEditingMessageId = editingMessage.id
            }
        } else {
            if (lastEditingMessageId != null) {
                textValue = TextFieldValue(state.draftText, TextRange(state.draftText.length))
                lastEditingMessageId = null
                knownCustomEmojis.clear()
            }
        }
    }

    BackHandler(enabled = isStickerMenuVisible || state.pendingMediaPaths.isNotEmpty() || showGallery || showCamera || showFullScreenEditor || showSendOptionsSheet || showScheduledMessagesSheet || showFullScreenEmojiPicker) {
        if (isGifSearchFocused) {
            focusManager.clearFocus()
        } else if (isStickerMenuVisible) {
            isStickerMenuVisible = false
        } else if (showFullScreenEmojiPicker) {
            showFullScreenEmojiPicker = false
        } else if (showSendOptionsSheet) {
            showSendOptionsSheet = false
        } else if (showScheduledMessagesSheet) {
            showScheduledMessagesSheet = false
        } else if (showFullScreenEditor) {
            showFullScreenEditor = false
        } else if (state.pendingMediaPaths.isNotEmpty()) {
            actions.onCancelMedia()
        } else if (showGallery) {
            showGallery = false
        } else if (showCamera) {
            showCamera = false
        }
    }

    // Gallery permissions
    val galleryPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val requestableGalleryPermissions = remember(context, galleryPermissions) {
        val declared = context.declaredPermissions()
        galleryPermissions.filter { it in declared }
    }
    val hasGalleryPermission = remember(context) {
        mutableStateOf(
            requestableGalleryPermissions.isEmpty() || context.hasAllPermissions(requestableGalleryPermissions)
        )
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        hasGalleryPermission.value = granted
        if (granted || requestableGalleryPermissions.isEmpty()) showGallery = true
    }

    // Camera permission
    val hasCameraPermission = remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission.value = granted
        if (granted) showCamera = true
    }

    if (showCamera) {
        CameraScreen(
            onImageCaptured = { uri ->
                val path = context.copyUriToTempPath(uri)
                if (path != null) {
                    actions.onMediaOrderChange((state.pendingMediaPaths + path).distinct())
                }
                showCamera = false
            },
            onDismiss = { showCamera = false }
        )
    } else {
        Box {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                ) {
                    InputPreviewSection(
                        editingMessage = state.editingMessage,
                        replyMessage = state.replyMessage,
                        pendingMediaPaths = state.pendingMediaPaths,
                        onCancelEdit = actions.onCancelEdit,
                        onCancelReply = actions.onCancelReply,
                        onCancelMedia = actions.onCancelMedia,
                        onMediaOrderChange = actions.onMediaOrderChange,
                        onMediaClick = actions.onMediaClick
                    )

                    AnimatedVisibility(
                        visible = state.mentionSuggestions.isNotEmpty(),
                        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
                    ) {
                        MentionSuggestions(
                            suggestions = state.mentionSuggestions,
                            onMentionClick = { user ->
                                val text = textValue.text
                                val selection = textValue.selection
                                val lastAt = text.lastIndexOf('@', selection.start - 1)
                                if (lastAt != -1) {
                                    val mentionText = user.username ?: user.firstName
                                    val newText = text.replaceRange(lastAt + 1, selection.start, "$mentionText ")

                                    val annotatedBuilder = AnnotatedString.Builder()
                                    annotatedBuilder.append(newText)

                                    textValue.annotatedString.getStringAnnotations(0, text.length).forEach { annotation ->
                                        if (annotation.start < lastAt) {
                                            annotatedBuilder.addStringAnnotation(
                                                annotation.tag,
                                                annotation.item,
                                                annotation.start,
                                                annotation.end
                                            )
                                        } else if (annotation.start >= selection.start) {
                                            val offset = (mentionText.length + 1) - (selection.start - (lastAt + 1))
                                            annotatedBuilder.addStringAnnotation(
                                                annotation.tag,
                                                annotation.item,
                                                annotation.start + offset,
                                                annotation.end + offset
                                            )
                                        }
                                    }

                                    if (user.username == null) {
                                        annotatedBuilder.addStringAnnotation(
                                            MENTION_TAG,
                                            user.id.toString(),
                                            lastAt,
                                            lastAt + mentionText.length + 1
                                        )
                                    }

                                    textValue = TextFieldValue(
                                        annotatedString = annotatedBuilder.toAnnotatedString(),
                                        selection = TextRange(lastAt + mentionText.length + 2)
                                    )
                                }
                                actions.onMentionQueryChange(null)
                            },
                            videoPlayerPool = videoPlayerPool
                        )
                    }

                    AnimatedVisibility(
                        visible = filteredCommands.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        BotCommandSuggestions(
                            commands = filteredCommands,
                            onCommandClick = { command ->
                                actions.onSend("/$command", emptyList(), MessageSendOptions())
                                textValue = TextFieldValue("")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AnimatedVisibility(
                        visible = state.currentInlineBotUsername != null || state.isInlineBotLoading,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        InlineBotResults(
                            inlineBotResults = state.inlineBotResults,
                            isInlineMode = state.currentInlineBotUsername != null,
                            isLoading = state.isInlineBotLoading,
                            onResultClick = { resultId ->
                                actions.onSendInlineResult(resultId)
                                textValue = TextFieldValue("")
                            },
                            onSwitchPmClick = { text ->
                                state.currentInlineBotUsername?.let { username ->
                                    actions.onInlineSwitchPm(username, text)
                                }
                            },
                            onLoadMore = { offset ->
                                actions.onLoadMoreInlineResults(offset)
                            }
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
                                    editingMessage = state.editingMessage,
                                    pendingMediaPaths = state.pendingMediaPaths,
                                    canSendMedia = canSendMedia,
                                    onAttachClick = {
                                        showGallery = true
                                    }
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = if (voiceRecorder.isRecording) 0.dp else 4.dp)
                            ) {
                                AnimatedContent(
                                    targetState = voiceRecorder.isRecording,
                                    transitionSpec = {
                                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                                    },
                                    label = "InputContent"
                                ) { isRecording ->
                                    if (isRecording) {
                                        RecordingUI(
                                            voiceRecorderState = voiceRecorder,
                                            onStop = { voiceRecorder.stopRecording(cancel = false) },
                                            onCancel = { voiceRecorder.stopRecording(cancel = true) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        InputTextFieldContainer(
                                            textValue = textValue,
                                            onValueChange = { incoming ->
                                                textValue =
                                                    mergeInputTextValuePreservingAnnotations(textValue, incoming)
                                            },
                                            onRichTextValueChange = { incoming ->
                                                textValue = incoming
                                            },
                                            isBot = state.isBot,
                                            botMenuButton = state.botMenuButton,
                                            botCommands = state.botCommands,
                                            canSendStickers = canSendStickers,
                                            canWriteText = canWriteText,
                                            isStickerMenuVisible = isStickerMenuVisible,
                                            onStickerMenuToggle = {
                                                isStickerMenuVisible = !isStickerMenuVisible
                                                if (isStickerMenuVisible) focusManager.clearFocus()
                                            },
                                            onShowBotCommands = actions.onShowBotCommands,
                                            onOpenMiniApp = actions.onOpenMiniApp,
                                            knownCustomEmojis = knownCustomEmojis,
                                            emojiFontFamily = emojiFontFamily,
                                            focusRequester = focusRequester,
                                            pendingMediaPaths = state.pendingMediaPaths,
                                            onFocus = { isStickerMenuVisible = false },
                                            onOpenFullScreenEditor = { showFullScreenEditor = true },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            if (!voiceRecorder.isLocked) {
                                if (state.scheduledMessages.isNotEmpty()) {
                                    IconButton(onClick = {
                                        actions.onRefreshScheduledMessages()
                                        showScheduledMessagesSheet = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = stringResource(R.string.action_scheduled_messages),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                InputBarSendButton(
                                    textValue = textValue,
                                    editingMessage = state.editingMessage,
                                    pendingMediaPaths = state.pendingMediaPaths,
                                    isOverCharLimit = isOverMessageLimit,
                                    canWriteText = canWriteText,
                                    canSendVoice = canSendVoice,
                                    canSendMedia = canSendMedia,
                                    isVideoMessageMode = isVideoMessageMode,
                                    onSendWithOptions = sendWithOptions,
                                    onShowSendOptionsMenu = {
                                        showSendOptionsSheet = true
                                        actions.onRefreshScheduledMessages()
                                    },
                                    onCameraClick = actions.onCameraClick,
                                    onVideoModeToggle = { isVideoMessageMode = !isVideoMessageMode },
                                    onVoiceStart = { voiceRecorder.startRecording() },
                                    onVoiceStop = { cancel -> voiceRecorder.stopRecording(cancel) },
                                    onVoiceLock = { voiceRecorder.lockRecording() }
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = !voiceRecorder.isRecording &&
                                (textValue.text.isNotEmpty() || state.pendingMediaPaths.isNotEmpty()),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Text(
                            text = stringResource(
                                R.string.message_length_counter,
                                currentMessageLength,
                                maxMessageLength
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOverMessageLimit) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = state.replyMarkup is ReplyMarkupModel.ShowKeyboard && textValue.text.isEmpty() && !isStickerMenuVisible && !isKeyboardVisible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        KeyboardMarkupView(
                            markup = state.replyMarkup as ReplyMarkupModel.ShowKeyboard,
                            onButtonClick = actions.onReplyMarkupButtonClick,
                            onOpenMiniApp = actions.onOpenMiniApp
                        )
                    }

                    AnimatedVisibility(
                        visible = isStickerMenuVisible,
                        enter = expandVertically(
                            animationSpec = tween(200),
                            expandFrom = Alignment.Top
                        ) + fadeIn(),
                        exit = shrinkVertically(
                            animationSpec = tween(200),
                            shrinkTowards = Alignment.Top
                        ) + fadeOut()
                    ) {
                        StickerEmojiMenu(
                            onStickerSelected = { sticker ->
                                actions.onStickerClick(sticker)
                            },
                            onEmojiSelected = { emoji, sticker ->
                                textValue = insertEmojiAtSelection(
                                    value = textValue,
                                    emoji = emoji,
                                    sticker = sticker,
                                    knownCustomEmojis = knownCustomEmojis
                                )
                            },
                            onGifSelected = { gif ->
                                actions.onGifClick(gif)
                            },
                            onSearchFocused = { focused ->
                                isGifSearchFocused = focused
                            },
                            videoPlayerPool = videoPlayerPool,
                            stickerRepository = stickerRepository
                        )
                    }
                    Spacer(Modifier.navigationBarsPadding())
                }
            }

            if (showGallery) {
                GalleryScreen(
                    onMediaSelected = { uris ->
                        val localPaths = uris.mapNotNull { uri ->
                            context.copyUriToTempPath(uri)
                        }
                        if (localPaths.isNotEmpty()) {
                            actions.onMediaOrderChange((state.pendingMediaPaths + localPaths).distinct())
                        }
                        showGallery = false
                    },
                    onDismiss = { showGallery = false },
                    onCameraClick = {
                        showGallery = false
                        if (hasCameraPermission.value || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            showCamera = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    attachBots = state.attachBots,
                    hasMediaAccess = hasGalleryPermission.value || requestableGalleryPermissions.isEmpty() || context.hasAllPermissions(
                        requestableGalleryPermissions
                    ),
                    onPickFromOtherSources = {
                        showGallery = false
                        actions.onAttachClick()
                    },
                    onRequestMediaAccess = {
                        if (requestableGalleryPermissions.isNotEmpty()) {
                            galleryPermissionLauncher.launch(requestableGalleryPermissions.toTypedArray())
                        }
                    },
                    onAttachBotClick = { bot ->
                        showGallery = false
                        actions.onAttachBotClick(bot)
                    }
                )
            }

            if (showFullScreenEditor) {
                val editorEntities = remember(textValue.annotatedString, knownCustomEmojis.size) {
                    extractEntities(textValue.annotatedString, knownCustomEmojis)
                }
                val richEntityCount = remember(editorEntities) {
                    editorEntities.count { richEntityToAnnotation(it.type) != null }
                }
                val selectedTextLength = remember(textValue.selection, textValue.text.length) {
                    (textValue.selection.end - textValue.selection.start).coerceAtLeast(0)
                }
                val hasFormattableSelectionInEditor = hasFormattableSelection(textValue)

                ModalBottomSheet(
                    onDismissRequest = { showFullScreenEditor = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.action_open_fullscreen_editor),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(
                                        R.string.fullscreen_editor_meta,
                                        textValue.text.length,
                                        richEntityCount
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.message_length_counter,
                                        currentMessageLength,
                                        maxMessageLength
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOverMessageLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            AssistChip(
                                onClick = {
                                    sendWithOptions(MessageSendOptions())
                                    showFullScreenEditor = false
                                },
                                enabled = !isOverMessageLimit,
                                label = { Text(text = stringResource(R.string.action_send)) }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = stringResource(R.string.rich_text_tools_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (selectedTextLength > 0) {
                                        stringResource(R.string.rich_text_selection_meta, selectedTextLength)
                                    } else {
                                        stringResource(R.string.rich_text_selection_hint)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = false,
                                        onClick = { showFullScreenEmojiPicker = true },
                                        label = { Text(text = stringResource(R.string.rich_text_emoji)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = { textValue = toggleRichEntity(textValue, MessageEntityType.Bold) },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_bold)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = { textValue = toggleRichEntity(textValue, MessageEntityType.Italic) },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_italic)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            textValue = toggleRichEntity(textValue, MessageEntityType.Underline)
                                        },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_underline)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            textValue = toggleRichEntity(textValue, MessageEntityType.Strikethrough)
                                        },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_strikethrough)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            textValue = toggleRichEntity(textValue, MessageEntityType.Spoiler)
                                        },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_spoiler)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = { textValue = toggleRichEntity(textValue, MessageEntityType.Code) },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_code)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            val selection = textValue.selection
                                            if (selection.start != selection.end) {
                                                val normalized =
                                                    if (selection.start <= selection.end) selection else TextRange(
                                                        selection.end,
                                                        selection.start
                                                    )
                                                val current = textValue.annotatedString
                                                    .getStringAnnotations(
                                                        RICH_ENTITY_TAG,
                                                        normalized.start,
                                                        normalized.end
                                                    )
                                                    .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.Pre }
                                                fullScreenLanguageValue =
                                                    (current?.let { decodeRichEntity(it.item) } as? MessageEntityType.Pre)?.language.orEmpty()
                                                showFullScreenLanguageDialog = true
                                            }
                                        },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_pre)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            val selection = textValue.selection
                                            if (selection.start != selection.end) {
                                                val normalized =
                                                    if (selection.start <= selection.end) selection else TextRange(
                                                        selection.end,
                                                        selection.start
                                                    )
                                                val current = textValue.annotatedString
                                                    .getStringAnnotations(
                                                        RICH_ENTITY_TAG,
                                                        normalized.start,
                                                        normalized.end
                                                    )
                                                    .firstOrNull { decodeRichEntity(it.item) is MessageEntityType.TextUrl }
                                                fullScreenLinkValue =
                                                    (current?.let { decodeRichEntity(it.item) } as? MessageEntityType.TextUrl)?.url
                                                        ?: "https://"
                                                showFullScreenLinkDialog = true
                                            }
                                        },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_link)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            textValue = insertMentionAtSelection(textValue)
                                        },
                                        label = { Text(text = stringResource(R.string.rich_text_mention)) }
                                    )
                                    FilterChip(
                                        selected = false,
                                        onClick = { textValue = clearRichFormatting(textValue) },
                                        enabled = hasFormattableSelectionInEditor,
                                        label = { Text(text = stringResource(R.string.rich_text_clear)) }
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedButton(
                                    onClick = {
                                        textValue = textValue.copy(selection = TextRange(0, textValue.text.length))
                                    },
                                    enabled = textValue.text.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(text = stringResource(R.string.action_select_all))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            InputTextField(
                                textValue = textValue,
                                onValueChange = { incoming ->
                                    textValue = mergeInputTextValuePreservingAnnotations(textValue, incoming)
                                },
                                onRichTextValueChange = { incoming ->
                                    textValue = incoming
                                },
                                enableContextMenu = false,
                                enableRichContextActions = false,
                                canWriteText = canWriteText,
                                knownCustomEmojis = knownCustomEmojis,
                                emojiFontFamily = emojiFontFamily,
                                focusRequester = fullScreenFocusRequester,
                                pendingMediaPaths = state.pendingMediaPaths,
                                onFocus = { isStickerMenuVisible = false },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 260.dp, max = 520.dp)
                                    .padding(horizontal = 12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.fullscreen_editor_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )

                            OutlinedButton(
                                onClick = { showFullScreenEditor = false },
                                modifier = Modifier
                                    .widthIn(min = 116.dp)
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(text = stringResource(R.string.action_done))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (showFullScreenLinkDialog) {
                    AlertDialog(
                        onDismissRequest = { showFullScreenLinkDialog = false },
                        title = { Text(text = stringResource(R.string.rich_text_link_title)) },
                        text = {
                            OutlinedTextField(
                                value = fullScreenLinkValue,
                                onValueChange = { fullScreenLinkValue = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(text = stringResource(R.string.rich_text_link_hint)) }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val normalizedUrl = normalizeEditorUrl(fullScreenLinkValue)
                                if (normalizedUrl != null) {
                                    textValue = applyTextUrlEntity(textValue, normalizedUrl)
                                }
                                showFullScreenLinkDialog = false
                            }) {
                                Text(text = stringResource(R.string.action_apply))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showFullScreenLinkDialog = false }) {
                                Text(text = stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                if (showFullScreenLanguageDialog) {
                    AlertDialog(
                        onDismissRequest = { showFullScreenLanguageDialog = false },
                        title = { Text(text = stringResource(R.string.rich_text_code_language_title)) },
                        text = {
                            OutlinedTextField(
                                value = fullScreenLanguageValue,
                                onValueChange = { fullScreenLanguageValue = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(text = stringResource(R.string.rich_text_code_language_hint)) }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                textValue = applyPreEntity(textValue, fullScreenLanguageValue)
                                showFullScreenLanguageDialog = false
                            }) {
                                Text(text = stringResource(R.string.action_apply))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showFullScreenLanguageDialog = false }) {
                                Text(text = stringResource(R.string.action_cancel))
                            }
                        }
                    )
                }

                if (showFullScreenEmojiPicker) {
                    ModalBottomSheet(
                        onDismissRequest = { showFullScreenEmojiPicker = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ) {
                        StickerEmojiMenu(
                            onStickerSelected = {},
                            onEmojiSelected = { emoji, sticker ->
                                textValue = insertEmojiAtSelection(
                                    value = textValue,
                                    emoji = emoji,
                                    sticker = sticker,
                                    knownCustomEmojis = knownCustomEmojis
                                )
                            },
                            onGifSelected = {},
                            onSearchFocused = {},
                            videoPlayerPool = videoPlayerPool,
                            stickerRepository = stickerRepository
                        )
                    }
                }
            }

            if (showSendOptionsSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSendOptionsSheet = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Button(
                            onClick = {
                                showSendOptionsSheet = false
                                sendWithOptions(MessageSendOptions(silent = true))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.action_send_silent))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                showSendOptionsSheet = false
                                pickScheduleDateTime(context) { scheduleDate ->
                                    sendWithOptions(MessageSendOptions(scheduleDate = scheduleDate))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.action_schedule_message))
                        }

                        if (state.scheduledMessages.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    showSendOptionsSheet = false
                                    showScheduledMessagesSheet = true
                                    actions.onRefreshScheduledMessages()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.action_scheduled_messages_count,
                                        state.scheduledMessages.size
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            if (showScheduledMessagesSheet) {
                val scheduledMessagesSorted = remember(state.scheduledMessages) {
                    state.scheduledMessages.sortedBy { it.date }
                }
                val nextScheduled = scheduledMessagesSorted.firstOrNull()
                val editableScheduledCount = remember(scheduledMessagesSorted) {
                    scheduledMessagesSorted.count { canEditScheduledMessage(it) }
                }

                ModalBottomSheet(
                    onDismissRequest = { showScheduledMessagesSheet = false },
                    dragHandle = { BottomSheetDefaults.DragHandle() },
                    containerColor = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 28.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.action_scheduled_messages),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = scheduledMessagesSorted.size.toString(),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.scheduled_messages_summary_count,
                                        scheduledMessagesSorted.size
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (nextScheduled != null) {
                                        stringResource(
                                            R.string.scheduled_messages_summary_next,
                                            formatScheduledTimestamp(nextScheduled.date)
                                        )
                                    } else {
                                        stringResource(R.string.scheduled_messages_empty)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(
                                        R.string.scheduled_messages_summary_editable,
                                        editableScheduledCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (scheduledMessagesSorted.isEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.scheduled_messages_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                            ) {
                                LazyColumn(
                                    contentPadding = PaddingValues(vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    itemsIndexed(
                                        scheduledMessagesSorted,
                                        key = { _, message -> message.id }) { index, message ->
                                        ScheduledMessageRow(
                                            message = message,
                                            onSendNow = { actions.onSendScheduledNow(message) },
                                            onEdit = {
                                                actions.onEditScheduledMessage(message)
                                                showScheduledMessagesSheet = false
                                                showFullScreenEditor = true
                                            },
                                            onDelete = {
                                                actions.onDeleteScheduledMessage(message)
                                                actions.onRefreshScheduledMessages()
                                            }
                                        )

                                        if (index < scheduledMessagesSorted.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    actions.onRefreshScheduledMessages()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(text = stringResource(R.string.action_refresh))
                            }

                            Button(
                                onClick = { showScheduledMessagesSheet = false },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(text = stringResource(R.string.action_done))
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(showFullScreenEditor) {
        if (showFullScreenEditor) {
            fullScreenFocusRequester.requestFocus()
        }
    }
}

@Composable
private fun ClosedTopicBar() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.topic_closed_bar),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InputBarLeadingIcons(
    editingMessage: MessageModel?,
    pendingMediaPaths: List<String>,
    canSendMedia: Boolean,
    onAttachClick: () -> Unit
) {
    if (editingMessage == null && pendingMediaPaths.isEmpty() && canSendMedia) {
        IconButton(
            onClick = onAttachClick
        ) {
            Icon(
                imageVector = Icons.Outlined.AddCircleOutline,
                contentDescription = stringResource(R.string.cd_attach),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else if (!canSendMedia) {
        Spacer(modifier = Modifier.width(12.dp))
    }
}

@Composable
private fun ScheduledMessageRow(
    message: MessageModel,
    onSendNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .fillMaxSize()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = scheduledMessageTypeLabel(message).take(1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatScheduledTimestamp(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = messagePreviewText(message),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = stringResource(R.string.scheduled_message_id, message.id),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(6.dp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onSendNow,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(
                text = stringResource(R.string.action_send_now),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }

        OutlinedButton(
            onClick = onEdit,
            enabled = canEditScheduledMessage(message),
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(
                text = stringResource(R.string.action_edit),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }

        FilledTonalButton(
            onClick = onDelete,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(
                text = stringResource(R.string.action_delete_message),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false
            )
        }
    }
}

private fun messagePreviewText(message: MessageModel): String {
    return when (val content = message.content) {
        is MessageContent.Text -> content.text
        is MessageContent.Photo -> if (content.caption.isNotBlank()) content.caption else "Photo"
        is MessageContent.Video -> if (content.caption.isNotBlank()) content.caption else "Video"
        is MessageContent.Document -> if (content.caption.isNotBlank()) content.caption else "Document"
        is MessageContent.Gif -> if (content.caption.isNotBlank()) content.caption else "GIF"
        is MessageContent.Sticker -> "Sticker"
        is MessageContent.Voice -> "Voice message"
        is MessageContent.VideoNote -> "Video message"
        is MessageContent.Audio -> "Audio"
        is MessageContent.Location -> "Location"
        is MessageContent.Venue -> content.title
        is MessageContent.Contact -> listOf(content.firstName, content.lastName).filter { it.isNotBlank() }
            .joinToString(" ")

        is MessageContent.Service -> content.text
        is MessageContent.Poll -> content.question
        is MessageContent.Unsupported -> "Unsupported message"
        else -> "Message"
    }
}

private fun scheduledMessageTypeLabel(message: MessageModel): String {
    return when (message.content) {
        is MessageContent.Text -> "Text"
        is MessageContent.Photo -> "Photo"
        is MessageContent.Video -> "Video"
        is MessageContent.Document -> "Document"
        is MessageContent.Gif -> "GIF"
        is MessageContent.Sticker -> "Sticker"
        is MessageContent.Voice -> "Voice"
        is MessageContent.VideoNote -> "Video message"
        else -> "Message"
    }
}

private fun canEditScheduledMessage(message: MessageModel): Boolean {
    return when (message.content) {
        is MessageContent.Text -> true

        else -> false
    }
}

private fun formatScheduledTimestamp(epochSeconds: Int): String {
    return try {
        val formatter = java.text.SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        formatter.format(Date(epochSeconds * 1000L))
    } catch (_: Exception) {
        ""
    }
}

private fun pickScheduleDateTime(context: Context, onSelected: (Int) -> Unit) {
    val calendar = Calendar.getInstance()
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(Calendar.HOUR_OF_DAY, hourOfDay)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val now = Calendar.getInstance()
                    if (selected.before(now)) {
                        selected.timeInMillis = now.timeInMillis + 60_000L
                    }
                    onSelected((selected.timeInMillis / 1000L).toInt())
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun normalizeEditorUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

private fun insertMentionAtSelection(value: TextFieldValue): TextFieldValue {
    val selection = if (value.selection.start <= value.selection.end) value.selection else TextRange(
        value.selection.end,
        value.selection.start
    )
    val base = value.annotatedString
    val insertion =
        if (selection.start == selection.end) "@" else "@${value.text.substring(selection.start, selection.end)}"

    val newAnnotated = buildAnnotatedString {
        append(base.subSequence(0, selection.start))
        append(insertion)
        append(base.subSequence(selection.end, base.length))
    }

    val newCursor = selection.start + insertion.length
    return value.copy(annotatedString = newAnnotated, selection = TextRange(newCursor, newCursor))
}

private fun insertEmojiAtSelection(
    value: TextFieldValue,
    emoji: String,
    sticker: StickerModel?,
    knownCustomEmojis: MutableMap<Long, StickerModel>
): TextFieldValue {
    val currentText = value.annotatedString
    val selection = value.selection

    val emojiAnnotated = if (sticker != null) {
        val customEmojiEntityId = sticker.customEmojiId ?: sticker.id
        knownCustomEmojis[customEmojiEntityId] = sticker
        val symbol = emoji.ifBlank { sticker.emoji.ifBlank { "\uD83D\uDE42" } }
        buildAnnotatedString {
            append(symbol)
            addStringAnnotation(CUSTOM_EMOJI_TAG, customEmojiEntityId.toString(), 0, symbol.length)
        }
    } else {
        AnnotatedString(emoji.ifBlank { "\uD83D\uDE42" })
    }

    val newText = buildAnnotatedString {
        append(currentText.subSequence(0, selection.start))
        append(emojiAnnotated)
        append(currentText.subSequence(selection.end, currentText.length))
    }

    return value.copy(
        annotatedString = newText,
        selection = TextRange(selection.start + emojiAnnotated.length)
    )
}

private fun Context.hasAllPermissions(permissions: List<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private data class InlineQueryInput(
    val botUsername: String,
    val query: String
)

private fun parseInlineQueryInput(text: String, selection: TextRange): InlineQueryInput? {
    if (!selection.collapsed) return null
    val cursor = selection.start
    if (!text.startsWith('@') || cursor !in 0..text.length) return null

    val firstSpaceIndex = text.indexOf(' ')
    if (firstSpaceIndex <= 1) return null
    if (cursor <= firstSpaceIndex) return null

    val botUsername = text.substring(1, firstSpaceIndex)
    if (botUsername.any { !it.isLetterOrDigit() && it != '_' }) return null

    val query = text.substring(firstSpaceIndex + 1)
    if (query.isBlank()) return null
    if (query.contains('\n')) return null

    return InlineQueryInput(
        botUsername = botUsername,
        query = query
    )
}

private fun String.isInlineBotPrefillText(): Boolean {
    if (!startsWith("@") || !endsWith(" ")) return false
    val username = drop(1).dropLast(1)
    return username.isNotEmpty() && username.all { it.isLetterOrDigit() || it == '_' }
}

private fun Context.declaredPermissions(): Set<String> {
    val info = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
    return info.requestedPermissions?.toSet().orEmpty()
}

private fun Context.copyUriToTempPath(uri: android.net.Uri): String? {
    return try {
        if (uri.scheme == "file") {
            return uri.path
        }
        val mime = contentResolver.getType(uri).orEmpty()
        val ext = when {
            mime.contains("video") -> "mp4"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }
        val file = File(cacheDir, "attach_${System.nanoTime()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}
