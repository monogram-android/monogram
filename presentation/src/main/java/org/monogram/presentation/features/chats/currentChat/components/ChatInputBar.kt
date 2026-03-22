package org.monogram.presentation.features.chats.currentChat.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.io.File
import java.io.FileOutputStream
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
    val isInlineBotLoading: Boolean = false,
    val attachBots: List<AttachMenuBotModel> = emptyList(),
)

@Immutable
data class ChatInputBarActions(
    val onSend: (String, List<MessageEntity>) -> Unit,
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
    val onSendMedia: (List<String>, String) -> Unit = { _, _ -> },
    val onMediaOrderChange: (List<String>) -> Unit = {},
    val onMediaClick: (String) -> Unit = {},
    val onShowBotCommands: () -> Unit = {},
    val onReplyMarkupButtonClick: (KeyboardButtonModel) -> Unit = {},
    val onOpenMiniApp: (String, String) -> Unit = { _, _ -> },
    val onMentionQueryChange: (String?) -> Unit = {},
    val onInlineQueryChange: (String, String) -> Unit = { _, _ -> },
    val onLoadMoreInlineResults: (String) -> Unit = {},
    val onSendInlineResult: (String) -> Unit = {},
    val onAttachBotClick: (AttachMenuBotModel) -> Unit = {},
    val onGalleryClick: () -> Unit = {},
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

    val knownCustomEmojis = remember { mutableStateMapOf<Long, StickerModel>() }

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    var lastEditingMessageId by remember { mutableStateOf<Long?>(null) }

    val voiceRecorder = rememberVoiceRecorder(onRecordingFinished = actions.onSendVoice)

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
    LaunchedEffect(textValue.text) {
        val text = textValue.text
        if (text.startsWith("@") && text.contains(" ")) {
            val parts = text.split(" ", limit = 2)
            val botUsername = parts[0].substring(1)
            val query = parts[1]
            if (botUsername.isNotEmpty()) {
                currentOnInlineQueryChange(botUsername, query)
            }
        }
    }

    LaunchedEffect(state.draftText) {
        if (textValue.text.isEmpty() && state.draftText.isNotEmpty()) {
            textValue = TextFieldValue(state.draftText, TextRange(state.draftText.length))
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

                                else -> {}
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

    BackHandler(enabled = isStickerMenuVisible || state.pendingMediaPaths.isNotEmpty() || showGallery || showCamera) {
        if (isGifSearchFocused) {
            focusManager.clearFocus()
        } else if (isStickerMenuVisible) {
            isStickerMenuVisible = false
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
                                actions.onSend("/$command", emptyList())
                                textValue = TextFieldValue("")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AnimatedVisibility(
                        visible = (state.inlineBotResults != null && (state.inlineBotResults.results.isNotEmpty() || state.inlineBotResults.switchPmText != null)) || state.isInlineBotLoading,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        InlineBotResults(
                            inlineBotResults = state.inlineBotResults,
                            isLoading = state.isInlineBotLoading,
                            onResultClick = { resultId ->
                                actions.onSendInlineResult(resultId)
                                textValue = TextFieldValue("")
                            },
                            onSwitchPmClick = { text ->
                                actions.onOpenMiniApp(
                                    text,
                                    "switch_pm"
                                )
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
                                            onValueChange = { textValue = it },
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
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            if (!voiceRecorder.isLocked) {
                                Spacer(modifier = Modifier.width(8.dp))

                                InputBarSendButton(
                                    textValue = textValue,
                                    editingMessage = state.editingMessage,
                                    pendingMediaPaths = state.pendingMediaPaths,
                                    canWriteText = canWriteText,
                                    canSendVoice = canSendVoice,
                                    canSendMedia = canSendMedia,
                                    isVideoMessageMode = isVideoMessageMode,
                                    knownCustomEmojis = knownCustomEmojis,
                                    onSend = actions.onSend,
                                    onSaveEdit = actions.onSaveEdit,
                                    onSendMedia = actions.onSendMedia,
                                    onCameraClick = actions.onCameraClick,
                                    onVideoModeToggle = { isVideoMessageMode = !isVideoMessageMode },
                                    onTextValueChange = { textValue = it },
                                    onKnownEmojisClear = { knownCustomEmojis.clear() },
                                    onVoiceStart = { voiceRecorder.startRecording() },
                                    onVoiceStop = { cancel -> voiceRecorder.stopRecording(cancel) },
                                    onVoiceLock = { voiceRecorder.lockRecording() }
                                )
                            }
                        }
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
                                val currentText = textValue.annotatedString
                                val selection = textValue.selection

                                val emojiAnnotated = if (sticker != null) {
                                    knownCustomEmojis[sticker.id] = sticker
                                    buildAnnotatedString {
                                        append(emoji)
                                        addStringAnnotation(CUSTOM_EMOJI_TAG, sticker.id.toString(), 0, emoji.length)
                                    }
                                } else {
                                    AnnotatedString(emoji)
                                }

                                val newText = buildAnnotatedString {
                                    append(currentText.subSequence(0, selection.start))
                                    append(emojiAnnotated)
                                    append(currentText.subSequence(selection.end, currentText.length))
                                }

                                textValue = textValue.copy(
                                    annotatedString = newText,
                                    selection = TextRange(selection.start + emojiAnnotated.length)
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

private fun Context.hasAllPermissions(permissions: List<String>): Boolean {
    return permissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
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
