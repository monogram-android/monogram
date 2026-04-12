package org.monogram.presentation.features.chats.chatList

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.features.chats.ChatListComponent
import org.monogram.presentation.features.chats.chatList.components.AccountMenu
import org.monogram.presentation.features.chats.chatList.components.ArchiveHeaderCard
import org.monogram.presentation.features.chats.chatList.components.ChatListItem
import org.monogram.presentation.features.chats.chatList.components.ChatListShimmer
import org.monogram.presentation.features.chats.chatList.components.ChatListTopBar
import org.monogram.presentation.features.chats.chatList.components.EmptyStateView
import org.monogram.presentation.features.chats.chatList.components.FolderTabs
import org.monogram.presentation.features.chats.chatList.components.MessageSearchItem
import org.monogram.presentation.features.chats.chatList.components.PermissionRequestSheet
import org.monogram.presentation.features.chats.chatList.components.SelectionTopBar
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.instantview.InstantViewer
import org.monogram.presentation.features.stickers.ui.menu.EmojisGrid
import org.monogram.presentation.features.webapp.MiniAppViewer
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatListContent(component: ChatListComponent) {
    val uiState by component.uiState.collectAsState()
    val foldersState by component.foldersState.collectAsState()
    val chatsState by component.chatsState.collectAsState()
    val selectionState by component.selectionState.collectAsState()
    val searchState by component.searchState.collectAsState()

    val scope = rememberCoroutineScope()

    val haptic = LocalHapticFeedback.current

    var showAccountMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    var showDeleteChatsSheet by remember { mutableStateOf(false) }
    var statusAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val statusMenuTransitionState = remember { MutableTransitionState(false) }

    LaunchedEffect(showStatusMenu) {
        statusMenuTransitionState.targetState = showStatusMenu
    }

    val isPermissionRequested by component.appPreferences.isPermissionRequested.collectAsState()
    var showPermissionRequest by remember { mutableStateOf(!isPermissionRequested) }

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTabletInterfaceEnabled = LocalTabletInterfaceEnabled.current
    val isTablet =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) && isTabletInterfaceEnabled

    val isCustomBackHandlingEnabled =
        searchState.isSearchActive || selectionState.selectedChatIds.isNotEmpty() || foldersState.selectedFolderId == -2 || uiState.isForwarding || uiState.instantViewUrl != null || uiState.webAppUrl != null || uiState.webViewUrl != null || showStatusMenu

    BackHandler(enabled = isCustomBackHandlingEnabled) {
        if (showStatusMenu) {
            showStatusMenu = false
        } else {
            component.handleBack()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = foldersState.folders.indexOfFirst { it.id == foldersState.selectedFolderId }.coerceAtLeast(0),
        pageCount = { foldersState.folders.size }
    )

    LaunchedEffect(foldersState.selectedFolderId) {
        val index = foldersState.folders.indexOfFirst { it.id == foldersState.selectedFolderId }.coerceAtLeast(0)
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }

    LaunchedEffect(pagerState.currentPage) {
        if (foldersState.folders.isNotEmpty()) {
            val folderId = foldersState.folders[pagerState.currentPage].id
            if (foldersState.selectedFolderId != folderId && foldersState.selectedFolderId != -2) {
                component.onFolderClicked(folderId)
            }
        }
    }

    val density = LocalDensity.current
    val tabsHeight = if (foldersState.folders.size > 1) 56.dp else 10.dp
    val archiveItemHeight = 78.dp
    val tabsHeightPx = with(density) { tabsHeight.toPx() }
    val archiveItemHeightPx = with(density) { archiveItemHeight.toPx() }

    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    var archiveRevealPx by remember { mutableFloatStateOf(0f) }

    var revealAnimationJob by remember { mutableStateOf<Job?>(null) }
    var headerAnimationJob by remember { mutableStateOf<Job?>(null) }

    var hasVibrated by remember { mutableStateOf(false) }
    var canRevealArchive by remember { mutableStateOf(true) }

    val currentFolder = foldersState.folders.getOrNull(pagerState.currentPage)
    val isMainFolder = currentFolder?.id == -1

    val isArchivePersistent = uiState.isArchivePinned && (uiState.isArchiveAlwaysVisible || isMainFolder)
    val canShowArchive = isArchivePersistent || isMainFolder

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
            onAddAccountClick = { /* TODO */ },
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
    val statusMenuScrimAlpha by animateFloatAsState(
        targetValue = if (statusMenuTransitionState.targetState) 0.18f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "ChatListStatusMenuScrimAlpha"
    )

    Scaffold(
        containerColor = if (isTablet) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = selectionState.selectedChatIds.isNotEmpty() && !uiState.isForwarding,
                    label = "TopBarSelectionAnimation",
                    transitionSpec = { fadeIn() togetherWith fadeOut() }
                ) { isSelectionMode ->
                    if (isSelectionMode) {
                        val selectedChats = chatsState.chats.filter { selectionState.selectedChatIds.contains(it.id) }
                        val canMarkUnread = selectedChats.any { !it.isMarkedAsUnread }
                        val allPinned = selectedChats.isNotEmpty() && selectedChats.all { it.isPinned }
                        val allMuted = selectedChats.isNotEmpty() && selectedChats.all { it.isMuted }
                        val isInArchive = foldersState.selectedFolderId == -2

                        SelectionTopBar(
                            selectedCount = selectionState.selectedChatIds.size,
                            isInArchive = isInArchive,
                            allPinned = allPinned,
                            allMuted = allMuted,
                            onClearSelection = { component.clearSelection() },
                            onPinClick = { component.onPinSelected() },
                            onMuteClick = { component.onMuteSelected(!allMuted) },
                            onArchiveClick = { component.onArchiveSelected(!isInArchive) },
                            onDeleteClick = { showDeleteChatsSheet = true },
                            onToggleReadClick = { component.onToggleReadSelected() },
                            canMarkUnread = canMarkUnread
                        )
                    } else {
                        if (uiState.isForwarding) {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(
                                            stringResource(R.string.forward_to_title),
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (selectionState.selectedChatIds.isNotEmpty()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.chats_selected_format,
                                                    selectionState.selectedChatIds.size
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = { component.handleBack() }) {
                                        Icon(Icons.Rounded.Close, stringResource(R.string.cancel_button))
                                    }
                                },
                                actions = {
                                    if (selectionState.selectedChatIds.isNotEmpty()) {
                                        IconButton(onClick = { component.onConfirmForwarding() }) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.Send,
                                                stringResource(R.string.action_send),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            )
                        } else if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive) {
                            TopAppBar(
                                title = {
                                    Text(
                                        stringResource(R.string.archived_chats_title),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { component.handleBack() }) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { component.onSearchToggle() }) {
                                        Icon(
                                            Icons.Rounded.Search,
                                            contentDescription = stringResource(R.string.action_search)
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            )
                        } else {
                            ChatListTopBar(
                                user = currentUser,
                                connectionStatus = uiState.connectionStatus,
                                isProxyEnabled = uiState.isProxyEnabled,
                                onRetryConnection = { component.retryConnection() },
                                onProxySettingsClick = { component.onProxySettingsClicked() },
                                isSearchActive = searchState.isSearchActive,
                                searchQuery = searchState.searchQuery,
                                onSearchQueryChange = component::onSearchQueryChange,
                                onSearchToggle = component::onSearchToggle,
                                onStatusClick = { anchorBounds ->
                                    statusAnchorBounds = anchorBounds ?: statusAnchorBounds
                                    showStatusMenu = true
                                },
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

                            if (foldersState.folders.size > 1) {
                                FolderTabs(
                                    modifier = Modifier,
                                    folders = foldersState.folders,
                                    pagerState = pagerState,
                                    onTabClick = { index ->
                                        if (pagerState.currentPage == index) {
                                            val folderId = foldersState.folders[index].id
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

                AnimatedVisibility(
                    visible = uiState.isForwarding && selectionState.selectedChatIds.isNotEmpty(),
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = { component.onConfirmForwarding() },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.action_send))
                    }
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
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
            if (searchState.isSearchActive || foldersState.selectedFolderId == -2) {
                var showAllGlobal by remember { mutableStateOf(false) }
                var showAllMessages by remember { mutableStateOf(false) }

                val scrollState = rememberLazyListState(
                    initialFirstVisibleItemIndex = if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive) foldersState.scrollPositions[-2]?.first ?: 0 else 0,
                    initialFirstVisibleItemScrollOffset = if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive) foldersState.scrollPositions[-2]?.second ?: 0 else 0
                )

                if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive) {
                    scrollStates[-2] = scrollState
                }

                val firstItemId = if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive) {
                    foldersState.chatsByFolder[-2]?.firstOrNull()?.id
                } else {
                    null
                }

                LaunchedEffect(firstItemId) {
                    if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive && !scrollState.isScrollInProgress && scrollState.firstVisibleItemIndex <= 1) {
                        scrollState.scrollToItem(0, 0)
                    }
                }

                if (foldersState.selectedFolderId == -2 && !searchState.isSearchActive) {
                    DisposableEffect(Unit) {
                        onDispose {
                            component.updateScrollPosition(-2, scrollState.firstVisibleItemIndex, scrollState.firstVisibleItemScrollOffset)
                        }
                    }
                }

                val isArchivedView = foldersState.selectedFolderId == -2 && !searchState.isSearchActive
                val archivedChats = if (isArchivedView) chatsState.chats else emptyList()
                val isArchivedLoading = if (isArchivedView) chatsState.isLoading else false
                val hasArchivedLoadState = isArchivedView && (foldersState.isLoadingByFolder.containsKey(-2) || chatsState.chats.isNotEmpty())
                val showArchivedShimmer =
                    isArchivedView && archivedChats.isEmpty() && (isArchivedLoading || !hasArchivedLoadState)
                val shouldAnimateFirstArchiveTransition = firstFolderTransitionCompleted[-2] != true

                LaunchedEffect(isArchivedView, archivedChats.size, isArchivedLoading, scrollState) {
                    if (!isArchivedView || isArchivedLoading || archivedChats.isEmpty()) {
                        return@LaunchedEffect
                    }

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
                        ),
                    ) {
                        if (searchState.isSearchActive) {
                        if (searchState.searchQuery.isEmpty() && (searchState.recentUsers.isNotEmpty() || searchState.recentOthers.isNotEmpty())) {
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
                                    TextButton(onClick = { component.onClearSearchHistory() }) {
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
                                        itemsIndexed(items = searchState.recentUsers, key = { index, chat -> "recent_user_${chat.id}_$index" }) { _, chat ->
                                            Column(
                                                modifier = Modifier
                                                    .width(64.dp)
                                                    .combinedClickable(
                                                        onClick = { onChatClicked(chat.id) },
                                                        onLongClick = {
                                                            component.onRemoveSearchHistoryItem(
                                                                chat.id
                                                            )
                                                        }
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
                                                            .clickable {
                                                                component.onRemoveSearchHistoryItem(
                                                                    chat.id
                                                                )
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.Close,
                                                            null,
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
                                    }
                                }
                            }

                            if (searchState.recentOthers.isNotEmpty()) {
                                itemsIndexed(
                                    items = searchState.recentOthers,
                                    key = { _, chat -> "recent_${chat.id}" }) { _, chat ->
                                    ChatListItem(
                                        modifier = Modifier.animateItem(),
                                        chat = chat,
                                        currentUserId = uiState.currentUser?.id,
                                        isSelected = false,
                                        onClick = { onChatClicked(chat.id) },
                                        onLongClick = { component.onRemoveSearchHistoryItem(chat.id) },
                                        isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                                        emojiFontFamily = emojiFontFamily,
                                        messageLines = messageLines,
                                        showPhotos = showPhotos
                                    )
                                }
                            }
                        }

                        if (searchState.searchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_section_chats),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            itemsIndexed(items = searchState.searchResults, key = { index, chat -> "search_${chat.id}_$index" }) { _, chat ->
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    chat = chat,
                                    currentUserId = uiState.currentUser?.id,
                                    isSelected = selectionState.selectedChatIds.contains(chat.id),
                                    onClick = { onChatClicked(chat.id) },
                                    onLongClick = { onChatLongClicked(chat.id) },
                                    isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                                    emojiFontFamily = emojiFontFamily,
                                    messageLines = messageLines,
                                    showPhotos = showPhotos
                                )
                            }
                        }

                        if (searchState.globalSearchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_section_global),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            val globalToDisplay =
                                if (showAllGlobal) searchState.globalSearchResults else searchState.globalSearchResults.take(3)

                            itemsIndexed(items = globalToDisplay, key = { _, chat -> "global_${chat.id}" }) { _, chat ->
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    chat = chat,
                                    currentUserId = uiState.currentUser?.id,
                                    isSelected = selectionState.selectedChatIds.contains(chat.id),
                                    onClick = { onChatClicked(chat.id) },
                                    onLongClick = { onChatLongClicked(chat.id) },
                                    isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                                    emojiFontFamily = emojiFontFamily,
                                    messageLines = messageLines,
                                    showPhotos = showPhotos
                                )
                            }

                            if (!showAllGlobal && searchState.globalSearchResults.size > 3) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showAllGlobal = true }
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
                            }
                        }

                        if (searchState.messageSearchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_section_messages),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            val messagesToDisplay =
                                if (showAllMessages) searchState.messageSearchResults else searchState.messageSearchResults.take(3)

                            itemsIndexed(
                                items = messagesToDisplay,
                                key = { index, msg -> "msg_${msg.id}_$index" }) { index, msg ->
                                if (showAllMessages && index >= messagesToDisplay.lastIndex - 5 && searchState.canLoadMoreMessages) {
                                    LaunchedEffect(Unit) { component.loadMoreMessages() }
                                }

                                MessageSearchItem(
                                    modifier = Modifier.animateItem(),
                                    message = msg,
                                    onClick = { component.onMessageClicked(msg.chatId, msg.id) }
                                )
                            }

                            if (!showAllMessages && searchState.messageSearchResults.size > 3) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showAllMessages = true }
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
                            }
                        }
                        } else {
                            if (archivedChats.isEmpty() && hasArchivedLoadState && !isArchivedLoading) {
                                item {
                                    EmptyStateView(modifier = Modifier.fillParentMaxSize())
                                }
                            }

                            itemsIndexed(items = archivedChats, key = { _, chat -> chat.id }) { _, chat ->
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    chat = chat,
                                    currentUserId = uiState.currentUser?.id,
                                    isSelected = selectionState.selectedChatIds.contains(chat.id),
                                    onClick = { onChatClicked(chat.id) },
                                    onLongClick = { onChatLongClicked(chat.id) },
                                    isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                                    emojiFontFamily = emojiFontFamily,
                                    messageLines = messageLines,
                                    showPhotos = showPhotos
                                )
                            }
                        }
                    }

                    if (isArchivedView) {
                        Crossfade(
                            targetState = showArchivedShimmer,
                            animationSpec = if (shouldAnimateFirstArchiveTransition) MaterialTheme.motionScheme.defaultEffectsSpec() else snap(),
                            label = "ArchiveChatsCrossfade"
                        ) { showShimmer ->
                            if (showShimmer) {
                                ChatListShimmer(itemCount = 8)
                            }
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { page ->
                    val folderId = foldersState.folders.getOrNull(page)?.id
                        ?: foldersState.folders.firstOrNull { it.id == foldersState.selectedFolderId }?.id
                    if (folderId == null) {
                        Box(modifier = Modifier.fillMaxSize())
                        return@HorizontalPager
                    }
                    val folderChats = foldersState.chatsByFolder[folderId] ?: emptyList()
                    val isFolderLoading = foldersState.isLoadingByFolder[folderId] ?: false
                    val hasFolderLoadState = foldersState.isLoadingByFolder.containsKey(folderId)
                    val showFolderShimmer = folderChats.isEmpty() && (isFolderLoading || !hasFolderLoadState)
                    val shouldAnimateFirstFolderTransition = firstFolderTransitionCompleted[folderId] != true

                    val scrollState = rememberLazyListState(
                        initialFirstVisibleItemIndex = foldersState.scrollPositions[folderId]?.first ?: 0,
                        initialFirstVisibleItemScrollOffset = foldersState.scrollPositions[folderId]?.second ?: 0
                    )

                    scrollStates[folderId] = scrollState

                    val firstItemId = folderChats.firstOrNull()?.id

                    LaunchedEffect(firstItemId) {
                        if (!scrollState.isScrollInProgress && scrollState.firstVisibleItemIndex <= 1) {
                            scrollState.scrollToItem(0, 0)
                        }
                    }

                    val shouldLoadMoreFolder by remember(folderChats, isFolderLoading, scrollState) {
                        derivedStateOf {
                            if (isFolderLoading || folderChats.isEmpty()) {
                                false
                            } else {
                                val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                                lastVisible >= folderChats.lastIndex - 5
                            }
                        }
                    }

                    LaunchedEffect(shouldLoadMoreFolder, folderId) {
                        if (shouldLoadMoreFolder) {
                            component.loadMore(folderId)
                        }
                    }

                    val isInitialLoad = remember(folderId) { mutableStateOf(true) }
                    LaunchedEffect(folderChats) {
                        if (isInitialLoad.value && folderChats.isNotEmpty()) {
                            if (foldersState.scrollPositions[folderId] == null) {
                                scrollState.scrollToItem(0, 0)
                            }
                            isInitialLoad.value = false
                        }
                    }

                    LaunchedEffect(folderId, hasFolderLoadState, showFolderShimmer) {
                        if (shouldAnimateFirstFolderTransition && hasFolderLoadState && !showFolderShimmer) {
                            firstFolderTransitionCompleted[folderId] = true
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            component.updateScrollPosition(folderId, scrollState.firstVisibleItemIndex, scrollState.firstVisibleItemScrollOffset)
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Crossfade(
                            targetState = showFolderShimmer,
                            animationSpec = if (shouldAnimateFirstFolderTransition) MaterialTheme.motionScheme.defaultEffectsSpec() else snap(),
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

                                    itemsIndexed(
                                        items = folderChats,
                                        key = { _, chat -> chat.id }
                                    ) { _, chat ->
                                        ChatListItem(
                                            modifier = Modifier.animateItem(),
                                            chat = chat,
                                            currentUserId = uiState.currentUser?.id,
                                            isSelected = selectionState.selectedChatIds.contains(chat.id),
                                            onClick = { onChatClicked(chat.id) },
                                            onLongClick = { onChatLongClicked(chat.id) },
                                            isTabletSelected = isTablet && selectionState.activeChatId == chat.id,
                                            emojiFontFamily = emojiFontFamily,
                                            messageLines = messageLines,
                                            showPhotos = showPhotos
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
                }
            }
        }
    }

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
                                component.onSetEmojiStatus(customEmojiId, sticker.path)
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
}
