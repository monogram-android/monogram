package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ui.AdvancedCircularRecorderScreen
import org.monogram.presentation.features.chats.conversation.ui.ChatInputBar
import org.monogram.presentation.features.chats.conversation.ui.MessageListShimmer
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChatContentBottomBar(
    state: ChatComponent.State,
    component: ChatComponent,
    chromeState: ChatContentChromeState,
    pendingMediaPaths: List<String>,
    pendingDocumentPaths: List<String>,
    onDraftLinkPreviewAction: (LinkPreviewAction) -> Unit,
    onPendingMediaPathsChanged: (List<String>) -> Unit,
    onPendingDocumentPathsChanged: (List<String>) -> Unit,
    onStartRecordingVideo: () -> Unit,
    onEditMediaPath: (String) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val pickMedia =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
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
                if (extension == "gif") {
                    component.onSendGifFile(file.absolutePath)
                } else {
                    albumPaths.add(file.absolutePath)
                }
            }
            if (albumPaths.isNotEmpty()) {
                onPendingMediaPathsChanged((pendingMediaPaths + albumPaths).distinct())
                onPendingDocumentPathsChanged(emptyList())
            }
        }

    if (chromeState.showInputBar) {
        val inputBarState = rememberChatInputBarState(
            state = state,
            pendingMediaPaths = pendingMediaPaths,
            pendingDocumentPaths = pendingDocumentPaths
        )
        val inputBarActions = rememberChatInputBarActions(
            component = component,
            state = state,
            onPickMedia = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onHideKeyboardAndClearFocus = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
            },
            onStartRecordingVideo = onStartRecordingVideo,
            onSetPendingMediaPaths = onPendingMediaPathsChanged,
            onSetPendingDocumentPaths = onPendingDocumentPathsChanged,
            onEditMediaPath = onEditMediaPath,
            onDraftLinkPreviewAction = onDraftLinkPreviewAction
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
                onClick = component::onJoinChat,
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

@Composable
internal fun ChatContentBody(
    state: ChatContentBodyUiState,
    component: ChatComponent,
    scrollState: LazyListState,
    messageListState: ChatMessageListUiState,
    groupedMessages: List<GroupedMessageItem>,
    searchUiState: ChatContentSearchUiState,
    selectedMessageId: Long?,
    topOverlayHeight: Dp,
    bottomContentPadding: Dp,
    contentAlpha: Float,
    contentOffset: Dp,
    isVisible: Boolean,
    showInitialLoading: Boolean,
    showScrollToBottomButton: Boolean,
    isRecordingVideo: Boolean,
    isAnyViewerOpen: Boolean,
    showAllSearchResults: Boolean,
    showSearchFilters: Boolean,
    showSearchSenderPicker: Boolean,
    onShowAllSearchResultsChanged: (Boolean) -> Unit,
    onShowSearchFiltersChanged: (Boolean) -> Unit,
    onShowSearchSenderPickerChanged: (Boolean) -> Unit,
    onContentRectChanged: (Rect) -> Unit,
    onMessageOptionsRequested: (Long, Offset, IntSize, Offset) -> Unit,
    onMessageAnchorChanged: (Offset, IntSize) -> Unit,
    onRecordingVideoChanged: (Boolean) -> Unit,
    onVideoRecorded: (File) -> Unit,
    onLinkPreviewAction: (LinkPreviewAction) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val currentKeyboardController = rememberUpdatedState(keyboardController)
    val currentFocusManager = rememberUpdatedState(focusManager)
    val currentIsVisible = rememberUpdatedState(isVisible)
    val currentShowInitialLoading = rememberUpdatedState(showInitialLoading)

    val onPhotoDownload: (Int) -> Unit = remember(component) {
        { fileId ->
            if (fileId != 0) {
                component.onDownloadFile(fileId)
            }
        }
    }
    val onPhotoClick: (MessageModel, List<String>, List<String?>, List<Long>, Int) -> Unit =
        remember(component) {
            { msg, paths, captions, messageIds, index ->
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
                        val startIndex =
                            validItems.indexOfFirst { (itemIndex, _, _) -> itemIndex == index }
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
    val onVideoClick: (MessageModel, String?, String?) -> Unit =
        remember(component, scrollState) {
            { msg, path, caption ->
                if (!currentIsVisible.value || currentShowInitialLoading.value || scrollState.isScrollInProgress) {
                    Unit
                } else {
                    val videoContent = msg.content as? MessageContent.Video
                    val supportsStreaming = videoContent?.supportsStreaming ?: false
                    val validPath = path?.takeIf { File(it).exists() }

                    if (validPath != null || supportsStreaming) {
                        currentKeyboardController.value?.hide()
                        currentFocusManager.value.clearFocus()
                        component.onOpenVideo(
                            path = validPath,
                            messageId = msg.id,
                            caption = caption
                        )
                    } else {
                        val fileId = when (val content = msg.content) {
                            is MessageContent.Video -> content.fileId
                            is MessageContent.Gif -> content.fileId
                            else -> 0
                        }
                        if (fileId != 0) {
                            component.onDownloadFile(fileId)
                        }
                    }
                }
            }
        }
    val onDocumentClick: (MessageModel) -> Unit = remember(component) {
        { msg ->
            val document = msg.content as? MessageContent.Document
            if (document != null) {
                val validPath = document.path?.takeIf { File(it).exists() }
                if (validPath != null) {
                    val normalizedPath = validPath.lowercase()
                    if (normalizedPath.endsWith(".jpg") || normalizedPath.endsWith(".png")) {
                        currentKeyboardController.value?.hide()
                        currentFocusManager.value.clearFocus()
                        component.onOpenImages(
                            images = listOf(validPath),
                            captions = listOf(document.caption),
                            startIndex = 0,
                            messageId = msg.id,
                            messageIds = listOf(msg.id)
                        )
                    } else if (normalizedPath.endsWith(".mp4")) {
                        currentKeyboardController.value?.hide()
                        currentFocusManager.value.clearFocus()
                        component.onOpenVideo(
                            path = validPath,
                            messageId = msg.id,
                            caption = document.caption
                        )
                    } else {
                        component.downloadUtils.openFile(validPath)
                    }
                } else {
                    component.onDownloadFile(document.fileId)
                }
            }
            Unit
        }
    }
    val onAudioClick: (MessageModel) -> Unit = remember(component) {
        { msg ->
            val audio = msg.content as? MessageContent.Audio
            if (audio != null) {
                val validPath = audio.path?.takeIf { File(it).exists() }
                if (validPath != null) {
                    currentKeyboardController.value?.hide()
                    currentFocusManager.value.clearFocus()
                    component.onOpenVideo(
                        path = validPath,
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
    val onMessageOptionsClick: (MessageModel, Offset, IntSize, Offset) -> Unit =
        remember(component, onMessageOptionsRequested) {
            { msg, position, size, clickPosition ->
                currentKeyboardController.value?.hide()
                currentFocusManager.value.clearFocus(force = true)
                onMessageOptionsRequested(msg.id, position, size, clickPosition)
            }
        }
    val onGoToReply: (MessageModel) -> Unit = remember(component) {
        { msg -> component.scrollToMessage(msg.id) }
    }
    val onMessagePositionChange: (Offset, IntSize) -> Unit = remember(onMessageAnchorChanged) {
        { position, size -> onMessageAnchorChanged(position, size) }
    }
    val onViaBotClick: (String) -> Unit = remember(component) {
        { botUsername ->
            component.onDraftChange("@$botUsername ")
            component.onInlineQueryChange("", "")
        }
    }
    val toProfile: (Long) -> Unit = remember(component) {
        { userId -> component.toProfile(userId) }
    }
    val onForwardOriginClick: (ForwardInfo) -> Unit = remember(component) {
        { forwardInfo -> component.onForwardOriginClick(forwardInfo) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                onContentRectChanged(
                    Rect(
                        offset = coordinates.positionInWindow(),
                        size = coordinates.size.toSize()
                    )
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
            ChatContentList(
                showNavPadding = false,
                topOverlayPadding = if (state.showTopOverlayPadding || topOverlayHeight > 0.dp) {
                    topOverlayHeight
                } else {
                    0.dp
                },
                state = messageListState,
                component = component,
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                onPhotoDownload = onPhotoDownload,
                onPhotoClick = onPhotoClick,
                onVideoClick = onVideoClick,
                onDocumentClick = onDocumentClick,
                onAudioClick = onAudioClick,
                onMessageOptionsClick = onMessageOptionsClick,
                onGoToReply = onGoToReply,
                selectedMessageId = selectedMessageId,
                onMessagePositionChange = onMessagePositionChange,
                onViaBotClick = onViaBotClick,
                toProfile = toProfile,
                onForwardOriginClick = onForwardOriginClick,
                onLinkPreviewAction = onLinkPreviewAction,
                downloadUtils = component.downloadUtils,
                isAnyViewerOpen = isAnyViewerOpen,
                bottomContentPadding = bottomContentPadding
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
                        onShowAllSearchResultsChanged(false)
                        component.onSearchResultClick(index)
                    },
                    onPrevious = component::onSearchPreviousResult,
                    onNext = component::onSearchNextResult,
                    onToggleShowAll = {
                        onShowAllSearchResultsChanged(!showAllSearchResults)
                    },
                    onToggleFilters = {
                        val nextExpanded = !showSearchFilters
                        onShowSearchFiltersChanged(nextExpanded)
                        if (!nextExpanded) {
                            onShowSearchSenderPickerChanged(false)
                        }
                    },
                    onToggleSenderPicker = {
                        onShowSearchSenderPickerChanged(!showSearchSenderPicker)
                    },
                    onSelectSender = { user: UserModel? ->
                        onShowSearchSenderPickerChanged(false)
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
                        onClick = component::onScrollToBottom,
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
                                            slideOutVertically { height -> -height } + fadeOut()
                                        )
                                    } else {
                                        (slideInVertically { height -> -height } + fadeIn()).togetherWith(
                                            slideOutVertically { height -> height } + fadeOut()
                                        )
                                    }.using(SizeTransform(clip = false))
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
                        onClose = { onRecordingVideoChanged(false) },
                        onVideoRecorded = { file ->
                            onRecordingVideoChanged(false)
                            onVideoRecorded(file)
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = showInitialLoading,
                enter = fadeIn(),
                exit = fadeOut()
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
