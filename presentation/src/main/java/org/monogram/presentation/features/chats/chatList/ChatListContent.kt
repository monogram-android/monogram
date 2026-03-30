package org.monogram.presentation.features.chats.chatList

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
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
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.ChatType
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.features.chats.ChatListComponent
import org.monogram.presentation.features.chats.chatList.components.*
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.instantview.InstantViewer
import org.monogram.presentation.features.stickers.ui.menu.EmojisGrid
import org.monogram.presentation.features.webapp.MiniAppViewer
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListContent(component: ChatListComponent) {
    val state by component.state.collectAsState()
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
    val isTablet = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED

    val isCustomBackHandlingEnabled =
        state.isSearchActive || state.selectedChatIds.isNotEmpty() || state.selectedFolderId == -2 || state.isForwarding || state.instantViewUrl != null || state.webAppUrl != null || state.webViewUrl != null || showStatusMenu

    BackHandler(enabled = isCustomBackHandlingEnabled) {
        if (showStatusMenu) {
            showStatusMenu = false
        } else {
            component.handleBack()
        }
    }

    val pagerState = rememberPagerState(
        initialPage = state.folders.indexOfFirst { it.id == state.selectedFolderId }.coerceAtLeast(0),
        pageCount = { state.folders.size }
    )

    LaunchedEffect(state.selectedFolderId) {
        val index = state.folders.indexOfFirst { it.id == state.selectedFolderId }.coerceAtLeast(0)
        if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
    }

    LaunchedEffect(pagerState.currentPage) {
        if (state.folders.isNotEmpty()) {
            val folderId = state.folders[pagerState.currentPage].id
            if (state.selectedFolderId != folderId && state.selectedFolderId != -2) {
                component.onFolderClicked(folderId)
            }
        }
    }

    val density = LocalDensity.current
    val tabsHeight = if (state.folders.size > 1) 56.dp else 10.dp
    val archiveItemHeight = 78.dp
    val tabsHeightPx = with(density) { tabsHeight.toPx() }
    val archiveItemHeightPx = with(density) { archiveItemHeight.toPx() }

    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    var archiveRevealPx by remember { mutableFloatStateOf(0f) }

    var revealAnimationJob by remember { mutableStateOf<Job?>(null) }
    var headerAnimationJob by remember { mutableStateOf<Job?>(null) }

    var hasVibrated by remember { mutableStateOf(false) }
    var canRevealArchive by remember { mutableStateOf(true) }

    val currentFolder = state.folders.getOrNull(pagerState.currentPage)
    val isMainFolder = currentFolder?.id == -1

    val isArchivePersistent = state.isArchivePinned && (state.isArchiveAlwaysVisible || isMainFolder)
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

    val nestedScrollConnection = remember(isArchivePersistent, canShowArchive, state.isArchiveAlwaysVisible, tabsHeightPx) {
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
                        -(archiveItemHeightPx + tabsHeightPx)
                    } else {
                        -tabsHeightPx
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
                        if (isArchivePersistent && !state.isArchiveAlwaysVisible) {
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

                val maxHide = if (isArchivePersistent) {
                    -(archiveItemHeightPx + tabsHeightPx)
                } else {
                    -tabsHeightPx
                }

                if (headerOffsetPx < 0f && headerOffsetPx > maxHide) {
                    val target = if (isArchivePersistent) {
                        if (headerOffsetPx > -archiveItemHeightPx / 2) {
                            0f
                        } else if (headerOffsetPx > -archiveItemHeightPx - tabsHeightPx / 2) {
                            -archiveItemHeightPx
                        } else {
                            maxHide
                        }
                    } else {
                        if (headerOffsetPx > maxHide / 2) 0f else maxHide
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

    var cachedStatusEmojiPath by remember(state.currentUser?.id) {
        mutableStateOf(state.currentUser?.statusEmojiPath)
    }

    LaunchedEffect(state.currentUser?.id, state.currentUser?.statusEmojiPath) {
        val statusEmojiPath = state.currentUser?.statusEmojiPath
        if (!statusEmojiPath.isNullOrBlank()) {
            cachedStatusEmojiPath = statusEmojiPath
        }
    }

    val currentUser = remember(state.currentUser, cachedStatusEmojiPath) {
        state.currentUser?.let { user ->
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
            attachMenuBots = state.attachMenuBots,
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
            updateState = state.updateState,
            onUpdateClick = { component.onUpdateClicked() },
            onBotClick = { bot ->
                component.onOpenWebApp(
                    url = state.botWebAppUrl ?: "",
                    botUserId = bot.botUserId,
                    botName = state.botWebAppName ?: bot.name
                )
            },
            videoPlayerPool = component.videoPlayerPool
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
        animationSpec = tween(durationMillis = 220),
        label = "ChatListStatusMenuScrimAlpha"
    )

    Scaffold(
        containerColor = if (isTablet) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    targetState = state.selectedChatIds.isNotEmpty() && !state.isForwarding,
                    label = "TopBarSelectionAnimation",
                    transitionSpec = { fadeIn() togetherWith fadeOut() }
                ) { isSelectionMode ->
                    if (isSelectionMode) {
                        val selectedChats = state.chats.filter { state.selectedChatIds.contains(it.id) }
                        val canMarkUnread = selectedChats.any { !it.isMarkedAsUnread }
                        val allPinned = selectedChats.isNotEmpty() && selectedChats.all { it.isPinned }
                        val allMuted = selectedChats.isNotEmpty() && selectedChats.all { it.isMuted }
                        val isInArchive = state.selectedFolderId == -2

                        SelectionTopBar(
                            selectedCount = state.selectedChatIds.size,
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
                        if (state.isForwarding) {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(
                                            stringResource(R.string.forward_to_title),
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (state.selectedChatIds.isNotEmpty()) {
                                            Text(
                                                text = stringResource(
                                                    R.string.chats_selected_format,
                                                    state.selectedChatIds.size
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
                                    if (state.selectedChatIds.isNotEmpty()) {
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
                        } else if (state.selectedFolderId == -2 && !state.isSearchActive) {
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
                                connectionStatus = state.connectionStatus,
                                isProxyEnabled = state.isProxyEnabled,
                                onRetryConnection = { component.retryConnection() },
                                onProxySettingsClick = { component.onProxySettingsClicked() },
                                isSearchActive = state.isSearchActive,
                                searchQuery = state.searchQuery,
                                onSearchQueryChange = component::onSearchQueryChange,
                                onSearchToggle = component::onSearchToggle,
                                onStatusClick = { anchorBounds ->
                                    statusAnchorBounds = anchorBounds ?: statusAnchorBounds
                                    showStatusMenu = true
                                },
                                onMenuClick = { showAccountMenu = true },
                                videoPlayerPool = component.videoPlayerPool
                            )
                        }
                    }
                }

                if (state.connectionStatus == ConnectionStatus.Connecting || state.connectionStatus == ConnectionStatus.Updating || state.connectionStatus == ConnectionStatus.ConnectingToProxy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }

                val isMainView = !state.isSearchActive && state.selectedFolderId != -2

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
                                val visibleTabsHeight = if (isArchivePersistent) {
                                    (tabsHeightPx + (headerOffsetPx + archiveItemHeightPx).coerceAtMost(0f)).coerceAtLeast(
                                        0f
                                    )
                                } else {
                                    (tabsHeightPx + headerOffsetPx).coerceAtLeast(0f)
                                }
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
                                            (archiveItemHeightPx + headerOffsetPx).coerceAtLeast(0f).toDp()
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
                                        isPinned = state.isArchivePinned,
                                        onClick = { component.onFolderClicked(-2) },
                                        onLongClick = { component.onArchivePinToggle() }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                            }

                            if (state.folders.size > 1) {
                                FolderTabs(
                                    modifier = Modifier.offset {
                                        val yOffset = if (isArchivePersistent) {
                                            (headerOffsetPx + archiveItemHeightPx).coerceAtMost(0f)
                                        } else {
                                            headerOffsetPx
                                        }
                                        IntOffset(0, yOffset.roundToInt())
                                    },
                                    folders = state.folders,
                                    pagerState = pagerState,
                                    onTabClick = { index ->
                                        if (pagerState.currentPage == index) {
                                            val folderId = state.folders[index].id
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
                    visible = !state.isSearchActive && state.selectedFolderId != -2 && !state.isForwarding,
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
                    visible = state.isForwarding && state.selectedChatIds.isNotEmpty(),
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
            shape = if (isTablet) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = if (isTablet) Color.Transparent else MaterialTheme.colorScheme.surface
        ) {
            if (state.isSearchActive || state.selectedFolderId == -2) {
                var showAllGlobal by remember { mutableStateOf(false) }
                var showAllMessages by remember { mutableStateOf(false) }

                val scrollState = rememberLazyListState(
                    initialFirstVisibleItemIndex = if (state.selectedFolderId == -2 && !state.isSearchActive) state.scrollPositions[-2]?.first ?: 0 else 0,
                    initialFirstVisibleItemScrollOffset = if (state.selectedFolderId == -2 && !state.isSearchActive) state.scrollPositions[-2]?.second ?: 0 else 0
                )

                if (state.selectedFolderId == -2 && !state.isSearchActive) {
                    scrollStates[-2] = scrollState
                }

                val firstItemId = if (state.selectedFolderId == -2 && !state.isSearchActive) {
                    state.chatsByFolder[-2]?.firstOrNull()?.id
                } else {
                    null
                }

                LaunchedEffect(firstItemId) {
                    if (state.selectedFolderId == -2 && !state.isSearchActive && !scrollState.isScrollInProgress && scrollState.firstVisibleItemIndex <= 1) {
                        scrollState.scrollToItem(0, 0)
                    }
                }

                if (state.selectedFolderId == -2 && !state.isSearchActive) {
                    DisposableEffect(Unit) {
                        onDispose {
                            component.updateScrollPosition(-2, scrollState.firstVisibleItemIndex, scrollState.firstVisibleItemScrollOffset)
                        }
                    }
                }

                val isArchivedView = state.selectedFolderId == -2 && !state.isSearchActive
                val archivedChats = if (isArchivedView) state.chatsByFolder[-2] ?: emptyList() else emptyList()
                val isArchivedLoading = if (isArchivedView) state.isLoadingByFolder[-2] ?: false else false
                val hasArchivedLoadState = if (isArchivedView) state.isLoadingByFolder.containsKey(-2) else false
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
                        contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
                    ) {
                        if (state.isSearchActive) {
                        if (state.searchQuery.isEmpty() && state.searchHistory.isNotEmpty()) {
                            val recentUsers =
                                state.searchHistory.filter { (it.type == ChatType.PRIVATE || it.type == ChatType.SECRET) && !it.isBot }
                            val recentOthers =
                                state.searchHistory.filter { it.type != ChatType.PRIVATE && it.type != ChatType.SECRET || it.isBot }

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

                            if (recentUsers.isNotEmpty()) {
                                item {
                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        itemsIndexed(items = recentUsers, key = { index, chat -> "recent_user_${chat.id}_$index" }) { _, chat ->
                                            Column(
                                                modifier = Modifier
                                                    .width(64.dp)
                                                    .combinedClickable(
                                                        onClick = { onChatClicked(chat.id) },
                                                        onLongClick = { component.onRemoveSearchHistoryItem(chat.id) }
                                                    ),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Box(contentAlignment = Alignment.TopEnd) {
                                                    Avatar(
                                                        path = chat.avatarPath,
                                                        fallbackPath = chat.personalAvatarPath,
                                                        name = chat.title,
                                                        size = 64.dp,
                                                        isOnline = chat.isOnline,
                                                        videoPlayerPool = component.videoPlayerPool
                                                    )
                                                    Box(
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .offset(x = 4.dp, y = (-4).dp)
                                                            .clip(CircleShape)
                                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                                            .clickable { component.onRemoveSearchHistoryItem(chat.id) },
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

                            if (recentOthers.isNotEmpty()) {
                                itemsIndexed(
                                    items = recentOthers,
                                    key = { _, chat -> "recent_${chat.id}" }) { _, chat ->
                                    ChatListItem(
                                        modifier = Modifier.animateItem(),
                                        chat = chat,
                                        currentUserId = state.currentUser?.id,
                                        isSelected = false,
                                        onClick = { onChatClicked(chat.id) },
                                        onLongClick = { component.onRemoveSearchHistoryItem(chat.id) },
                                        emojiFontFamily = emojiFontFamily,
                                        messageLines = messageLines,
                                        showPhotos = showPhotos,
                                        videoPlayerPool = component.videoPlayerPool
                                    )
                                }
                            }
                        }

                        if (state.searchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_section_chats),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            itemsIndexed(items = state.searchResults, key = { index, chat -> "search_${chat.id}_$index" }) { _, chat ->
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    chat = chat,
                                    currentUserId = state.currentUser?.id,
                                    isSelected = state.selectedChatIds.contains(chat.id),
                                    onClick = { onChatClicked(chat.id) },
                                    onLongClick = { onChatLongClicked(chat.id) },
                                    emojiFontFamily = emojiFontFamily,
                                    messageLines = messageLines,
                                    showPhotos = showPhotos,
                                    videoPlayerPool = component.videoPlayerPool
                                )
                            }
                        }

                        if (state.globalSearchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_section_global),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            val globalToDisplay =
                                if (showAllGlobal) state.globalSearchResults else state.globalSearchResults.take(3)

                            itemsIndexed(items = globalToDisplay, key = { _, chat -> "global_${chat.id}" }) { _, chat ->
                                ChatListItem(
                                    modifier = Modifier.animateItem(),
                                    chat = chat,
                                    currentUserId = state.currentUser?.id,
                                    isSelected = state.selectedChatIds.contains(chat.id),
                                    onClick = { onChatClicked(chat.id) },
                                    onLongClick = { onChatLongClicked(chat.id) },
                                    emojiFontFamily = emojiFontFamily,
                                    messageLines = messageLines,
                                    showPhotos = showPhotos,
                                    videoPlayerPool = component.videoPlayerPool
                                )
                            }

                            if (!showAllGlobal && state.globalSearchResults.size > 3) {
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

                        if (state.messageSearchResults.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_section_messages),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            val messagesToDisplay =
                                if (showAllMessages) state.messageSearchResults else state.messageSearchResults.take(3)

                            itemsIndexed(
                                items = messagesToDisplay,
                                key = { index, msg -> "msg_${msg.id}_$index" }) { index, msg ->
                                if (showAllMessages && index >= messagesToDisplay.lastIndex - 5 && state.canLoadMoreMessages) {
                                    LaunchedEffect(Unit) { component.loadMoreMessages() }
                                }

                                MessageSearchItem(
                                    modifier = Modifier.animateItem(),
                                    message = msg,
                                    onClick = { component.onMessageClicked(msg.chatId, msg.id) },
                                    videoPlayerPool = component.videoPlayerPool
                                )
                            }

                            if (!showAllMessages && state.messageSearchResults.size > 3) {
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
                                    currentUserId = state.currentUser?.id,
                                    isSelected = state.selectedChatIds.contains(chat.id),
                                    onClick = { onChatClicked(chat.id) },
                                    onLongClick = { onChatLongClicked(chat.id) },
                                    isTabletSelected = isTablet && state.activeChatId == chat.id,
                                    emojiFontFamily = emojiFontFamily,
                                    messageLines = messageLines,
                                    showPhotos = showPhotos,
                                    videoPlayerPool = component.videoPlayerPool
                                )
                            }
                        }
                    }

                    if (isArchivedView) {
                        Crossfade(
                            targetState = showArchivedShimmer,
                            animationSpec = if (shouldAnimateFirstArchiveTransition) tween(360) else tween(0),
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
                    val folderId = state.folders.getOrNull(page)?.id
                        ?: state.folders.firstOrNull { it.id == state.selectedFolderId }?.id
                    if (folderId == null) {
                        Box(modifier = Modifier.fillMaxSize())
                        return@HorizontalPager
                    }
                    val folderChats = state.chatsByFolder[folderId] ?: emptyList()
                    val isFolderLoading = state.isLoadingByFolder[folderId] ?: false
                    val hasFolderLoadState = state.isLoadingByFolder.containsKey(folderId)
                    val showFolderShimmer = folderChats.isEmpty() && (isFolderLoading || !hasFolderLoadState)
                    val shouldAnimateFirstFolderTransition = firstFolderTransitionCompleted[folderId] != true

                    val scrollState = rememberLazyListState(
                        initialFirstVisibleItemIndex = state.scrollPositions[folderId]?.first ?: 0,
                        initialFirstVisibleItemScrollOffset = state.scrollPositions[folderId]?.second ?: 0
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
                            if (state.scrollPositions[folderId] == null) {
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
                            animationSpec = if (shouldAnimateFirstFolderTransition) tween(360) else tween(0),
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
                                    contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp)
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
                                            currentUserId = state.currentUser?.id,
                                            isSelected = state.selectedChatIds.contains(chat.id),
                                            onClick = { onChatClicked(chat.id) },
                                            onLongClick = { onChatLongClicked(chat.id) },
                                            isTabletSelected = isTablet && state.activeChatId == chat.id,
                                            videoPlayerPool = component.videoPlayerPool,
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
                enter = fadeIn(animationSpec = tween(180)) +
                        slideInVertically(animationSpec = tween(260)) { -it / 5 } +
                        scaleIn(
                            animationSpec = tween(260),
                            initialScale = 0.94f,
                            transformOrigin = TransformOrigin(0.85f, 0f)
                        ),
                exit = fadeOut(animationSpec = tween(130)) +
                        slideOutVertically(animationSpec = tween(170)) { -it / 6 } +
                        scaleOut(
                            animationSpec = tween(170),
                            targetScale = 0.97f,
                            transformOrigin = TransformOrigin(0.85f, 0f)
                        ),
                modifier = Modifier.offset { IntOffset(clampedLeftPx, clampedTopPx) }
            ) {
                Surface(
                    modifier = Modifier
                        .width(menuWidth)
                        .heightIn(max = maxMenuHeightDp)
                        .clip(RoundedCornerShape(24.dp))
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
        visible = state.instantViewUrl != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        state.instantViewUrl?.let { url ->
            InstantViewer(
                url = url,
                messageRepository = koinInject(),
                onDismiss = { component.onDismissInstantView() },
                onOpenWebView = { component.onOpenWebView(it) },
                videoPlayerPool = component.videoPlayerPool
            )
        }
    }

    AnimatedVisibility(
        visible = state.webAppUrl != null || state.webAppBotId != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val webAppUrl = state.webAppUrl
        val botUserId = state.webAppBotId
        val botName = state.webAppBotName

        Log.d("MiniAppViewer", "webAppUrl: $webAppUrl, botUserId: $botUserId, botName: $botName")

        if (botUserId != null) {
            MiniAppViewer(
                chatId = botUserId,
                botUserId = botUserId,
                baseUrl = webAppUrl ?: "",
                botName = botName ?: stringResource(R.string.mini_app_default_name),
                messageRepository = koinInject(),
                onDismiss = { component.onDismissWebApp() }
            )
        }
    }

    if (showDeleteChatsSheet) {
        ConfirmationSheet(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.delete_chats_title, state.selectedChatIds.size),
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
