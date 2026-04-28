package org.monogram.presentation.features.chats.conversation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.StickerModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.camera.CameraScreen
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarActions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarCapabilities
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarComposerSection
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ChatInputBarState
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ClosedTopicBar
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ComposerAttachmentState
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ComposerBotState
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ComposerRowState
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ComposerSuggestionState
import org.monogram.presentation.features.chats.conversation.ui.inputbar.FullScreenEditorSheet
import org.monogram.presentation.features.chats.conversation.ui.inputbar.InputBarMode
import org.monogram.presentation.features.chats.conversation.ui.inputbar.RestrictedInputBar
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ScheduleDatePickerDialog
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ScheduleTimePickerDialog
import org.monogram.presentation.features.chats.conversation.ui.inputbar.ScheduledMessagesSheet
import org.monogram.presentation.features.chats.conversation.ui.inputbar.SlowModeInputBar
import org.monogram.presentation.features.chats.conversation.ui.inputbar.applyMentionSuggestion
import org.monogram.presentation.features.chats.conversation.ui.inputbar.buildEditingMessageTextValue
import org.monogram.presentation.features.chats.conversation.ui.inputbar.buildScheduledDateEpochSeconds
import org.monogram.presentation.features.chats.conversation.ui.inputbar.copyUriToTempDocumentPath
import org.monogram.presentation.features.chats.conversation.ui.inputbar.copyUriToTempPath
import org.monogram.presentation.features.chats.conversation.ui.inputbar.declaredPermissions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.extractEntities
import org.monogram.presentation.features.chats.conversation.ui.inputbar.hasAllPermissions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.isInlineBotPrefillText
import org.monogram.presentation.features.chats.conversation.ui.inputbar.parseInlineQueryInput
import org.monogram.presentation.features.chats.conversation.ui.inputbar.rememberVoiceRecorder
import org.monogram.presentation.features.chats.conversation.ui.message.getEmojiFontFamily
import org.monogram.presentation.features.gallery.GalleryScreen
import org.monogram.presentation.features.gallery.components.PollComposerSheet
import java.util.Calendar
import kotlin.math.ceil

private enum class AttachmentPickerMode {
    Default,
    MediaOnly
}

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

    val canWriteText by remember(state.isAdmin, state.permissions.canSendBasicMessages) {
        derivedStateOf { state.isAdmin || state.permissions.canSendBasicMessages }
    }
    val canSendPhotos by remember(state.isAdmin, state.permissions.canSendPhotos) {
        derivedStateOf { state.isAdmin || state.permissions.canSendPhotos }
    }
    val canSendVideos by remember(state.isAdmin, state.permissions.canSendVideos) {
        derivedStateOf { state.isAdmin || state.permissions.canSendVideos }
    }
    val canSendDocuments by remember(state.isAdmin, state.permissions.canSendDocuments) {
        derivedStateOf { state.isAdmin || state.permissions.canSendDocuments }
    }
    val canSendAudios by remember(state.isAdmin, state.permissions.canSendAudios) {
        derivedStateOf { state.isAdmin || state.permissions.canSendAudios }
    }
    val canUseMediaPicker by remember(canSendPhotos, canSendVideos) {
        derivedStateOf { canSendPhotos || canSendVideos }
    }
    val canUseDocumentPicker by remember(canSendDocuments, canSendAudios) {
        derivedStateOf { canSendDocuments || canSendAudios }
    }
    val canSendPolls by remember(state.isAdmin, state.permissions.canSendPolls) {
        derivedStateOf { state.isAdmin || state.permissions.canSendPolls }
    }
    val canOpenAttachSheet by remember(
        canUseMediaPicker,
        canUseDocumentPicker,
        canSendPolls,
        state.attachBots
    ) {
        derivedStateOf { canUseMediaPicker || canUseDocumentPicker || canSendPolls || state.attachBots.isNotEmpty() }
    }
    val canSendStickers by remember(state.isAdmin, state.permissions.canSendOtherMessages) {
        derivedStateOf { state.isAdmin || state.permissions.canSendOtherMessages }
    }
    val canSendVoice by remember(state.isAdmin, state.permissions.canSendVoiceNotes) {
        derivedStateOf { state.isAdmin || state.permissions.canSendVoiceNotes }
    }
    val canSendVideoNotes by remember(state.isAdmin, state.permissions.canSendVideoNotes) {
        derivedStateOf { state.isAdmin || state.permissions.canSendVideoNotes }
    }
    val canSendAnything by remember(
        canWriteText,
        canOpenAttachSheet,
        canSendStickers,
        canSendVoice,
        canSendVideoNotes,
        canSendPolls
    ) {
        derivedStateOf {
            canWriteText || canOpenAttachSheet || canSendStickers || canSendVoice || canSendVideoNotes || canSendPolls
        }
    }
    val capabilities = remember(
        canWriteText,
        canSendPhotos,
        canSendVideos,
        canSendDocuments,
        canSendAudios,
        canOpenAttachSheet,
        canSendStickers,
        canSendVoice,
        canSendVideoNotes,
        canSendAnything
    ) {
        ChatInputBarCapabilities(
            canWriteText = canWriteText,
            canSendPhotos = canSendPhotos,
            canSendVideos = canSendVideos,
            canSendDocuments = canSendDocuments,
            canSendAudios = canSendAudios,
            canOpenAttachSheet = canOpenAttachSheet,
            canSendStickers = canSendStickers,
            canSendVoice = canSendVoice,
            canSendVideoNotes = canSendVideoNotes,
            canSendAnything = canSendAnything
        )
    }

    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }

    var textValue by rememberSaveable(state.editingMessage?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draftText))
    }
    var isStickerMenuVisible by rememberSaveable { mutableStateOf(false) }
    var closeStickerMenuWithoutSlide by remember { mutableStateOf(false) }
    var openStickerMenuAfterKeyboardClosed by remember { mutableStateOf(false) }
    var openKeyboardAfterStickerMenuClosed by remember { mutableStateOf(false) }
    var isVideoMessageMode by rememberSaveable { mutableStateOf(false) }
    var isGifSearchFocused by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showPollComposer by rememberSaveable { mutableStateOf(false) }
    var showFullScreenEditor by rememberSaveable { mutableStateOf(false) }
    var showSendOptionsSheet by rememberSaveable { mutableStateOf(false) }
    var attachmentPickerMode by remember { mutableStateOf(AttachmentPickerMode.Default) }
    var showScheduleDatePicker by rememberSaveable { mutableStateOf(false) }
    var showScheduleTimePicker by rememberSaveable { mutableStateOf(false) }
    var pendingScheduleDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showScheduledMessagesSheet by rememberSaveable { mutableStateOf(false) }

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
    val navigationBottomInsetPx = WindowInsets.navigationBars.getBottom(density)
    val bottomInset = with(density) { maxOf(imeBottomPx, navigationBottomInsetPx).toDp() }

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

    LaunchedEffect(showPollComposer) {
        if (showPollComposer) {
            openStickerMenuAfterKeyboardClosed = false
            openKeyboardAfterStickerMenuClosed = false
            closeStickerMenuWithoutSlide = false
            isStickerMenuVisible = false
            hideKeyboardAndClearFocus()
        }
    }

    LaunchedEffect(canSendStickers) {
        if (!canSendStickers && isStickerMenuVisible) {
            isStickerMenuVisible = false
        }
    }

    LaunchedEffect(canSendVideoNotes, canSendVoice) {
        if (!canSendVideoNotes && isVideoMessageMode) {
            isVideoMessageMode = false
        }
        if (!canSendVoice && canSendVideoNotes) {
            isVideoMessageMode = true
        }
    }

    var lastEditingMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastReplyMessageId by rememberSaveable { mutableStateOf<Long?>(null) }

    var slowModeRemainingSeconds by remember {
        mutableIntStateOf(0)
    }
    LaunchedEffect(state.slowModeDelay, state.slowModeDelayExpiresIn, state.isAdmin) {
        slowModeRemainingSeconds = if (!state.isAdmin && state.slowModeDelay > 0) {
            ceil(state.slowModeDelayExpiresIn).toInt().coerceAtLeast(0)
        } else {
            0
        }
    }
    LaunchedEffect(slowModeRemainingSeconds, state.slowModeDelay, state.isAdmin) {
        if (!state.isAdmin && state.slowModeDelay > 0 && slowModeRemainingSeconds > 0) {
            delay(1000)
            slowModeRemainingSeconds = (slowModeRemainingSeconds - 1).coerceAtLeast(0)
        }
    }
    val isSlowModeActive by remember(state.isAdmin, state.slowModeDelay, slowModeRemainingSeconds) {
        derivedStateOf { !state.isAdmin && state.slowModeDelay > 0 && slowModeRemainingSeconds > 0 }
    }

    fun activateSlowModeCooldown() {
        if (!state.isAdmin && state.slowModeDelay > 0) {
            slowModeRemainingSeconds = state.slowModeDelay
        }
    }

    val voiceRecorder = rememberVoiceRecorder(
        onRecordingFinished = { path, duration, waveform ->
            if (!canSendVoice || isSlowModeActive) return@rememberVoiceRecorder
            actions.onSendVoice(path, duration, waveform)
            activateSlowModeCooldown()
        },
        onPermissionDenied = {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    )
    val maxMessageLength by remember(
        state.pendingMediaPaths,
        state.pendingDocumentPaths,
        state.isPremiumUser
    ) {
        derivedStateOf {
            if ((state.pendingMediaPaths.isNotEmpty() || state.pendingDocumentPaths.isNotEmpty()) && !state.isPremiumUser) 1024
            else 4096
        }
    }
    val currentMessageLength by remember(textValue.text) {
        derivedStateOf { textValue.text.length }
    }
    val isOverMessageLimit by remember(currentMessageLength, maxMessageLength) {
        derivedStateOf { currentMessageLength > maxMessageLength }
    }
    val canSendPendingAttachments by remember(
        state.pendingMediaPaths,
        state.pendingDocumentPaths,
        canUseMediaPicker,
        canUseDocumentPicker
    ) {
        derivedStateOf {
            (state.pendingMediaPaths.isNotEmpty() && canUseMediaPicker) ||
                    (state.pendingDocumentPaths.isNotEmpty() && canUseDocumentPicker)
        }
    }

    val sendWithOptions: (MessageSendOptions) -> Unit = sendWithOptions@{
        if (isOverMessageLimit) return@sendWithOptions
        val isTextEmpty = textValue.text.isBlank()
        val captionEntities = extractEntities(textValue.annotatedString, knownCustomEmojis)
        val isScheduling = it.scheduleDate != null
        var sentInstantMessage = false

        val canSendNow = when {
            state.pendingMediaPaths.isNotEmpty() && canUseMediaPicker -> true
            state.pendingDocumentPaths.isNotEmpty() && canUseDocumentPicker -> true
            state.editingMessage != null -> false
            canWriteText && !isTextEmpty -> true
            else -> false
        }

        if (isSlowModeActive && canSendNow && !isScheduling) {
            return@sendWithOptions
        }

        if (state.pendingMediaPaths.isNotEmpty() && canUseMediaPicker) {
            actions.onSendMedia(state.pendingMediaPaths, textValue.text, captionEntities, it)
            textValue = TextFieldValue("")
            knownCustomEmojis.clear()
            sentInstantMessage = !isScheduling
        } else if (state.pendingDocumentPaths.isNotEmpty() && canUseDocumentPicker) {
            actions.onSendDocuments(state.pendingDocumentPaths, textValue.text, captionEntities, it)
            textValue = TextFieldValue("")
            knownCustomEmojis.clear()
            sentInstantMessage = !isScheduling
        } else if (state.editingMessage != null && canWriteText) {
            if (!isTextEmpty) {
                actions.onSaveEdit(textValue.text, captionEntities)
            }
        } else if (canWriteText && !isTextEmpty) {
            actions.onSend(textValue.text, captionEntities, it)
            textValue = TextFieldValue("")
            knownCustomEmojis.clear()
            sentInstantMessage = !isScheduling
        }

        if (sentInstantMessage) {
            activateSlowModeCooldown()
        }

        if (it.scheduleDate != null) {
            actions.onRefreshScheduledMessages()
        }
    }

    val filteredCommands by remember(textValue.text, state.botCommands) {
        derivedStateOf {
            if (textValue.text.startsWith("/")) {
                val query = textValue.text.substring(1).lowercase()
                state.botCommands.filter { it.command.lowercase().startsWith(query) }
            } else {
                emptyList()
            }
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
    LaunchedEffect(textValue.text, textValue.selection, canSendStickers) {
        if (!canSendStickers) {
            currentOnInlineQueryChange("", "")
            return@LaunchedEffect
        }

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

    LaunchedEffect(state.replyMessage) {
        val replyMessage = state.replyMessage
        if (replyMessage != null) {
            if (replyMessage.id != lastReplyMessageId && state.editingMessage == null) {
                openStickerMenuAfterKeyboardClosed = false
                openKeyboardAfterStickerMenuClosed = false
                if (isStickerMenuVisible) {
                    closeStickerMenuWithoutSlide = true
                    isStickerMenuVisible = false
                }
                focusRequester.requestFocus()
                keyboardController?.show()
                lastReplyMessageId = replyMessage.id
            }
        } else if (lastReplyMessageId != null) {
            lastReplyMessageId = null
        }
    }

    BackHandler(enabled = isStickerMenuVisible || openStickerMenuAfterKeyboardClosed || openKeyboardAfterStickerMenuClosed || state.pendingMediaPaths.isNotEmpty() || state.pendingDocumentPaths.isNotEmpty() || showGallery || showCamera || showPollComposer || showFullScreenEditor || showSendOptionsSheet || showScheduledMessagesSheet || showScheduleDatePicker || showScheduleTimePicker) {
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
        } else if (state.pendingDocumentPaths.isNotEmpty()) {
            actions.onDocumentOrderChange(emptyList())
        } else if (showGallery) {
            showGallery = false
        } else if (showPollComposer) {
            showPollComposer = false
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
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val documentsPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val localPaths = uris.mapNotNull { uri -> context.copyUriToTempDocumentPath(uri) }
        if (localPaths.isNotEmpty()) {
            actions.onDocumentOrderChange((state.pendingDocumentPaths + localPaths).distinct())
            actions.onMediaOrderChange(emptyList())
        }
        attachmentPickerMode = AttachmentPickerMode.Default
    }

    val inputBarMode by remember(
        canSendAnything,
        isSlowModeActive,
        textValue.text,
        state.pendingMediaPaths,
        state.pendingDocumentPaths,
        state.editingMessage,
        voiceRecorder.isRecording
    ) {
        derivedStateOf {
            when {
                !canSendAnything -> InputBarMode.Restricted
                isSlowModeActive &&
                        textValue.text.isBlank() &&
                        state.pendingMediaPaths.isEmpty() &&
                        state.pendingDocumentPaths.isEmpty() &&
                        state.editingMessage == null &&
                        !voiceRecorder.isRecording -> InputBarMode.SlowMode

                else -> InputBarMode.Composer
            }
        }
    }

    if (showCamera) {
        CameraScreen(
            onImageCaptured = { uri ->
                val path = context.copyUriToTempPath(uri)
                if (path != null) {
                    actions.onMediaOrderChange((state.pendingMediaPaths + path).distinct())
                    actions.onDocumentOrderChange(emptyList())
                }
                showCamera = false
                attachmentPickerMode = AttachmentPickerMode.Default
            },
            onDismiss = {
                showCamera = false
                attachmentPickerMode = AttachmentPickerMode.Default
            }
        )
    } else {
        Box {
            AnimatedContent(
                targetState = inputBarMode,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 4 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(150)) + slideOutVertically(animationSpec = tween(150)) { it / 4 }
                        )
                },
                label = "InputBarModeTransition"
            ) { mode ->
                when (mode) {
                    InputBarMode.Composer -> ChatInputBarComposerSection(
                        editingMessage = state.editingMessage,
                        replyMessage = state.replyMessage,
                        attachments = ComposerAttachmentState(
                            pendingMediaPaths = state.pendingMediaPaths,
                            pendingDocumentPaths = state.pendingDocumentPaths,
                            scheduledMessagesCount = state.scheduledMessages.size
                        ),
                        suggestions = ComposerSuggestionState(
                            mentionSuggestions = state.mentionSuggestions,
                            filteredCommands = filteredCommands,
                            currentInlineBotUsername = state.currentInlineBotUsername.takeIf { canSendStickers },
                            isInlineBotLoading = canSendStickers && state.isInlineBotLoading,
                            inlineBotResults = state.inlineBotResults.takeIf { canSendStickers },
                            replyMarkup = state.replyMarkup,
                            isGifSearchFocused = isGifSearchFocused
                        ),
                        botState = ComposerBotState(
                            isBot = state.isBot,
                            botMenuButton = state.botMenuButton,
                            botCommands = state.botCommands
                        ),
                        rowState = ComposerRowState(
                            textValue = textValue,
                            editingMessage = state.editingMessage,
                            isStickerMenuVisible = isStickerMenuVisible,
                            closeStickerMenuWithoutSlide = closeStickerMenuWithoutSlide,
                            isKeyboardVisible = isKeyboardVisible,
                            bottomInset = bottomInset,
                            stickerMenuHeight = stickerMenuHeight,
                            showFullScreenEditor = showFullScreenEditor,
                            currentMessageLength = currentMessageLength,
                            maxMessageLength = maxMessageLength,
                            isOverMessageLimit = isOverMessageLimit,
                            showSendOptionsSheet = showSendOptionsSheet,
                            isVideoMessageMode = isVideoMessageMode,
                            isSlowModeActive = isSlowModeActive,
                            slowModeRemainingSeconds = slowModeRemainingSeconds
                        ),
                        onTextValueChange = { textValue = it },
                        knownCustomEmojis = knownCustomEmojis,
                        emojiFontFamily = emojiFontFamily,
                        focusRequester = focusRequester,
                        capabilities = capabilities,
                        canSendAttachments = canSendPendingAttachments,
                        canPasteMediaFromClipboard = canUseMediaPicker && state.editingMessage == null,
                        voiceRecorder = voiceRecorder,
                        stickerRepository = stickerRepository,
                        onCancelEdit = actions.onCancelEdit,
                        onCancelReply = actions.onCancelReply,
                        onCancelMedia = actions.onCancelMedia,
                        onCancelDocuments = { actions.onDocumentOrderChange(emptyList()) },
                        onAddMedia = {
                            if (!canUseMediaPicker) return@ChatInputBarComposerSection
                            openStickerMenuAfterKeyboardClosed = false
                            openKeyboardAfterStickerMenuClosed = false
                            closeStickerMenuWithoutSlide = false
                            isStickerMenuVisible = false
                            hideKeyboardAndClearFocus()
                            attachmentPickerMode = AttachmentPickerMode.MediaOnly
                            showGallery = true
                        },
                        onAddDocuments = {
                            if (!canUseDocumentPicker) return@ChatInputBarComposerSection
                            documentsPickerLauncher.launch(arrayOf("*/*"))
                        },
                        onMediaOrderChange = actions.onMediaOrderChange,
                        onDocumentOrderChange = actions.onDocumentOrderChange,
                        onMediaClick = actions.onMediaClick,
                        onPasteImages = { uris ->
                            if (!canUseMediaPicker || state.editingMessage != null) return@ChatInputBarComposerSection
                            val localPaths = uris.mapNotNull { uri ->
                                context.copyUriToTempPath(uri)
                            }
                            if (localPaths.isNotEmpty()) {
                                actions.onMediaOrderChange((state.pendingMediaPaths + localPaths).distinct())
                                actions.onDocumentOrderChange(emptyList())
                            }
                        },
                        onMentionClick = { user ->
                            textValue = applyMentionSuggestion(textValue, user)
                        },
                        onMentionQueryClear = { actions.onMentionQueryChange(null) },
                        onInlineResultClick = { resultId ->
                            if (!canSendStickers || isSlowModeActive) return@ChatInputBarComposerSection
                            actions.onSendInlineResult(resultId)
                            textValue = TextFieldValue("")
                            activateSlowModeCooldown()
                        },
                        onInlineSwitchPmClick = { text ->
                            if (!canSendStickers) return@ChatInputBarComposerSection
                            state.currentInlineBotUsername?.let { username ->
                                actions.onInlineSwitchPm(username, text)
                            }
                        },
                        onLoadMoreInlineResults = { offset ->
                            if (!canSendStickers) return@ChatInputBarComposerSection
                            actions.onLoadMoreInlineResults(offset)
                        },
                        onCommandClick = { command ->
                            if (isSlowModeActive || !canWriteText) return@ChatInputBarComposerSection
                            actions.onSend("/$command", emptyList(), MessageSendOptions())
                            textValue = TextFieldValue("")
                            activateSlowModeCooldown()
                        },
                        onAttachClick = {
                            if (!canOpenAttachSheet) return@ChatInputBarComposerSection
                            openStickerMenuAfterKeyboardClosed = false
                            openKeyboardAfterStickerMenuClosed = false
                            closeStickerMenuWithoutSlide = false
                            isStickerMenuVisible = false
                            hideKeyboardAndClearFocus()
                            attachmentPickerMode = AttachmentPickerMode.Default
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
                            attachmentPickerMode = if (state.pendingMediaPaths.isNotEmpty()) {
                                AttachmentPickerMode.MediaOnly
                            } else {
                                AttachmentPickerMode.Default
                            }
                            showSendOptionsSheet = true
                            actions.onRefreshScheduledMessages()
                        },
                        onSendAsDocument = {
                            showSendOptionsSheet = false
                            sendWithOptions(MessageSendOptions(sendAsDocument = true))
                        },
                        onCameraClick = {
                            hideKeyboardAndClearFocus()
                            actions.onCameraClick()
                        },
                        onVideoModeToggle = {
                            if (canSendVideoNotes) {
                                isVideoMessageMode = !isVideoMessageMode
                            }
                        },
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
                        onStickerClick = { stickerPath ->
                            if (!canSendStickers || isSlowModeActive) return@ChatInputBarComposerSection
                            actions.onStickerClick(stickerPath)
                            activateSlowModeCooldown()
                        },
                        onGifClick = { gif ->
                            if (!canSendStickers || isSlowModeActive) return@ChatInputBarComposerSection
                            actions.onGifClick(gif)
                            activateSlowModeCooldown()
                        },
                        onGifSearchFocusedChange = { isGifSearchFocused = it },
                        onReplyMarkupButtonClick = actions.onReplyMarkupButtonClick
                    )

                    InputBarMode.SlowMode -> SlowModeInputBar(
                        remainingSeconds = slowModeRemainingSeconds,
                        scheduledMessagesCount = state.scheduledMessages.size,
                        onOpenScheduledMessages = {
                            actions.onRefreshScheduledMessages()
                            showScheduledMessagesSheet = true
                        }
                    )

                    InputBarMode.Restricted -> RestrictedInputBar(
                        isCurrentUserRestricted = state.isCurrentUserRestricted,
                        restrictedUntilDate = state.restrictedUntilDate
                    )
                }
            }

            FullScreenEditorSheet(
                visible = showFullScreenEditor,
                textValue = textValue,
                onTextValueChange = { textValue = it },
                canWriteText = canWriteText,
                pendingMediaPaths = if (state.pendingMediaPaths.isNotEmpty()) state.pendingMediaPaths else state.pendingDocumentPaths,
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
            onDismissRequest = {
                showGallery = false
                attachmentPickerMode = AttachmentPickerMode.Default
            },
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
                        actions.onDocumentOrderChange(emptyList())
                    }
                    showGallery = false
                    attachmentPickerMode = AttachmentPickerMode.Default
                },
                onDismiss = {
                    showGallery = false
                    attachmentPickerMode = AttachmentPickerMode.Default
                },
                onCameraClick = {
                    if (!canUseMediaPicker) return@GalleryScreen
                    showGallery = false
                    attachmentPickerMode = AttachmentPickerMode.Default
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
                canSelectMedia = canUseMediaPicker,
                canUseCamera = canUseMediaPicker,
                canAttachFiles = canUseDocumentPicker && attachmentPickerMode == AttachmentPickerMode.Default,
                canCreatePoll = canSendPolls && !state.isSecretChat && attachmentPickerMode == AttachmentPickerMode.Default,
                onAttachFileClick = {
                    if (!canUseDocumentPicker) return@GalleryScreen
                    showGallery = false
                    attachmentPickerMode = AttachmentPickerMode.Default
                    documentsPickerLauncher.launch(arrayOf("*/*"))
                },
                onCreatePollClick = {
                    if (canSendPolls && !state.isSecretChat) {
                        showGallery = false
                        attachmentPickerMode = AttachmentPickerMode.Default
                        showPollComposer = true
                    }
                },
                attachBots = state.attachBots,
                hasMediaAccess = canUseMediaPicker && (hasGalleryPermission.value || hasFullGalleryPermission() || hasPartialGalleryPermission()),
                isPartialAccess = canUseMediaPicker && isPartialGalleryAccess,
                onPickFromOtherSources = {
                    if (!canUseMediaPicker) return@GalleryScreen
                    showGallery = false
                    attachmentPickerMode = AttachmentPickerMode.Default
                    actions.onAttachClick()
                },
                onRequestMediaAccess = {
                    if (canUseMediaPicker && requestableGalleryPermissions.isNotEmpty()) {
                        galleryPermissionLauncher.launch(requestableGalleryPermissions.toTypedArray())
                    }
                },
                onAttachBotClick = { bot ->
                    showGallery = false
                    attachmentPickerMode = AttachmentPickerMode.Default
                    actions.onAttachBotClick(bot)
                },
                modifier = Modifier.fillMaxHeight()
            )
        }
    }

    if (showPollComposer) {
        PollComposerSheet(
            onDismiss = { showPollComposer = false },
            onCreatePoll = { poll ->
                if (isSlowModeActive) return@PollComposerSheet
                showPollComposer = false
                actions.onSendPoll(poll)
                activateSlowModeCooldown()
            }
        )
    }

}
