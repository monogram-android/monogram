package org.monogram.feature.dialog.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.Outcome
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.UploadItem
import org.monogram.core.models.displayedChatAction
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.ChatWallpaper
import org.monogram.core.ui.components.MessageListSkeleton
import org.monogram.core.ui.components.OnlineLeaseExpiry
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SearchField
import org.monogram.core.ui.components.TypingDots
import org.monogram.core.ui.components.UnreadBadge
import org.monogram.core.ui.components.liveActionTransition
import org.monogram.core.ui.components.peerStatusLabel
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.components.typingStatusText
import org.monogram.core.ui.components.uiLabel
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingListSize
import org.monogram.core.ui.menu.AppMenuPlacementState
import org.monogram.core.ui.menu.AppMenuScrimPopup
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogDayKind
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.albumSlice
import org.monogram.feature.dialog.dialogSyncStatus
import org.monogram.feature.dialog.followBottomFromScroll
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isAlbumHead
import org.monogram.feature.dialog.messageRowIndex
import org.monogram.feature.dialog.shouldFollowIncomingNewest
import org.monogram.feature.dialog.shouldPageNewer
import org.monogram.feature.dialog.shouldPageOlder
import org.monogram.feature.dialog.unreadDividerIndex
import org.monogram.feature.dialog.visibleAlbumMessageIds
import org.monogram.network.http.MediaPriority
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import org.monogram.core.ui.components.SponsorBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.DialogHistoryPane(
    component: DialogComponent,
    state: DialogStore.State,
    composer: androidx.compose.runtime.MutableState<TextFieldValue>,
    selectingMessageId: androidx.compose.runtime.MutableState<Int?>,
    selectedMessageIds: androidx.compose.runtime.MutableState<List<Int>>,
    packDocumentId: androidx.compose.runtime.MutableState<Long?>,
    instantViewUrl: androidx.compose.runtime.MutableState<String?>,
    instantViewHash: androidx.compose.runtime.MutableIntState,
    pendingDeleteId: androidx.compose.runtime.MutableState<Int?>,
    taskDraftFor: androidx.compose.runtime.MutableState<Pair<Int, Int>?>,
    peerListId: androidx.compose.runtime.MutableState<Int?>,
    peerListKind: androidx.compose.runtime.MutableState<String?>,
    clipboard: androidx.compose.ui.platform.Clipboard,
    attachScope: kotlinx.coroutines.CoroutineScope,
) {
    val listState = rememberLazyListState()
    var lastScrolledAnchor by rememberSaveable(state.chatId.value) { mutableStateOf<Int?>(null) }
    var followBottom by rememberSaveable(state.chatId.value) { mutableStateOf(false) }
    var previousCount by rememberSaveable(state.chatId.value) { mutableIntStateOf(0) }
    var previousNewestId by rememberSaveable(state.chatId.value) { mutableStateOf<Int?>(null) }
    var menuMessageId by rememberSaveable { mutableStateOf<Int?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val menuVisibility = remember { MutableTransitionState(false) }
    menuVisibility.targetState = menuExpanded
    var menuTouch by remember { mutableStateOf<Offset?>(null) }
    var selectingMessageId by selectingMessageId
    var selectedIds by selectedMessageIds
    var packDocumentId by packDocumentId
    var instantViewUrl by instantViewUrl
    var instantViewHash by instantViewHash
    var pendingDeleteId by pendingDeleteId
    var taskDraftFor by taskDraftFor
    var peerListId by peerListId
    var peerListKind by peerListKind
    LaunchedEffect(menuExpanded, menuVisibility.isIdle, menuVisibility.currentState) {
        if (!menuExpanded && menuVisibility.isIdle && !menuVisibility.currentState) {
            menuMessageId = null
        }
    }
    val menuMessage = state.messages.firstOrNull { it.id.id == menuMessageId }
    val selectingMessage = state.messages.firstOrNull { it.id.id == selectingMessageId }
    val multiSelecting = selectedIds.isNotEmpty()
    val appearance by AppearanceSettings.state.collectAsState()
    val pinned = state.pinnedMessages.getOrNull(state.pinnedIndex)
        ?: state.pinnedMessages.firstOrNull()
    val showPinned = !state.isCommentThread && pinned != null
    val topPadding by animateDpAsState(
        targetValue = if (showPinned) 68.dp else 8.dp,
        label = "pinned-top-padding",
    )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .collapseComposerOnTap(composer.value.selection.length > 0) {
                    composer.value = collapseComposerSelection(composer.value)
                },
        ) {
            ChatWallpaper(
                appearance.wallpaperPath, appearance.wallpaperDim,
                appearance.wallpaperMode, Modifier.fillMaxSize(),
            )
            if (state.messages.isEmpty()) {
                if (state.loading) {
                    MessageListSkeleton(modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        text = stringResource(R.string.dialog_empty),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                CompositionLocalProvider(
                    LocalDialogMedia provides component.mediaRepository,
                ) {
                Box(modifier = Modifier.fillMaxSize()) {
                val listScope = rememberCoroutineScope()
                LaunchedEffect(listState) {
                    snapshotFlow {
                        Triple(
                            listState.isScrollInProgress,
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                        )
                    }.collect { (scrolling, index, offset) ->
                        followBottom = followBottomFromScroll(
                            currentlyFollowing = followBottom,
                            scrolling = scrolling,
                            firstVisibleIndex = index,
                            firstVisibleOffset = offset,
                        )
                    }
                }
                LaunchedEffect(state.messages.firstOrNull()?.id?.id, state.messages.size) {
                    val newestId = state.messages.firstOrNull()?.id?.id
                    val grew = state.messages.size > previousCount && previousCount > 0
                    val newestArrived = newestId != null && newestId != previousNewestId && previousCount > 0
                    val olderPageGrew = grew && !newestArrived
                    previousCount = state.messages.size
                    previousNewestId = newestId
                    if (
                        shouldFollowIncomingNewest(
                            followBottom = followBottom,
                            scrolling = listState.isScrollInProgress,
                            newestArrived = newestArrived,
                            olderPageGrew = olderPageGrew,
                        )
                    ) {
                        listState.scrollToItem(0)
                    }
                }
                val zone = remember { ZoneId.systemDefault() }
                val messageHeads = remember(state.messages) {
                    state.messages.mapIndexedNotNull { index, message ->
                        if (isAlbumHead(state.messages, index)) index to message else null
                    }
                }
                val messageById = remember(state.messages) {
                    state.messages.associateBy { it.id.id }
                }
                val unreadAt = remember(
                    state.messages,
                    state.unreadCount,
                    state.readInboxMaxId,
                    state.hasNewer,
                ) {
                    unreadDividerIndex(
                        state.messages,
                        state.unreadCount,
                        state.readInboxMaxId,
                        state.hasNewer,
                    )
                }
                val unreadRow = unreadAt?.let { messageRowIndex(state.messages, state.messages[it].id.id) }
                LaunchedEffect(state.anchorMessageId, state.messages.size, state.anchorAtTop) {
                    val anchor = state.anchorMessageId ?: return@LaunchedEffect
                    if (anchor == lastScrolledAnchor) return@LaunchedEffect
                    val alreadyVisible = !state.anchorAtTop && listState.firstVisibleItemIndex == 0 &&
                        state.messages.firstOrNull()?.id?.id == anchor
                    if (alreadyVisible) {
                        lastScrolledAnchor = anchor
                        return@LaunchedEffect
                    }
                    val index = messageRowIndex(state.messages, anchor)
                    if (index >= 0) {
                        if (state.anchorAtTop || index > 1) followBottom = false
                        listState.scrollToItem(index)
                        if (state.anchorAtTop) {
                            // A reversed list aligns the row to the bottom edge; the first unread
                            // belongs at the top edge with the unread messages below it.
                            repeat(3) {
                                withFrameNanos { }
                                val layout = listState.layoutInfo
                                val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
                                    ?: return@repeat
                                val height = layout.viewportSize.height -
                                    layout.beforeContentPadding - layout.afterContentPadding
                                listState.scrollToItem(index, row.size - height)
                            }
                        }
                        lastScrolledAnchor = anchor
                    }
                }
                LaunchedEffect(listState, state.messages.size, state.hasOlder, state.loadingOlder) {
                    snapshotFlow {
                        val visible = listState.layoutInfo.visibleItemsInfo
                        Triple(
                            listState.firstVisibleItemIndex,
                            visible.minOfOrNull { it.index },
                            visible.maxOfOrNull { it.index },
                        )
                    }.collect { (first, _, last) ->
                        if (last != null &&
                            historyPagingAllowed(state.searchQuery) &&
                            shouldPageOlder(
                                lastVisibleIndex = last,
                                size = messageHeads.size,
                                hasOlder = state.hasOlder,
                                loadingOlder = state.loadingOlder,
                                firstVisibleIndex = first,
                            )
                        ) {
                            component.onLoadOlder()
                        }
                    }
                }
                LaunchedEffect(listState, state.messages.size, state.hasNewer, state.loadingNewer) {
                    snapshotFlow {
                        listState.firstVisibleItemIndex
                    }.collect { first ->
                        if (
                            historyPagingAllowed(state.searchQuery) &&
                            shouldPageNewer(first, state.hasNewer, state.loadingNewer)
                        ) {
                            component.onLoadNewer()
                        }
                    }
                }
                LaunchedEffect(listState, state.chatId, messageHeads) {
                    snapshotFlow {
                        val scrolling = listState.isScrollInProgress
                        val index = listState.layoutInfo.visibleItemsInfo.minByOrNull { it.index }?.index
                        // reverseLayout: index 0 / no forward scroll means the newest row is on screen.
                        val atLiveEdge = listState.firstVisibleItemIndex == 0 || !listState.canScrollForward
                        Triple(
                            scrolling,
                            index?.let { messageHeads.getOrNull(it)?.second?.id?.id },
                            atLiveEdge,
                        )
                    }.collect { (scrolling, id, atLiveEdge) ->
                        if (scrolling || id == null) return@collect
                        component.onSaveScroll(id)
                        component.onVisibleNewest(id, atLiveEdge)
                    }
                }
                LaunchedEffect(listState, state.chatId, messageHeads) {
                    snapshotFlow {
                        visibleAlbumMessageIds(
                            state.messages,
                            listState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.index },
                        )
                    }.collect { ids ->
                        // Start the viewport prefetch during a fling. Waiting for
                        // isScrollInProgress to clear is what made media pop in late.
                        if (ids.isNotEmpty()) component.onVisibleWindow(ids)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = topPadding,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    reverseLayout = true,
                ) {
                    items(
                        items = messageHeads,
                        key = { (_, msg) -> "${msg.id.chatId.value}:${msg.id.id}" },
                        contentType = { (_, msg) -> msg.mediaKind ?: "text" },
                    ) { (index, message) ->
                        val album = albumSlice(state.messages, index)
                        val olderMessage = state.messages.getOrNull(index + album.size)
                        val newerMessage = state.messages.getOrNull(index - 1)
                        val joinsOlder = message.belongsToSameBlockAs(olderMessage, zone)
                        val joinsNewer = message.belongsToSameBlockAs(newerMessage, zone)
                        val newDay = olderMessage == null ||
                            !DialogTime.sameLocalDay(message.date, olderMessage.date, zone)
                        if (unreadAt != null && index == unreadAt) {
                            Text(
                                text = stringResource(R.string.dialog_unread),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column {
                            if (newDay) {
                                DialogDateSeparator(epochSeconds = message.date, zone = zone)
                            }
                            val selectableForForwarding = album.all(::isForwardSelectionCandidate)
                            val selectedForForwarding = album.any { it.id.id in selectedIds }
                            val rowModifier = if (multiSelecting && selectableForForwarding) {
                                (if (selectedForForwarding) {
                                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                } else {
                                    Modifier
                                })
                                    .forwardSelectionTap(
                                        selected = selectedForForwarding,
                                        onToggle = {
                                            selectedIds = toggleForwardSelection(selectedIds, album)
                                        },
                                    )
                            } else {
                                Modifier
                            }
                            Box(modifier = Modifier.fillMaxWidth().then(rowModifier)) {
                            val quoted = message.replyToMsgId?.let(messageById::get)
                            val caption = album.firstOrNull { !it.text.isNullOrBlank() }
                            SwipeToReply(
                                enabled = menuMessage == null && selectingMessage == null && !multiSelecting &&
                                    message.mediaKind != "service" && !message.pending && message.id.id > 0 &&
                                    (state.canSendPlain || state.canSendPhotos),
                                onReply = { component.onReply(message) },
                            ) {
                            MessageBubble(
                                onMarkupButton = { button ->
                                    component.onBotButton(message.id.id, button, fromKeyboard = false)
                                },
                                onToggleChecklist = { itemId ->
                                    component.onToggleChecklist(message.id.id, itemId)
                                },
                                onPollVote = component::onVotePoll,
                                onAddChecklistItem = { messageId, nextId ->
                                    taskDraftFor = messageId to nextId
                                },
                                selectable = selectingMessage?.id == message.id,
                                message = if (album.size > 1 && caption != null) {
                                    message.copy(text = caption.text, entities = caption.entities)
                                } else {
                                    message
                                },
                                album = album,
                                sender = state.senders[message.senderId],
                                senderTag = state.senderTags[message.senderId],
                                showSender = state.isGroup && !message.outgoing && !joinsOlder,
                                showReadStatus = !state.isChannel && appearance.showReadStatus,
                                showAvatar = state.isGroup && !message.outgoing && !joinsNewer,
                                joinsMessageAbove = joinsOlder,
                                joinsMessageBelow = joinsNewer,
                                mediaRepository = component.mediaRepository,
                                reserveAvatarGutter = state.isGroup && !message.outgoing,
                                onOpenSender = message.senderId?.let { id ->
                                    { component.onOpenPeer(id) }
                                },
                                onOpenForwardSource = message.fwdFromId?.let { id ->
                                    { component.onOpenPeer(PeerId(id)) }
                                },
                                onOpenMenu = if (multiSelecting) null else { position ->
                                    selectingMessageId = null
                                    menuTouch = position
                                    menuMessageId = message.id.id
                                    menuExpanded = true
                                    component.onLoadReadReceipts(message.id.id)
                                },
                                onOpenStickerPack = { packDocumentId = it },
                                onInstantView = { preview ->
                                    instantViewUrl = preview.url
                                    instantViewHash = preview.hash
                                },
                                quoted = quoted,
                                quotedSender = quoted?.senderId?.let { state.senders[it]?.title },
                                hideTopicRootReply = state.inForumTopic &&
                                    message.replyToMsgId == state.threadTopId,
                                onReact = { emoji, doc ->
                                    component.onReact(message.id.id, emoji, doc)
                                },
                                onShowReactionUsers = if (state.isChannel) {
                                    null
                                } else {
                                    {
                                        component.onLoadReactionUsers(message.id.id)
                                        peerListId = message.id.id
                                        peerListKind = "reactions"
                                    }
                                },
                                onShowPollVoters = {
                                    component.onLoadPollVoters(message.id.id)
                                    peerListId = message.id.id
                                    peerListKind = "poll"
                                },
                                onAddReaction = {
                                    menuTouch = null
                                    menuMessageId = message.id.id
                                    menuExpanded = true
                                },
                                onComments = message.discussionPeerId?.let {
                                    { component.onOpenComments(message) }
                                },
                                onQuoteClick = message.replyToMsgId?.takeIf { it > 0 }?.let { target ->
                                    { component.onJumpToMessage(target) }
                                },
                                modifier = Modifier.padding(top = if (joinsOlder) 0.dp else 6.dp),
                            )
                            }
                            if (menuMessage?.id == message.id &&
                                (!menuVisibility.isIdle || menuVisibility.currentState || menuVisibility.targetState)
                            ) {
                            val menuPlacement = remember { AppMenuPlacementState() }
                            AppMenuScrimPopup(
                                visible = menuExpanded,
                                onDismiss = { menuExpanded = false },
                            )
                            Popup(
                                popupPositionProvider = rememberMessageMenuPosition(
                                    outgoing = message.outgoing,
                                    touch = menuTouch,
                                    placementState = menuPlacement,
                                ),
                                onDismissRequest = { menuExpanded = false },
                                properties = PopupProperties(
                                    focusable = true,
                                    clippingEnabled = false,
                                ),
                            ) {
                            MessageActionMenu(
                                expanded = menuExpanded,
                                visibilityState = menuVisibility,
                                message = menuMessage,
                                actions = messageMenuActions(state, message),
                                outgoing = message.outgoing,
                                growth = menuPlacement.growth,
                                seenByRow = if (state.isGroup && !state.isChannel) {
                                    {
                                        MessageSeenByRow(
                                            viewers = state.messageViewers[message.id.id],
                                            viewerAvatar = { viewer ->
                                                viewer.avatarCacheKey
                                                    ?.let { component.mediaRepository?.cachedFile(it) }
                                                    ?: component.mediaRepository?.cachedAvatar(
                                                        PeerId(viewer.peerId.value),
                                                    )
                                            },
                                            onOpenProfile = { id ->
                                                component.onOpenPeer(PeerId(id))
                                            },
                                        )
                                    }
                                } else {
                                    {
                                        OutboxReadRow(
                                            state = state.outboxReadStates[message.id.id],
                                            onOpenPrivacy = null,
                                        )
                                    }
                                },
                                onDismiss = { menuExpanded = false },
                                onReply = component::onReply,
                                onCopy = { copied ->
                                    copied.text?.takeIf { it.isNotBlank() }?.let {
                                        attachScope.launch {
                                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", it)))
                                        }
                                    }
                                },
                                onSelectText = { selectingMessageId = it.id.id },
                                onSelectForForwarding = {
                                    selectedIds = toggleForwardSelection(selectedIds, album)
                                },
                                onEdit = component::onEdit,
                                onDelete = { pendingDeleteId = it.id.id },
                                onForward = component::onForwardPick,
                                onReact = { emoji, doc ->
                                    component.onReact(message.id.id, emoji, doc)
                                },
                                recentReactions = state.recentReactions,
                            )
                            }
                            }
                            }
                        }
                    }
                    if (state.loadingOlder) {
                        item(key = "loading-older") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                MonogramLoading(size = MonogramLoadingListSize)
                            }
                        }
                }
                }
                val firstVisible by remember {
                    derivedStateOf { listState.firstVisibleItemIndex }
                }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.unreadReactionsCount > 0,
                        enter = fadeIn() + scaleIn(initialScale = 0.82f),
                        exit = fadeOut() + scaleOut(targetScale = 0.82f),
                    ) {
                        Box {
                            SmallFloatingActionButton(
                                onClick = {
                                    lastScrolledAnchor = null
                                    component.onJumpUnreadReaction()
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = stringResource(R.string.dialog_jump_unread_reaction),
                                )
                            }
                            UnreadBadge(
                                count = state.unreadReactionsCount,
                                muted = false,
                                compact = true,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp),
                            )
                        }
                    }
                    if (state.unreadReactionsCount > 0 && state.unreadMentionsCount > 0) {
                        Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.unreadMentionsCount > 0,
                        enter = fadeIn() + scaleIn(initialScale = 0.82f),
                        exit = fadeOut() + scaleOut(targetScale = 0.82f),
                    ) {
                        Box {
                            val mentionJump = stringResource(R.string.dialog_jump_mention)
                            SmallFloatingActionButton(
                                onClick = {
                                    lastScrolledAnchor = null
                                    component.onJumpMention()
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { contentDescription = mentionJump },
                            ) {
                                Text(
                                    text = "@",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            UnreadBadge(
                                count = state.unreadMentionsCount,
                                muted = false,
                                compact = true,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp),
                            )
                        }
                    }
                    if ((state.unreadMentionsCount > 0 || state.unreadReactionsCount > 0) && firstVisible > 0) {
                        Spacer(Modifier.height(8.dp))
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = firstVisible > 0,
                        enter = fadeIn() + scaleIn(initialScale = 0.82f),
                        exit = fadeOut() + scaleOut(targetScale = 0.82f),
                    ) {
                        // Badge lives outside the button: FAB content is clipped to the
                        // button shape, which cut the overhanging counter off.
                        Box {
                            SmallFloatingActionButton(
                                onClick = {
                                    if (unreadRow != null && firstVisible > unreadRow) {
                                        // Re-arm the anchor: the same unread message may be tapped twice.
                                        lastScrolledAnchor = null
                                        component.onJumpUnread()
                                    } else {
                                        followBottom = true
                                        if (!state.hasNewer) {
                                            listScope.launch { listState.scrollToItem(0) }
                                        }
                                        component.onJumpLatest()
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = if (unreadRow != null && firstVisible > unreadRow) {
                                        stringResource(R.string.dialog_jump_unread)
                                    } else {
                                        stringResource(R.string.dialog_jump_latest)
                                    },
                                )
                            }
                            UnreadBadge(
                                count = state.unreadCount,
                                muted = false,
                                compact = true,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp),
                            )
                        }
                    }
                    }
            }
            }
        val hasInlineHits = state.inlineResults?.results?.isNotEmpty() == true
        val showInline = state.inlineQuery != null &&
            (state.inlineLoading || hasInlineHits || (state.inlineResults != null && !state.inlineError))
        if (showInline) {
            InlineBotResultsOverlay(
                query = state.inlineQuery!!,
                page = state.inlineResults,
                loading = state.inlineLoading,
                error = state.inlineError,
                mediaRepository = component.mediaRepository,
                onSelect = component::onSendInlineResult,
                onLoadMore = component::onLoadMoreInlineResults,
                onRetry = component::onRetryInlineResults,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            val showMentions = state.mentionToken != null &&
                (state.mentionLoading ||
                    state.mentionCandidates.isNotEmpty() ||
                    state.isGroup ||
                    state.isChannel)
            MentionOverlayHost(
                visible = showMentions,
                handle = state.mentionToken.orEmpty(),
                candidates = state.mentionCandidates,
                loading = state.mentionLoading,
                mediaRepository = component.mediaRepository,
                onSelect = component::onSelectMention,
                hasMore = state.mentionHasMore,
                onLoadMore = component::onLoadMoreMentions,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showPinned,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (pinned != null) {
                PinnedMessageBar(
                    pinned = pinned,
                    total = state.pinnedMessages.size,
                    mediaRepository = component.mediaRepository,
                    onJump = component::onNextPinned,
                    onOpenList = component::onOpenPinnedList,
                )
            }
        }
        }
    }
}

private fun Modifier.forwardSelectionTap(
    selected: Boolean,
    onToggle: () -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    role = Role.Checkbox
    toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
    onClick {
        onToggle()
        true
    }
}.pointerInput(onToggle) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop || change.isConsumed) {
                moved = true
            }
            if (!change.pressed && !moved) {
                change.consume()
                onToggle()
            }
        } while (event.changes.any { it.pressed })
    }
}
