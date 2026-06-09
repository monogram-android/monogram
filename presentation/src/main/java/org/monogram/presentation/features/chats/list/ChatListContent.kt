package org.monogram.presentation.features.chats.list

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.TopicModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.domain.repository.ForwardTarget
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.features.chats.conversation.ui.message.getEmojiFontFamily
import org.monogram.presentation.features.chats.list.components.AccountMenu
import org.monogram.presentation.features.chats.list.components.ArchiveHeaderCard
import org.monogram.presentation.features.chats.list.components.ChatListItem
import org.monogram.presentation.features.chats.list.components.ChatListShimmer
import org.monogram.presentation.features.chats.list.components.ChatListTopBar
import org.monogram.presentation.features.chats.list.components.EmptyStateView
import org.monogram.presentation.features.chats.list.components.FolderTabs
import org.monogram.presentation.features.chats.list.components.MessageSearchItem
import org.monogram.presentation.features.chats.list.components.PermissionRequestSheet
import org.monogram.presentation.features.chats.list.components.SelectionTopBar
import org.monogram.presentation.features.instantview.InstantViewer
import org.monogram.presentation.features.stickers.ui.menu.ActionMenuPopup
import org.monogram.presentation.features.stickers.ui.menu.EmojisGrid
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.webapp.MiniAppViewer
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatListContent(component: ChatListComponent) {
    val stickerRepository: StickerRepository = koinInject()
    val uiState by component.uiState.collectAsState()
    val foldersState by component.foldersState.collectAsState()
    val chatsState by component.chatsState.collectAsState()
    val selectionState by component.selectionState.collectAsState()
    val searchState by component.searchState.collectAsState()

    val scope = rememberCoroutineScope()

    val haptic = LocalHapticFeedback.current

    var showAccountMenu by remember { mutableStateOf(false) }
    var showChatListActionsMenu by remember { mutableStateOf(false) }
    var showSelectionActionsMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var showDeleteChatsSheet by remember { mutableStateOf(false) }
    var showLeaveChatSheet by remember { mutableStateOf(false) }
    var showClearHistorySheet by remember { mutableStateOf(false) }
    var forwardCommentText by remember { mutableStateOf("") }
    var forwardSendCopy by remember { mutableStateOf(false) }
    var forwardRemoveCaption by remember { mutableStateOf(false) }
    var isForwardPanelCollapsed by remember { mutableStateOf(false) }
    var statusAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val statusMenuTransitionState = remember { MutableTransitionState(false) }

    LaunchedEffect(showStatusMenu) {
        statusMenuTransitionState.targetState = showStatusMenu
    }

    LaunchedEffect(uiState.isForwarding, selectionState.selectedForwardTargets.isEmpty()) {
        if (!uiState.isForwarding || selectionState.selectedForwardTargets.isEmpty()) {
            isForwardPanelCollapsed = false
        }
    }

    val isPermissionRequested by component.appPreferences.isPermissionRequested.collectAsState()
    var showPermissionRequest by remember { mutableStateOf(!isPermissionRequested) }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTabletInterfaceEnabled = LocalTabletInterfaceEnabled.current
    val isTablet =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) && isTabletInterfaceEnabled

    val isCustomBackHandlingEnabled =
        searchState.isSearchActive ||
                selectionState.selectedChatIds.isNotEmpty() ||
                selectionState.selectedForwardTargets.isNotEmpty() ||
                selectionState.forwardTopicPickerChatId != null ||
                foldersState.selectedFolderId == -2 ||
                uiState.isForwarding ||
                uiState.instantViewUrl != null ||
                uiState.webAppUrl != null ||
                uiState.webViewUrl != null ||
                showStatusMenu ||
                showChatListActionsMenu ||
                showSelectionActionsMenu

    BackHandler(enabled = isCustomBackHandlingEnabled) {
        if (showChatListActionsMenu) {
            showChatListActionsMenu = false
        } else if (showSelectionActionsMenu) {
            showSelectionActionsMenu = false
        } else if (showStatusMenu) {
            showStatusMenu = false
        } else {
            component.handleBack()
        }
    }

    val showAllChatsFolder by component.appPreferences.showAllChatsFolder.collectAsState()
    val visibleFolders = remember(foldersState.folders, showAllChatsFolder) {
        val userFolders = foldersState.folders.filter { it.id >= 0 }
        if (showAllChatsFolder || userFolders.isEmpty()) {
            foldersState.folders
        } else {
            foldersState.folders.filterNot { it.id == -1 }
        }
    }

    val pagerState = rememberPagerState(
        initialPage = visibleFolders.indexOfFirst { it.id == foldersState.selectedFolderId }
            .coerceAtLeast(0),
        pageCount = { visibleFolders.size }
    )

    LaunchedEffect(foldersState.selectedFolderId, visibleFolders) {
        val index =
            visibleFolders.indexOfFirst { it.id == foldersState.selectedFolderId }.coerceAtLeast(0)
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }

    LaunchedEffect(pagerState.currentPage, visibleFolders, foldersState.selectedFolderId) {
        if (visibleFolders.isNotEmpty() && pagerState.currentPage < visibleFolders.size) {
            val folderId = visibleFolders[pagerState.currentPage].id
            if (foldersState.selectedFolderId != folderId && foldersState.selectedFolderId != -2) {
                component.onFolderClicked(folderId)
            }
        }
    }

    val density = LocalDensity.current
    val tabsHeight = if (visibleFolders.size > 1) 56.dp else 10.dp
    val archiveItemHeight = 78.dp
    val tabsHeightPx = with(density) { tabsHeight.toPx() }
    val archiveItemHeightPx = with(density) { archiveItemHeight.toPx() }

    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    var archiveRevealPx by remember { mutableFloatStateOf(0f) }

    var revealAnimationJob by remember { mutableStateOf<Job?>(null) }
    var headerAnimationJob by remember { mutableStateOf<Job?>(null) }

    var hasVibrated by remember { mutableStateOf(false) }
    var canRevealArchive by remember { mutableStateOf(true) }

    val currentFolder = visibleFolders.getOrNull(pagerState.currentPage)
    val isMainFolder = currentFolder?.id == -1

    val isArchivePersistent = uiState.isArchivePinned && (uiState.isArchiveAlwaysVisible || isMainFolder)
    val canShowArchive = isArchivePersistent || isMainFolder
    val currentFolderChats = foldersState.chatsByFolder[foldersState.selectedFolderId].orEmpty()
    val hasUnreadInCurrentFolder = remember(currentFolderChats) {
        currentFolderChats.any(::hasChatListUnreadState)
    }

    val lastArchivePersistent = remember { mutableStateOf(isArchivePersistent) }

    LaunchedEffect(isArchivePersistent) {
        if (isArchivePersistent == lastArchivePersistent.value) return@LaunchedEffect
        lastArchivePersistent.value = isArchivePersistent

        if (isArchivePersistent) {
            val startOffset = if (archiveRevealPx > 0f) {
                archiveRevealPx - archiveItemHeightPx
            } else {
                headerOffsetPx - archiveItemHeightPx
            }

            headerOffsetPx = startOffset
            archiveRevealPx = 0f

            headerAnimationJob?.cancel()
            headerAnimationJob = launch {
                animate(initialValue = headerOffsetPx, targetValue = 0f) { value, _ ->
                    headerOffsetPx = value
                }
            }
        } else {
            val currentVisibleArchiveHeight = (archiveItemHeightPx + headerOffsetPx).coerceAtLeast(0f)
            archiveRevealPx = currentVisibleArchiveHeight

            headerOffsetPx = (headerOffsetPx + archiveItemHeightPx).coerceAtMost(0f)

            if (archiveRevealPx > 0f) {
                revealAnimationJob?.cancel()
                revealAnimationJob = launch {
                    animate(initialValue = archiveRevealPx, targetValue = 0f) { value, _ ->
                        archiveRevealPx = value
                    }
                }
            }
        }
    }

    val isArchiveRevealed = archiveRevealPx > 0f && !isArchivePersistent
    BackHandler(enabled = isArchiveRevealed) {
        revealAnimationJob?.cancel()
        revealAnimationJob = scope.launch {
            animate(initialValue = archiveRevealPx, targetValue = 0f) { value, _ ->
                archiveRevealPx = value
            }
        }
    }

    val nestedScrollConnection = remember(isArchivePersistent, canShowArchive, uiState.isArchiveAlwaysVisible, tabsHeightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    revealAnimationJob?.cancel()
                    headerAnimationJob?.cancel()
                }

                val delta = available.y

                if (delta < 0) {
                    if (canShowArchive && !isArchivePersistent && archiveRevealPx > 0f) {
                        val oldReveal = archiveRevealPx
                        val newReveal = (oldReveal + delta).coerceAtLeast(0f)
                        archiveRevealPx = newReveal

                        if (newReveal == 0f) hasVibrated = false

                        return Offset(0f, newReveal - oldReveal)
                    }

                    val maxHide = if (isArchivePersistent) {
                        -archiveItemHeightPx
                    } else {
                        0f
                    }

                    val oldOffset = headerOffsetPx
                    val newOffset = (oldOffset + delta).coerceIn(maxHide, 0f)
                    headerOffsetPx = newOffset

                    if (source == NestedScrollSource.UserInput && newOffset < 0f) {
                        canRevealArchive = false
                    }

                    if (abs(newOffset - oldOffset) > 0.5f) {
                        return Offset(0f, newOffset - oldOffset)
                    }
                } else {
                    if (headerOffsetPx < 0f) {
                        if (source == NestedScrollSource.UserInput) {
                            canRevealArchive = false
                        }

                        var limit = 0f
                        if (isArchivePersistent && !uiState.isArchiveAlwaysVisible) {
                            limit = -archiveItemHeightPx
                        }

                        if (headerOffsetPx < limit) {
                            val oldOffset = headerOffsetPx
                            val newOffset = (oldOffset + delta).coerceAtMost(limit)
                            headerOffsetPx = newOffset
                            return Offset(0f, newOffset - oldOffset)
                        }
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    revealAnimationJob?.cancel()
                    headerAnimationJob?.cancel()
                }

                val delta = available.y
                if (delta > 0) {
                    if (source == NestedScrollSource.UserInput && (consumed.y > 0f || headerOffsetPx < 0f)) {
                        canRevealArchive = false
                    }

                    if (headerOffsetPx < 0f) {
                        val oldOffset = headerOffsetPx
                        val newOffset = (oldOffset + delta).coerceAtMost(0f)
                        headerOffsetPx = newOffset
                        return Offset(0f, newOffset - oldOffset)
                    }
                    else if (canShowArchive && !isArchivePersistent && headerOffsetPx == 0f && canRevealArchive && source == NestedScrollSource.UserInput) {
                        val oldReveal = archiveRevealPx
                        val newReveal = (oldReveal + delta * 0.5f).coerceIn(0f, archiveItemHeightPx)
                        archiveRevealPx = newReveal

                        if (newReveal >= archiveItemHeightPx && !hasVibrated) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasVibrated = true
                        } else if (newReveal < archiveItemHeightPx) {
                            hasVibrated = false
                        }

                        return Offset(0f, (newReveal - oldReveal) * 2f)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                canRevealArchive = true

                if (canShowArchive && !isArchivePersistent && archiveRevealPx > 0f && archiveRevealPx < archiveItemHeightPx) {
                    val target = if (archiveRevealPx > archiveItemHeightPx / 2) archiveItemHeightPx else 0f

                    if (target == 0f) hasVibrated = false

                    revealAnimationJob = scope.launch {
                        animate(initialValue = archiveRevealPx, targetValue = target) { value, _ ->
                            archiveRevealPx = value
                        }
                    }
                }

                val maxHide = if (isArchivePersistent) -archiveItemHeightPx else 0f

                if (headerOffsetPx < 0f && headerOffsetPx > maxHide) {
                    val target = if (isArchivePersistent) {
                        if (headerOffsetPx > -archiveItemHeightPx / 2) 0f else -archiveItemHeightPx
                    } else {
                        0f
                    }
                    headerAnimationJob = scope.launch {
                        animate(initialValue = headerOffsetPx, targetValue = target) { value, _ ->
                            headerOffsetPx = value
                        }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val isFabExpanded by remember { derivedStateOf { headerOffsetPx > -10f } }

    var cachedStatusEmojiPath by remember(uiState.currentUser?.id) {
        mutableStateOf(uiState.currentUser?.statusEmojiPath)
    }

    LaunchedEffect(uiState.currentUser?.id, uiState.currentUser?.statusEmojiPath) {
        val statusEmojiPath = uiState.currentUser?.statusEmojiPath
        if (!statusEmojiPath.isNullOrBlank()) {
            cachedStatusEmojiPath = statusEmojiPath
        }
    }

    LaunchedEffect(uiState.currentUser?.statusEmojiId) {
        val statusEmojiId = uiState.currentUser?.statusEmojiId ?: 0L
        if (statusEmojiId != 0L) {
            stickerRepository.getCustomEmojiFile(statusEmojiId).firstOrNull()?.let { resolvedPath ->
                if (!resolvedPath.isNullOrBlank()) {
                    cachedStatusEmojiPath = resolvedPath
                }
            }
        }
    }

    val currentUser = remember(uiState.currentUser, cachedStatusEmojiPath) {
        uiState.currentUser?.let { user ->
            if (user.statusEmojiId != 0L && user.statusEmojiPath.isNullOrBlank() && !cachedStatusEmojiPath.isNullOrBlank()) {
                user.copy(statusEmojiPath = cachedStatusEmojiPath)
            } else {
                user
            }
        }
    }

    if (showAccountMenu) {
        AccountMenu(
            user = currentUser,
            attachMenuBots = uiState.attachMenuBots,
            onDismiss = { showAccountMenu = false },
            onSavedMessagesClick = {
                currentUser?.id?.let { component.onChatClicked(it) }
            },
            onSettingsClick = { component.onSettingsClicked() },
            onHelpClick = {
                component.onOpenInstantView("https://telegram.org/faq#general-questions")
            },
            onProfileClick = {
                currentUser?.id?.let { component.onProfileClicked(it) }
            },
            updateState = uiState.updateState,
            onUpdateClick = { component.onUpdateClicked() },
            onBotClick = { bot ->
                component.onOpenWebApp(
                    url = uiState.botWebAppUrl ?: "",
                    botUserId = bot.botUserId,
                    botName = uiState.botWebAppName ?: bot.name
                )
            }
        )
    }

    if (showPermissionRequest) {
        PermissionRequestSheet(onDismiss = {
            showPermissionRequest = false
            component.appPreferences.setPermissionRequested(true)
        })
    }

    val scrollStates = remember { mutableMapOf<Int, LazyListState>() }
    val firstFolderTransitionCompleted = remember { mutableStateMapOf<Int, Boolean>() }

    val context = LocalContext.current
    val emojiStyle by component.appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }
    val messageLines by component.appPreferences.chatListMessageLines.collectAsState()
    val showPhotos by component.appPreferences.showChatListPhotos.collectAsState()

    val onChatClicked = remember(component) { { id: Long -> component.onChatClicked(id) } }
    val onChatLongClicked = remember(component) { { id: Long -> component.onChatLongClicked(id) } }
    val selectedForwardChatIds = remember(selectionState.selectedForwardTargets) {
        selectionState.selectedForwardTargets.map { it.chatId }.toSet()
    }
    val statusMenuScrimAlpha by animateFloatAsState(
        targetValue = if (statusMenuTransitionState.targetState) 0.18f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "ChatListStatusMenuScrimAlpha"
    )
    val topBarMode = remember(
        selectionState.selectedChatIds,
        uiState.isForwarding,
        foldersState.selectedFolderId,
        searchState.isSearchActive,
        currentUser,
        uiState.connectionStatus,
        uiState.isProxyEnabled
    ) {
        when {
            selectionState.selectedChatIds.isNotEmpty() && !uiState.isForwarding -> {
                ChatListTopBarMode.Selection(isInArchive = foldersState.selectedFolderId == -2)
            }

            uiState.isForwarding -> ChatListTopBarMode.Forwarding
            foldersState.selectedFolderId == -2 && !searchState.isSearchActive -> ChatListTopBarMode.Archive
            else -> ChatListTopBarMode.Default(
                user = currentUser,
                connectionStatus = uiState.connectionStatus,
                isProxyEnabled = uiState.isProxyEnabled,
                isSearchActive = searchState.isSearchActive
            )
        }
    }

    Scaffold(
        containerColor = if (isTablet) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = topBarMode,
                    label = "TopBarSelectionAnimation",
                    transitionSpec = { fadeIn() togetherWith fadeOut() }
                ) { mode ->
                    when (mode) {
                        is ChatListTopBarMode.Selection -> {
                            SelectionModeTopBar(
                                selectionState = selectionState,
                                onClearSelection = component::clearSelection,
                                onMoreClick = { showSelectionActionsMenu = true }
                            )
                        }

                        ChatListTopBarMode.Forwarding -> {
                            ForwardingModeTopBar(onBackClick = component::handleBack)
                        }

                        ChatListTopBarMode.Archive -> {
                            ArchiveModeTopBar(
                                onBackClick = component::handleBack,
                                onSearchClick = component::onSearchToggle,
                                onMoreClick = { showChatListActionsMenu = true }
                            )
                        }

                        is ChatListTopBarMode.Default -> {
                            ChatListTopBar(
                                user = mode.user,
                                connectionStatus = mode.connectionStatus,
                                isProxyEnabled = mode.isProxyEnabled,
                                onRetryConnection = { component.retryConnection() },
                                onProxySettingsClick = { component.onProxySettingsClicked() },
                                isSearchActive = mode.isSearchActive,
                                searchQuery = searchState.searchQuery,
                                onSearchQueryChange = component::onSearchQueryChange,
                                onSearchToggle = component::onSearchToggle,
                                onStatusClick = { anchorBounds ->
                                    statusAnchorBounds = anchorBounds ?: statusAnchorBounds
                                    showStatusMenu = true
                                },
                                onActionsClick = { showChatListActionsMenu = true },
                                onMenuClick = { showAccountMenu = true }
                            )
                        }
                    }
                }

                if (uiState.connectionStatus == ConnectionStatus.Connecting || uiState.connectionStatus == ConnectionStatus.Updating || uiState.connectionStatus == ConnectionStatus.ConnectingToProxy) {
                    Column {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                val isMainView = !searchState.isSearchActive && foldersState.selectedFolderId != -2

                if (isMainView) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) {
                                val visibleArchiveHeight = if (isArchivePersistent) {
                                    (archiveItemHeightPx + headerOffsetPx).coerceAtLeast(0f)
                                } else if (isMainFolder) {
                                    archiveRevealPx
                                } else {
                                    0f
                                }
                                val visibleTabsHeight = tabsHeightPx
                                (visibleArchiveHeight + visibleTabsHeight).toDp()
                            })
                            .clip(RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp)),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(with(density) {
                                        if (isArchivePersistent) {
                                            (archiveItemHeightPx + headerOffsetPx).coerceAtLeast(0f)
                                                .toDp()
                                        } else if (isMainFolder) {
                                            archiveRevealPx.toDp()
                                        } else {
                                            0.dp
                                        }
                                    })
                                    .graphicsLayer {
                                        alpha = if (isArchivePersistent) {
                                            ((archiveItemHeightPx + headerOffsetPx) / archiveItemHeightPx).coerceIn(
                                                0f,
                                                1f
                                            )
                                        } else if (isMainFolder) {
                                            (archiveRevealPx / archiveItemHeightPx).coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                        clip = true
                                    }
                            ) {
                                Column(
                                    Modifier.offset {
                                        if (isArchivePersistent) {
                                            IntOffset(0, headerOffsetPx.roundToInt())
                                        } else {
                                            IntOffset(0, 0)
                                        }
                                    }
                                ) {
                                    ArchiveHeaderCard(
                                        isPinned = uiState.isArchivePinned,
                                        onClick = { component.onFolderClicked(-2) },
                                        onLongClick = { component.onArchivePinToggle() }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                            }

                            if (visibleFolders.size > 1) {
                                FolderTabs(
                                    modifier = Modifier,
                                    folders = visibleFolders,
                                    pagerState = pagerState,
                                    onTabClick = { index ->
                                        if (pagerState.currentPage == index) {
                                            val folderId = visibleFolders[index].id
                                            scope.launch {
                                                scrollStates[folderId]?.animateScrollToItem(0)
                                            }
                                        } else {
                                            scope.launch {
                                                pagerState.animateScrollToPage(index)
                                            }
                                        }
                                    },
                                    onEditClick = { component.onEditFoldersClicked() },
                                    onEditFolderClick = { folder -> component.onEditFolder(folder.id) },
                                    onDeleteFolderClick = { folder -> component.onDeleteFolder(folder.id) },
                                    onReorderFoldersClick = { component.onEditFoldersClicked() }
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isTablet) {
                AnimatedVisibility(
                    visible = !searchState.isSearchActive && foldersState.selectedFolderId != -2 && !uiState.isForwarding,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { component.onNewChatClicked() },
                        icon = { Icon(Icons.Rounded.Edit, null) },
                        text = { Text(stringResource(R.string.new_chat_fab)) },
                        expanded = isFabExpanded,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize(),
            shape = if (isTablet) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = if (isTablet) Color.Transparent else MaterialTheme.colorScheme.surface
        ) {
            ChatListBody(
                component = component,
                foldersState = foldersState,
                chatsState = chatsState,
                selectionState = selectionState,
                selectedForwardChatIds = selectedForwardChatIds,
                isForwarding = uiState.isForwarding,
                searchState = searchState,
                visibleFolders = visibleFolders,
                pagerState = pagerState,
                scrollStates = scrollStates,
                firstFolderTransitionCompleted = firstFolderTransitionCompleted,
                currentUserId = uiState.currentUser?.id,
                isTablet = isTablet,
                emojiFontFamily = emojiFontFamily,
                messageLines = messageLines,
                showPhotos = showPhotos,
                onChatClicked = onChatClicked,
                onChatLongClicked = onChatLongClicked
            )
        }
    }

    ForwardTopicPickerSheet(
        chatId = selectionState.forwardTopicPickerChatId,
        title = selectionState.forwardTopicPickerChatTitle,
        topics = selectionState.forwardTopics,
        isLoading = selectionState.isLoadingForwardTopics,
        onTopicSelected = component::onForwardTopicSelected,
        onDismiss = component::onDismissForwardTopicPicker
    )

    ForwardConfirmationPanel(
        visible = uiState.isForwarding && selectionState.selectedForwardTargets.isNotEmpty(),
        targets = selectionState.selectedForwardTargets,
        selectedChats = selectionState.selectedForwardChats,
        commentText = forwardCommentText,
        onCommentTextChange = { forwardCommentText = it },
        sendCopy = forwardSendCopy,
        onSendCopyChange = {
            forwardSendCopy = it
            if (!it) forwardRemoveCaption = false
        },
        removeCaption = forwardRemoveCaption,
        onRemoveCaptionChange = { forwardRemoveCaption = it },
        onRemoveTarget = component::onRemoveForwardTarget,
        isCollapsed = isForwardPanelCollapsed,
        onCollapsedChange = { isForwardPanelCollapsed = it },
        onSend = {
            component.onConfirmForwarding(
                sendCopy = forwardSendCopy,
                removeCaption = forwardRemoveCaption,
                commentText = forwardCommentText
            )
        }
    )

    if (statusMenuTransitionState.currentState || statusMenuTransitionState.targetState) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = statusMenuScrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showStatusMenu = false }
        ) {
            val menuHorizontalPadding = 16.dp
            val menuAnchorOverlap = 12.dp
            val menuBottomMargin = 8.dp
            val menuWidth = (maxWidth - menuHorizontalPadding * 2).coerceAtLeast(280.dp)
            val menuHeightLimit = 520.dp

            val rootWidthPx = constraints.maxWidth.coerceAtLeast(1)
            val rootHeightPx = constraints.maxHeight.coerceAtLeast(1)
            val menuWidthPx = with(density) { menuWidth.roundToPx() }
            val horizontalPaddingPx = with(density) { menuHorizontalPadding.roundToPx() }
            val anchorOverlapPx = with(density) { menuAnchorOverlap.roundToPx() }
            val menuBottomMarginPx = with(density) { menuBottomMargin.roundToPx() }
            val minMenuVisibleHeightPx = with(density) { 220.dp.roundToPx() }

            val statusBarTopPx = WindowInsets.statusBars.getTop(density)
            val navigationBarBottomPx = WindowInsets.navigationBars.getBottom(density)
            val minTopPx = statusBarTopPx + with(density) { 8.dp.roundToPx() }
            val fallbackTopPx = statusBarTopPx + with(density) { 56.dp.roundToPx() }

            val desiredTopPx = statusAnchorBounds
                ?.let { it.bottom.roundToInt() - anchorOverlapPx }
                ?: fallbackTopPx
            val desiredLeftPx = statusAnchorBounds
                ?.let { ((it.left + it.right) / 2f - menuWidthPx / 2f).roundToInt() }
                ?: horizontalPaddingPx

            val maxLeftPx = (rootWidthPx - menuWidthPx - horizontalPaddingPx).coerceAtLeast(horizontalPaddingPx)
            val clampedLeftPx = desiredLeftPx.coerceIn(horizontalPaddingPx, maxLeftPx)

            val maxTopPx = (
                    rootHeightPx - navigationBarBottomPx - menuBottomMarginPx - minMenuVisibleHeightPx
                    ).coerceAtLeast(minTopPx)
            val clampedTopPx = desiredTopPx.coerceIn(minTopPx, maxTopPx)

            val maxMenuHeightPx = (
                    rootHeightPx - clampedTopPx - navigationBarBottomPx - menuBottomMarginPx
                    ).coerceAtLeast(minMenuVisibleHeightPx)
            val maxMenuHeightDp = with(density) { maxMenuHeightPx.toDp() }.coerceAtMost(menuHeightLimit)

            AnimatedVisibility(
                visibleState = statusMenuTransitionState,
                enter = fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        slideInVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) { -it / 5 } +
                        scaleIn(
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            initialScale = 0.94f,
                            transformOrigin = TransformOrigin(0.85f, 0f)
                        ),
                exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
                        slideOutVertically(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()) { -it / 6 } +
                        scaleOut(
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            targetScale = 0.97f,
                            transformOrigin = TransformOrigin(0.85f, 0f)
                        ),
                modifier = Modifier.offset { IntOffset(clampedLeftPx, clampedTopPx) }
            ) {
                Surface(
                    modifier = Modifier
                        .width(menuWidth)
                        .heightIn(max = maxMenuHeightDp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    EmojisGrid(
                        onEmojiSelected = { _, sticker ->
                            val customEmojiId = sticker?.customEmojiId
                            if (sticker != null && customEmojiId != null) {
                                if (!sticker.path.isNullOrBlank()) {
                                    cachedStatusEmojiPath = sticker.path
                                }
                                component.onSetEmojiStatus(customEmojiId, null)
                                showStatusMenu = false
                            }
                        },
                        emojiOnlyMode = true,
                        contentPadding = PaddingValues(bottom = 12.dp)
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = uiState.instantViewUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        uiState.instantViewUrl?.let { url ->
            InstantViewer(
                url = url,
                messageRepository = koinInject(),
                fileRepository = koinInject(),
                onDismiss = { component.onDismissInstantView() },
                onOpenWebView = { component.onOpenWebView(it) }
            )
        }
    }

    AnimatedVisibility(
        visible = uiState.webAppUrl != null || uiState.webAppBotId != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val webAppUrl = uiState.webAppUrl
        val botUserId = uiState.webAppBotId
        val botName = uiState.webAppBotName

        Log.d("MiniAppViewer", "webAppUrl: $webAppUrl, botUserId: $botUserId, botName: $botName")

        if (botUserId != null) {
            MiniAppViewer(
                chatId = botUserId,
                botUserId = botUserId,
                baseUrl = webAppUrl ?: "",
                botName = botName ?: stringResource(R.string.mini_app_default_name),
                webAppRepository = koinInject(),
                onDismiss = { component.onDismissWebApp() }
            )
        }
    }

    if (showDeleteChatsSheet) {
        ConfirmationSheet(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.delete_chats_title, selectionState.selectedChatIds.size),
            description = stringResource(R.string.delete_chats_confirmation),
            confirmText = stringResource(R.string.action_delete_chats),
            onConfirm = {
                component.onDeleteSelected()
                showDeleteChatsSheet = false
            },
            onDismiss = { showDeleteChatsSheet = false }
        )
    }

    if (showLeaveChatSheet) {
        ConfirmationSheet(
            icon = Icons.AutoMirrored.Rounded.ExitToApp,
            title = stringResource(R.string.leave_chat_title),
            description = stringResource(R.string.leave_chat_confirmation),
            confirmText = stringResource(R.string.action_leave),
            onConfirm = {
                component.onLeaveSelected()
                showLeaveChatSheet = false
            },
            onDismiss = { showLeaveChatSheet = false }
        )
    }

    if (showClearHistorySheet) {
        ConfirmationSheet(
            icon = Icons.Rounded.CleaningServices,
            title = stringResource(R.string.clear_history_title),
            description = stringResource(R.string.clear_history_confirmation),
            confirmText = stringResource(R.string.action_clear_history),
            onConfirm = {
                component.onClearHistorySelected(revoke = false)
                showClearHistorySheet = false
            },
            onDismiss = { showClearHistorySheet = false }
        )
    }

    ChatListActionsPopup(
        visible = showChatListActionsMenu,
        hasUnreadChats = hasUnreadInCurrentFolder,
        canEditFolders = foldersState.selectedFolderId != -2,
        onDismiss = { showChatListActionsMenu = false },
        onMarkAllRead = {
            showChatListActionsMenu = false
            component.onMarkCurrentFolderRead()
        },
        onEditFolders = {
            showChatListActionsMenu = false
            component.onEditFoldersClicked()
        }
    )

    SelectionActionsPopup(
        visible = showSelectionActionsMenu,
        selectionState = selectionState,
        isInArchive = foldersState.selectedFolderId == -2,
        onDismiss = { showSelectionActionsMenu = false },
        onPin = {
            showSelectionActionsMenu = false
            component.onPinSelected()
        },
        onMute = {
            showSelectionActionsMenu = false
            component.onMuteSelected(!selectionState.allMuted)
        },
        onArchive = {
            showSelectionActionsMenu = false
            component.onArchiveSelected(foldersState.selectedFolderId != -2)
        },
        onToggleRead = {
            showSelectionActionsMenu = false
            component.onToggleReadSelected()
        },
        onClearHistory = {
            showSelectionActionsMenu = false
            showClearHistorySheet = true
        },
        onReport = {
            showSelectionActionsMenu = false
            component.onReportSelected("other")
        },
        onLeave = {
            showSelectionActionsMenu = false
            showLeaveChatSheet = true
        },
        onDelete = {
            showSelectionActionsMenu = false
            showDeleteChatsSheet = true
        }
    )
}

private sealed interface ChatListTopBarMode {
    data class Selection(val isInArchive: Boolean) : ChatListTopBarMode
    data object Forwarding : ChatListTopBarMode
    data object Archive : ChatListTopBarMode
    data class Default(
        val user: UserModel?,
        val connectionStatus: ConnectionStatus,
        val isProxyEnabled: Boolean,
        val isSearchActive: Boolean
    ) : ChatListTopBarMode
}

@Composable
private fun ChatListActionsPopup(
    visible: Boolean,
    hasUnreadChats: Boolean,
    canEditFolders: Boolean,
    onDismiss: () -> Unit,
    onMarkAllRead: () -> Unit,
    onEditFolders: () -> Unit
) {
    ActionMenuPopup(
        visible = visible,
        onDismiss = onDismiss,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp, end = 16.dp)
    ) {
        MenuOptionRow(
            icon = Icons.Rounded.DoneAll,
            title = stringResource(R.string.action_mark_all_as_read),
            enabled = hasUnreadChats,
            onClick = onMarkAllRead
        )
        if (canEditFolders) {
            MenuOptionRow(
                icon = Icons.Rounded.Edit,
                title = stringResource(R.string.action_edit_folders),
                onClick = onEditFolders
            )
        }
    }
}

@Composable
private fun SelectionActionsPopup(
    visible: Boolean,
    selectionState: ChatListComponent.SelectionState,
    isInArchive: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onToggleRead: () -> Unit,
    onClearHistory: () -> Unit,
    onReport: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit
) {
    val capabilities = selectionState.capabilities
    ActionMenuPopup(
        visible = visible,
        onDismiss = onDismiss,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp, end = 16.dp)
    ) {
        MenuOptionRow(
            icon = Icons.Rounded.PushPin,
            title = stringResource(if (selectionState.allPinned) R.string.action_unpin else R.string.action_pin),
            enabled = capabilities.canPin,
            onClick = onPin
        )
        MenuOptionRow(
            icon = if (selectionState.allMuted) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
            title = stringResource(if (selectionState.allMuted) R.string.menu_unmute else R.string.menu_mute),
            enabled = capabilities.canMute,
            onClick = onMute
        )
        MenuOptionRow(
            icon = if (isInArchive) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
            title = stringResource(if (isInArchive) R.string.menu_unarchive else R.string.menu_archive),
            enabled = capabilities.canArchive,
            onClick = onArchive
        )
        MenuOptionRow(
            icon = Icons.Rounded.DoneAll,
            title = stringResource(
                if (selectionState.canMarkUnread) {
                    R.string.action_mark_as_unread
                } else {
                    R.string.action_mark_as_read
                }
            ),
            enabled = capabilities.canToggleRead,
            onClick = onToggleRead
        )
        if (capabilities.canClearHistory) {
            MenuOptionRow(
                icon = Icons.Rounded.CleaningServices,
                title = stringResource(R.string.menu_clear_history),
                onClick = onClearHistory
            )
        }
        if (capabilities.canReport) {
            MenuOptionRow(
                icon = Icons.Rounded.Report,
                title = stringResource(R.string.menu_report),
                onClick = onReport
            )
        }
        if (capabilities.canLeave) {
            MenuOptionRow(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                title = stringResource(R.string.menu_leave),
                destructive = true,
                onClick = onLeave
            )
        }
        MenuOptionRow(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.action_delete),
            enabled = capabilities.canDelete,
            destructive = true,
            onClick = onDelete
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListBody(
    component: ChatListComponent,
    foldersState: ChatListComponent.FoldersState,
    chatsState: ChatListComponent.ChatsState,
    selectionState: ChatListComponent.SelectionState,
    selectedForwardChatIds: Set<Long>,
    isForwarding: Boolean,
    searchState: ChatListComponent.SearchState,
    visibleFolders: List<FolderModel>,
    pagerState: PagerState,
    scrollStates: MutableMap<Int, LazyListState>,
    firstFolderTransitionCompleted: MutableMap<Int, Boolean>,
    currentUserId: Long?,
    isTablet: Boolean,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    showPhotos: Boolean,
    onChatClicked: (Long) -> Unit,
    onChatLongClicked: (Long) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (searchState.isSearchActive || foldersState.selectedFolderId == -2) {
            SearchOrArchiveContent(
                component = component,
                foldersState = foldersState,
                chatsState = chatsState,
                selectionState = selectionState,
                selectedForwardChatIds = selectedForwardChatIds,
                isForwarding = isForwarding,
                searchState = searchState,
                firstFolderTransitionCompleted = firstFolderTransitionCompleted,
                scrollStates = scrollStates,
                currentUserId = currentUserId,
                isTablet = isTablet,
                emojiFontFamily = emojiFontFamily,
                messageLines = messageLines,
                showPhotos = showPhotos,
                onChatClicked = onChatClicked,
                onChatLongClicked = onChatLongClicked
            )
        } else {
            FolderPagerContent(
                component = component,
                foldersState = foldersState,
                selectionState = selectionState,
                selectedForwardChatIds = selectedForwardChatIds,
                isForwarding = isForwarding,
                visibleFolders = visibleFolders,
                pagerState = pagerState,
                firstFolderTransitionCompleted = firstFolderTransitionCompleted,
                scrollStates = scrollStates,
                currentUserId = currentUserId,
                isTablet = isTablet,
                emojiFontFamily = emojiFontFamily,
                messageLines = messageLines,
                showPhotos = showPhotos,
                onChatClicked = onChatClicked,
                onChatLongClicked = onChatLongClicked
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchOrArchiveContent(
    component: ChatListComponent,
    foldersState: ChatListComponent.FoldersState,
    chatsState: ChatListComponent.ChatsState,
    selectionState: ChatListComponent.SelectionState,
    selectedForwardChatIds: Set<Long>,
    isForwarding: Boolean,
    searchState: ChatListComponent.SearchState,
    firstFolderTransitionCompleted: MutableMap<Int, Boolean>,
    scrollStates: MutableMap<Int, LazyListState>,
    currentUserId: Long?,
    isTablet: Boolean,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    showPhotos: Boolean,
    onChatClicked: (Long) -> Unit,
    onChatLongClicked: (Long) -> Unit
) {
    var showAllGlobal by remember(searchState.isSearchActive, searchState.searchQuery) {
        mutableStateOf(false)
    }
    var showAllMessages by remember(searchState.isSearchActive, searchState.searchQuery) {
        mutableStateOf(false)
    }

    val isArchivedView = foldersState.selectedFolderId == -2 && !searchState.isSearchActive
    val scrollState = rememberManagedChatListState(
        stateKey = if (isArchivedView) "archive" else "search",
        restoredPosition = if (isArchivedView) foldersState.scrollPositions[-2] else null
    )

    RegisterScrollState(
        folderId = -2,
        enabled = isArchivedView,
        scrollState = scrollState,
        scrollStates = scrollStates,
        onSaveScrollPosition = component::updateScrollPosition
    )

    val archivedChats = if (isArchivedView) chatsState.chats else emptyList()
    val isArchivedLoading = isArchivedView && chatsState.isLoading
    val hasArchivedLoadState = isArchivedView && (
            foldersState.isLoadingByFolder.containsKey(-2) || chatsState.chats.isNotEmpty()
            )
    val showArchivedShimmer =
        isArchivedView && archivedChats.isEmpty() && (isArchivedLoading || !hasArchivedLoadState)
    val shouldAnimateFirstArchiveTransition = firstFolderTransitionCompleted[-2] != true
    val globalToDisplay = remember(showAllGlobal, searchState.globalSearchResults) {
        if (showAllGlobal) searchState.globalSearchResults else searchState.globalSearchResults.take(
            3
        )
    }
    val messagesToDisplay = remember(showAllMessages, searchState.messageSearchResults) {
        if (showAllMessages) searchState.messageSearchResults else searchState.messageSearchResults.take(
            3
        )
    }
    val messagePaginationKeys = remember(showAllMessages, messagesToDisplay) {
        if (!showAllMessages) {
            emptySet<String>()
        } else {
            messagesToDisplay.mapIndexedNotNull { index, message ->
                if (index >= messagesToDisplay.lastIndex - 5) {
                    "msg_${message.id}_$index"
                } else {
                    null
                }
            }.toSet()
        }
    }

    LaunchedEffect(isArchivedView, archivedChats.firstOrNull()?.id) {
        if (isArchivedView && !scrollState.isScrollInProgress && scrollState.firstVisibleItemIndex <= 1) {
            scrollState.scrollToItem(0, 0)
        }
    }

    LaunchedEffect(
        isArchivedView,
        archivedChats.size,
        isArchivedLoading,
        scrollState
    ) {
        if (!isArchivedView || isArchivedLoading || archivedChats.isEmpty()) return@LaunchedEffect

        snapshotFlow {
            val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= archivedChats.lastIndex - 5
        }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    component.loadMore(-2)
                }
            }
    }

    LaunchedEffect(messagePaginationKeys, searchState.canLoadMoreMessages, scrollState) {
        if (!searchState.canLoadMoreMessages || messagePaginationKeys.isEmpty()) {
            return@LaunchedEffect
        }

        snapshotFlow {
            scrollState.layoutInfo.visibleItemsInfo.any { visibleItem ->
                (visibleItem.key as? String) in messagePaginationKeys
            }
        }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    component.loadMoreMessages()
                }
            }
    }

    LaunchedEffect(isArchivedView, hasArchivedLoadState, showArchivedShimmer) {
        if (isArchivedView && shouldAnimateFirstArchiveTransition && hasArchivedLoadState && !showArchivedShimmer) {
            firstFolderTransitionCompleted[-2] = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "ChatList" },
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = 88.dp,
                start = if (isTablet) 12.dp else 0.dp,
                end = if (isTablet) 12.dp else 0.dp
            )
        ) {
            if (searchState.isSearchActive) {
                if (searchState.searchQuery.isEmpty() &&
                    (searchState.recentUsers.isNotEmpty() || searchState.recentOthers.isNotEmpty())
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.search_recent),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = component::onClearSearchHistory) {
                                Text(stringResource(R.string.action_clear_all))
                            }
                        }
                    }

                    if (searchState.recentUsers.isNotEmpty()) {
                        item {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(
                                    items = searchState.recentUsers,
                                    key = { index, chat -> "recent_user_${chat.id}_$index" }
                                ) { _, chat ->
                                    RecentSearchUserItem(
                                        chat = chat,
                                        onClick = { onChatClicked(chat.id) },
                                        onRemove = { component.onRemoveSearchHistoryItem(chat.id) }
                                    )
                                }
                            }
                        }
                    }

                    if (searchState.recentOthers.isNotEmpty()) {
                        itemsIndexed(
                            items = searchState.recentOthers,
                            key = { _, chat -> "recent_${chat.id}" }
                        ) { _, chat ->
                            ChatListChatRow(
                                chat = chat,
                                currentUserId = currentUserId,
                                isSelected = false,
                                isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                                emojiFontFamily = emojiFontFamily,
                                messageLines = messageLines,
                                showPhotos = showPhotos,
                                onClick = { onChatClicked(chat.id) },
                                onLongClick = { component.onRemoveSearchHistoryItem(chat.id) }
                            )
                        }
                    }
                }

                if (searchState.searchResults.isNotEmpty()) {
                    item {
                        SearchSectionHeader(text = stringResource(R.string.search_section_chats))
                    }
                    itemsIndexed(
                        items = searchState.searchResults,
                        key = { index, chat -> "search_${chat.id}_$index" }
                    ) { _, chat ->
                        ChatListChatRow(
                            chat = chat,
                            currentUserId = currentUserId,
                            isSelected = if (isForwarding) selectedForwardChatIds.contains(chat.id) else selectionState.selectedChatIds.contains(
                                chat.id
                            ),
                            isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                            emojiFontFamily = emojiFontFamily,
                            messageLines = messageLines,
                            showPhotos = showPhotos,
                            onClick = { onChatClicked(chat.id) },
                            onLongClick = { onChatLongClicked(chat.id) }
                        )
                    }
                }

                if (searchState.globalSearchResults.isNotEmpty()) {
                    item {
                        SearchSectionHeader(text = stringResource(R.string.search_section_global))
                    }
                    itemsIndexed(
                        items = globalToDisplay,
                        key = { _, chat -> "global_${chat.id}" }
                    ) { _, chat ->
                        ChatListChatRow(
                            chat = chat,
                            currentUserId = currentUserId,
                            isSelected = if (isForwarding) selectedForwardChatIds.contains(chat.id) else selectionState.selectedChatIds.contains(
                                chat.id
                            ),
                            isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                            emojiFontFamily = emojiFontFamily,
                            messageLines = messageLines,
                            showPhotos = showPhotos,
                            onClick = { onChatClicked(chat.id) },
                            onLongClick = { onChatLongClicked(chat.id) }
                        )
                    }

                    if (!showAllGlobal && searchState.globalSearchResults.size > 3) {
                        item {
                            ShowMoreButton(onClick = { showAllGlobal = true })
                        }
                    }
                }

                if (searchState.messageSearchResults.isNotEmpty()) {
                    item {
                        SearchSectionHeader(text = stringResource(R.string.search_section_messages))
                    }
                    itemsIndexed(
                        items = messagesToDisplay,
                        key = { index, msg -> "msg_${msg.id}_$index" }
                    ) { _, msg ->
                        MessageSearchItem(
                            modifier = Modifier.animateItem(),
                            message = msg,
                            onClick = { component.onMessageClicked(msg.chatId, msg.id) }
                        )
                    }

                    if (!showAllMessages && searchState.messageSearchResults.size > 3) {
                        item {
                            ShowMoreButton(onClick = { showAllMessages = true })
                        }
                    }
                }
            } else {
                if (archivedChats.isEmpty() && hasArchivedLoadState && !isArchivedLoading) {
                    item {
                        EmptyStateView(modifier = Modifier.fillParentMaxSize())
                    }
                }

                itemsIndexed(items = archivedChats, key = { _, chat -> chat.id }) { _, chat ->
                    ChatListChatRow(
                        chat = chat,
                        currentUserId = currentUserId,
                        isSelected = if (isForwarding) selectedForwardChatIds.contains(chat.id) else selectionState.selectedChatIds.contains(
                            chat.id
                        ),
                        isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                        emojiFontFamily = emojiFontFamily,
                        messageLines = messageLines,
                        showPhotos = showPhotos,
                        onClick = { onChatClicked(chat.id) },
                        onLongClick = { onChatLongClicked(chat.id) }
                    )
                }
            }
        }

        if (isArchivedView) {
            Crossfade(
                targetState = showArchivedShimmer,
                animationSpec = if (shouldAnimateFirstArchiveTransition) {
                    MaterialTheme.motionScheme.defaultEffectsSpec()
                } else {
                    snap()
                },
                label = "ArchiveChatsCrossfade"
            ) { showShimmer ->
                if (showShimmer) {
                    ChatListShimmer(itemCount = 8)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPagerContent(
    component: ChatListComponent,
    foldersState: ChatListComponent.FoldersState,
    selectionState: ChatListComponent.SelectionState,
    selectedForwardChatIds: Set<Long>,
    isForwarding: Boolean,
    visibleFolders: List<FolderModel>,
    pagerState: PagerState,
    firstFolderTransitionCompleted: MutableMap<Int, Boolean>,
    scrollStates: MutableMap<Int, LazyListState>,
    currentUserId: Long?,
    isTablet: Boolean,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    showPhotos: Boolean,
    onChatClicked: (Long) -> Unit,
    onChatLongClicked: (Long) -> Unit
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1
    ) { page ->
        val folder = visibleFolders.getOrNull(page)
            ?: visibleFolders.firstOrNull { it.id == foldersState.selectedFolderId }
        val folderId = folder?.id
        if (folderId == null) {
            Box(modifier = Modifier.fillMaxSize())
            return@HorizontalPager
        }

        FolderPageContent(
            component = component,
            folderId = folderId,
            folderChats = foldersState.chatsByFolder[folderId].orEmpty(),
            isFolderLoading = foldersState.isLoadingByFolder[folderId] ?: false,
            hasFolderLoadState = foldersState.isLoadingByFolder.containsKey(folderId),
            restoredPosition = foldersState.scrollPositions[folderId],
            selectionState = selectionState,
            selectedForwardChatIds = selectedForwardChatIds,
            isForwarding = isForwarding,
            shouldAnimateFirstFolderTransition = firstFolderTransitionCompleted[folderId] != true,
            scrollStates = scrollStates,
            onMarkFirstTransitionComplete = { firstFolderTransitionCompleted[folderId] = true },
            currentUserId = currentUserId,
            isTablet = isTablet,
            emojiFontFamily = emojiFontFamily,
            messageLines = messageLines,
            showPhotos = showPhotos,
            onChatClicked = onChatClicked,
            onChatLongClicked = onChatLongClicked
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderPageContent(
    component: ChatListComponent,
    folderId: Int,
    folderChats: List<ChatModel>,
    isFolderLoading: Boolean,
    hasFolderLoadState: Boolean,
    restoredPosition: Pair<Int, Int>?,
    selectionState: ChatListComponent.SelectionState,
    selectedForwardChatIds: Set<Long>,
    isForwarding: Boolean,
    shouldAnimateFirstFolderTransition: Boolean,
    scrollStates: MutableMap<Int, LazyListState>,
    onMarkFirstTransitionComplete: () -> Unit,
    currentUserId: Long?,
    isTablet: Boolean,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    showPhotos: Boolean,
    onChatClicked: (Long) -> Unit,
    onChatLongClicked: (Long) -> Unit
) {
    val shouldHoldInitialFolderContent = folderId > 0 &&
            shouldAnimateFirstFolderTransition &&
            isFolderLoading
    val showFolderShimmer = shouldHoldInitialFolderContent ||
            (folderChats.isEmpty() && (isFolderLoading || !hasFolderLoadState))
    val scrollState = rememberManagedChatListState(
        stateKey = "folder:$folderId",
        restoredPosition = restoredPosition
    )

    RegisterScrollState(
        folderId = folderId,
        enabled = true,
        scrollState = scrollState,
        scrollStates = scrollStates,
        onSaveScrollPosition = component::updateScrollPosition
    )

    LaunchedEffect(folderChats.firstOrNull()?.id) {
        if (!scrollState.isScrollInProgress && scrollState.firstVisibleItemIndex <= 1) {
            scrollState.scrollToItem(0, 0)
        }
    }

    LaunchedEffect(folderId, folderChats.size, isFolderLoading, scrollState) {
        if (isFolderLoading || folderChats.isEmpty()) return@LaunchedEffect

        snapshotFlow {
            val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= folderChats.lastIndex - 5
        }
            .distinctUntilChanged()
            .collect { shouldLoad ->
                if (shouldLoad) {
                    component.loadMore(folderId)
                }
            }
    }

    val isInitialLoad = remember(folderId) { mutableStateOf(true) }
    LaunchedEffect(folderChats) {
        if (isInitialLoad.value && folderChats.isNotEmpty()) {
            if (restoredPosition == null) {
                scrollState.scrollToItem(0, 0)
            }
            isInitialLoad.value = false
        }
    }

    LaunchedEffect(folderId, hasFolderLoadState, showFolderShimmer) {
        if (shouldAnimateFirstFolderTransition && hasFolderLoadState && !showFolderShimmer) {
            onMarkFirstTransitionComplete()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = showFolderShimmer,
            animationSpec = if (shouldAnimateFirstFolderTransition) {
                MaterialTheme.motionScheme.defaultEffectsSpec()
            } else {
                snap()
            },
            label = "FolderChatsCrossfade"
        ) { showShimmer ->
            if (showShimmer) {
                ChatListShimmer()
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "ChatList" },
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = 88.dp,
                        start = if (isTablet) 4.dp else 0.dp,
                        end = if (isTablet) 4.dp else 0.dp
                    )
                ) {
                    if (folderChats.isEmpty() && hasFolderLoadState && !isFolderLoading) {
                        item {
                            EmptyStateView(modifier = Modifier.fillParentMaxSize())
                        }
                    }

                    itemsIndexed(items = folderChats, key = { _, chat -> chat.id }) { _, chat ->
                        ChatListChatRow(
                            chat = chat,
                            currentUserId = currentUserId,
                            isSelected = if (isForwarding) selectedForwardChatIds.contains(chat.id) else selectionState.selectedChatIds.contains(
                                chat.id
                            ),
                            isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                            emojiFontFamily = emojiFontFamily,
                            messageLines = messageLines,
                            showPhotos = showPhotos,
                            onClick = { onChatClicked(chat.id) },
                            onLongClick = { onChatLongClicked(chat.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterScrollState(
    folderId: Int,
    enabled: Boolean,
    scrollState: LazyListState,
    scrollStates: MutableMap<Int, LazyListState>,
    onSaveScrollPosition: (Int, Int, Int) -> Unit
) {
    DisposableEffect(folderId, enabled, scrollState) {
        if (enabled) {
            scrollStates[folderId] = scrollState
        }
        onDispose {
            if (enabled) {
                onSaveScrollPosition(
                    folderId,
                    scrollState.firstVisibleItemIndex,
                    scrollState.firstVisibleItemScrollOffset
                )
                scrollStates.remove(folderId, scrollState)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatListChatRow(
    chat: ChatModel,
    currentUserId: Long?,
    isSelected: Boolean,
    isTabletSelected: Boolean,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    showPhotos: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ChatListItem(
        modifier = Modifier,
        chat = chat,
        currentUserId = currentUserId,
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        isTabletSelected = isTabletSelected,
        emojiFontFamily = emojiFontFamily,
        messageLines = messageLines,
        showPhotos = showPhotos
    )
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ShowMoreButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.action_show_more),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun RecentSearchUserItem(
    chat: ChatModel,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRemove
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Avatar(
                path = chat.avatarPath,
                fallbackPath = chat.personalAvatarPath,
                name = chat.title,
                size = 64.dp,
                isOnline = chat.isOnline
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .offset(x = 4.dp, y = (-4).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = chat.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionModeTopBar(
    selectionState: ChatListComponent.SelectionState,
    onClearSelection: () -> Unit,
    onMoreClick: () -> Unit
) {
    SelectionTopBar(
        selectedCount = selectionState.selectedChatIds.size,
        onClearSelection = onClearSelection,
        onMoreClick = onMoreClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardingModeTopBar(
    onBackClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.forward_to_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Rounded.Close, stringResource(R.string.cancel_button))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardTopicPickerSheet(
    chatId: Long?,
    title: String,
    topics: List<TopicModel>,
    isLoading: Boolean,
    onTopicSelected: (Long, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    if (chatId == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.forward_topic_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column {
                    ForwardTopicRow(
                        title = stringResource(R.string.forward_main_chat),
                        subtitle = stringResource(R.string.forward_main_chat_subtitle),
                        onClick = { onTopicSelected(chatId, null) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                    if (isLoading) {
                        Text(
                            text = stringResource(R.string.loading_text),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        topics.forEachIndexed { index, topic ->
                            ForwardTopicRow(
                                title = topic.name,
                                subtitle = topic.lastMessageText.takeIf { it.isNotBlank() },
                                onClick = { onTopicSelected(chatId, topic.id) }
                            )
                            if (index != topics.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 68.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardTopicRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "#",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ForwardConfirmationPanel(
    visible: Boolean,
    targets: List<ForwardTarget>,
    selectedChats: List<ChatModel>,
    commentText: String,
    onCommentTextChange: (String) -> Unit,
    sendCopy: Boolean,
    onSendCopyChange: (Boolean) -> Unit,
    removeCaption: Boolean,
    onRemoveCaptionChange: (Boolean) -> Unit,
    onRemoveTarget: (ForwardTarget) -> Unit,
    isCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onSend: () -> Unit
) {
    fun Modifier.forwardPanelSwipe(): Modifier = pointerInput(isCollapsed) {
        var totalDrag = 0f
        detectVerticalDragGestures(
            onDragStart = { totalDrag = 0f },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                totalDrag += dragAmount
            },
            onDragEnd = {
                when {
                    totalDrag > 72f -> onCollapsedChange(true)
                    totalDrag < -72f -> onCollapsedChange(false)
                }
                totalDrag = 0f
            },
            onDragCancel = { totalDrag = 0f }
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                AnimatedContent(
                    targetState = isCollapsed,
                    label = "ForwardPanelCollapseAnimation",
                    transitionSpec = {
                        if (targetState) {
                            slideInVertically { it / 2 } + fadeIn() togetherWith
                                    slideOutVertically { -it / 4 } + fadeOut()
                        } else {
                            slideInVertically { -it / 4 } + fadeIn() togetherWith
                                    slideOutVertically { it / 2 } + fadeOut()
                        }
                    }
                ) { collapsed ->
                    if (collapsed) {
                        ForwardCollapsedPanel(
                            targetsCount = targets.size,
                            onExpand = { onCollapsedChange(false) },
                            onSend = onSend,
                            modifier = Modifier.forwardPanelSwipe()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .forwardPanelSwipe()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BottomSheetDefaults.DragHandle(
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.forward_confirm_title),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.forward_recipients_count,
                                            targets.size
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                LazyRow(
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 12.dp
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(
                                        targets,
                                        key = { index, target -> "${target.chatId}_${target.forumTopicId}_$index" }) { _, target ->
                                        ForwardRecipientPill(
                                            target = target,
                                            selectedChats = selectedChats,
                                            onRemove = { onRemoveTarget(target) }
                                        )
                                    }
                                }
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                TextField(
                                    value = commentText,
                                    onValueChange = onCommentTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(stringResource(R.string.forward_comment_hint)) },
                                    maxLines = 3,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent
                                    )
                                )
                            }

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column {
                                    ForwardOptionRow(
                                        title = stringResource(R.string.forward_without_author),
                                        checked = sendCopy,
                                        enabled = true,
                                        onCheckedChange = onSendCopyChange
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    )
                                    ForwardOptionRow(
                                        title = stringResource(R.string.forward_without_caption),
                                        checked = removeCaption,
                                        enabled = sendCopy,
                                        onCheckedChange = onRemoveCaptionChange
                                    )
                                }
                            }

                            Button(
                                onClick = onSend,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ButtonDefaults.MediumContainerHeight),
                                shapes = org.monogram.presentation.core.ui.ExpressiveDefaults.buttonShapesFor(
                                    ButtonDefaults.MediumContainerHeight
                                )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.action_send),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ForwardCollapsedPanel(
    targetsCount: Int,
    onExpand: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .clickable(onClick = onExpand)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BottomSheetDefaults.DragHandle()
        Text(
            text = stringResource(R.string.forward_recipients_count, targetsCount),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Button(
            onClick = onSend,
            modifier = Modifier.height(40.dp),
            shapes = org.monogram.presentation.core.ui.ExpressiveDefaults.buttonShapesFor(
                ButtonDefaults.MediumContainerHeight
            )
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_send))
        }
    }
}

@Composable
private fun ForwardRecipientPill(
    target: ForwardTarget,
    selectedChats: List<ChatModel>,
    onRemove: () -> Unit
) {
    val chatTitle = selectedChats.firstOrNull { it.id == target.chatId }?.title
        ?: target.chatId.toString()
    val label = if (target.forumTopicId != null) {
        "$chatTitle #${target.forumTopicId}"
    } else {
        chatTitle
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .height(40.dp)
            .clickable(onClick = onRemove)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_close),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ForwardOptionRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveModeTopBar(
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                stringResource(R.string.archived_chats_title),
                fontWeight = FontWeight.SemiBold
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
            }
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.action_search)
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.menu_more)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
}

@Composable
private fun rememberManagedChatListState(
    stateKey: String,
    restoredPosition: Pair<Int, Int>?
): LazyListState {
    val initialIndex = restoredPosition?.first ?: 0
    val initialOffset = restoredPosition?.second ?: 0
    return remember(stateKey) {
        LazyListState(
            firstVisibleItemIndex = initialIndex,
            firstVisibleItemScrollOffset = initialOffset
        )
    }
}

private fun hasChatListUnreadState(chat: ChatModel): Boolean {
    return chat.unreadCount > 0 ||
            chat.isMarkedAsUnread ||
            chat.unreadMentionCount > 0 ||
            chat.unreadReactionCount > 0
}
