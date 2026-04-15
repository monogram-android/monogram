package org.monogram.presentation.features.chats.currentChat

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.ReplyMarkupModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
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
    val chatUiState by component.chatUiState.collectAsState()
    val selectionState by component.selectionState.collectAsState()
    val searchState by component.searchState.collectAsState()
    val appearanceState by component.appearanceState.collectAsState()
    val messagesState by component.messagesState.collectAsState()
    val inputState by component.inputState.collectAsState()
    val pinnedState by component.pinnedState.collectAsState()
    val mediaViewerState by component.mediaViewerState.collectAsState()
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

    // Menu States
    var selectedMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    val transformedMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val originalMessageTexts = remember { mutableStateMapOf<Long, String>() }
    val latestMessagesState = rememberUpdatedState(messagesState.messages)
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
    val isComments = chatUiState.rootMessage != null
    val isForumList = chatUiState.viewAsTopics && chatUiState.currentTopicId == null
    var showScrollToBottomButton by remember { mutableStateOf(false) }
    var lastAutoScrollMessageCount by remember(chatUiState.chatId, chatUiState.currentTopicId) { mutableIntStateOf(0) }

    val isAnyViewerOpen = mediaViewerState.fullScreenImages != null ||
            mediaViewerState.fullScreenVideoPath != null ||
            mediaViewerState.fullScreenVideoMessageId != null ||
            mediaViewerState.youtubeUrl != null ||
            mediaViewerState.instantViewUrl != null ||
            mediaViewerState.miniAppUrl != null ||
            mediaViewerState.webViewUrl != null ||
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
                    isLoadingOlder = messagesState.isLoadingOlder,
                    isLoadingNewer = messagesState.isLoadingNewer,
                    isAtBottom = messagesState.isAtBottom,
                    hasMessages = groupedMessages.isNotEmpty()
                )
                val targetIndex = groupedIndexToLazyIndex(index, leadingItems)

                    scrollState.scrollToMessageIndex(
                        index = targetIndex,
                        align = ScrollAlign.Center,
                        animated = appearanceState.isChatAnimationsEnabled,
                        staged = true
                    )
            }
        } else {
            component.onPinnedMessageClick(msg)
        }
    })

    LaunchedEffect(Unit) {
        isVisible = true
        if (mediaViewerState.fullScreenVideoPath != null || mediaViewerState.fullScreenVideoMessageId != null) {
            component.onDismissVideo()
        }
    }

    LaunchedEffect(messagesState.messages) {
        if (transformedMessageTexts.isEmpty() && originalMessageTexts.isEmpty()) return@LaunchedEffect
        val ids = messagesState.messages.map { it.id }.toSet()
        transformedMessageTexts.keys.toList().forEach { id ->
            if (id !in ids) {
                transformedMessageTexts.remove(id)
                originalMessageTexts.remove(id)
            }
        }
    }

    // Initial Loading Delay logic
    LaunchedEffect(
        messagesState.isLoading,
        messagesState.messages.isEmpty(),
        chatUiState.viewAsTopics,
        chatUiState.currentTopicId,
        chatUiState.isLoadingTopics,
        chatUiState.rootMessage
    ) {
        val isActuallyLoading = if (chatUiState.viewAsTopics && chatUiState.currentTopicId == null) {
            chatUiState.isLoadingTopics && chatUiState.topics.isEmpty()
        } else if (chatUiState.currentTopicId != null) {
            messagesState.isLoading && messagesState.messages.isEmpty() && chatUiState.rootMessage == null
        } else {
            messagesState.isLoading && messagesState.messages.isEmpty()
        }
        if (isActuallyLoading) {
            if (appearanceState.isChatAnimationsEnabled) delay(200)
            showInitialLoading = true
        } else {
            showInitialLoading = false
        }
    }

    // Unified command-based scrolling: restore, jump, bottom.
    LaunchedEffect(messagesState.pendingScrollCommand, isComments) {
        val command = messagesState.pendingScrollCommand ?: return@LaunchedEffect

        val leadingItems = chatContentLeadingItemsCount(
            isComments = isComments,
            showNavPadding = false,
            isLoadingOlder = messagesState.isLoadingOlder,
            isLoadingNewer = messagesState.isLoadingNewer,
            isAtBottom = messagesState.isAtBottom,
            hasMessages = groupedMessages.isNotEmpty()
        )

        when (command) {
            is ChatScrollCommand.RestoreViewport -> {
                if (command.atBottom || command.anchorMessageId == null) {
                    scrollState.scrollToChatBottomStaged(
                        isComments = isComments,
                        animated = false
                    )
                } else {
                    val groupedIndex = groupedMessageIndexById[command.anchorMessageId]
                        ?: awaitGroupedIndex(
                            messageId = command.anchorMessageId,
                            groupedMessageIndexByIdProvider = { groupedMessageIndexById }
                        )
                        ?: -1
                    if (groupedIndex >= 0) {
                        val targetIndex = groupedIndexToLazyIndex(groupedIndex, leadingItems)
                        scrollState.restoreViewportAtIndex(
                            targetIndex = targetIndex,
                            anchorOffsetPx = command.anchorOffsetPx
                        )
                    } else {
                        scrollState.scrollToChatBottomStaged(
                            isComments = isComments,
                            animated = false
                        )
                    }
                }
                component.onScrollCommandConsumed()
            }

            is ChatScrollCommand.JumpToMessage -> {
                val groupedIndex = groupedMessageIndexById[command.messageId]
                    ?: awaitGroupedIndex(
                        messageId = command.messageId,
                        groupedMessageIndexByIdProvider = { groupedMessageIndexById }
                    )
                    ?: -1
                if (groupedIndex >= 0) {
                    val targetIndex = groupedIndexToLazyIndex(groupedIndex, leadingItems)
                    scrollState.scrollToMessageIndex(
                        index = targetIndex,
                        align = command.align,
                        animated = command.animated && appearanceState.isChatAnimationsEnabled,
                        staged = true
                    )
                }
                component.onScrollCommandConsumed()
            }

            is ChatScrollCommand.ScrollToBottom -> {
                scrollState.scrollToChatBottomStaged(
                    isComments = isComments,
                    animated = command.animated && appearanceState.isChatAnimationsEnabled
                )
                component.onScrollCommandConsumed()
            }
        }
    }

    // Unified bottom-status + bottom-button controller with hysteresis/debounce for smoothness.
    LaunchedEffect(
        scrollState,
        isComments,
        isForumList,
        showInitialLoading
    ) {
        var lastReportedBottomState: Boolean? = null
        snapshotFlow {
            BottomVisibilitySnapshot(
                isAtBottom = scrollState.isAtBottom(
                    isComments = isComments,
                    isLatestLoaded = messagesState.isLatestLoaded
                ),
                isNearBottom = scrollState.isNearBottom(
                    isComments = isComments
                ),
                unreadCount = chatUiState.unreadCount
            )
        }
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                if (lastReportedBottomState != snapshot.isAtBottom) {
                    component.onBottomReached(snapshot.isAtBottom)
                    lastReportedBottomState = snapshot.isAtBottom
                }

                val shouldShow = !isForumList &&
                        !showInitialLoading &&
                        (snapshot.unreadCount > 0 || !snapshot.isNearBottom)

                if (shouldShow) {
                    showScrollToBottomButton = true
                } else {
                    delay(120)
                    val keepVisible = snapshot.unreadCount > 0 || !snapshot.isNearBottom
                    if (!keepVisible) {
                        showScrollToBottomButton = false
                    }
                }
            }
    }

    // Save full viewport (anchor + pixel offset) for precise restore after reopen.
    LaunchedEffect(
        scrollState,
        groupedMessages,
        isComments,
        messagesState.isLatestLoaded,
        messagesState.isLoadingOlder,
        messagesState.isLoadingNewer,
        messagesState.isAtBottom
    ) {
        snapshotFlow {
            buildViewportSnapshot(
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                isComments = isComments,
                isLatestLoaded = messagesState.isLatestLoaded,
                isLoadingOlder = messagesState.isLoadingOlder,
                isLoadingNewer = messagesState.isLoadingNewer,
                isAtBottom = messagesState.isAtBottom,
                showNavPadding = false
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(120)
            .collect { viewport ->
                component.updateViewport(viewport)
            }
    }

    DisposableEffect(
        scrollState,
        groupedMessages,
        isComments,
        chatUiState.currentTopicId,
        messagesState.isLatestLoaded,
        messagesState.isLoadingOlder,
        messagesState.isLoadingNewer,
        messagesState.isAtBottom
    ) {
        onDispose {
            val viewport = buildViewportSnapshot(
                scrollState = scrollState,
                groupedMessages = groupedMessages,
                isComments = isComments,
                isLatestLoaded = messagesState.isLatestLoaded,
                isLoadingOlder = messagesState.isLoadingOlder,
                isLoadingNewer = messagesState.isLoadingNewer,
                isAtBottom = messagesState.isAtBottom,
                showNavPadding = false
            )
            if (viewport != null) {
                component.updateViewport(viewport)
            }
        }
    }

    // Performance: Update visible range for repository
    LaunchedEffect(scrollState, groupedMessages, chatUiState.rootMessage) {
        snapshotFlow { scrollState.layoutInfo.visibleItemsInfo }
            .map { visibleItems ->
                val visibleIds = LinkedHashSet<Long>()
                val nearbyIds = LinkedHashSet<Long>()
                if (visibleItems.isNotEmpty()) {
                    val minIndex = visibleItems.minOf { it.index }
                    val maxIndex = visibleItems.maxOf { it.index }

                    visibleItems.forEach { item ->
                        val groupedIndex = if (chatUiState.rootMessage != null) item.index - 1 else item.index
                        groupedMessages.getOrNull(groupedIndex)?.let { grouped ->
                            when (grouped) {
                                is GroupedMessageItem.Single -> visibleIds.add(grouped.message.id)
                                is GroupedMessageItem.Album -> grouped.messages.forEach { message ->
                                    visibleIds.add(message.id)
                                }
                            }
                        }
                    }

                    val nearbyStart = (minIndex - 5).coerceAtLeast(0)
                    val nearbyEnd = maxIndex + 5
                    for (index in nearbyStart..nearbyEnd) {
                        if (index in minIndex..maxIndex) continue
                        val groupedIndex = if (chatUiState.rootMessage != null) index - 1 else index
                        groupedMessages.getOrNull(groupedIndex)?.let { grouped ->
                            when (grouped) {
                                is GroupedMessageItem.Single -> nearbyIds.add(grouped.message.id)
                                is GroupedMessageItem.Album -> grouped.messages.forEach { message ->
                                    nearbyIds.add(message.id)
                                }
                            }
                        }
                    }
                }
                val visibleIdList = visibleIds.toList()
                visibleIdList to nearbyIds.filterNot(visibleIds::contains)
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
    LaunchedEffect(messageCount, messagesState.isLatestLoaded, isComments) {
        val previousMessageCount = lastAutoScrollMessageCount
        lastAutoScrollMessageCount = messageCount

        if (isComments || previousMessageCount == 0 || messageCount <= previousMessageCount) {
            return@LaunchedEffect
        }

        val isAtBottomNow = scrollState.isAtBottom(
            isComments = isComments,
            isLatestLoaded = messagesState.isLatestLoaded
        )
        if ((messagesState.isAtBottom || isAtBottomNow) &&
            !messagesState.isLoading &&
            !messagesState.isLoadingOlder &&
            !messagesState.isLoadingNewer &&
            !scrollState.isScrollInProgress
        ) {
            scrollState.scrollToChatBottomStaged(
                isComments = isComments,
                animated = appearanceState.isChatAnimationsEnabled
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

    LaunchedEffect(chatUiState.showBotCommands, isRecordingVideo) {
        if (chatUiState.showBotCommands || isRecordingVideo) {
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
        if (albumPaths.isNotEmpty()) pendingDocumentPaths = emptyList()
    }


    val contentAlpha by animateFloatAsState(
        targetValue = if (isVisible || !appearanceState.isChatAnimationsEnabled || isOverlay) 1f else 0f,
        animationSpec = if (appearanceState.isChatAnimationsEnabled && !isOverlay) tween(300) else snap(),
        label = "ContentAlpha"
    )
    val contentOffset by animateDpAsState(
        targetValue = if (isVisible || !appearanceState.isChatAnimationsEnabled || isOverlay) 0.dp else 20.dp,
        animationSpec = if (appearanceState.isChatAnimationsEnabled && !isOverlay) tween(300) else snap(),
        label = "ContentOffset"
    )

    val showInputBar by remember(
        chatUiState.isMember,
        chatUiState.isChannel,
        chatUiState.isGroup,
        chatUiState.canWrite,
        chatUiState.currentTopicId,
        selectionState.selectedMessageIds,
        chatUiState.viewAsTopics,
        isRecordingVideo
    ) {
        derivedStateOf {
            (chatUiState.isMember || !chatUiState.isChannel && !chatUiState.isGroup) &&
                    (chatUiState.canWrite || chatUiState.currentTopicId != null) &&
                    !isRecordingVideo &&
                    selectionState.selectedMessageIds.isEmpty() &&
                    (!chatUiState.viewAsTopics || chatUiState.currentTopicId != null)
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var renderPinnedMessagesList by rememberSaveable { mutableStateOf(pinnedState.showPinnedMessagesList) }
    var pendingPinnedSheetAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(pinnedState.showPinnedMessagesList) {
        if (pinnedState.showPinnedMessagesList) {
            renderPinnedMessagesList = true
        }
    }

    val requestPinnedMessagesListDismiss = {
        if (pinnedState.showPinnedMessagesList) {
            component.onDismissPinnedMessages()
        }
    }

    val isCustomBackHandlingEnabled by remember(
        editingPhotoPath,
        editingVideoPath,
        selectedMessageId,
        selectionState.selectedMessageIds,
        chatUiState.currentTopicId,
        chatUiState.showBotCommands,
        chatUiState.restrictUserId,
        pinnedState.showPinnedMessagesList,
        mediaViewerState.fullScreenImages,
        mediaViewerState.fullScreenVideoPath,
        mediaViewerState.fullScreenVideoMessageId,
        mediaViewerState.miniAppUrl,
        mediaViewerState.webViewUrl,
        mediaViewerState.instantViewUrl,
        mediaViewerState.youtubeUrl
    ) {
        derivedStateOf {
            editingPhotoPath != null ||
                    editingVideoPath != null ||
                    selectedMessageId != null ||
                    selectionState.selectedMessageIds.isNotEmpty() ||
                    chatUiState.currentTopicId != null ||
                    chatUiState.showBotCommands ||
                    chatUiState.restrictUserId != null ||
                    pinnedState.showPinnedMessagesList ||
                    mediaViewerState.fullScreenImages != null ||
                    mediaViewerState.fullScreenVideoPath != null ||
                    mediaViewerState.fullScreenVideoMessageId != null ||
                    mediaViewerState.miniAppUrl != null ||
                    mediaViewerState.webViewUrl != null ||
                    mediaViewerState.instantViewUrl != null ||
                    mediaViewerState.youtubeUrl != null
        }
    }
    val selectedCount = selectionState.selectedMessageIds.size
    val selectedMessageIdSet by remember(selectionState.selectedMessageIds) {
        derivedStateOf { selectionState.selectedMessageIds.toHashSet() }
    }
    val canRevokeSelected by remember(messagesState.messages, selectedMessageIdSet) {
        derivedStateOf {
            if (selectedMessageIdSet.isEmpty()) {
                false
            } else {
                messagesState.messages.any { it.id in selectedMessageIdSet && it.canBeDeletedForAllUsers }
            }
        }
    }
    val topBarUiState = remember(
        chatUiState.currentTopicId,
        chatUiState.rootMessage,
        chatUiState.isGroup,
        chatUiState.isChannel,
        chatUiState.isAdmin,
        chatUiState.permissions,
        chatUiState.otherUser,
        chatUiState.currentUser,
        chatUiState.typingAction,
        chatUiState.memberCount,
        chatUiState.onlineCount,
        chatUiState.topics,
        chatUiState.chatTitle,
        chatUiState.chatAvatar,
        chatUiState.chatPersonalAvatar,
        chatUiState.chatEmojiStatus,
        chatUiState.isOnline,
        chatUiState.isVerified,
        chatUiState.isSponsor,
        chatUiState.isWhitelistedInAdBlock,
        chatUiState.isInstalledFromGooglePlay,
        chatUiState.isMuted,
        searchState.isSearchActive,
        searchState.searchQuery,
        pinnedState.pinnedMessage,
        pinnedState.pinnedMessageCount
    ) {
        ChatContentTopBarUiState(
            currentTopicId = chatUiState.currentTopicId,
            rootMessage = chatUiState.rootMessage,
            isGroup = chatUiState.isGroup,
            isChannel = chatUiState.isChannel,
            isAdmin = chatUiState.isAdmin,
            permissions = chatUiState.permissions,
            otherUser = chatUiState.otherUser,
            currentUser = chatUiState.currentUser,
            typingAction = chatUiState.typingAction,
            memberCount = chatUiState.memberCount,
            onlineCount = chatUiState.onlineCount,
            topics = chatUiState.topics,
            chatTitle = chatUiState.chatTitle,
            chatAvatar = chatUiState.chatAvatar,
            chatPersonalAvatar = chatUiState.chatPersonalAvatar,
            chatEmojiStatus = chatUiState.chatEmojiStatus,
            isOnline = chatUiState.isOnline,
            isVerified = chatUiState.isVerified,
            isSponsor = chatUiState.isSponsor,
            isWhitelistedInAdBlock = chatUiState.isWhitelistedInAdBlock,
            isInstalledFromGooglePlay = chatUiState.isInstalledFromGooglePlay,
            isMuted = chatUiState.isMuted,
            isSearchActive = searchState.isSearchActive,
            searchQuery = searchState.searchQuery,
            pinnedMessage = pinnedState.pinnedMessage,
            pinnedMessageCount = pinnedState.pinnedMessageCount
        )
    }

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
                    ChatContentBackground(state = appearanceState)
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
                        ChatContentTopBar(
                            topBarState = topBarUiState,
                            selectedCount = selectedCount,
                            canRevokeSelected = canRevokeSelected,
                            component = component,
                            contentAlpha = contentAlpha,
                            onBack = {
                                keyboardController?.hide()
                                if (chatUiState.currentTopicId != null) {
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
                    },
                    bottomBar = {
                        if (showInputBar) {
                            val inputReplyMarkup = remember(messagesState.messages) {
                                messagesState.messages.firstOrNull { it.replyMarkup is ReplyMarkupModel.ShowKeyboard }?.replyMarkup
                            }
                            val inputBarState = remember(
                                inputState,
                                pendingMediaPaths,
                                pendingDocumentPaths,
                                inputReplyMarkup,
                                chatUiState.topics,
                                chatUiState.currentTopicId,
                                chatUiState.permissions,
                                chatUiState.slowModeDelay,
                                chatUiState.slowModeDelayExpiresIn,
                                chatUiState.isCurrentUserRestricted,
                                chatUiState.restrictedUntilDate,
                                chatUiState.isAdmin,
                                chatUiState.isChannel,
                                chatUiState.currentUser,
                                chatUiState.isSecretChat
                            ) {
                                ChatInputBarState(
                                    replyMessage = inputState.replyMessage,
                                    editingMessage = inputState.editingMessage,
                                    draftText = inputState.draftText,
                                    pendingMediaPaths = pendingMediaPaths,
                                    pendingDocumentPaths = pendingDocumentPaths,
                                    isClosed = chatUiState.topics.find { it.id.toLong() == chatUiState.currentTopicId }?.isClosed
                                        ?: false,
                                    permissions = chatUiState.permissions,
                                    slowModeDelay = chatUiState.slowModeDelay,
                                    slowModeDelayExpiresIn = chatUiState.slowModeDelayExpiresIn,
                                    isCurrentUserRestricted = chatUiState.isCurrentUserRestricted,
                                    restrictedUntilDate = chatUiState.restrictedUntilDate,
                                    isAdmin = chatUiState.isAdmin,
                                    isChannel = chatUiState.isChannel,
                                    isBot = inputState.isBot,
                                    botCommands = inputState.botCommands,
                                    botMenuButton = inputState.botMenuButton,
                                    replyMarkup = inputReplyMarkup,
                                    mentionSuggestions = inputState.mentionSuggestions,
                                    inlineBotResults = inputState.inlineBotResults,
                                    currentInlineBotUsername = inputState.currentInlineBotUsername,
                                    currentInlineQuery = inputState.currentInlineQuery,
                                    isInlineBotLoading = inputState.isInlineBotLoading,
                                    attachBots = inputState.attachMenuBots,
                                    scheduledMessages = inputState.scheduledMessages,
                                    isPremiumUser = chatUiState.currentUser?.isPremium == true,
                                    isSecretChat = chatUiState.isSecretChat
                                )
                            }

                            val inputBarActions =
                                remember(component, pendingMediaPaths, pendingDocumentPaths) {
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
                                        pendingDocumentPaths = emptyList()
                                    },
                                    onSendDocuments = { paths, caption, captionEntities, options ->
                                        paths.forEachIndexed { index, path ->
                                            component.onSendDocument(
                                                path,
                                                caption = if (index == 0) caption else "",
                                                captionEntities = if (index == 0) captionEntities else emptyList(),
                                                sendOptions = options
                                            )
                                        }
                                        pendingDocumentPaths = emptyList()
                                        pendingMediaPaths = emptyList()
                                    },
                                    onMediaOrderChange = {
                                        pendingMediaPaths = it
                                        if (it.isNotEmpty()) {
                                            pendingDocumentPaths = emptyList()
                                        }
                                    },
                                    onDocumentOrderChange = {
                                        pendingDocumentPaths = it
                                        if (it.isNotEmpty()) {
                                            pendingMediaPaths = emptyList()
                                        }
                                    },
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
                                            if (inputState.isBot) inputState.chatId else 0L
                                        )
                                    },
                                    onOpenMiniApp = { url, name ->
                                        component.onOpenMiniApp(
                                            url,
                                            name,
                                            if (inputState.isBot) inputState.chatId else 0L
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
                                    onSendPoll = { poll ->
                                        component.onSendPoll(poll)
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
                        } else if (!chatUiState.isMember && (chatUiState.isChannel || chatUiState.isGroup)) {
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
                                chatUiState = chatUiState,
                                appearanceState = appearanceState,
                                messagesState = messagesState,
                                selectionState = selectionState,
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
                                            component.onScrollToBottom()
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
                                        visible = chatUiState.unreadCount > 0,
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
                                                targetState = chatUiState.unreadCount,
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
                                        isGroup = chatUiState.isGroup,
                                        isChannel = chatUiState.isChannel
                                    )
                                }
                            }
                        }
                    }
                }
            }


            // Modals & Overlays
            if (renderPinnedMessagesList) {
                PinnedMessagesListSheet(
                    isVisible = pinnedState.showPinnedMessagesList,
                    allPinnedMessages = pinnedState.allPinnedMessages,
                    pinnedMessageCount = pinnedState.pinnedMessageCount,
                    isLoadingPinnedMessages = pinnedState.isLoadingPinnedMessages,
                    isGroup = chatUiState.isGroup,
                    isChannel = chatUiState.isChannel,
                    fontSize = appearanceState.fontSize,
                    letterSpacing = appearanceState.letterSpacing,
                    bubbleRadius = appearanceState.bubbleRadius,
                    stickerSize = appearanceState.stickerSize,
                    autoDownloadMobile = appearanceState.autoDownloadMobile,
                    autoDownloadWifi = appearanceState.autoDownloadWifi,
                    autoDownloadRoaming = appearanceState.autoDownloadRoaming,
                    autoDownloadFiles = appearanceState.autoDownloadFiles,
                    autoplayGifs = appearanceState.autoplayGifs,
                    autoplayVideos = appearanceState.autoplayVideos,
                    onDismissRequest = requestPinnedMessagesListDismiss,
                    onHidden = {
                        renderPinnedMessagesList = false
                        pendingPinnedSheetAction?.invoke()
                        pendingPinnedSheetAction = null
                    },
                    onMessageClick = {
                        pendingPinnedSheetAction = { scrollToMessageState.value(it) }
                        requestPinnedMessagesListDismiss()
                    },
                    onUnpin = { component.onUnpinMessage(it) },
                    onReplyClick = {
                        pendingPinnedSheetAction = { scrollToMessageState.value(it) }
                        requestPinnedMessagesListDismiss()
                    },
                    onReactionClick = { id, r -> component.onSendReaction(id, r) },
                    downloadUtils = component.downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )
            }

            inputState.selectedStickerSet?.let { stickerSet ->
                StickerSetSheet(
                    stickerSet = stickerSet,
                    onDismiss = { component.onDismissStickerSet() },
                    onStickerClick = { component.onSendSticker(it) }
                )
            }

            if (chatUiState.showPollVoters) {
                PollVotersSheet(
                    voters = chatUiState.pollVoters,
                    isLoading = chatUiState.isPollVotersLoading,
                    onUserClick = {
                        component.onDismissVoters()
                        component.toProfile(it)
                    },
                    onDismiss = { component.onDismissVoters() }
                )
            }

            if (chatUiState.showBotCommands) {
                BotCommandsSheet(
                    commands = inputState.botCommands,
                    onCommandClick = { component.onBotCommandClick(it) },
                    onDismiss = { component.onDismissBotCommands() }
                )
            }

            ChatContentViewers(
                chatUiState = chatUiState,
                appearanceState = appearanceState,
                messagesState = messagesState,
                mediaViewerState = mediaViewerState,
                component = component,
                localClipboard = localClipboard
            )

            selectedMessage?.let { msg ->
                ChatMessageOptionsMenu(
                    chatUiState = chatUiState,
                    appearanceState = appearanceState,
                    pinnedState = pinnedState,
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
                        if (!originalMessageTexts.containsKey(msg.id)) {
                            return@ChatMessageOptionsMenu
                        }
                        transformedMessageTexts.remove(msg.id)
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

            if (chatUiState.showReportDialog) {
                ReportChatDialog(
                    onDismiss = { component.onDismissReportDialog() },
                    onReasonSelected = { component.onReportReasonSelected(it) }
                )
            }

            if (chatUiState.restrictUserId != null) {
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
                else if (selectionState.selectedMessageIds.isNotEmpty()) component.onClearSelection()
                else if (selectedMessageId != null) selectedMessageId = null
                else if (chatUiState.showBotCommands) component.onDismissBotCommands()
                else if (chatUiState.restrictUserId != null) component.onDismissRestrictDialog()
                else if (pinnedState.showPinnedMessagesList && !isAnyViewerOpen) requestPinnedMessagesListDismiss()
                else if (mediaViewerState.fullScreenImages != null) component.onDismissImages()
                else if (mediaViewerState.fullScreenVideoPath != null || mediaViewerState.fullScreenVideoMessageId != null) component.onDismissVideo()
                else if (mediaViewerState.instantViewUrl != null) component.onDismissInstantView()
                else if (mediaViewerState.youtubeUrl != null) component.onDismissYouTube()
                else if (mediaViewerState.miniAppUrl != null) component.onDismissMiniApp()
                else if (mediaViewerState.webViewUrl != null) component.onDismissWebView()
                else if (chatUiState.currentTopicId != null) component.onTopicClick(0)
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

private suspend fun LazyListState.scrollToMessageIndex(
    index: Int,
    align: ScrollAlign,
    animated: Boolean,
    staged: Boolean
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    val boundedIndex = index.coerceIn(0, total - 1)
    val distance = abs(firstVisibleItemIndex - boundedIndex)

    if (staged && distance > 20) {
        val coarseIndex = when {
            boundedIndex > firstVisibleItemIndex -> (boundedIndex - 10).coerceAtLeast(0)
            boundedIndex < firstVisibleItemIndex -> (boundedIndex + 10).coerceAtMost(total - 1)
            else -> boundedIndex
        }
        scrollToItem(coarseIndex)
    }

    scrollToItem(boundedIndex)

    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundedIndex } ?: return
    val viewportStart = layoutInfo.viewportStartOffset
    val viewportEnd = layoutInfo.viewportEndOffset
    val viewportCenter = (viewportStart + viewportEnd) / 2

    val targetPosition = when (align) {
        ScrollAlign.Start -> viewportStart
        ScrollAlign.Center -> viewportCenter - (itemInfo.size / 2)
        ScrollAlign.End -> viewportEnd - itemInfo.size
    }
    val delta = (itemInfo.offset - targetPosition).toFloat()

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

private suspend fun LazyListState.scrollToChatBottomStaged(
    isComments: Boolean,
    animated: Boolean
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return

    val targetIndex = if (isComments) total - 1 else 0
    val distance = abs(firstVisibleItemIndex - targetIndex)

    if (distance > 24) {
        val coarse = if (isComments) {
            (targetIndex - 8).coerceAtLeast(0)
        } else {
            (targetIndex + 8).coerceAtMost(total - 1)
        }
        scrollToItem(coarse)
    }

    if (animated) {
        animateScrollToItem(targetIndex)
    } else {
        scrollToItem(targetIndex)
    }

    val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (targetInfo != null) {
        val delta = if (isComments) {
            ((targetInfo.offset + targetInfo.size) - layoutInfo.viewportEndOffset).toFloat()
        } else {
            (targetInfo.offset - layoutInfo.viewportStartOffset).toFloat()
        }
        if (abs(delta) > 1f) {
            scrollBy(delta)
        }
    }

    scrollToItem(targetIndex)
}

private suspend fun awaitGroupedIndex(
    messageId: Long,
    groupedMessageIndexByIdProvider: () -> Map<Long, Int>,
    timeoutMs: Long = 1200L
): Int? {
    return withTimeoutOrNull(timeoutMs) {
        snapshotFlow { groupedMessageIndexByIdProvider()[messageId] }
            .filterNotNull()
            .first()
    }
}

private suspend fun LazyListState.restoreViewportAtIndex(
    targetIndex: Int,
    anchorOffsetPx: Int
) {
    val total = layoutInfo.totalItemsCount
    if (total <= 0) return
    val boundedIndex = targetIndex.coerceIn(0, total - 1)

    scrollToItem(boundedIndex)
    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == boundedIndex } ?: return
    val viewportStart = layoutInfo.viewportStartOffset
    val desiredOffset = viewportStart + anchorOffsetPx
    val delta = (itemInfo.offset - desiredOffset).toFloat()

    if (abs(delta) > 1f) {
        scrollBy(delta)
    }
}

private fun buildViewportSnapshot(
    scrollState: LazyListState,
    groupedMessages: List<GroupedMessageItem>,
    isComments: Boolean,
    isLatestLoaded: Boolean,
    isLoadingOlder: Boolean,
    isLoadingNewer: Boolean,
    isAtBottom: Boolean,
    showNavPadding: Boolean
): ChatViewportCacheEntry? {
    if (groupedMessages.isEmpty()) {
        return ChatViewportCacheEntry(atBottom = true)
    }

    val atBottomNow = scrollState.isAtBottom(
        isComments = isComments,
        isLatestLoaded = isLatestLoaded
    )
    if (atBottomNow) {
        return ChatViewportCacheEntry(atBottom = true)
    }

    val leadingItems = chatContentLeadingItemsCount(
        isComments = isComments,
        showNavPadding = showNavPadding,
        isLoadingOlder = isLoadingOlder,
        isLoadingNewer = isLoadingNewer,
        isAtBottom = isAtBottom,
        hasMessages = groupedMessages.isNotEmpty()
    )
    val info = scrollState.layoutInfo
    val anchorItem = info.visibleItemsInfo.firstOrNull { itemInfo ->
        val groupedIndex = lazyIndexToGroupedIndex(itemInfo.index, leadingItems)
        groupedIndex in groupedMessages.indices
    } ?: return null

    val groupedIndex = lazyIndexToGroupedIndex(anchorItem.index, leadingItems)
    val anchorMessageId = groupedMessages.getOrNull(groupedIndex)?.firstMessageId ?: return null

    return ChatViewportCacheEntry(
        anchorMessageId = anchorMessageId,
        anchorOffsetPx = anchorItem.offset - info.viewportStartOffset,
        atBottom = false
    )
}
