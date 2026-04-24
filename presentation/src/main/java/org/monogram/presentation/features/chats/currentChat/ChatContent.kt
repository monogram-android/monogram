package org.monogram.presentation.features.chats.currentChat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentBackground
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentEffects
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentList
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentOverlays
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentSearchOverlay
import org.monogram.presentation.features.chats.currentChat.chatContent.ChatContentTopBar
import org.monogram.presentation.features.chats.currentChat.chatContent.GroupedMessageItem
import org.monogram.presentation.features.chats.currentChat.chatContent.chatContentLeadingItemsCount
import org.monogram.presentation.features.chats.currentChat.chatContent.extractTextContent
import org.monogram.presentation.features.chats.currentChat.chatContent.groupMessagesByAlbum
import org.monogram.presentation.features.chats.currentChat.chatContent.groupedIndexToLazyIndex
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatChromeState
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatContentPermissionState
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatInputBarActions
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatInputBarState
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatMessageListState
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatSearchUiState
import org.monogram.presentation.features.chats.currentChat.chatContent.rememberChatTopBarUiState
import org.monogram.presentation.features.chats.currentChat.chatContent.scrollToMessageIndex
import org.monogram.presentation.features.chats.currentChat.chatContent.withUpdatedTextContent
import org.monogram.presentation.features.chats.currentChat.components.AdvancedCircularRecorderScreen
import org.monogram.presentation.features.chats.currentChat.components.ChatInputBar
import org.monogram.presentation.features.chats.currentChat.components.MessageListShimmer
import org.monogram.presentation.features.chats.currentChat.components.chats.LocalLinkHandler
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatContent(
    component: ChatComponent,
    isOverlay: Boolean = false,
) {
    val state by component.state.collectAsState()
    val scrollState = rememberLazyListState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val localClipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTabletInterfaceEnabled = LocalTabletInterfaceEnabled.current
    val isTablet =
        adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED && isTabletInterfaceEnabled

    var isVisible by remember { mutableStateOf(false) }
    var showInitialLoading by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var topOverlayHeight by remember { mutableStateOf(0.dp) }

    // Menu States
    var selectedMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    val transformedMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val originalMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val latestMessagesState = rememberUpdatedState(state.messages)
    val selectedMessageIdState = rememberUpdatedState(selectedMessageId)
    val displayMessages by remember {
        derivedStateOf {
            val baseMessages = latestMessagesState.value
            baseMessages.map { message ->
                val transformedText = transformedMessageTexts[message.id]
                val transformedMessage = if (transformedText != null) {
                    message.withUpdatedTextContent(transformedText)
                } else {
                    message
                }

                if (state.rootMessage != null && transformedMessage.replyToMsgId == state.rootMessage?.id) {
                    transformedMessage.copy(
                        replyToMsgId = null,
                        replyToMsg = null
                    )
                } else {
                    transformedMessage
                }
            }
        }
    }
    val displayMessagesById by remember(displayMessages) {
        derivedStateOf { displayMessages.associateBy(MessageModel::id) }
    }
    val selectedMessage by remember {
        derivedStateOf {
            val currentSelectedId = selectedMessageIdState.value
            currentSelectedId?.let(displayMessagesById::get)
        }
    }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuMessageSize by remember { mutableStateOf(IntSize.Zero) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    var contentRect by remember { mutableStateOf(Rect.Zero) }

    var pendingMediaPaths by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pendingDocumentPaths by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var editingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var editingVideoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingBlockUserId by rememberSaveable { mutableStateOf<Long?>(null) }

    val groupedMessages by remember {
        derivedStateOf { groupMessagesByAlbum(displayMessages) }
    }
    val groupedMessageIndexById by remember(groupedMessages) {
        derivedStateOf {
            buildMap {
                groupedMessages.forEachIndexed { index, item ->
                    when (item) {
                        is GroupedMessageItem.Single -> put(item.message.id, index)
                        is GroupedMessageItem.Album -> item.messages.forEach { message ->
                            put(message.id, index)
                        }
                    }
                }
            }
        }
    }
    val isComments = state.rootMessage != null
    val isForumList = state.viewAsTopics && state.currentTopicId == null
    var showScrollToBottomButton by remember { mutableStateOf(false) }
    var showAllSearchResults by rememberSaveable(
        state.chatId,
        state.currentTopicId,
        state.isSearchActive,
        state.searchQuery
    ) {
        mutableStateOf(false)
    }
    var showSearchSenderPicker by rememberSaveable(
        state.chatId,
        state.currentTopicId,
        state.isSearchActive
    ) {
        mutableStateOf(false)
    }
    var showSearchFilters by rememberSaveable(
        state.chatId,
        state.currentTopicId,
        state.isSearchActive
    ) {
        mutableStateOf(false)
    }
    var hasUserScrolledAwayFromBottom by rememberSaveable(state.chatId, state.currentTopicId) {
        mutableStateOf(false)
    }
    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()
    val searchUiState = rememberChatSearchUiState(state)

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
        val index = groupedMessageIndexById[msg.id] ?: -1
        if (index != -1) {
            coroutineScope.launch {
                val leadingItems = chatContentLeadingItemsCount(
                    isComments = isComments,
                    showNavPadding = false,
                    isLoadingOlder = state.isLoadingOlder,
                    isLoadingNewer = state.isLoadingNewer,
                    isAtBottom = state.isAtBottom,
                    hasMessages = groupedMessages.isNotEmpty()
                )
                val targetIndex = groupedIndexToLazyIndex(index, leadingItems)

                scrollState.scrollToMessageIndex(
                    index = targetIndex,
                    align = ScrollAlign.Center,
                    animated = state.isChatAnimationsEnabled,
                    staged = true
                )
            }
        } else {
            component.onPinnedMessageClick(msg)
        }
    })

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
        if (albumPaths.isNotEmpty()) pendingDocumentPaths = emptyList()
    }

    val shouldAnimateContentEntrance = state.isChatAnimationsEnabled && isOverlay
    val contentAlpha by animateFloatAsState(
        targetValue = if (isVisible || !shouldAnimateContentEntrance) 1f else 0f,
        animationSpec = if (shouldAnimateContentEntrance) tween(300) else snap(),
        label = "ContentAlpha"
    )
    val contentOffset by animateDpAsState(
        targetValue = if (isVisible || !shouldAnimateContentEntrance) 0.dp else 20.dp,
        animationSpec = if (shouldAnimateContentEntrance) tween(300) else snap(),
        label = "ContentOffset"
    )

    val permissionState = rememberChatContentPermissionState(state)
    val messageListState = rememberChatMessageListState(
        state = state,
        displayMessages = displayMessages,
        canSendAnything = permissionState.canSendAnything,
        showInitialLoading = showInitialLoading
    )
    val chromeState = rememberChatChromeState(
        state = state,
        isRecordingVideo = isRecordingVideo,
        editingPhotoPath = editingPhotoPath,
        editingVideoPath = editingVideoPath,
        selectedMessageId = selectedMessageId
    )

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var renderPinnedMessagesList by rememberSaveable { mutableStateOf(state.showPinnedMessagesList) }
    var pendingPinnedSheetAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    ChatContentEffects(
        component = component,
        state = state,
        scrollState = scrollState,
        groupedMessages = groupedMessages,
        groupedMessageIndexById = groupedMessageIndexById,
        isComments = isComments,
        isForumList = isForumList,
        isDragged = isDragged,
        isRecordingVideo = isRecordingVideo,
        showInitialLoading = showInitialLoading,
        hasUserScrolledAwayFromBottom = hasUserScrolledAwayFromBottom,
        transformedMessageTexts = transformedMessageTexts,
        originalMessageTexts = originalMessageTexts,
        onVisible = {
            isVisible = true
        },
        onShowInitialLoadingChanged = { showInitialLoading = it },
        onHasUserScrolledAwayFromBottomChanged = { hasUserScrolledAwayFromBottom = it },
        onShowScrollToBottomButtonChanged = { showScrollToBottomButton = it },
        onHideKeyboardAndClearFocus = { force ->
            focusManager.clearFocus(force = force)
            keyboardController?.hide()
        },
        onRenderPinnedMessagesListChanged = { renderPinnedMessagesList = it },
        onSearchFiltersChanged = { showSearchFilters = it },
        onSearchSenderPickerChanged = { showSearchSenderPicker = it }
    )

    val requestPinnedMessagesListDismiss = {
        if (state.showPinnedMessagesList) {
            component.onDismissPinnedMessages()
        }
    }
    val topBarUiState = rememberChatTopBarUiState(state)

    CompositionLocalProvider(LocalLinkHandler provides { component.onLinkClick(it) }) {
        val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val headerOverlayHeight = statusBarHeight + 16.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onGloballyPositioned { containerSize = it.size }
        ) {
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

                if (isTablet) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerOverlayHeight)
                            .graphicsLayer {
                                alpha = contentAlpha
                                translationY = contentOffset.toPx()
                            }
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = contentAlpha; translationY = contentOffset.toPx() }
                        .semantics { contentDescription = "ChatContent" },
                    containerColor = Color.Transparent,
                    topBar = {
                        Box(
                            modifier = Modifier.onSizeChanged {
                                topOverlayHeight = with(density) { it.height.toDp() }
                            }
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChatContentTopBar(
                                    topBarState = topBarUiState,
                                    selectedCount = chromeState.selectedCount,
                                    canRevokeSelected = chromeState.canRevokeSelected,
                                    component = component,
                                    contentAlpha = contentAlpha,
                                    onBack = {
                                        keyboardController?.hide()
                                        if (state.currentTopicId != null) {
                                            component.onTopicClick(0)
                                        } else {
                                            component.onBackClicked()
                                        }
                                    },
                                    onOpenMenu = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus(force = true)
                                    },
                                    onPinnedMessageClick = { msg -> scrollToMessageState.value(msg) },
                                    showBack = !isTablet
                                )

                            }
                        }
                    },
                    bottomBar = {
                        if (chromeState.showInputBar) {
                            val inputBarState = rememberChatInputBarState(
                                state = state,
                                pendingMediaPaths = pendingMediaPaths,
                                pendingDocumentPaths = pendingDocumentPaths
                            )

                            val inputBarActions = rememberChatInputBarActions(
                                component = component,
                                state = state,
                                pendingMediaPaths = pendingMediaPaths,
                                pendingDocumentPaths = pendingDocumentPaths,
                                onPickMedia = {
                                    pickMedia.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                        )
                                    )
                                },
                                onHideKeyboardAndClearFocus = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                },
                                onStartRecordingVideo = {
                                    isRecordingVideo = true
                                },
                                onSetPendingMediaPaths = { paths ->
                                    pendingMediaPaths = paths
                                },
                                onSetPendingDocumentPaths = { paths ->
                                    pendingDocumentPaths = paths
                                },
                                onEditMediaPath = { path ->
                                    if (path.endsWith(".mp4")) {
                                        editingVideoPath = path
                                    } else {
                                        editingPhotoPath = path
                                    }
                                }
                            )

                            ChatInputBar(
                                state = inputBarState,
                                actions = inputBarActions,
                                appPreferences = component.appPreferences,
                                stickerRepository = component.stickerRepository
                            )
                        } else if (chromeState.showJoinButton) {
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
                            .padding(bottom = if (!state.canWrite && !chromeState.showJoinButton) 0.dp else padding.calculateBottomPadding())
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

                            val onForwardOriginClickStable: (ForwardInfo) -> Unit =
                                remember(component) {
                                    { forwardInfo ->
                                        component.onForwardOriginClick(forwardInfo)
                                    }
                                }

                            ChatContentList(
                                showNavPadding = false,
                                topOverlayPadding = if (
                                    (state.viewAsTopics && state.currentTopicId == null) ||
                                    state.rootMessage != null
                                ) {
                                    topOverlayHeight
                                } else {
                                    0.dp
                                },
                                state = messageListState,
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
                                onForwardOriginClick = onForwardOriginClickStable,
                                downloadUtils = component.downloadUtils,
                                isAnyViewerOpen = isAnyViewerOpen,
                                bottomContentPadding = if (state.rootMessage != null && (chromeState.showInputBar || chromeState.showJoinButton)) 120.dp else 8.dp
                            )

                            AnimatedVisibility(
                                visible = state.isSearchActive,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 12.dp, vertical = 16.dp)
                            ) {
                                ChatContentSearchOverlay(
                                    context = context,
                                    query = state.searchQuery,
                                    results = state.searchResults,
                                    totalCount = state.searchResultsTotalCount,
                                    selectedIndex = state.selectedSearchResultIndex,
                                    isSearching = state.isSearchingMessages,
                                    canLoadMore = searchUiState.canLoadMoreSearchResults,
                                    showAllResults = showAllSearchResults,
                                    showSearchFilters = showSearchFilters,
                                    showSearchSenderPicker = showSearchSenderPicker,
                                    hasFiltersApplied = searchUiState.hasSearchFiltersApplied,
                                    selectedSender = state.searchSender,
                                    searchSenderCandidates = searchUiState.searchSenderCandidates,
                                    fromEpochSeconds = state.searchDateFromEpochSeconds,
                                    toEpochSeconds = state.searchDateToEpochSeconds,
                                    onLoadMore = component::onLoadMoreSearchResults,
                                    onResultClick = { index ->
                                        showAllSearchResults = false
                                        component.onSearchResultClick(index)
                                    },
                                    onPrevious = component::onSearchPreviousResult,
                                    onNext = component::onSearchNextResult,
                                    onToggleShowAll = {
                                        showAllSearchResults = !showAllSearchResults
                                    },
                                    onToggleFilters = {
                                        val nextExpanded = !showSearchFilters
                                        showSearchFilters = nextExpanded
                                        if (!nextExpanded) {
                                            showSearchSenderPicker = false
                                        }
                                    },
                                    onToggleSenderPicker = {
                                        showSearchSenderPicker = !showSearchSenderPicker
                                    },
                                    onSelectSender = { user ->
                                        showSearchSenderPicker = false
                                        component.onSearchSenderChange(user)
                                    },
                                    onApplyDateRange = component::onSearchDateRangeChange
                                )
                            }

                            AnimatedVisibility(
                                visible = showScrollToBottomButton && !state.isSearchActive,
                                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                            ) {
                                Box {
                                    FloatingActionButton(
                                        onClick = {
                                            component.onScrollToBottom()
                                        },
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        elevation = FloatingActionButtonDefaults.elevation(
                                            defaultElevation = 0.dp,
                                            pressedElevation = 0.dp,
                                            focusedElevation = 0.dp,
                                            hoveredElevation = 0.dp
                                        ),
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
            ChatContentOverlays(
                state = state,
                component = component,
                localClipboard = localClipboard,
                groupedMessages = groupedMessages,
                isAnyViewerOpen = isAnyViewerOpen,
                renderPinnedMessagesList = renderPinnedMessagesList,
                requestPinnedMessagesListDismiss = requestPinnedMessagesListDismiss,
                onPinnedSheetHidden = {
                    renderPinnedMessagesList = false
                    pendingPinnedSheetAction?.invoke()
                    pendingPinnedSheetAction = null
                },
                onPinnedMessageClick = {
                    pendingPinnedSheetAction = { scrollToMessageState.value(it) }
                    requestPinnedMessagesListDismiss()
                },
                selectedMessage = selectedMessage,
                menuOffset = menuOffset,
                menuMessageSize = menuMessageSize,
                clickOffset = clickOffset,
                contentRect = contentRect,
                canRestoreOriginalText = selectedMessage?.let { msg ->
                    originalMessageTexts.containsKey(msg.id)
                } == true,
                onApplyTransformedText = { newText ->
                    val msg = selectedMessage ?: return@ChatContentOverlays
                    val originalText = msg.extractTextContent()
                    if (!originalText.isNullOrBlank() && !originalMessageTexts.containsKey(msg.id)) {
                        originalMessageTexts[msg.id] = originalText
                    }
                    transformedMessageTexts[msg.id] = newText
                },
                onRestoreOriginalText = {
                    val msg = selectedMessage ?: return@ChatContentOverlays
                    if (!originalMessageTexts.containsKey(msg.id)) {
                        return@ChatContentOverlays
                    }
                    transformedMessageTexts.remove(msg.id)
                    originalMessageTexts.remove(msg.id)
                },
                onDismissMessageOptions = { selectedMessageId = null },
                pendingBlockUserId = pendingBlockUserId,
                onRequestBlockUser = { userId ->
                    pendingBlockUserId = userId
                },
                onConfirmBlockUser = { userId ->
                    component.onBlockUser(userId)
                    pendingBlockUserId = null
                },
                onDismissBlockUser = { pendingBlockUserId = null },
                editingPhotoPath = editingPhotoPath,
                onClosePhotoEditor = { editingPhotoPath = null },
                onSavePhotoEditor = { newPath ->
                    val path = editingPhotoPath ?: return@ChatContentOverlays
                    val newList = pendingMediaPaths.toMutableList()
                    val index = newList.indexOf(path)
                    if (index != -1) {
                        newList[index] = newPath
                        pendingMediaPaths = newList
                    }
                    editingPhotoPath = null
                },
                editingVideoPath = editingVideoPath,
                onCloseVideoEditor = { editingVideoPath = null },
                onSaveVideoEditor = { newPath ->
                    val path = editingVideoPath ?: return@ChatContentOverlays
                    val newList = pendingMediaPaths.toMutableList()
                    val index = newList.indexOf(path)
                    if (index != -1) {
                        newList[index] = newPath
                        pendingMediaPaths = newList
                    }
                    editingVideoPath = null
                },
                isCustomBackHandlingEnabled = chromeState.isCustomBackHandlingEnabled,
                onBack = {
                    if (editingPhotoPath != null) editingPhotoPath = null
                    else if (editingVideoPath != null) editingVideoPath = null
                    else if (state.selectedMessageIds.isNotEmpty()) component.onClearSelection()
                    else if (selectedMessageId != null) selectedMessageId = null
                    else if (state.showBotCommands) component.onDismissBotCommands()
                    else if (state.restrictUserId != null) component.onDismissRestrictDialog()
                    else if (state.showPinnedMessagesList && !isAnyViewerOpen) requestPinnedMessagesListDismiss()
                    else if (state.fullScreenImages != null) component.onDismissImages()
                    else if (state.fullScreenVideoPath != null || state.fullScreenVideoMessageId != null) component.onDismissVideo()
                    else if (state.instantViewUrl != null) component.onDismissInstantView()
                    else if (state.youtubeUrl != null) component.onDismissYouTube()
                    else if (state.miniAppUrl != null) component.onDismissMiniApp()
                    else if (state.webViewUrl != null) component.onDismissWebView()
                    else if (state.isSearchActive) component.onSearchToggle()
                    else if (state.currentTopicId != null) component.onTopicClick(0)
                }
            )
        }
    }
}
