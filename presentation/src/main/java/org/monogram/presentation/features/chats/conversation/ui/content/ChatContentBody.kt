package org.monogram.presentation.features.chats.conversation.ui.content

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import org.monogram.presentation.core.util.copyUriToTempMediaFile
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ui.AdvancedCircularRecorderScreen
import org.monogram.presentation.features.chats.conversation.ui.ChatInputBar
import org.monogram.presentation.features.chats.conversation.ui.MessageListShimmer
import org.monogram.presentation.features.chats.conversation.ui.message.LinkPreviewAction
import org.monogram.presentation.features.share.PendingAttachment
import org.monogram.presentation.features.share.inferPendingAttachmentKind
import java.io.File

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
            val attachments = uris.mapNotNull { uri ->
                val copied = context.copyUriToTempMediaFile(uri) ?: return@mapNotNull null
                val path = copied.localPath
                PendingAttachment(
                    localPath = path,
                    kind = inferPendingAttachmentKind(
                        localPath = path,
                        mimeType = copied.mimeType
                    ),
                    deleteAfterUse = true
                )
            }
            if (attachments.isNotEmpty()) {
                component.onStageAttachments((state.stagedAttachments + attachments).distinctBy { it.localPath })
            }
        }

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

    AnimatedContent(
        targetState = when {
            chromeState.showInputBar -> BottomBarContent.Input
            chromeState.showJoinButton -> BottomBarContent.Join
            else -> BottomBarContent.None
        },
        transitionSpec = {
            (slideInVertically(
                animationSpec = tween(durationMillis = 240),
                initialOffsetY = { it / 3 }
            ) + fadeIn(animationSpec = tween(durationMillis = 180)))
                .togetherWith(
                    slideOutVertically(
                        animationSpec = tween(durationMillis = 220),
                        targetOffsetY = { it / 3 }
                    ) + fadeOut(animationSpec = tween(durationMillis = 160))
                )
        },
        label = "ChatBottomBar"
    ) { content ->
        when (content) {
            BottomBarContent.Input -> ChatInputBar(
                state = inputBarState,
                actions = inputBarActions,
                appPreferences = component.appPreferences,
                stickerRepository = component.stickerRepository
            )

            BottomBarContent.Join -> Box(
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

            BottomBarContent.None -> Box(modifier = Modifier.fillMaxWidth())
        }
    }
}

private enum class BottomBarContent {
    Input,
    Join,
    None
}

@Composable
internal fun ChatContentBody(
    state: ChatContentBodyUiState,
    component: ChatComponent,
    scrollState: LazyListState,
    isDragged: Boolean,
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
    showUnreadShortcutButtons: Boolean,
    unreadMentionCount: Int,
    unreadReactionCount: Int,
    showReactions: Boolean,
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
                    content?.fileId?.takeIf { it != 0 }
                        ?.let { component.onDownloadFile(it, userInitiated = true) }
                }
                Unit
            }
        }
    val onVideoClick: (MessageModel, String?, String?) -> Unit =
        remember(component, scrollState) {
            { msg, path, caption ->
                val videoContent = msg.content as? MessageContent.Video
                val supportsStreaming = videoContent?.supportsStreaming ?: false
                val validPath = path?.takeIf { File(it).exists() }

                if (!currentIsVisible.value || currentShowInitialLoading.value) {
                    Unit
                } else if (validPath == null && !supportsStreaming) {
                    val fileId = when (val content = msg.content) {
                        is MessageContent.Video -> content.fileId
                        is MessageContent.Gif -> content.fileId
                        is MessageContent.VideoNote -> content.fileId
                        else -> 0
                    }
                    if (fileId != 0) {
                        component.onDownloadFile(fileId, userInitiated = true)
                    }
                } else if (scrollState.isScrollInProgress) {
                    Unit
                } else {
                    currentKeyboardController.value?.hide()
                    currentFocusManager.value.clearFocus()
                    component.onOpenVideo(
                        path = validPath,
                        messageId = msg.id,
                        caption = caption
                    )
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
                    component.onDownloadFile(document.fileId, userInitiated = true)
                }
            }
            Unit
        }
    }
    val onAudioClick: (MessageModel) -> Unit = remember(component) {
        { msg ->
            when (val content = msg.content) {
                is MessageContent.Audio -> {
                    val validPath = content.path?.takeIf { File(it).exists() }
                    if (validPath != null) {
                        currentKeyboardController.value?.hide()
                        currentFocusManager.value.clearFocus()
                        component.onOpenVideo(
                            path = validPath,
                            messageId = msg.id,
                            caption = content.caption
                        )
                    } else {
                        component.onDownloadFile(content.fileId, userInitiated = true)
                    }
                }

                is MessageContent.Voice -> if (content.path.isNullOrBlank()) {
                    component.onDownloadFile(content.fileId, userInitiated = true)
                }

                else -> Unit
            }
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
    val showMentionButton = showUnreadShortcutButtons && unreadMentionCount > 0
    val showReactionButton = showUnreadShortcutButtons && showReactions && unreadReactionCount > 0
    val showBottomActionStack =
        !state.isSearchActive && (showScrollToBottomButton || showMentionButton || showReactionButton)

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
                isDragged = isDragged,
                groupedMessages = groupedMessages,
                onPhotoDownload = onPhotoDownload,
                onPhotoClick = onPhotoClick,
                onVideoClick = onVideoClick,
                onDocumentClick = onDocumentClick,
                onAudioClick = onAudioClick,
                onMessageOptionsClick = onMessageOptionsClick,
                onGoToReply = onGoToReply,
                onChecklistTaskToggle = { messageId, taskId, isDone ->
                    component.onToggleChecklistTask(messageId, taskId, isDone)
                },
                onChecklistEdit = { message ->
                    component.onOpenChecklistEditor(message)
                },
                onPaidMediaBuy = { message ->
                    component.onOpenInvoice(messageId = message.id)
                },
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
                visible = showBottomActionStack,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    UnreadShortcutButton(
                        visible = showReactionButton,
                        count = unreadReactionCount,
                        contentDescription = stringResource(R.string.cd_reactions),
                        onClick = component::onScrollToNextUnreadReaction,
                        onLongClick = component::onClearUnreadReactions
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddReaction,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    UnreadShortcutButton(
                        visible = showMentionButton,
                        count = unreadMentionCount,
                        contentDescription = stringResource(R.string.cd_mentions),
                        onClick = component::onScrollToNextUnreadMention,
                        onLongClick = component::onClearUnreadMentions
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AlternateEmail,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    UnreadShortcutButton(
                        visible = showScrollToBottomButton,
                        count = state.unreadCount,
                        contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                        onClick = component::onJumpToLatest
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
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
                enter = fadeIn(animationSpec = tween(durationMillis = 180, delayMillis = 40)) +
                        scaleIn(
                            animationSpec = tween(durationMillis = 220),
                            initialScale = 0.985f
                        ),
                exit = fadeOut(animationSpec = tween(durationMillis = 140)) +
                        scaleOut(
                            animationSpec = tween(durationMillis = 140),
                            targetScale = 0.992f
                        )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.14f))
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

@Composable
private fun UnreadShortcutButton(
    visible: Boolean,
    count: Int,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
    ) {
        Box {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .semantics { this.contentDescription = contentDescription }
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }

            AnimatedVisibility(
                visible = count > 0,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-8).dp)
            ) {
                UnreadShortcutBadge(count)
            }
        }
    }
}

@Composable
private fun UnreadShortcutBadge(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
        shadowElevation = 4.dp
    ) {
        AnimatedContent(
            targetState = count,
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
        ) { value ->
            Text(
                text = if (value > 999) "999+" else value.toString(),
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
