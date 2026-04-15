package org.monogram.presentation.features.chats.currentChat.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotMenuButtonModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.KeyboardButtonModel
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.InlineBotResultsModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.camera.CameraScreen
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.chats.currentChat.components.inputbar.ChatInputBarComposerSection
import org.monogram.presentation.features.chats.currentChat.components.inputbar.FullScreenEditorSheet
import org.monogram.presentation.features.chats.currentChat.components.inputbar.ScheduleDatePickerDialog
import org.monogram.presentation.features.chats.currentChat.components.inputbar.ScheduleTimePickerDialog
import org.monogram.presentation.features.chats.currentChat.components.inputbar.ScheduledMessagesSheet
import org.monogram.presentation.features.chats.currentChat.components.inputbar.applyMentionSuggestion
import org.monogram.presentation.features.chats.currentChat.components.inputbar.buildEditingMessageTextValue
import org.monogram.presentation.features.chats.currentChat.components.inputbar.buildScheduledDateEpochSeconds
import org.monogram.presentation.features.chats.currentChat.components.inputbar.copyUriToTempDocumentPath
import org.monogram.presentation.features.chats.currentChat.components.inputbar.copyUriToTempPath
import org.monogram.presentation.features.chats.currentChat.components.inputbar.declaredPermissions
import org.monogram.presentation.features.chats.currentChat.components.inputbar.extractEntities
import org.monogram.presentation.features.chats.currentChat.components.inputbar.hasAllPermissions
import org.monogram.presentation.features.chats.currentChat.components.inputbar.isInlineBotPrefillText
import org.monogram.presentation.features.chats.currentChat.components.inputbar.parseInlineQueryInput
import org.monogram.presentation.features.chats.currentChat.components.inputbar.rememberVoiceRecorder
import org.monogram.presentation.features.gallery.GalleryScreen
import org.monogram.presentation.features.gallery.components.PollComposerSheet
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@Immutable
data class ChatInputBarState(
    val replyMessage: MessageModel? = null,
    val editingMessage: MessageModel? = null,
    val draftText: String = "",
    val pendingMediaPaths: List<String> = emptyList(),
    val pendingDocumentPaths: List<String> = emptyList(),
    val isClosed: Boolean = false,
    val permissions: ChatPermissionsModel = ChatPermissionsModel(),
    val slowModeDelay: Int = 0,
    val slowModeDelayExpiresIn: Double = 0.0,
    val isCurrentUserRestricted: Boolean = false,
    val restrictedUntilDate: Int = 0,
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
    val onSendDocuments: (List<String>, String, List<MessageEntity>, MessageSendOptions) -> Unit = { _, _, _, _ -> },
    val onMediaOrderChange: (List<String>) -> Unit = {},
    val onDocumentOrderChange: (List<String>) -> Unit = {},
    val onMediaClick: (String) -> Unit = {},
    val onSendPoll: (PollDraft) -> Unit = {},
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

private enum class InputBarMode {
    Composer,
    SlowMode,
    Restricted
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

    val canWriteText by remember(state.isChannel, state.isAdmin, state.permissions.canSendBasicMessages) {
        derivedStateOf { if (state.isChannel) true else (state.isAdmin || state.permissions.canSendBasicMessages) }
    }
    val canSendMedia by remember(
        state.isChannel,
        state.isAdmin,
        state.permissions.canSendPhotos,
        state.permissions.canSendVideos,
        state.permissions.canSendDocuments,
        state.permissions.canSendAudios
    ) {
        derivedStateOf {
            if (state.isChannel) {
                true
            } else {
                state.isAdmin ||
                        state.permissions.canSendPhotos ||
                        state.permissions.canSendVideos ||
                        state.permissions.canSendDocuments ||
                        state.permissions.canSendAudios
            }
        }
    }
    val canSendStickers by remember(state.isChannel, state.isAdmin, state.permissions.canSendOtherMessages) {
        derivedStateOf { if (state.isChannel) true else (state.isAdmin || state.permissions.canSendOtherMessages) }
    }
    val canSendVoice by remember(state.isChannel, state.isAdmin, state.permissions.canSendVoiceNotes) {
        derivedStateOf { if (state.isChannel) true else (state.isAdmin || state.permissions.canSendVoiceNotes) }
    }
    val canSendPolls by remember(state.isChannel, state.isAdmin, state.permissions.canSendPolls) {
        derivedStateOf { if (state.isChannel) true else (state.isAdmin || state.permissions.canSendPolls) }
    }
    val canSendVideoNotes by remember(state.isChannel, state.isAdmin, state.permissions.canSendVideoNotes) {
        derivedStateOf { if (state.isChannel) true else (state.isAdmin || state.permissions.canSendVideoNotes) }
    }
    val canSendAnything by remember(canWriteText, canSendMedia, canSendStickers, canSendVoice, canSendVideoNotes) {
        derivedStateOf { canWriteText || canSendMedia || canSendStickers || canSendVoice || canSendVideoNotes }
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

    val voiceRecorder = rememberVoiceRecorder { path, duration, waveform ->
        if (!canSendVoice || isSlowModeActive) return@rememberVoiceRecorder
        actions.onSendVoice(path, duration, waveform)
        activateSlowModeCooldown()
    }
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

    val sendWithOptions: (MessageSendOptions) -> Unit = sendWithOptions@{
        if (isOverMessageLimit) return@sendWithOptions
        val isTextEmpty = textValue.text.isBlank()
        val captionEntities = extractEntities(textValue.annotatedString, knownCustomEmojis)
        val isScheduling = it.scheduleDate != null
        var sentInstantMessage = false

        val canSendNow = when {
            state.pendingMediaPaths.isNotEmpty() && canSendMedia -> true
            state.pendingDocumentPaths.isNotEmpty() && canSendMedia -> true
            state.editingMessage != null -> false
            canWriteText && !isTextEmpty -> true
            else -> false
        }

        if (isSlowModeActive && canSendNow && !isScheduling) {
            return@sendWithOptions
        }

        if (state.pendingMediaPaths.isNotEmpty() && canSendMedia) {
            actions.onSendMedia(state.pendingMediaPaths, textValue.text, captionEntities, it)
            textValue = TextFieldValue("")
            knownCustomEmojis.clear()
            sentInstantMessage = !isScheduling
        } else if (state.pendingDocumentPaths.isNotEmpty() && canSendMedia) {
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
    val documentsPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val localPaths = uris.mapNotNull { uri -> context.copyUriToTempDocumentPath(uri) }
        if (localPaths.isNotEmpty()) {
            actions.onDocumentOrderChange((state.pendingDocumentPaths + localPaths).distinct())
            actions.onMediaOrderChange(emptyList())
        }
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
            },
            onDismiss = { showCamera = false }
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
                        pendingMediaPaths = state.pendingMediaPaths,
                        pendingDocumentPaths = state.pendingDocumentPaths,
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
                        canPasteMediaFromClipboard = canSendMedia && state.editingMessage == null,
                        canSendStickers = canSendStickers,
                        canSendVoice = canSendVoice,
                        canSendVideoNotes = canSendVideoNotes,
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
                        isSlowModeActive = isSlowModeActive,
                        slowModeRemainingSeconds = slowModeRemainingSeconds,
                        replyMarkup = state.replyMarkup,
                        showSendOptionsSheet = showSendOptionsSheet,
                        stickerRepository = stickerRepository,
                        onCancelEdit = actions.onCancelEdit,
                        onCancelReply = actions.onCancelReply,
                        onCancelMedia = actions.onCancelMedia,
                        onCancelDocuments = { actions.onDocumentOrderChange(emptyList()) },
                        onMediaOrderChange = actions.onMediaOrderChange,
                        onDocumentOrderChange = actions.onDocumentOrderChange,
                        onMediaClick = actions.onMediaClick,
                        onPasteImages = { uris ->
                            if (!canSendMedia || state.editingMessage != null) return@ChatInputBarComposerSection
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
                            state.currentInlineBotUsername?.let { username ->
                                actions.onInlineSwitchPm(username, text)
                            }
                        },
                        onLoadMoreInlineResults = actions.onLoadMoreInlineResults,
                        onCommandClick = { command ->
                            if (isSlowModeActive || !canWriteText) return@ChatInputBarComposerSection
                            actions.onSend("/$command", emptyList(), MessageSendOptions())
                            textValue = TextFieldValue("")
                            activateSlowModeCooldown()
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
                        actions.onDocumentOrderChange(emptyList())
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
                canCreatePoll = canSendPolls && !state.isSecretChat,
                onAttachFileClick = {
                    showGallery = false
                    documentsPickerLauncher.launch(arrayOf("*/*"))
                },
                onCreatePollClick = {
                    if (canSendPolls && !state.isSecretChat) {
                        showGallery = false
                        showPollComposer = true
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

    if (showPollComposer) {
        PollComposerSheet(
            onDismiss = { showPollComposer = false },
            onCreatePoll = { poll: PollDraft ->
                if (isSlowModeActive) return@PollComposerSheet
                showPollComposer = false
                actions.onSendPoll(poll)
                activateSlowModeCooldown()
            }
        )
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
private fun SlowModeInputBar(
    remainingSeconds: Int,
    scheduledMessagesCount: Int,
    onOpenScheduledMessages: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.slow_mode_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedContent(
                targetState = remainingSeconds.coerceAtLeast(0),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + slideInVertically(animationSpec = tween(200)) { it / 2 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(120)) + slideOutVertically(animationSpec = tween(120)) { -it / 2 }
                        )
                },
                label = "SlowModeRemaining"
            ) { seconds ->
                Text(
                    text = formatSlowModeDuration(seconds),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (scheduledMessagesCount > 0) {
                IconButton(onClick = onOpenScheduledMessages) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = stringResource(R.string.action_scheduled_messages),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RestrictedInputBar(
    isCurrentUserRestricted: Boolean,
    restrictedUntilDate: Int
) {
    val restrictionDetails = remember(isCurrentUserRestricted, restrictedUntilDate) {
        if (!isCurrentUserRestricted) {
            null
        } else if (restrictedUntilDate <= 0) {
            RestrictionDetails.Permanent
        } else {
            RestrictionDetails.Until(formatRestrictedUntilDate(restrictedUntilDate))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.input_error_not_allowed),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(
                visible = restrictionDetails != null,
                enter = fadeIn(animationSpec = tween(220)) + expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(animationSpec = tween(140))
            ) {
                val detailsText = when (restrictionDetails) {
                    is RestrictionDetails.Until -> stringResource(
                        R.string.logs_restricted_until,
                        restrictionDetails.value
                    )

                    RestrictionDetails.Permanent -> stringResource(R.string.logs_restricted_permanently)
                    null -> ""
                }

                Text(
                    text = detailsText,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private sealed interface RestrictionDetails {
    data class Until(val value: String) : RestrictionDetails
    data object Permanent : RestrictionDetails
}

private fun formatRestrictedUntilDate(epochSeconds: Int): String {
    val formatter = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault()
    )
    return formatter.format(Date(epochSeconds.toLong() * 1000L))
}

private fun formatSlowModeDuration(totalSeconds: Int): String {
    val clamped = totalSeconds.coerceAtLeast(0)
    val hours = clamped / 3600
    val minutes = (clamped % 3600) / 60
    val seconds = clamped % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
