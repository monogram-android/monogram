package org.monogram.presentation.features.chats.currentChat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.features.chats.currentChat.chatContent.*
import org.monogram.presentation.features.chats.currentChat.components.*
import org.monogram.presentation.features.chats.currentChat.components.chats.BotCommandsSheet
import org.monogram.presentation.features.chats.currentChat.components.chats.LocalLinkHandler
import org.monogram.presentation.features.chats.currentChat.components.chats.PollVotersSheet
import org.monogram.presentation.features.chats.currentChat.components.pins.PinnedMessagesListSheet
import org.monogram.presentation.features.chats.currentChat.editor.photo.PhotoEditorScreen
import org.monogram.presentation.features.chats.currentChat.editor.video.VideoEditorScreen
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatContent(
    component: ChatComponent,
    isOverlay: Boolean = false,
) {
    val state by component.state.collectAsState()
    val scrollState = rememberLazyListState()
    val context = LocalContext.current
    val localClipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTablet = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED

    var isVisible by remember { mutableStateOf(false) }
    var showInitialLoading by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }

    // Menu States
    var selectedMessageId by remember { mutableStateOf<Long?>(null) }
    val transformedMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val originalMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val latestMessagesState = rememberUpdatedState(state.messages)
    val selectedMessageIdState = rememberUpdatedState(selectedMessageId)
    val displayMessages by remember {
        derivedStateOf {
            val baseMessages = latestMessagesState.value
            if (transformedMessageTexts.isEmpty()) {
                baseMessages
            } else {
                baseMessages.map { message ->
                    val transformedText = transformedMessageTexts[message.id] ?: return@map message
                    message.withUpdatedTextContent(transformedText)
                }
            }
        }
    }
    val selectedMessage by remember {
        derivedStateOf {
            val currentSelectedId = selectedMessageIdState.value
            displayMessages.find { it.id == currentSelectedId }
        }
    }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuMessageSize by remember { mutableStateOf(IntSize.Zero) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    var contentRect by remember { mutableStateOf(Rect.Zero) }

    var pendingMediaPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingPhotoPath by remember { mutableStateOf<String?>(null) }
    var editingVideoPath by remember { mutableStateOf<String?>(null) }
    var pendingBlockUserId by remember { mutableStateOf<Long?>(null) }

    val groupedMessages by remember {
        derivedStateOf { groupMessagesByAlbum(displayMessages) }
    }
    val isComments = state.rootMessage != null
    val isForumList = state.viewAsTopics && state.currentTopicId == null
    var showScrollToBottomButton by remember { mutableStateOf(false) }

    val isAnyViewerOpen = state.fullScreenImages != null ||
            state.fullScreenVideoPath != null ||
            state.fullScreenVideoMessageId != null ||
            state.youtubeUrl != null ||
            state.instantViewUrl != null ||
            state.miniAppUrl != null ||
            state.webViewUrl != null ||
            editingPhotoPath != null ||
            editingVideoPath != null ||
            isRecordingVideo

    val scrollToMessageState = rememberUpdatedState(newValue = { msg: MessageModel ->
        val index = groupedMessages.indexOfFirst { item ->
            when (item) {
                is GroupedMessageItem.Single -> item.message.id == msg.id
                is GroupedMessageItem.Album -> item.messages.any { it.id == msg.id }
            }
        }
        if (index != -1) {
            coroutineScope.launch {
                val targetIndex = if (isComments) {
                    if (state.rootMessage != null) index + 1 else index
                } else index

                scrollState.scrollMessageToCenter(
                    index = targetIndex,
                    animated = state.isChatAnimationsEnabled
                )
            }
        } else {
            component.onPinnedMessageClick(msg)
        }
    })

    LaunchedEffect(Unit) {
        isVisible = true
        if (state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null) {
            component.onDismissVideo()
        }
    }

    LaunchedEffect(state.messages) {
        val ids = state.messages.map { it.id }.toSet()
        transformedMessageTexts.keys.toList().forEach { id ->
            if (id !in ids) {
                transformedMessageTexts.remove(id)
                originalMessageTexts.remove(id)
            }
        }
    }

    // Initial Loading Delay logic
    LaunchedEffect(
        state.isLoading,
        state.messages.isEmpty(),
        state.viewAsTopics,
        state.currentTopicId,
        state.isLoadingTopics,
        state.rootMessage
    ) {
        val isActuallyLoading = if (state.viewAsTopics && state.currentTopicId == null) {
            state.isLoadingTopics && state.topics.isEmpty()
        } else if (state.currentTopicId != null) {
            state.isLoading && state.messages.isEmpty() && state.rootMessage == null
        } else {
            state.isLoading && state.messages.isEmpty()
        }
        if (isActuallyLoading) {
            if (state.isChatAnimationsEnabled) delay(200)
            showInitialLoading = true
        } else {
            showInitialLoading = false
        }
    }

    // Scroll to message when requested by component
    LaunchedEffect(state.scrollToMessageId, groupedMessages) {
        state.scrollToMessageId?.let { id ->
            val index = groupedMessages.indexOfFirst { item ->
                if (id == state.currentTopicId) {
                    false
                } else {
                    when (item) {
                        is GroupedMessageItem.Single -> item.message.id == id
                        is GroupedMessageItem.Album -> item.messages.any { it.id == id }
                    }
                }
            }
            if (index != -1) {
                component.onScrollToMessageConsumed()

                val targetIndex = if (isComments) {
                    if (state.rootMessage != null) index + 1 else index
                } else index

                scrollState.scrollMessageToCenter(
                    index = targetIndex,
                    animated = state.isChatAnimationsEnabled
                )
            }
        }
    }

    // Unified bottom-status + bottom-button controller with hysteresis/debounce for smoothness.
    LaunchedEffect(
        scrollState,
        isComments,
        isForumList,
        showInitialLoading,
        state.unreadCount,
        state.isLatestLoaded
    ) {
        snapshotFlow {
            BottomVisibilitySnapshot(
                isAtBottom = scrollState.isAtBottom(
                    isComments = isComments,
                    isLatestLoaded = state.isLatestLoaded
                ),
                isNearBottom = scrollState.isNearBottom(
                    isComments = isComments
                ),
                unreadCount = state.unreadCount
            )
        }
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                component.onBottomReached(snapshot.isAtBottom)

                val shouldShow = !isForumList &&
                        !showInitialLoading &&
                        (snapshot.unreadCount > 0 || !snapshot.isNearBottom)

                if (shouldShow) {
                    showScrollToBottomButton = true
                } else {
                    delay(120)
                    val keepVisible = state.unreadCount > 0 || !snapshot.isNearBottom
                    if (!keepVisible) {
                        showScrollToBottomButton = false
                    }
                }
            }
    }

    // Save scroll position
    LaunchedEffect(scrollState, groupedMessages, isComments, state.rootMessage, state.isLatestLoaded) {
        snapshotFlow { scrollState.isScrollInProgress to scrollState.firstVisibleItemIndex }
            .filter { !it.first }
            .map {
                val isAtBottom = scrollState.isAtBottom(
                    isComments = isComments,
                    isLatestLoaded = state.isLatestLoaded
                )

                if (isAtBottom && !isComments) {
                    0L
                } else {
                    val visibleItems = scrollState.layoutInfo.visibleItemsInfo
                    if (visibleItems.isNotEmpty()) {
                        val firstVisibleItem = if (isComments) {
                            visibleItems.firstOrNull { it.index > 0 }
                        } else {
                            visibleItems.firstOrNull { it.index >= 0 }
                        }

                        if (firstVisibleItem != null) {
                            val groupedIndex =
                                if (state.rootMessage != null) firstVisibleItem.index - 1 else firstVisibleItem.index
                            groupedMessages.getOrNull(groupedIndex)?.firstMessageId
                        } else null
                    } else null
                }
            }
            .distinctUntilChanged()
            .collect { messageId ->
                if (messageId != null) {
                    component.updateScrollPosition(messageId)
                }
            }
    }

    // Performance: Update visible range for repository
    LaunchedEffect(scrollState, groupedMessages, state.rootMessage) {
        snapshotFlow { scrollState.layoutInfo.visibleItemsInfo }
            .map { visibleItems ->
                val visibleIds = mutableListOf<Long>()
                val nearbyIds = mutableListOf<Long>()
                if (visibleItems.isNotEmpty()) {
                    val minIndex = visibleItems.minOf { it.index }
                    val maxIndex = visibleItems.maxOf { it.index }

                    visibleItems.forEach { item ->
                        val groupedIndex = if (state.rootMessage != null) item.index - 1 else item.index
                        groupedMessages.getOrNull(groupedIndex)?.let { grouped ->
                            when (grouped) {
                                is GroupedMessageItem.Single -> visibleIds.add(grouped.message.id)
                                is GroupedMessageItem.Album -> visibleIds.addAll(grouped.messages.map { it.id })
                            }
                        }
                    }

                    val nearbyStart = (minIndex - 5).coerceAtLeast(0)
                    val nearbyEnd = maxIndex + 5
                    for (index in nearbyStart..nearbyEnd) {
                        if (index in minIndex..maxIndex) continue
                        val groupedIndex = if (state.rootMessage != null) index - 1 else index
                        groupedMessages.getOrNull(groupedIndex)?.let { grouped ->
                            when (grouped) {
                                is GroupedMessageItem.Single -> nearbyIds.add(grouped.message.id)
                                is GroupedMessageItem.Album -> nearbyIds.addAll(grouped.messages.map { it.id })
                            }
                        }
                    }
                }
                visibleIds.distinct() to nearbyIds.distinct().filterNot { it in visibleIds }
            }
            .distinctUntilChanged()
            .debounce(100)
            .collect { (visibleIds, nearbyIds) ->
                (component as? DefaultChatComponent)?.let {
                    it.repositoryMessage.updateVisibleRange(it.chatId, visibleIds, nearbyIds)
                }
            }
    }

    // Auto-scroll to bottom when new messages arrive and we are already at the bottom
    val messageCount = groupedMessages.size
    LaunchedEffect(messageCount, state.isLatestLoaded) {
        if (isComments) return@LaunchedEffect

        val isAtBottomNow = scrollState.isAtBottom(
            isComments = isComments,
            isLatestLoaded = state.isLatestLoaded
        )
        if ((state.isAtBottom || isAtBottomNow) &&
            !state.isLoading &&
            !state.isLoadingOlder &&
            !state.isLoadingNewer &&
            !scrollState.isScrollInProgress
        ) {
            scrollState.scrollToChatBottom(
                isComments = isComments,
                animated = state.isChatAnimationsEnabled
            )
        }
    }

    // Scroll Management
    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LaunchedEffect(state.showBotCommands, isRecordingVideo) {
        if (state.showBotCommands || isRecordingVideo) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    // Pick Media Result
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val albumPaths = mutableListOf<String>()
        uris.forEach { uri ->
            val mimeType = context.contentResolver.getType(uri)
            val extension = when {
                mimeType == "image/gif" -> "gif"
                mimeType?.startsWith("video/") == true -> "mp4"
                else -> "jpg"
            }
            val file = File(context.cacheDir, "temp_media_${System.nanoTime()}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            if (extension == "gif") component.onSendGifFile(file.absolutePath)
            else albumPaths.add(file.absolutePath)
        }
        if (albumPaths.isNotEmpty()) pendingMediaPaths = (pendingMediaPaths + albumPaths).distinct()
    }


    val contentAlpha by animateFloatAsState(
        targetValue = if (isVisible || !state.isChatAnimationsEnabled || isOverlay) 1f else 0f,
        animationSpec = if (state.isChatAnimationsEnabled && !isOverlay) tween(300) else snap(),
        label = "ContentAlpha"
    )
    val contentOffset by animateDpAsState(
        targetValue = if (isVisible || !state.isChatAnimationsEnabled || isOverlay) 0.dp else 20.dp,
        animationSpec = if (state.isChatAnimationsEnabled && !isOverlay) tween(300) else snap(),
        label = "ContentOffset"
    )

    val showInputBar = (state.isMember || !state.isChannel && !state.isGroup) &&
            (state.canWrite || state.currentTopicId != null) &&
            !isRecordingVideo &&
            state.selectedMessageIds.isEmpty() &&
            (!state.viewAsTopics || state.currentTopicId != null)

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val isCustomBackHandlingEnabled =
        (editingPhotoPath != null || editingVideoPath != null || selectedMessageId != null || state.selectedMessageIds.isNotEmpty() || state.currentTopicId != null || state.showBotCommands || state.restrictUserId != null || state.fullScreenImages != null || state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null || state.miniAppUrl != null || state.webViewUrl != null || state.instantViewUrl != null || state.youtubeUrl != null)

    CompositionLocalProvider(LocalLinkHandler provides { component.onLinkClick(it) }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onGloballyPositioned { containerSize = it.size }
        ) {
            /*if (isDragToBackEnabled && !isTablet && !isCustomBackHandlingEnabled && dragOffsetX.value > 0 && previousChild != null) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    renderChild(previousChild)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(
                                    alpha = 0.3f * (1f - (dragOffsetX.value / containerSize.width.toFloat()).coerceIn(
                                        0f,
                                        1f
                                    ))
                                )
                            )
                    )
                }
            }*/

            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha
                            translationY = contentOffset.toPx()
                        }
                ) {
                    ChatContentBackground(state = state)
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = contentAlpha; translationY = contentOffset.toPx() }
                        .semantics { contentDescription = "ChatContent" },
                    containerColor = Color.Transparent,
                    topBar = {
                        ChatContentTopBar(
                            state = state,
                            component = component,
                            contentAlpha = contentAlpha,
                            onBack = { keyboardController?.hide(); component.onBackClicked() },
                            onOpenMenu = {
                                keyboardController?.hide()
                                focusManager.clearFocus(force = true)
                            },
                            onPinnedMessageClick = { msg -> scrollToMessageState.value(msg) },
                            showBack = !isTablet
                        )
                    },
                    bottomBar = {
                        if (showInputBar) {
                            val inputBarState = remember(state, pendingMediaPaths) {
                                ChatInputBarState(
                                    replyMessage = state.replyMessage,
                                    editingMessage = state.editingMessage,
                                    draftText = state.draftText,
                                    pendingMediaPaths = pendingMediaPaths,
                                    isClosed = state.topics.find { it.id.toLong() == state.currentTopicId }?.isClosed
                                        ?: false,
                                    permissions = state.permissions,
                                    isAdmin = state.isAdmin,
                                    isChannel = state.isChannel,
                                    isBot = state.isBot,
                                    botCommands = state.botCommands,
                                    botMenuButton = state.botMenuButton,
                                    replyMarkup = state.messages.firstOrNull { it.replyMarkup is ReplyMarkupModel.ShowKeyboard }?.replyMarkup,
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

                            val inputBarActions = remember(component, pendingMediaPaths) {
                                ChatInputBarActions(
                                    onSend = { text, entities, options ->
                                        component.onSendMessage(
                                            text,
                                            entities,
                                            options
                                        )
                                    },
                                    onStickerClick = { component.onSendSticker(it) },
                                    onGifClick = { component.onSendGif(it) },
                                    onAttachClick = {
                                        pickMedia.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                            )
                                        )
                                    },
                                    onCameraClick = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus(force = true)
                                        isRecordingVideo = true
                                    },
                                    onSendVoice = { path, duration, waveform ->
                                        component.onSendVoice(path, duration, waveform)
                                    },
                                    onCancelReply = { component.onCancelReply() },
                                    onCancelEdit = { component.onCancelEdit() },
                                    onSaveEdit = { t, e -> component.onSaveEditedMessage(t, e) },
                                    onDraftChange = { component.onDraftChange(it) },
                                    onTyping = { component.onTyping() },
                                    onCancelMedia = { pendingMediaPaths = emptyList() },
                                    onSendMedia = { paths, caption, captionEntities, options ->
                                        if (paths.size > 1) component.onSendAlbum(
                                            paths,
                                            caption,
                                            captionEntities,
                                            options
                                        )
                                        else paths.firstOrNull()?.let {
                                            if (it.endsWith(".mp4")) component.onSendVideo(
                                                it,
                                                caption,
                                                captionEntities,
                                                options
                                            )
                                            else component.onSendPhoto(it, caption, captionEntities, options)
                                        }
                                        pendingMediaPaths = emptyList()
                                    },
                                    onMediaOrderChange = { pendingMediaPaths = it },
                                    onMediaClick = { path ->
                                        if (path.endsWith(".mp4")) {
                                            editingVideoPath = path
                                        } else {
                                            editingPhotoPath = path
                                        }
                                    },
                                    onShowBotCommands = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus(force = true)
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
                                    onMentionQueryChange = { component.onMentionQueryChange(it) },
                                    onInlineQueryChange = { bot, query ->
                                        component.onInlineQueryChange(bot, query)
                                    },
                                    onLoadMoreInlineResults = { offset ->
                                        component.onLoadMoreInlineResults(offset)
                                    },
                                    onSendInlineResult = { resultId -> component.onSendInlineResult(resultId) },
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
                                    onRefreshScheduledMessages = { component.onRefreshScheduledMessages() },
                                    onEditScheduledMessage = { message -> component.onEditMessage(message) },
                                    onDeleteScheduledMessage = { message -> component.onDeleteMessage(message) },
                                    onSendScheduledNow = { message -> component.onSendScheduledNow(message) }
                                )
                            }

                            ChatInputBar(
                                state = inputBarState,
                                actions = inputBarActions,
                                appPreferences = component.appPreferences,
                                stickerRepository = component.stickerRepository
                            )
                        } else if (!state.isMember && (state.isChannel || state.isGroup)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .windowInsetsPadding(WindowInsets.navigationBars),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { component.onJoinChat() },
                                    shapes = ExpressiveDefaults.largeButtonShapes(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(ButtonDefaults.MediumContainerHeight)
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_join),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .onGloballyPositioned { coordinates ->
                                contentRect = Rect(
                                    offset = coordinates.positionInWindow(),
                                    size = coordinates.size.toSize()
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = contentAlpha
                                    translationY = contentOffset.toPx()
                                }
                        ) {
                            val currentKeyboardController = rememberUpdatedState(keyboardController)
                            val currentFocusManager = rememberUpdatedState(focusManager)
                            val currentIsVisible = rememberUpdatedState(isVisible)
                            val currentShowInitialLoading = rememberUpdatedState(showInitialLoading)

                            val onPhotoDownloadStable: (Int) -> Unit = remember(component) {
                                { fileId: Int ->
                                    if (fileId != 0) {
                                        component.onDownloadFile(fileId)
                                    }
                                }
                            }

                            val onPhotoClickStable: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit =
                                remember(component) {
                                    { msg: MessageModel, paths: List<String>, captions: List<String?>, messageIds: List<Long>, index: Int ->
                                    val content = msg.content as? MessageContent.Photo
                                    val clickedPath = paths.getOrNull(index)
                                        ?.takeIf { it.isNotBlank() && File(it).exists() }
                                        ?: content?.path?.takeIf { File(it).exists() }

                                    if (clickedPath != null) {
                                        currentKeyboardController.value?.hide()
                                        currentFocusManager.value.clearFocus()

                                        val validItems = paths.mapIndexedNotNull { itemIndex, path ->
                                            val validPath = path.takeIf { it.isNotBlank() && File(it).exists() }
                                                ?: return@mapIndexedNotNull null
                                            Triple(itemIndex, validPath, captions.getOrNull(itemIndex))
                                        }

                                        if (validItems.isNotEmpty()) {
                                            val validPaths = validItems.map { it.second }
                                            val validCaptions = validItems.map { it.third }
                                            val validMessageIds = validItems.map { (itemIndex, _, _) ->
                                                messageIds.getOrNull(itemIndex) ?: msg.id
                                            }
                                            val startIndex = validItems.indexOfFirst { (itemIndex, _, _) -> itemIndex == index }
                                                .takeIf { it >= 0 }
                                                ?: validPaths.indexOf(clickedPath).takeIf { it >= 0 }
                                                ?: 0

                                            component.onOpenImages(
                                                images = validPaths,
                                                captions = validCaptions,
                                                startIndex = startIndex,
                                                messageId = msg.id,
                                                messageIds = validMessageIds
                                            )
                                        }
                                    } else {
                                        content?.fileId?.takeIf { it != 0 }?.let(component::onDownloadFile)
                                    }
                                        Unit
                                    }
                                }

                            val onVideoClickStable: (MessageModel, String?, String?) -> Unit =
                                remember(component, scrollState) {
                                    { msg: MessageModel, path: String?, caption: String? ->
                                        if (!currentIsVisible.value || currentShowInitialLoading.value || scrollState.isScrollInProgress) {
                                            Unit
                                        } else {
                                        val videoContent = msg.content as? MessageContent.Video
                                        val supportsStreaming = videoContent?.supportsStreaming ?: false
                                        val validPath = path?.takeIf { File(it).exists() }

                                        if (validPath != null || supportsStreaming) {
                                            currentKeyboardController.value?.hide()
                                            currentFocusManager.value.clearFocus()
                                            component.onOpenVideo(path = validPath, messageId = msg.id, caption = caption)
                                        } else {
                                            val fileId = when (val c = msg.content) {
                                                is MessageContent.Video -> c.fileId
                                                is MessageContent.Gif -> c.fileId
                                                else -> 0
                                            }
                                            if (fileId != 0) {
                                                component.onDownloadFile(fileId)
                                            }
                                        }
                                        }
                                    }
                                }

                            val onDocumentClickStable: (MessageModel) -> Unit = remember(component) {
                                { msg: MessageModel ->
                                    val doc = msg.content as? MessageContent.Document
                                    if (doc != null) {
                                        val validDocPath = doc.path?.takeIf { File(it).exists() }
                                        if (validDocPath != null) {
                                            val path = validDocPath.lowercase()
                                            if (path.endsWith(".jpg") || path.endsWith(".png")) {
                                                currentKeyboardController.value?.hide()
                                                currentFocusManager.value.clearFocus()
                                                component.onOpenImages(
                                                    images = listOf(validDocPath),
                                                    captions = listOf(doc.caption),
                                                    startIndex = 0,
                                                    messageId = msg.id,
                                                    messageIds = listOf(msg.id)
                                                )
                                            } else if (path.endsWith(".mp4")) {
                                                currentKeyboardController.value?.hide()
                                                currentFocusManager.value.clearFocus()
                                                component.onOpenVideo(
                                                    path = validDocPath,
                                                    messageId = msg.id,
                                                    caption = doc.caption
                                                )
                                            } else {
                                                component.downloadUtils.openFile(validDocPath)
                                            }
                                        } else {
                                            component.onDownloadFile(doc.fileId)
                                        }
                                    }
                                    Unit
                                }
                            }

                            val onAudioClickStable: (MessageModel) -> Unit = remember(component) {
                                { msg: MessageModel ->
                                    val audio = msg.content as? MessageContent.Audio
                                    if (audio != null) {
                                        val validAudioPath = audio.path?.takeIf { File(it).exists() }
                                        if (validAudioPath != null) {
                                            currentKeyboardController.value?.hide()
                                            currentFocusManager.value.clearFocus()
                                            component.onOpenVideo(
                                                path = validAudioPath,
                                                messageId = msg.id,
                                                caption = audio.caption
                                            )
                                        } else {
                                            component.onDownloadFile(audio.fileId)
                                        }
                                    }
                                    Unit
                                }
                            }

                            val onMessageOptionsClickStable: (MessageModel, Offset, IntSize, Offset) -> Unit =
                                remember(component) {
                                    { msg: MessageModel, pos: Offset, size: IntSize, clickPos: Offset ->
                                        currentKeyboardController.value?.hide()
                                        currentFocusManager.value.clearFocus(force = true)
                                        selectedMessageId = msg.id
                                        menuOffset = pos
                                        menuMessageSize = size
                                        clickOffset = clickPos
                                    }
                                }

                            val onGoToReplyStable: (MessageModel) -> Unit = remember(scrollToMessageState) {
                                { msg: MessageModel ->
                                    scrollToMessageState.value(msg)
                                }
                            }

                            val onMessagePositionChangeStable: (Offset, IntSize) -> Unit = remember {
                                { pos: Offset, size: IntSize ->
                                    menuOffset = pos
                                    menuMessageSize = size
                                }
                            }

                            val onViaBotClickStable: (String) -> Unit = remember(component) {
                                { botUsername: String ->
                                    val prefill = "@$botUsername "
                                    component.onDraftChange(prefill)
                                    component.onInlineQueryChange("", "")
                                }
                            }

                            val toProfileStable: (Long) -> Unit = remember(component) {
                                { userId: Long ->
                                    component.toProfile(userId)
                                }
                            }

                            ChatContentList(
                                showNavPadding = false,
                                state = state,
                                component = component,
                                scrollState = scrollState,
                                groupedMessages = groupedMessages,
                                onPhotoDownload = onPhotoDownloadStable,
                                onPhotoClick = onPhotoClickStable,
                                onVideoClick = onVideoClickStable,
                                onDocumentClick = onDocumentClickStable,
                                onAudioClick = onAudioClickStable,
                                onMessageOptionsClick = onMessageOptionsClickStable,
                                onGoToReply = onGoToReplyStable,
                                selectedMessageId = selectedMessageId,
                                onMessagePositionChange = onMessagePositionChangeStable,
                                onViaBotClick = onViaBotClickStable,
                                toProfile = toProfileStable,
                                    downloadUtils = component.downloadUtils,
                                    isAnyViewerOpen = isAnyViewerOpen
                                )

                            AnimatedVisibility(
                                visible = showScrollToBottomButton,
                                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                            ) {
                                Box {
                                    FloatingActionButton(
                                        onClick = {
                                            if (!state.isLatestLoaded) {
                                                component.onScrollToBottom()
                                            } else {
                                                coroutineScope.launch {
                                                    scrollState.scrollToChatBottom(
                                                        isComments = isComments,
                                                        animated = state.isChatAnimationsEnabled
                                                    )
                                                }
                                            }
                                        },
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    AnimatedVisibility(
                                        visible = state.unreadCount > 0,
                                        enter = scaleIn() + fadeIn(),
                                        exit = scaleOut() + fadeOut(),
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .offset(y = (-8).dp)
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                            shadowElevation = 4.dp
                                        ) {
                                            AnimatedContent(
                                                targetState = state.unreadCount,
                                                transitionSpec = {
                                                    if (targetState > initialState) {
                                                        (slideInVertically { height -> height } + fadeIn()).togetherWith(
                                                            slideOutVertically { height -> -height } + fadeOut())
                                                    } else {
                                                        (slideInVertically { height -> -height } + fadeIn()).togetherWith(
                                                            slideOutVertically { height -> height } + fadeOut())
                                                    }.using(
                                                        SizeTransform(clip = false)
                                                    )
                                                },
                                                label = "UnreadCountAnimation"
                                            ) { count ->
                                                Text(
                                                    text = if (count > 999) "999+" else count.toString(),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (isRecordingVideo) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                        .zIndex(10f)
                                ) {
                                    AdvancedCircularRecorderScreen(
                                        onClose = { isRecordingVideo = false },
                                        onVideoRecorded = { file ->
                                            isRecordingVideo = false
                                            component.onVideoRecorded(file)
                                        }
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = showInitialLoading,
                                enter = fadeIn(),
                                exit = fadeOut(animationSpec = tween(400))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    MessageListShimmer(
                                        isGroup = state.isGroup,
                                        isChannel = state.isChannel
                                    )
                                }
                            }
                        }
                    }
                }
            }


            // Modals & Overlays
            if (state.showPinnedMessagesList) {
                PinnedMessagesListSheet(
                    state = state,
                    onDismiss = { component.onDismissPinnedMessages() },
                    onMessageClick = { scrollToMessageState.value(it); component.onDismissPinnedMessages() },
                    onUnpin = { component.onUnpinMessage(it) },
                    onReplyClick = { scrollToMessageState.value(it); component.onDismissPinnedMessages() },
                    onReactionClick = { id, r -> component.onSendReaction(id, r) },
                    downloadUtils = component.downloadUtils
                )
            }

            state.selectedStickerSet?.let { stickerSet ->
                StickerSetSheet(
                    stickerSet = stickerSet,
                    onDismiss = { component.onDismissStickerSet() },
                    onStickerClick = { component.onSendSticker(it) }
                )
            }

            if (state.showPollVoters) {
                PollVotersSheet(
                    voters = state.pollVoters,
                    isLoading = state.isPollVotersLoading,
                    onUserClick = {
                        component.onDismissVoters()
                        component.toProfile(it)
                    },
                    onDismiss = { component.onDismissVoters() }
                )
            }

            if (state.showBotCommands) {
                BotCommandsSheet(
                    commands = state.botCommands,
                    onCommandClick = { component.onBotCommandClick(it) },
                    onDismiss = { component.onDismissBotCommands() }
                )
            }

            ChatContentViewers(
                state = state,
                component = component,
                localClipboard = localClipboard
            )

            selectedMessage?.let { msg ->
                ChatMessageOptionsMenu(
                    state = state,
                    component = component,
                    selectedMessage = msg,
                    menuOffset = menuOffset,
                    menuMessageSize = menuMessageSize,
                    clickOffset = clickOffset,
                    contentRect = contentRect,
                    groupedMessages = groupedMessages,
                    downloadUtils = component.downloadUtils,
                    localClipboard = localClipboard,
                    canRestoreOriginalText = originalMessageTexts.containsKey(msg.id),
                    onApplyTransformedText = { newText ->
                        val originalText = msg.extractTextContent()
                        if (!originalText.isNullOrBlank() && !originalMessageTexts.containsKey(msg.id)) {
                            originalMessageTexts[msg.id] = originalText
                        }
                        transformedMessageTexts[msg.id] = newText
                    },
                    onRestoreOriginalText = {
                        val originalText = originalMessageTexts[msg.id] ?: return@ChatMessageOptionsMenu
                        transformedMessageTexts[msg.id] = originalText
                        originalMessageTexts.remove(msg.id)
                    },
                    onBlockRequest = { userId ->
                        pendingBlockUserId = userId
                    },
                    onDismiss = { selectedMessageId = null }
                )
            }

            pendingBlockUserId?.let { userId ->
                ConfirmationSheet(
                    icon = Icons.Rounded.Block,
                    title = stringResource(R.string.block_user_title),
                    description = stringResource(R.string.block_user_confirmation),
                    confirmText = stringResource(R.string.action_block),
                    onConfirm = {
                        component.onBlockUser(userId)
                        pendingBlockUserId = null
                    },
                    onDismiss = { pendingBlockUserId = null }
                )
            }

            if (state.showReportDialog) {
                ReportChatDialog(
                    onDismiss = { component.onDismissReportDialog() },
                    onReasonSelected = { component.onReportReasonSelected(it) }
                )
            }

            if (state.restrictUserId != null) {
                RestrictUserSheet(
                    onDismiss = { component.onDismissRestrictDialog() },
                    onConfirm = { permissions, untilDate -> component.onConfirmRestrict(permissions, untilDate) }
                )
            }

            editingPhotoPath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(20f)
                ) {
                    PhotoEditorScreen(
                        imagePath = path,
                        onClose = { editingPhotoPath = null },
                        onSave = { newPath ->
                            val newList = pendingMediaPaths.toMutableList()
                            val index = newList.indexOf(path)
                            if (index != -1) {
                                newList[index] = newPath
                                pendingMediaPaths = newList
                            }
                            editingPhotoPath = null
                        }
                    )
                }
            }

            editingVideoPath?.let { path ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(20f)
                ) {
                    VideoEditorScreen(
                        videoPath = path,
                        onClose = { editingVideoPath = null },
                        onSave = { newPath ->
                            val newList = pendingMediaPaths.toMutableList()
                            val index = newList.indexOf(path)
                            if (index != -1) {
                                newList[index] = newPath
                                pendingMediaPaths = newList
                            }
                            editingVideoPath = null
                        }
                    )
                }
            }

            BackHandler(enabled = isCustomBackHandlingEnabled) {
                if (editingPhotoPath != null) editingPhotoPath = null
                else if (editingVideoPath != null) editingVideoPath = null
                else if (state.selectedMessageIds.isNotEmpty()) component.onClearSelection()
                else if (selectedMessageId != null) selectedMessageId = null
                else if (state.showBotCommands) component.onDismissBotCommands()
                else if (state.restrictUserId != null) component.onDismissRestrictDialog()
                else if (state.fullScreenImages != null) component.onDismissImages()
                else if (state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null) component.onDismissVideo()
                else if (state.instantViewUrl != null) component.onDismissInstantView()
                else if (state.youtubeUrl != null) component.onDismissYouTube()
                else if (state.miniAppUrl != null) component.onDismissMiniApp()
                else if (state.webViewUrl != null) component.onDismissWebView()
                else if (state.currentTopicId != null) component.onBackClicked()
            }
        }
    }
}

private fun MessageModel.extractTextContent(): String? {
    return when (val c = content) {
        is MessageContent.Text -> c.text
        is MessageContent.Photo -> c.caption
        is MessageContent.Video -> c.caption
        is MessageContent.Gif -> c.caption
        is MessageContent.Document -> c.caption
        is MessageContent.Audio -> c.caption
        else -> null
    }
}

private fun MessageModel.withUpdatedTextContent(newText: String): MessageModel {
    val updatedContent = when (val c = content) {
        is MessageContent.Text -> c.copy(text = newText, entities = emptyList(), webPage = null)
        is MessageContent.Photo -> c.copy(caption = newText, entities = emptyList())
        is MessageContent.Video -> c.copy(caption = newText, entities = emptyList())
        is MessageContent.Gif -> c.copy(caption = newText, entities = emptyList())
        is MessageContent.Document -> c.copy(caption = newText, entities = emptyList())
        is MessageContent.Audio -> c.copy(caption = newText, entities = emptyList())
        else -> return this
    }
    return copy(content = updatedContent)
}

private suspend fun LazyListState.scrollMessageToCenter(
    index: Int,
    animated: Boolean
) {
    if (animated) animateScrollToItem(index) else scrollToItem(index)

    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val itemCenter = itemInfo.offset + (itemInfo.size / 2)
    val delta = (itemCenter - viewportCenter).toFloat()

    if (abs(delta) > 1f) {
        if (animated) {
            animateScrollBy(delta)
        } else {
            scrollBy(delta)
        }
    }
}

private data class BottomVisibilitySnapshot(
    val isAtBottom: Boolean,
    val isNearBottom: Boolean,
    val unreadCount: Int
)

private fun LazyListState.isAtBottom(
    isComments: Boolean,
    isLatestLoaded: Boolean
): Boolean {
    if (!isLatestLoaded) return false

    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return true

    return if (isComments) {
        val lastVisible = visible.last()
        lastVisible.index >= info.totalItemsCount - 1 &&
                abs((info.viewportEndOffset - (lastVisible.offset + lastVisible.size)).toFloat()) <= 40f
    } else {
        val firstVisible = visible.first()
        firstVisible.index == 0 &&
                abs((firstVisible.offset - info.viewportStartOffset).toFloat()) <= 40f
    }
}

private fun LazyListState.isNearBottom(isComments: Boolean): Boolean {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return true

    return if (isComments) {
        val lastVisible = visible.last()
        val distance = abs((info.viewportEndOffset - (lastVisible.offset + lastVisible.size)).toFloat())
        lastVisible.index >= info.totalItemsCount - 2 && distance <= 240f
    } else {
        val firstVisible = visible.first()
        val distance = abs((firstVisible.offset - info.viewportStartOffset).toFloat())
        firstVisible.index <= 1 && distance <= 240f
    }
}

private suspend fun LazyListState.scrollToChatBottom(
    isComments: Boolean,
    animated: Boolean
) {
    val targetIndex = if (isComments) {
        val total = layoutInfo.totalItemsCount
        if (total > 0) total - 1 else 0
    } else {
        0
    }

    if (animated) {
        animateScrollToItem(targetIndex)
    } else {
        scrollToItem(targetIndex)
    }
}
