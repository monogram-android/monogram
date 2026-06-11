package org.monogram.presentation.features.chats.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.features.chats.conversation.ui.LocalVoicePlaybackController
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentBackground
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentBody
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentBottomBar
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentEffects
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentOverlays
import org.monogram.presentation.features.chats.conversation.ui.content.ChatContentTopBar
import org.monogram.presentation.features.chats.conversation.ui.content.extractTextContent
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatChromeState
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatContentBodyUiState
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatContentPermissionState
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatMessageListState
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatMessagePresentationState
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatSearchUiState
import org.monogram.presentation.features.chats.conversation.ui.content.rememberChatTopBarUiState
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import org.monogram.presentation.features.chats.conversation.ui.message.LocalLinkHandler
import org.monogram.presentation.features.chats.conversation.ui.message.LocalMessageRenderDependencies
import org.monogram.presentation.features.chats.conversation.ui.message.PreviewImageViewerRequest
import org.monogram.presentation.features.chats.conversation.ui.message.PreviewVideoViewerRequest
import org.monogram.presentation.features.chats.conversation.ui.message.rememberChatMessageRenderDependencies
import org.monogram.presentation.features.chats.conversation.ui.rememberVoicePlaybackController

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatContent(
    component: ChatComponent,
    isOverlay: Boolean = false,
    onSwipeBackBlockedChanged: (Boolean) -> Unit = {},
) {
    val state by component.state.collectAsState()
    val scrollState = rememberLazyListState()
    val density = LocalDensity.current
    val localClipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTabletInterfaceEnabled = LocalTabletInterfaceEnabled.current
    val isTablet =
        adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED &&
                isTabletInterfaceEnabled

    var isVisible by remember { mutableStateOf(false) }
    var showInitialLoading by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var topOverlayHeight by remember { mutableStateOf(0.dp) }

    var selectedMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    val transformedMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val originalMessageTexts = remember { mutableStateMapOf<Long, String>() }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuMessageSize by remember { mutableStateOf(IntSize.Zero) }
    var clickOffset by remember { mutableStateOf(Offset.Zero) }
    var contentRect by remember { mutableStateOf(Rect.Zero) }

    var pendingMediaPaths by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var pendingDocumentPaths by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var editingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var editingVideoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingBlockUserId by rememberSaveable { mutableStateOf<Long?>(null) }
    var previewImages by remember { mutableStateOf<PreviewImageViewerRequest?>(null) }
    var previewVideo by remember { mutableStateOf<PreviewVideoViewerRequest?>(null) }

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
    var renderPinnedMessagesList by rememberSaveable { mutableStateOf(state.showPinnedMessagesList) }
    var pendingPinnedSheetAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()
    val messagePresentationState = rememberChatMessagePresentationState(
        state = state,
        selectedMessageId = selectedMessageId,
        transformedMessageTexts = transformedMessageTexts
    )
    val bodyUiState = rememberChatContentBodyUiState(state)
    val searchUiState = rememberChatSearchUiState(state)
    val permissionState = rememberChatContentPermissionState(state)
    val topBarUiState = rememberChatTopBarUiState(state)
    val chromeState = rememberChatChromeState(
        state = state,
        isRecordingVideo = isRecordingVideo,
        editingPhotoPath = editingPhotoPath,
        editingVideoPath = editingVideoPath,
        selectedMessageId = selectedMessageId
    )
    val messageListState = rememberChatMessageListState(
        state = state,
        displayMessages = messagePresentationState.displayMessages,
        canSendAnything = permissionState.canSendAnything,
        showInitialLoading = showInitialLoading
    )
    val messageRenderDependencies by rememberChatMessageRenderDependencies(
        messages = remember(messagePresentationState.displayMessages, state.rootMessage) {
            buildList {
                addAll(messagePresentationState.displayMessages)
                state.rootMessage?.let(::add)
            }
        }
    )
    val voicePlaybackController = rememberVoicePlaybackController()

    val isAnyViewerOpen by remember(
        state.fullScreenImages,
        state.fullScreenVideoPath,
        state.fullScreenVideoMessageId,
        state.youtubeUrl,
        state.instantViewUrl,
        state.miniAppUrl,
        state.webViewUrl,
        editingPhotoPath,
        editingVideoPath,
        previewImages,
        previewVideo,
        isRecordingVideo
    ) {
        derivedStateOf {
            state.fullScreenImages != null ||
                    state.fullScreenVideoPath != null ||
                    state.fullScreenVideoMessageId != null ||
                    state.youtubeUrl != null ||
                    state.instantViewUrl != null ||
                    state.miniAppUrl != null ||
                    state.webViewUrl != null ||
                    editingPhotoPath != null ||
                    editingVideoPath != null ||
                    previewImages != null ||
                    previewVideo != null ||
                    isRecordingVideo
        }
    }

    val onLinkPreviewAction = remember(component, state.chatId, state.isBot) {
        { action: LinkPreviewAction ->
            when (action) {
                is LinkPreviewAction.OpenImageViewer -> {
                    previewVideo = null
                    previewImages = action.request
                }

                is LinkPreviewAction.OpenVideoViewer -> {
                    previewImages = null
                    previewVideo = action.request
                }

                is LinkPreviewAction.OpenInstantView -> component.onOpenInstantView(action.url)
                is LinkPreviewAction.OpenYouTube -> component.onOpenYouTube(action.url)
                is LinkPreviewAction.OpenMiniApp -> component.onOpenMiniApp(
                    action.url,
                    action.name,
                    if (state.isBot) state.chatId else 0L
                )

                is LinkPreviewAction.OpenWebView -> component.onOpenWebView(action.url)
                is LinkPreviewAction.OpenLink -> component.onLinkClick(action.url)
            }
        }
    }

    LaunchedEffect(isAnyViewerOpen, onSwipeBackBlockedChanged) {
        onSwipeBackBlockedChanged(isAnyViewerOpen)
    }

    DisposableEffect(onSwipeBackBlockedChanged) {
        onDispose { onSwipeBackBlockedChanged(false) }
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

    ChatContentEffects(
        component = component,
        state = state,
        scrollState = scrollState,
        groupedMessages = messagePresentationState.groupedMessages,
        groupedMessageIndexById = messagePresentationState.groupedMessageIndexById,
        isComments = messagePresentationState.isComments,
        isForumList = messagePresentationState.isForumList,
        isDragged = isDragged,
        isRecordingVideo = isRecordingVideo,
        showInitialLoading = showInitialLoading,
        hasUserScrolledAwayFromBottom = hasUserScrolledAwayFromBottom,
        transformedMessageTexts = transformedMessageTexts,
        originalMessageTexts = originalMessageTexts,
        onVisible = { isVisible = true },
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
    val bottomContentPadding =
        if (state.rootMessage != null && (chromeState.showInputBar || chromeState.showJoinButton)) {
            120.dp
        } else {
            8.dp
        }

    CompositionLocalProvider(
        LocalLinkHandler provides { component.onLinkClick(it) },
        LocalMessageRenderDependencies provides messageRenderDependencies,
        LocalVoicePlaybackController provides voicePlaybackController
    ) {
        val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val headerOverlayHeight = statusBarHeight + 16.dp
        val animatedLayerModifier = Modifier.graphicsLayer {
            alpha = contentAlpha
            translationY = contentOffset.toPx()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .then(animatedLayerModifier)) {
                    ChatContentBackground(state = state)
                }

                if (isTablet) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerOverlayHeight)
                            .then(animatedLayerModifier)
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(animatedLayerModifier)
                        .semantics { contentDescription = "ChatContent" },
                    containerColor = Color.Transparent,
                    topBar = {
                        Box(
                            modifier = Modifier.onSizeChanged {
                                topOverlayHeight = with(density) { it.height.toDp() }
                            }
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
                                onPinnedMessageClick = { message ->
                                    component.scrollToMessage(
                                        message.id
                                    )
                                },
                                showBack = !isTablet
                            )
                        }
                    },
                    bottomBar = {
                        ChatContentBottomBar(
                            state = state,
                            component = component,
                            chromeState = chromeState,
                            pendingMediaPaths = pendingMediaPaths,
                            pendingDocumentPaths = pendingDocumentPaths,
                            onDraftLinkPreviewAction = onLinkPreviewAction,
                            onPendingMediaPathsChanged = { pendingMediaPaths = it },
                            onPendingDocumentPathsChanged = { pendingDocumentPaths = it },
                            onStartRecordingVideo = { isRecordingVideo = true },
                            onEditMediaPath = { path ->
                                if (path.endsWith(".mp4")) {
                                    editingVideoPath = path
                                } else {
                                    editingPhotoPath = path
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = padding.calculateBottomPadding())
                            .consumeWindowInsets(padding)
                    ) {
                        ChatContentBody(
                            state = bodyUiState,
                            component = component,
                            scrollState = scrollState,
                            messageListState = messageListState,
                            groupedMessages = messagePresentationState.groupedMessages,
                            searchUiState = searchUiState,
                            selectedMessageId = selectedMessageId,
                            topOverlayHeight = topOverlayHeight,
                            bottomContentPadding = bottomContentPadding,
                            contentAlpha = contentAlpha,
                            contentOffset = contentOffset,
                            isVisible = isVisible,
                            showInitialLoading = showInitialLoading,
                            showScrollToBottomButton = showScrollToBottomButton,
                            isRecordingVideo = isRecordingVideo,
                            isAnyViewerOpen = isAnyViewerOpen,
                            showAllSearchResults = showAllSearchResults,
                            showSearchFilters = showSearchFilters,
                            showSearchSenderPicker = showSearchSenderPicker,
                            onShowAllSearchResultsChanged = { showAllSearchResults = it },
                            onShowSearchFiltersChanged = { showSearchFilters = it },
                            onShowSearchSenderPickerChanged = { showSearchSenderPicker = it },
                            onContentRectChanged = { contentRect = it },
                            onMessageOptionsRequested = { messageId, offset, size, clickPosition ->
                                selectedMessageId = messageId
                                menuOffset = offset
                                menuMessageSize = size
                                clickOffset = clickPosition
                            },
                            onMessageAnchorChanged = { offset, size ->
                                menuOffset = offset
                                menuMessageSize = size
                            },
                            onRecordingVideoChanged = { isRecordingVideo = it },
                            onVideoRecorded = component::onVideoRecorded,
                            onLinkPreviewAction = onLinkPreviewAction
                        )
                    }
                }
            }

            ChatContentOverlays(
                state = state,
                component = component,
                localClipboard = localClipboard,
                groupedMessages = messagePresentationState.groupedMessages,
                isAnyViewerOpen = isAnyViewerOpen,
                renderPinnedMessagesList = renderPinnedMessagesList,
                requestPinnedMessagesListDismiss = requestPinnedMessagesListDismiss,
                onPinnedSheetHidden = {
                    renderPinnedMessagesList = false
                    pendingPinnedSheetAction?.invoke()
                    pendingPinnedSheetAction = null
                },
                onPinnedMessageClick = {
                    pendingPinnedSheetAction = { component.scrollToMessage(it.id) }
                    requestPinnedMessagesListDismiss()
                },
                selectedMessage = messagePresentationState.selectedMessage,
                menuOffset = menuOffset,
                menuMessageSize = menuMessageSize,
                clickOffset = clickOffset,
                contentRect = contentRect,
                canRestoreOriginalText = messagePresentationState.selectedMessage?.let { message ->
                    originalMessageTexts.containsKey(message.id)
                } == true,
                onApplyTransformedText = { newText ->
                    val message =
                        messagePresentationState.selectedMessage ?: return@ChatContentOverlays
                    val originalText = message.extractTextContent()
                    if (!originalText.isNullOrBlank() && !originalMessageTexts.containsKey(message.id)) {
                        originalMessageTexts[message.id] = originalText
                    }
                    transformedMessageTexts[message.id] = newText
                },
                onRestoreOriginalText = {
                    val message =
                        messagePresentationState.selectedMessage ?: return@ChatContentOverlays
                    if (!originalMessageTexts.containsKey(message.id)) {
                        return@ChatContentOverlays
                    }
                    transformedMessageTexts.remove(message.id)
                    originalMessageTexts.remove(message.id)
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
                previewImages = previewImages,
                onDismissPreviewImages = { previewImages = null },
                previewVideo = previewVideo,
                onDismissPreviewVideo = { previewVideo = null },
                isCustomBackHandlingEnabled = chromeState.isCustomBackHandlingEnabled,
                onBack = {
                    if (editingPhotoPath != null) editingPhotoPath = null
                    else if (editingVideoPath != null) editingVideoPath = null
                    else if (previewImages != null) previewImages = null
                    else if (previewVideo != null) previewVideo = null
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
