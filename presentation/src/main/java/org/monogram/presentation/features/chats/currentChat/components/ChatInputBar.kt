package org.monogram.presentation.features.chats.currentChat.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
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
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.chats.currentChat.components.inputbar.*
import org.monogram.presentation.features.gallery.GalleryScreen
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
    val isSecretChat: Boolean = false,
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
    var closeStickerMenuWithoutSlide by remember { mutableStateOf(false) }
    var openStickerMenuAfterKeyboardClosed by remember { mutableStateOf(false) }
    var openKeyboardAfterStickerMenuClosed by remember { mutableStateOf(false) }
    var isVideoMessageMode by remember { mutableStateOf(false) }
    var isGifSearchFocused by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showFullScreenEditor by remember { mutableStateOf(false) }
    var showSendOptionsSheet by remember { mutableStateOf(false) }
    var showScheduleDatePicker by remember { mutableStateOf(false) }
    var showScheduleTimePicker by remember { mutableStateOf(false) }
    var pendingScheduleDateMillis by remember { mutableStateOf<Long?>(null) }
    var showScheduledMessagesSheet by remember { mutableStateOf(false) }

    val knownCustomEmojis = remember { mutableStateMapOf<Long, StickerModel>() }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    fun hideKeyboardAndClearFocus(force: Boolean = true) {
        keyboardController?.hide()
        focusManager.clearFocus(force = force)
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    var lastImeHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(imeBottomPx) {
        if (imeBottomPx > 0) {
            lastImeHeightPx = imeBottomPx
        }
    }
    val stickerMenuHeight = with(density) {
        val imeHeightDp = maxOf(imeBottomPx, lastImeHeightPx).toDp()
        val fallbackHeightDp = maxOf(configuration.screenHeightDp.dp * 0.42f, 320.dp)
        maxOf(imeHeightDp, fallbackHeightDp)
    }
    val transitionHoldBottomInset = with(density) {
        if (!isKeyboardVisible && !isStickerMenuVisible && (openStickerMenuAfterKeyboardClosed || openKeyboardAfterStickerMenuClosed)) {
            lastImeHeightPx.toDp()
        } else {
            0.dp
        }
    }

    LaunchedEffect(isKeyboardVisible, openStickerMenuAfterKeyboardClosed) {
        if (!isKeyboardVisible && openStickerMenuAfterKeyboardClosed) {
            openStickerMenuAfterKeyboardClosed = false
            closeStickerMenuWithoutSlide = false
            isStickerMenuVisible = true
        }
    }

    LaunchedEffect(isKeyboardVisible, openKeyboardAfterStickerMenuClosed) {
        if (isKeyboardVisible && openKeyboardAfterStickerMenuClosed) {
            openKeyboardAfterStickerMenuClosed = false
        }
    }

    LaunchedEffect(showGallery) {
        if (showGallery) {
            openStickerMenuAfterKeyboardClosed = false
            openKeyboardAfterStickerMenuClosed = false
            closeStickerMenuWithoutSlide = false
            isStickerMenuVisible = false
            hideKeyboardAndClearFocus()
        }
    }

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
                buildEditingMessageTextValue(editingMessage, knownCustomEmojis)?.let { newValue ->
                    textValue = newValue
                    focusRequester.requestFocus()
                }
                lastEditingMessageId = editingMessage.id
            }
        } else if (lastEditingMessageId != null) {
            textValue = TextFieldValue(state.draftText, TextRange(state.draftText.length))
            lastEditingMessageId = null
            knownCustomEmojis.clear()
        }
    }

    BackHandler(enabled = isStickerMenuVisible || openStickerMenuAfterKeyboardClosed || openKeyboardAfterStickerMenuClosed || state.pendingMediaPaths.isNotEmpty() || showGallery || showCamera || showFullScreenEditor || showSendOptionsSheet || showScheduledMessagesSheet || showScheduleDatePicker || showScheduleTimePicker) {
        if (isGifSearchFocused) {
            focusManager.clearFocus()
        } else if (openStickerMenuAfterKeyboardClosed) {
            openStickerMenuAfterKeyboardClosed = false
        } else if (openKeyboardAfterStickerMenuClosed) {
            openKeyboardAfterStickerMenuClosed = false
        } else if (isStickerMenuVisible) {
            closeStickerMenuWithoutSlide = false
            isStickerMenuVisible = false
        } else if (showScheduleTimePicker) {
            showScheduleTimePicker = false
            pendingScheduleDateMillis = null
        } else if (showScheduleDatePicker) {
            showScheduleDatePicker = false
            pendingScheduleDateMillis = null
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
    val galleryPermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )

        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val fullGalleryPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val requestableGalleryPermissions = remember(context, galleryPermissions) {
        val declared = context.declaredPermissions()
        galleryPermissions.filter { it in declared }
    }
    val requestableFullGalleryPermissions = remember(context, fullGalleryPermissions) {
        val declared = context.declaredPermissions()
        fullGalleryPermissions.filter { it in declared }
    }

    fun hasPartialGalleryPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasFullGalleryPermission(): Boolean {
        return requestableFullGalleryPermissions.isEmpty() || context.hasAllPermissions(
            requestableFullGalleryPermissions
        )
    }

    val hasGalleryPermission = remember(context, requestableFullGalleryPermissions) {
        mutableStateOf(
            hasFullGalleryPermission() || hasPartialGalleryPermission()
        )
    }
    val isPartialGalleryAccess = hasPartialGalleryPermission() && !hasFullGalleryPermission()
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val hasAccess = hasFullGalleryPermission() || hasPartialGalleryPermission()
        hasGalleryPermission.value = hasAccess
        if (hasAccess || requestableGalleryPermissions.isEmpty()) showGallery = true
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
            ChatInputBarComposerSection(
                editingMessage = state.editingMessage,
                replyMessage = state.replyMessage,
                pendingMediaPaths = state.pendingMediaPaths,
                mentionSuggestions = state.mentionSuggestions,
                filteredCommands = filteredCommands,
                currentInlineBotUsername = state.currentInlineBotUsername,
                isInlineBotLoading = state.isInlineBotLoading,
                inlineBotResults = state.inlineBotResults,
                isBot = state.isBot,
                botMenuButton = state.botMenuButton,
                botCommands = state.botCommands,
                scheduledMessagesCount = state.scheduledMessages.size,
                textValue = textValue,
                onTextValueChange = { textValue = it },
                knownCustomEmojis = knownCustomEmojis,
                emojiFontFamily = emojiFontFamily,
                focusRequester = focusRequester,
                canWriteText = canWriteText,
                canSendMedia = canSendMedia,
                canSendStickers = canSendStickers,
                canSendVoice = canSendVoice,
                isStickerMenuVisible = isStickerMenuVisible,
                closeStickerMenuWithoutSlide = closeStickerMenuWithoutSlide,
                isKeyboardVisible = isKeyboardVisible,
                transitionHoldBottomInset = transitionHoldBottomInset,
                stickerMenuHeight = stickerMenuHeight,
                voiceRecorder = voiceRecorder,
                isGifSearchFocused = isGifSearchFocused,
                showFullScreenEditor = showFullScreenEditor,
                currentMessageLength = currentMessageLength,
                maxMessageLength = maxMessageLength,
                isOverMessageLimit = isOverMessageLimit,
                isVideoMessageMode = isVideoMessageMode,
                replyMarkup = state.replyMarkup,
                showSendOptionsSheet = showSendOptionsSheet,
                stickerRepository = stickerRepository,
                onCancelEdit = actions.onCancelEdit,
                onCancelReply = actions.onCancelReply,
                onCancelMedia = actions.onCancelMedia,
                onMediaOrderChange = actions.onMediaOrderChange,
                onMediaClick = actions.onMediaClick,
                onMentionClick = { user ->
                    textValue = applyMentionSuggestion(textValue, user)
                },
                onMentionQueryClear = { actions.onMentionQueryChange(null) },
                onInlineResultClick = { resultId ->
                    actions.onSendInlineResult(resultId)
                    textValue = TextFieldValue("")
                },
                onInlineSwitchPmClick = { text ->
                    state.currentInlineBotUsername?.let { username ->
                        actions.onInlineSwitchPm(username, text)
                    }
                },
                onLoadMoreInlineResults = actions.onLoadMoreInlineResults,
                onCommandClick = { command ->
                    actions.onSend("/$command", emptyList(), MessageSendOptions())
                    textValue = TextFieldValue("")
                },
                onAttachClick = {
                    openStickerMenuAfterKeyboardClosed = false
                    openKeyboardAfterStickerMenuClosed = false
                    closeStickerMenuWithoutSlide = false
                    isStickerMenuVisible = false
                    hideKeyboardAndClearFocus()
                    showGallery = true
                },
                onStickerMenuToggle = {
                    if (isStickerMenuVisible) {
                        openStickerMenuAfterKeyboardClosed = false
                        openKeyboardAfterStickerMenuClosed = true
                        closeStickerMenuWithoutSlide = true
                        isStickerMenuVisible = false
                        focusRequester.requestFocus()
                    } else {
                        openKeyboardAfterStickerMenuClosed = false
                        closeStickerMenuWithoutSlide = false
                        if (isKeyboardVisible) {
                            openStickerMenuAfterKeyboardClosed = true
                            hideKeyboardAndClearFocus()
                        } else {
                            openStickerMenuAfterKeyboardClosed = false
                            isStickerMenuVisible = true
                            focusManager.clearFocus()
                        }
                    }
                },
                onShowBotCommands = {
                    openStickerMenuAfterKeyboardClosed = false
                    openKeyboardAfterStickerMenuClosed = false
                    closeStickerMenuWithoutSlide = false
                    isStickerMenuVisible = false
                    hideKeyboardAndClearFocus()
                    actions.onShowBotCommands()
                },
                onOpenMiniApp = actions.onOpenMiniApp,
                onInputFocus = {
                    openStickerMenuAfterKeyboardClosed = false
                    openKeyboardAfterStickerMenuClosed = false
                    if (isStickerMenuVisible) {
                        closeStickerMenuWithoutSlide = true
                    }
                    isStickerMenuVisible = false
                },
                onOpenFullScreenEditor = { showFullScreenEditor = true },
                onOpenScheduledMessages = {
                    actions.onRefreshScheduledMessages()
                    showScheduledMessagesSheet = true
                },
                onSendWithOptions = sendWithOptions,
                onShowSendOptionsMenu = {
                    openStickerMenuAfterKeyboardClosed = false
                    openKeyboardAfterStickerMenuClosed = false
                    closeStickerMenuWithoutSlide = false
                    isStickerMenuVisible = false
                    hideKeyboardAndClearFocus()
                    showSendOptionsSheet = true
                    actions.onRefreshScheduledMessages()
                },
                onCameraClick = {
                    hideKeyboardAndClearFocus()
                    actions.onCameraClick()
                },
                onVideoModeToggle = { isVideoMessageMode = !isVideoMessageMode },
                onVoiceStart = {
                    hideKeyboardAndClearFocus()
                    voiceRecorder.startRecording()
                },
                onVoiceStop = { cancel -> voiceRecorder.stopRecording(cancel) },
                onVoiceLock = { voiceRecorder.lockRecording() },
                onSendSilent = {
                    showSendOptionsSheet = false
                    sendWithOptions(MessageSendOptions(silent = true))
                },
                onScheduleMessage = {
                    showSendOptionsSheet = false
                    pendingScheduleDateMillis = null
                    showScheduleDatePicker = true
                },
                onOpenScheduledMessagesFromPopup = {
                    showSendOptionsSheet = false
                    showScheduledMessagesSheet = true
                    actions.onRefreshScheduledMessages()
                },
                onDismissSendOptions = { showSendOptionsSheet = false },
                onStickerClick = actions.onStickerClick,
                onGifClick = actions.onGifClick,
                onGifSearchFocusedChange = { isGifSearchFocused = it },
                onReplyMarkupButtonClick = actions.onReplyMarkupButtonClick
            )

            FullScreenEditorSheet(
                visible = showFullScreenEditor,
                textValue = textValue,
                onTextValueChange = { textValue = it },
                canWriteText = canWriteText,
                pendingMediaPaths = state.pendingMediaPaths,
                knownCustomEmojis = knownCustomEmojis,
                emojiFontFamily = emojiFontFamily,
                isKeyboardVisible = isKeyboardVisible,
                isOverMessageLimit = isOverMessageLimit,
                currentMessageLength = currentMessageLength,
                maxMessageLength = maxMessageLength,
                stickerRepository = stickerRepository,
                isPremiumUser = state.isPremiumUser,
                isSecretChat = state.isSecretChat,
                onDismiss = { showFullScreenEditor = false },
                onSend = {
                    sendWithOptions(MessageSendOptions())
                    showFullScreenEditor = false
                },
                onEditorFocus = { isStickerMenuVisible = false },
                onDraftAutosave = { text ->
                    if (state.editingMessage == null) {
                        actions.onDraftChange(text)
                    }
                }
            )

            if (showScheduleDatePicker) {
                ScheduleDatePickerDialog(
                    onDismiss = {
                        showScheduleDatePicker = false
                        pendingScheduleDateMillis = null
                    },
                    onDateSelected = { selectedDateMillis ->
                        pendingScheduleDateMillis = selectedDateMillis
                        showScheduleDatePicker = false
                        showScheduleTimePicker = true
                    }
                )
            }

            if (showScheduleTimePicker) {
                val defaultTime = remember {
                    Calendar.getInstance().let { now -> now.get(Calendar.HOUR_OF_DAY) to now.get(Calendar.MINUTE) }
                }

                ScheduleTimePickerDialog(
                    initialHour = defaultTime.first,
                    initialMinute = defaultTime.second,
                    onDismiss = {
                        showScheduleTimePicker = false
                        pendingScheduleDateMillis = null
                    },
                    onConfirm = { hour, minute ->
                        val selectedDateMillis = pendingScheduleDateMillis
                        pendingScheduleDateMillis = null
                        showScheduleTimePicker = false
                        if (selectedDateMillis != null) {
                            val scheduleDate = buildScheduledDateEpochSeconds(selectedDateMillis, hour, minute)
                            sendWithOptions(MessageSendOptions(scheduleDate = scheduleDate))
                        }
                    }
                )
            }

            ScheduledMessagesSheet(
                visible = showScheduledMessagesSheet,
                scheduledMessages = state.scheduledMessages,
                onDismiss = { showScheduledMessagesSheet = false },
                onRefresh = actions.onRefreshScheduledMessages,
                onEdit = { message ->
                    actions.onEditScheduledMessage(message)
                    showScheduledMessagesSheet = false
                    showFullScreenEditor = true
                },
                onDelete = { message ->
                    actions.onDeleteScheduledMessage(message)
                    actions.onRefreshScheduledMessages()
                },
                onSendNow = { message ->
                    actions.onSendScheduledNow(message)
                }
            )
        }
    }

    if (showGallery && !showCamera) {
        ModalBottomSheet(
            onDismissRequest = { showGallery = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background
        ) {
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
                    if (hasCameraPermission.value || ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        showCamera = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                attachBots = state.attachBots,
                hasMediaAccess = hasGalleryPermission.value || hasFullGalleryPermission() || hasPartialGalleryPermission(),
                isPartialAccess = isPartialGalleryAccess,
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
                },
                modifier = Modifier.fillMaxHeight()
            )
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
