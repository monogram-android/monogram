package org.monogram.feature.chats.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arkivanov.essenty.backhandler.BackCallback
import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.displayPreview
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.ChatListSkeleton
import org.monogram.core.ui.components.FolderChipItem
import org.monogram.core.ui.components.FolderChipRow
import org.monogram.core.ui.components.FolderChips
import org.monogram.core.ui.components.isPeerOnline
import org.monogram.core.ui.components.uiLabel
import org.monogram.core.ui.collectWhenActive
import org.monogram.core.ui.perf.RecompositionProbe
import org.monogram.core.ui.perf.perfSpan
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.showsMiniPlayer
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.feature.chats.ChatsComponent
import org.monogram.feature.chats.archivePreviewTitles
import org.monogram.feature.chats.FolderListScroll
import org.monogram.feature.chats.clampFolderScroll
import org.monogram.feature.chats.defaultFolderId
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.feature.chats.R
import org.monogram.feature.chats.folderChipItems
import org.monogram.feature.chats.folderScrollKey
import org.monogram.feature.chats.folderUnreadBadge
import org.monogram.feature.chats.onFolderChipClick
import org.monogram.feature.chats.unreadChatIds
import org.monogram.feature.chats.visibleChats
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.core.ui.components.SearchField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsContent(
    component: ChatsComponent,
    folders: List<Folder> = emptyList(),
    modifier: Modifier = Modifier,
    selectedChatId: Long? = null,
    listActive: Boolean = true,
) {
    val state = collectWhenActive(component.state, listActive)
    val appearance by AppearanceSettings.state.collectAsState()
    if (state.chats.isEmpty() && state.loading && state.error == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var selectedFolderId by rememberSaveable { mutableStateOf<Int?>(null) }
    // The list opens on the first folder; a tap or the archive outranks that default.
    var folderChoiceMade by rememberSaveable { mutableStateOf(false) }
    val defaultFolder = remember(folders, appearance.showAllChats) { defaultFolderId(folders, appearance.showAllChats) }
    var archiveOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<FolderChipItem?>(null) }
    var rowMenu by remember { mutableStateOf<Chat?>(null) }
    var searchFocusRequest by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val archiveListState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val texts = chatListTexts()
    var folderScroll by rememberSaveable(stateSaver = FolderScrollSaver) {
        mutableStateOf(emptyMap<Int, FolderListScroll>())
    }
    val homeFolderId = selectedFolderId.takeUnless { it == ARCHIVE_FOLDER_ID }
    val viewingArchive = archiveOpen || selectedFolderId == ARCHIVE_FOLDER_ID
    val selectFolder: (Int?) -> Unit = { id ->
        val next = onFolderChipClick(
            selectedId = homeFolderId,
            tappedId = id,
            currentScroll = FolderListScroll(
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
            ),
            saved = folderScroll,
        )
        folderScroll = next.saved
        selectedFolderId = next.selectedId
        folderChoiceMade = true
        if (next.scrollToTop) {
            scope.launch { listState.animateScrollToItem(0) }
        } else {
            val restored = clampFolderScroll(
                next.saved[folderScrollKey(next.selectedId)],
                state.chats.size,
            )
            scope.launch { listState.scrollToItem(restored.index, restored.offset) }
        }
    }
    val selectFolderState = rememberUpdatedState(selectFolder)
    val stableSelectFolder: (Int?) -> Unit = remember { { id -> selectFolderState.value(id) } }
    val openFoldersState = rememberUpdatedState(component::onOpenFolders)
    val stableOpenFolders: () -> Unit = remember { { openFoldersState.value() } }
    val chipLongPressState = rememberUpdatedState<(FolderChipItem) -> Unit>(
        newValue = { item -> folderMenu = item },
    )
    val stableChipLongPress: (FolderChipItem) -> Unit = remember {
        { item -> chipLongPressState.value(item) }
    }
    val loadMoreState = rememberUpdatedState(component::onLoadMore)
    val stableLoadMore: () -> Unit = remember { { loadMoreState.value() } }
    val openChatState = rememberUpdatedState(component::onChatClick)
    val stableOpenChat: (PeerId) -> Unit = remember { { id -> openChatState.value(id) } }
    val openAvatarState = rememberUpdatedState(component::onPeerProfile)
    val stableOpenAvatar: (PeerId) -> Unit = remember { { id -> openAvatarState.value(id) } }
    val markReadState = rememberUpdatedState(component::onMarkRead)
    val stableMarkReadIds: (List<PeerId>) -> Unit = remember { { ids -> markReadState.value(ids) } }
    val stableMarkReadOne: (PeerId) -> Unit = remember { { id -> markReadState.value(listOf(id)) } }
    val markUnreadState = rememberUpdatedState<(PeerId) -> Unit>(
        newValue = { id -> component.onMarkUnread(id, true) },
    )
    val stableMarkUnread: (PeerId) -> Unit = remember { { id -> markUnreadState.value(id) } }
    val queryState = rememberUpdatedState(component::onQueryChanged)
    val stableQueryChanged: (String) -> Unit = remember { { value -> queryState.value(value) } }
    val stableDismissFolderMenu = remember { { folderMenu = null } }
    val stableDismissRowMenu = remember { { rowMenu = null } }
    val stableRowMenu: (Chat) -> Unit = remember { { chat -> rowMenu = chat } }
    val markFolderReadState = rememberUpdatedState<(List<PeerId>) -> Unit> { ids ->
        folderMenu = null
        markReadState.value(ids)
    }
    val stableMarkFolderRead: (List<PeerId>) -> Unit =
        remember { { ids -> markFolderReadState.value(ids) } }
    val editFoldersState = rememberUpdatedState {
        folderMenu = null
        openFoldersState.value()
    }
    val stableEditFolders: () -> Unit = remember { { editFoldersState.value() } }
    // Paging follows the visible folder: a rule-based folder needs its own dialog stream.
    LaunchedEffect(homeFolderId, viewingArchive) {
        component.onFolderSelected(
            if (viewingArchive) ARCHIVE_FOLDER_ID else homeFolderId,
        )
    }
    // Folders arrive after the first frame; a selected folder that vanished also lands here.
    LaunchedEffect(defaultFolder, folders, archiveOpen, appearance.showAllChats) {
        if (archiveOpen) return@LaunchedEffect
        val current = selectedFolderId
        val missing = (current != null && current != ARCHIVE_FOLDER_ID &&
            folders.isNotEmpty() && folders.none { it.id == current }) ||
            (current == null && !appearance.showAllChats && defaultFolder != null)
        if (missing) {
            selectedFolderId = defaultFolder
            folderChoiceMade = defaultFolder != null
        }
        if ((current == null || missing) && !folderChoiceMade && defaultFolder != null) {
            selectedFolderId = defaultFolder
        }
    }
    val closeArchive = {
        searchOpen = false
        component.onQueryChanged("")
        archiveOpen = false
        if (selectedFolderId == ARCHIVE_FOLDER_ID) selectedFolderId = null
    }
    val openArchive = {
        searchOpen = false
        component.onQueryChanged("")
        archiveOpen = true
        folderChoiceMade = true
    }
    val openArchiveState = rememberUpdatedState(openArchive)
    val stableOpenArchive: () -> Unit = remember { { openArchiveState.value() } }
    DisposableEffect(component, viewingArchive) {
        val callback = BackCallback(isEnabled = viewingArchive) {
            searchOpen = false
            component.onQueryChanged("")
            archiveOpen = false
            if (selectedFolderId == ARCHIVE_FOLDER_ID) selectedFolderId = null
        }
        component.backHandler.register(callback)
        onDispose { component.backHandler.unregister(callback) }
    }

    LaunchedEffect(searchFocusRequest) {
        if (searchFocusRequest <= 0) return@LaunchedEffect
        // Wait for the search field to enter the composition before focusing it.
        repeat(2) { withFrameNanos { } }
        runCatching { searchFocus.requestFocus() }
            .onFailure { AppLog.warn("chats", "search focus unavailable") }
    }

    val allChatsLabel = stringResource(R.string.chats_folder_all)
    val archiveLabel = stringResource(R.string.chats_folder_archive)
    val brandLabel = stringResource(R.string.chats_brand)
    val selfOnline = isPeerOnline(state.self?.status, null)
    val selfPeerId = state.self?.id
    val openAvatarsInProfile = appearance.openProfileOnAvatarTap
    val showChatAvatars = appearance.showChatAvatars
    val showReadStatus = appearance.showReadStatus
    val showMutedCounter = appearance.showMutedCounter
    val markReadLabel = stringResource(R.string.chats_mark_read)
    val markAllReadLabel = stringResource(R.string.chats_mark_all_read)
    val markUnreadLabel = stringResource(R.string.chats_mark_unread)
    val manageFoldersLabel = stringResource(R.string.chats_folder_manage)
    val foldersAtBottom = appearance.foldersAtBottom
    val showAllChats = appearance.showAllChats
    val chipItems = remember(state.chats, folders, showMutedCounter, allChatsLabel, showAllChats) {
        perfSpan("chips") {
            FolderChips(
                folderChipItems(
                    chats = state.chats,
                    folders = folders,
                    allChatsLabel = allChatsLabel,
                    showMutedCounter = showMutedCounter,
                    showAllChats = showAllChats,
                ),
            )
        }
    }
    val allChats = remember { ChatListSnapshot() }
    val shownChats = remember { ChatListSnapshot() }
    val archivedChats = remember { ChatListSnapshot() }
    allChats.replace(state.chats)
    perfSpan("shownChats") {
        shownChats.replace(filterChats(visibleChats(state.chats, folders, homeFolderId), state.query))
    }
    val archivedSlice = remember(state.chats) {
        perfSpan("archiveSlice") {
            visibleChats(state.chats, emptyList(), ARCHIVE_FOLDER_ID)
        }
    }
    perfSpan("archivedChats") {
        archivedChats.replace(filterChats(archivedSlice, state.query))
    }
    val archivedPreview = remember(archivedSlice) {
        folderUnreadBadge(archivedSlice)
    }
    val archivedTitles = remember(archivedSlice, texts.untitled) {
        archivePreviewTitles(
            chats = archivedSlice,
            untitledLabel = texts.untitled,
        )
    }
    // Counted for visibility only: the header never prints totals.
    val archivedCount = archivedSlice.size
    val layoutDirection = LocalLayoutDirection.current

    AnimatedContent(
        targetState = viewingArchive,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val dir = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
            if (targetState) {
                slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it / 4 * dir }
            } else {
                slideInHorizontally { -it / 4 * dir } togetherWith slideOutHorizontally { it * dir }
            }
        },
        label = "archive-screen",
    ) { archive ->
        RecompositionProbe(if (archive) "ChatsPaneArchive" else "ChatsPaneHome")
        val paneChats = if (archive) archivedChats else shownChats
        val paneListState = if (archive) archiveListState else listState
        val paneEmpty = paneChats.isEmpty
        val atTop by remember(paneListState) {
            derivedStateOf {
                paneListState.firstVisibleItemIndex == 0 &&
                    paneListState.firstVisibleItemScrollOffset <= AtTopTolerance
            }
        }
        val rawSync = when {
            paneEmpty && (state.loading || state.syncing) -> AppSyncStatus.Connecting
            state.syncing || (state.loading && !paneEmpty) -> AppSyncStatus.Syncing
            state.loadingMore -> AppSyncStatus.LoadingMore
            else -> AppSyncStatus.Hidden
        }
        val syncStatus = rememberDebouncedSync(
            status = rawSync,
            immediate = paneEmpty && rawSync == AppSyncStatus.Connecting,
        )
        val unreadChats = paneChats.unmutedUnread
        val subtitle = when {
            syncStatus == AppSyncStatus.Connecting -> syncStatus.uiLabel()
            syncStatus == AppSyncStatus.Syncing -> syncStatus.uiLabel()
            state.error?.kind == TelegramError.Kind.Network ->
                stringResource(R.string.chats_waiting_network)
            unreadChats > 0 ->
                pluralStringResource(R.plurals.chats_unread_chats, unreadChats, unreadChats)
            else -> null
        }
        val folderTitle = when {
            archive -> archiveLabel
            homeFolderId == null -> allChatsLabel
            else -> folders.firstOrNull { it.id == homeFolderId }?.title ?: allChatsLabel
        }
        val markAllIds = remember(state.chats, archive) { unreadChatIds(state.chats, archive) }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                ChatsTopBar(
                    atTop = atTop,
                    brandTitle = if (archive) archiveLabel else brandLabel,
                    brandEmojiDocumentId = if (archive) null else state.self?.emojiStatusDocumentId,
                    folderTitle = folderTitle,
                    folderSubtitle = subtitle,
                    archive = archive,
                    selfTitle = state.self?.title ?: stringResource(R.string.chats_profile),
                    selfAvatar = rememberSelfAvatar(
                        self = state.self,
                        mediaRepository = component.mediaRepository,
                    ),
                    selfOnline = selfOnline,
                    mediaRepository = component.mediaRepository,
                    searchOpen = searchOpen,
                    onBack = closeArchive,
                    onToggleSearch = { searchOpen = !searchOpen },
                    onOpenProfile = component::onOpenSelfProfile,
                    onOpenSettings = component::onOpenSettings,
                    onMarkAllRead = { component.onMarkRead(markAllIds) },
                    searchLabel = stringResource(R.string.chats_search),
                    searchCloseLabel = stringResource(R.string.chats_search_close),
                    backLabel = stringResource(R.string.chats_back),
                    profileLabel = stringResource(R.string.chats_my_profile),
                    settingsLabel = stringResource(R.string.chats_settings),
                    markAllReadLabel = markAllReadLabel,
                    markAllReadEnabled = markAllIds.isNotEmpty(),
                )
            },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = inner.calculateTopPadding()),
            ) {
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SearchField(
                        query = state.query,
                        onQueryChanged = component::onQueryChanged,
                        placeholder = stringResource(R.string.chats_search),
                        closeLabel = stringResource(R.string.chats_search_close),
                        focusRequester = searchFocus,
                    )
                }
                AppStatusBanner(
                    sync = syncStatus,
                    error = state.error,
                    onRetry = component::onRefresh,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            folderSwipeModifier(
                                folderIds = chipItems.items.map { it.id },
                                selectedId = homeFolderId,
                                enabled = !archive && !searchOpen && state.query.isBlank(),
                                onSelect = stableSelectFolder,
                            ),
                        ),
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.loading && paneEmpty,
                        modifier = Modifier.fillMaxSize(),
                        enter = fadeIn(animationSpec = tween(180)),
                        exit = fadeOut(animationSpec = tween(220)),
                    ) {
                        ChatListSkeleton(showAvatar = appearance.showChatAvatars)
                    }
                    ChatsLazyList(
                        chats = paneChats,
                        listState = paneListState,
                        archive = archive,
                        searchOpen = searchOpen,
                        query = state.query,
                        homeFolderId = homeFolderId,
                        chips = chipItems,
                        allChats = allChats,
                        folders = folders,
                        archivedTitles = archivedTitles,
                        archivedUnmuted = archivedPreview.unmuted,
                        archivedMuted = if (showMutedCounter) archivedPreview.muted else 0,
                        showArchiveRow = !archive && homeFolderId == null && !searchOpen &&
                            state.query.isBlank() && archivedCount > 0,
                        loading = state.loading,
                        error = state.error,
                        hasMore = state.hasMore,
                        loadingMore = state.loadingMore,
                        selectedChatId = selectedChatId,
                        selfPeerId = selfPeerId,
                        showAvatar = showChatAvatars,
                        showReadStatus = showReadStatus,
                        openAvatarsInProfile = openAvatarsInProfile,
                        texts = texts,
                        mediaRepository = component.mediaRepository,
                        folderMenu = folderMenu,
                        rowMenuId = rowMenu?.id?.value,
                        onSelectFolder = stableSelectFolder,
                        onManageFolders = stableOpenFolders,
                        onFolderLongPress = stableChipLongPress,
                        onDismissFolderMenu = stableDismissFolderMenu,
                        onMarkFolderRead = stableMarkFolderRead,
                        onEditFolders = stableEditFolders,
                        onOpenArchive = stableOpenArchive,
                        onOpenChat = stableOpenChat,
                        onOpenAvatar = stableOpenAvatar,
                        onRowMenu = stableRowMenu,
                        onDismissRowMenu = stableDismissRowMenu,
                        onMarkRead = stableMarkReadOne,
                        onMarkUnread = stableMarkUnread,
                        onClearSearch = stableQueryChanged,
                        onLoadMore = stableLoadMore,
                        markReadLabel = markReadLabel,
                        markUnreadLabel = markUnreadLabel,
                        manageFoldersLabel = manageFoldersLabel,
                        foldersAtBottom = foldersAtBottom,
                    )
                    if (foldersAtBottom && !archive && !searchOpen) {
                        FloatingFolderBar(
                            chips = chipItems,
                            selectedId = homeFolderId,
                            allChats = allChats,
                            folders = folders,
                            folderMenu = folderMenu,
                            markReadLabel = markReadLabel,
                            manageFoldersLabel = manageFoldersLabel,
                            onSelectFolder = stableSelectFolder,
                            onManageFolders = stableOpenFolders,
                            onFolderLongPress = stableChipLongPress,
                            onDismissFolderMenu = stableDismissFolderMenu,
                            onMarkFolderRead = stableMarkFolderRead,
                            onEditFolders = stableEditFolders,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

/** Per-chat long-press actions. Telegram offers exactly one of mark read / mark unread. */
@Composable
internal fun ChatRowMenu(
    unread: Boolean,
    markReadLabel: String,
    markUnreadLabel: String,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
) {
    AppMenuSurface {
        AppMenuGroup {
            if (unread) {
                AppMenuItem(
                    text = markReadLabel,
                    icon = Icons.Outlined.MarkChatRead,
                    onClick = onMarkRead,
                )
            } else {
                AppMenuItem(
                    text = markUnreadLabel,
                    icon = Icons.Outlined.MarkChatUnread,
                    onClick = onMarkUnread,
                )
            }
        }
    }
}

@Composable
private fun FloatingFolderBar(
    chips: FolderChips,
    selectedId: Int?,
    allChats: ChatListSnapshot,
    folders: List<Folder>,
    folderMenu: FolderChipItem?,
    markReadLabel: String,
    manageFoldersLabel: String,
    onSelectFolder: (Int?) -> Unit,
    onManageFolders: () -> Unit,
    onFolderLongPress: (FolderChipItem) -> Unit,
    onDismissFolderMenu: () -> Unit,
    onMarkFolderRead: (List<PeerId>) -> Unit,
    onEditFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val miniPlayerPad = if (MediaPlaybackHolder.session(context).showsMiniPlayer()) 72.dp else 0.dp
    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp + miniPlayerPad),
        shape = MaterialTheme.shapes.extraLarge,
        color = barColor,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Box {
            FolderChipRow(
                chips = chips,
                selectedId = selectedId,
                onSelect = onSelectFolder,
                onManage = onManageFolders,
                manageContentDescription = manageFoldersLabel,
                onLongPress = onFolderLongPress,
                edgeFadeColor = barColor,
            )
            AppMenuPopup(
                expanded = folderMenu != null,
                onDismiss = onDismissFolderMenu,
            ) {
                FolderChipMenu(
                    chats = allChats.items(),
                    folders = folders,
                    folderId = folderMenu?.id,
                    markReadLabel = markReadLabel,
                    editLabel = manageFoldersLabel,
                    onMarkRead = onMarkFolderRead,
                    onEditFolders = onEditFolders,
                )
            }
        }
    }
}

@Composable
internal fun FolderChipMenu(
    chats: List<Chat>,
    folders: List<Folder>,
    folderId: Int?,
    markReadLabel: String,
    editLabel: String,
    onMarkRead: (List<PeerId>) -> Unit,
    onEditFolders: () -> Unit,
) {
    val unread = remember(folderId, chats, folders) {
        if (folderId == null) emptyList() else {
            visibleChats(chats, folders, folderId)
                .filter { it.unreadCount > 0 }
                .map { it.id }
        }
    }
    AppMenuSurface {
        AppMenuGroup {
            AppMenuItem(
                text = markReadLabel,
                icon = Icons.Outlined.MarkChatRead,
                enabled = unread.isNotEmpty(),
                onClick = { onMarkRead(unread) },
            )
            AppMenuItem(
                text = editLabel,
                icon = Icons.Outlined.Edit,
                onClick = onEditFolders,
            )
        }
    }
}

@Composable
private fun rememberSelfAvatar(
    self: Profile?,
    mediaRepository: MediaRepository?,
) = rememberEnsuredFile(
    generation = rememberCacheGeneration(
        remember(mediaRepository, self?.id, self?.avatarCacheKey) {
            self?.id?.let { mediaRepository?.cacheGeneration(peerAvatarCacheKey(it, self.avatarCacheKey)) }
        },
    ),
    identity = self?.id?.value to self?.avatarCacheKey,
    resolve = {
        self?.avatarCacheKey?.let { mediaRepository?.cachedFile(it) }
            ?: self?.id?.let { mediaRepository?.cachedAvatar(it) }
    },
    ensure = {
        val repo = mediaRepository ?: return@rememberEnsuredFile null
        val peer = self?.id ?: return@rememberEnsuredFile null
        when (
            val result = repo.ensureLocalAvatar(
                peer,
                peerAvatarCacheKey(peer, self.avatarCacheKey),
                MediaPriority.THUMB,
            )
        ) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> null
        }
    },
)

@Composable
private fun rememberDebouncedSync(
    status: AppSyncStatus,
    immediate: Boolean,
): AppSyncStatus {
    var shown by remember { mutableStateOf(status) }
    LaunchedEffect(status, immediate) {
        when {
            status == AppSyncStatus.Hidden -> shown = AppSyncStatus.Hidden
            immediate -> shown = status
            else -> {
                kotlinx.coroutines.delay(220)
                shown = status
            }
        }
    }
    return shown
}

private fun filterChats(
    chats: List<Chat>,
    query: String,
): List<Chat> {
    val q = query.trim()
    if (q.isEmpty()) return chats
    return chats.filter {
        it.title.contains(q, ignoreCase = true) ||
            it.displayPreview().contains(q, ignoreCase = true)
    }
}

/** Scroll slop that still counts as "the list is at the top". */
private const val AtTopTolerance = 8

private val FolderScrollSaver = listSaver<Map<Int, FolderListScroll>, Int>(
    save = { entries -> entries.flatMap { (id, scroll) -> listOf(id, scroll.index, scroll.offset) } },
    restore = { values ->
        values.chunked(3).associate { (id, index, offset) -> id to FolderListScroll(index, offset) }
    },
)

@Composable
internal fun FolderEmptyState(
    archive: Boolean,
    filtered: Boolean,
    query: String,
    onShowAll: () -> Unit,
    onClearSearch: (String) -> Unit,
) {
    val motion = listMotionEnabled()
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val enterFloat = spring<Float>(dampingRatio = 0.6f, stiffness = 800f)
    val enterDp = spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.6f, stiffness = 800f)
    val badgeScale by animateFloatAsState(
        targetValue = if (appeared || !motion) 1f else 0.82f,
        animationSpec = if (motion) enterFloat else snap(),
        label = "emptyBadge",
    )
    val badgeAlpha by animateFloatAsState(
        targetValue = if (appeared || !motion) 1f else 0f,
        animationSpec = if (motion) tween(180) else snap(),
        label = "emptyBadgeAlpha",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (appeared || !motion) 1f else 0f,
        animationSpec = if (motion) tween(220, delayMillis = 60) else snap(),
        label = "emptyText",
    )
    val textShift by animateDpAsState(
        targetValue = if (appeared || !motion) 0.dp else 14.dp,
        animationSpec = if (motion) enterDp else snap(),
        label = "emptyTextShift",
    )
    val icon = when {
        archive -> Icons.Outlined.Inventory2
        query.isNotBlank() -> Icons.Outlined.SearchOff
        else -> Icons.Outlined.Forum
    }
    val title = when {
        archive -> stringResource(R.string.chats_archive_empty)
        query.isNotBlank() -> stringResource(R.string.chats_search_empty)
        filtered -> stringResource(R.string.chats_folder_empty)
        else -> stringResource(R.string.chats_empty)
    }
    val body = when {
        archive -> stringResource(R.string.chats_archive_empty_body)
        query.isNotBlank() -> stringResource(R.string.chats_search_empty_body, query)
        filtered -> stringResource(R.string.chats_folder_empty_body)
        else -> stringResource(R.string.chats_empty_body)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = badgeScale
                    scaleY = badgeScale
                    alpha = badgeAlpha
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = textAlpha
                translationY = textShift.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (query.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(
                    onClick = { onClearSearch("") },
                    shapes = ExpressiveDefaults.buttonShapesFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(stringResource(R.string.chats_search_clear))
                }
            } else if (filtered) {
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(
                    onClick = onShowAll,
                    shapes = ExpressiveDefaults.buttonShapesFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(stringResource(R.string.chats_empty_show_all))
                }
            }
        }
    }
}

@Composable
private fun listMotionEnabled(): Boolean =
    android.provider.Settings.Global.getFloat(
        androidx.compose.ui.platform.LocalContext.current.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) > 0f
