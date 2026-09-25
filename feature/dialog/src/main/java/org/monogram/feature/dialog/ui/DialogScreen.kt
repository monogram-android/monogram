package org.monogram.feature.dialog.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.monogram.core.models.poll
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
import org.monogram.core.ui.components.listItemMotion
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
import org.monogram.feature.dialog.liftAnchorTarget
import org.monogram.feature.dialog.shouldFollowIncomingNewest
import org.monogram.feature.dialog.shouldPageNewer
import org.monogram.feature.dialog.shouldPageOlder
import org.monogram.feature.dialog.unreadDividerIndex
import org.monogram.network.http.MediaPriority
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import org.monogram.core.ui.components.SponsorBadge
import androidx.compose.ui.platform.LocalResources

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialogScreen(component: DialogComponent, modifier: Modifier) {
    val state by component.state.collectAsState()
    val mediaTitleHolder = LocalChatTitleHolder.current
    SideEffect { mediaTitleHolder?.value = state.title }
    val receiptHolder = LocalReadReceiptHolder.current
    val resources = LocalResources.current
    SideEffect {
        receiptHolder?.label = { message ->
            seenByLabelFor(
                viewers = state.messageViewers[message.id.id],
                nobody = null,
                seenBy = { count -> resources.getString(R.string.dialog_seen_by_count, count) },
                playedBy = { count -> resources.getString(R.string.dialog_played_by_count, count) },
            )
        }
        receiptHolder?.viewers = { message -> state.messageViewers[message.id.id] }
        receiptHolder?.avatar = { viewer ->
            viewer.avatarCacheKey?.let { component.mediaRepository?.cachedFile(it) }
                ?: component.mediaRepository?.cachedAvatar(
                    PeerId(viewer.peerId.value),
                )
        }
    }
    if (state.showTopicList) {
        TopicsContent(component = component, state = state, modifier = modifier)
        return
    }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(showSearch) {
        if (showSearch) runCatching { searchFocus.requestFocus() }
    }
    val peerListId = rememberSaveable { mutableStateOf<Int?>(null) }
    val peerListKind = rememberSaveable { mutableStateOf<String?>(null) }
    val peerListFilter = rememberSaveable { mutableStateOf<String?>(null) }
    val packDocumentId = rememberSaveable { mutableStateOf<Long?>(null) }
    val selectingMessageId = rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedMessageIds = rememberSaveable(state.chatId.value) { mutableStateOf<List<Int>>(emptyList()) }
    val taskDraftFor = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val taskDraft = remember { mutableStateOf("") }
    LaunchedEffect(state.messages) {
        val pruned = pruneForwardSelection(selectedMessageIds.value, state.messages)
        if (pruned != selectedMessageIds.value) selectedMessageIds.value = pruned
    }
    val forwardableSelectedMessages = remember(
        state.messages,
        selectedMessageIds.value,
        state.canForward,
    ) {
        selectedForwardableMessages(
            messages = state.messages,
            selectedIds = selectedMessageIds.value,
            canForward = { message -> messageMenuActions(state, message).canForward },
        )
    }
    androidx.activity.compose.BackHandler(
        enabled = selectedMessageIds.value.isNotEmpty() || selectingMessageId.value != null,
    ) {
        if (selectedMessageIds.value.isNotEmpty()) {
            selectedMessageIds.value = emptyList()
        } else {
            selectingMessageId.value = null
        }
    }
    val composer = rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft))
    }
    val context = LocalContext.current
    val attachScope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachScope.launch {
            val picked = uris.mapNotNull { copyPickedMedia(context, it, "photo", "jpg") }
            sendPickedMedia(component, composer, picked, attachSingle = true)
        }
    }
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachScope.launch {
            val picked = uris.mapNotNull { copyPickedMedia(context, it, "video", "mp4") }
            sendPickedMedia(component, composer, picked, attachSingle = false)
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attachScope.launch {
            val picked = uris.mapNotNull { copyPickedMedia(context, it, "document", "bin") }
            sendPickedMedia(component, composer, picked, attachSingle = false)
        }
    }
    var galleryAccess by remember { mutableStateOf(AttachGalleryAccess.granted(context)) }
    var galleryAccessDenied by remember { mutableStateOf(false) }
    var deviceMedia by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }
    var deviceMediaFailed by remember { mutableStateOf(false) }
    var deviceMediaWanted by remember { mutableIntStateOf(0) }
    var deviceMediaComplete by remember { mutableStateOf(false) }
    fun reloadGallery() {
        deviceMedia = emptyList()
        deviceMediaComplete = false
        deviceMediaFailed = false
        deviceMediaWanted = AttachMediaStore.FirstPage
    }
    val galleryAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        galleryAccess = AttachGalleryAccess.granted(context)
        galleryAccessDenied = !galleryAccess
        if (galleryAccess) reloadGallery()
    }
    val pendingDeleteId = rememberSaveable { mutableStateOf<Int?>(null) }
    val instantViewUrl = rememberSaveable { mutableStateOf<String?>(null) }
    val instantViewHash = rememberSaveable { mutableIntStateOf(0) }
    val clipboard = LocalClipboard.current
    val attachOpen = state.composerPanel == ComposerPanels.ATTACH
    LaunchedEffect(attachOpen) {
        if (!attachOpen) return@LaunchedEffect
        galleryAccess = AttachGalleryAccess.granted(context)
        if (galleryAccess) reloadGallery()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!attachOpen) return@LifecycleEventEffect
        galleryAccess = AttachGalleryAccess.granted(context)
        if (galleryAccess) reloadGallery()
    }
    LaunchedEffect(attachOpen, galleryAccess, deviceMediaWanted) {
        if (!attachOpen || !galleryAccess) return@LaunchedEffect
        while (deviceMedia.size < deviceMediaWanted) {
            val limit = (deviceMediaWanted - deviceMedia.size)
                .coerceAtMost(AttachMediaStore.PageSize)
            val page = AttachMediaStore.page(context, deviceMedia.size, limit).getOrElse {
                deviceMediaFailed = true
                return@LaunchedEffect
            }
            if (page.isEmpty()) {
                deviceMediaComplete = true
                break
            }
            if (page.size < limit) deviceMediaComplete = true
            val seen = deviceMedia.mapTo(mutableSetOf()) { it.uri }
            deviceMedia = deviceMedia + page.filter { it.uri !in seen }
            deviceMediaFailed = false
        }
    }
    LaunchedEffect(state.pendingChatId) {
        if (state.pendingChatId != null) component.openPendingChatIfAny()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var locationPicker by remember { mutableStateOf(false) }
    var locationDraft by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locating by remember { mutableStateOf(false) }
    var pendingLocationPreview by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingLocationPreview = true
        } else {
            attachScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.dialog_location_permission_needed),
                )
            }
        }
    }
    val sendLocation: () -> Unit = {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
        ) {
            pendingLocationPreview = true
        } else {
            locationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    LaunchedEffect(attachOpen, pendingLocationPreview) {
        if (!pendingLocationPreview || attachOpen) return@LaunchedEffect
        pendingLocationPreview = false
        locationDraft = null
        locationPicker = true
        locating = true
        lastKnownLocation(context)?.let {
            locationDraft = it
            locating = false
        }
        val fix = currentLocation(context)
        locating = false
        if (fix != null) {
            locationDraft = fix
        } else if (locationDraft == null) {
            snackbarHostState.showSnackbar(
                context.getString(R.string.dialog_location_unavailable),
            )
        }
    }
    val forwardedMessage = state.forwardHint?.let {
        stringResource(R.string.dialog_forwarded, it)
    }
    LaunchedEffect(forwardedMessage) {
        val text = forwardedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        component.onClearForwardHint()
    }
    val botToast = when (state.botNotice) {
        null -> null
        "inline-switch" -> stringResource(R.string.dialog_bot_switch_inline)
        "callback-password" -> stringResource(R.string.dialog_bot_callback_password)
        else -> state.botNotice.takeIf { !state.botAlert }
    }
    LaunchedEffect(botToast) {
        val text = botToast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        component.onClearBotNotice()
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    LaunchedEffect(state.botUrl) {
        val url = state.botUrl ?: return@LaunchedEffect
        runCatching { uriHandler.openUri(url) }
        component.onClearBotUrl()
    }
    LaunchedEffect(state.copyText) {
        val text = state.copyText ?: return@LaunchedEffect
        clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("text", text)))
        component.onClearCopyText()
    }
    val title = when {
        state.isCommentThread -> stringResource(R.string.dialog_comments_title)
        state.isSelf -> stringResource(R.string.dialog_saved_messages)
        state.title.isNotBlank() -> state.title
        else -> stringResource(R.string.dialog_title, state.chatId.value)
    }
    val syncStatus = dialogSyncStatus(
        loading = state.loading,
        searching = state.searching,
        messagesEmpty = state.messages.isEmpty(),
        loadingOlder = state.loadingOlder,
        loadingNewer = state.loadingNewer,
        prefetchingOlder = state.prefetchingOlder,
    )
    // Saved Messages has no presence, so it never shows a status or a live lease.
    val presenceStatus = state.peerStatus.takeIf { !state.isSelf }
    val presenceAt = state.peerStatusAt.takeIf { !state.isSelf }
    val presenceNow = rememberPeerStatusNow(presenceStatus, presenceAt)
    OnlineLeaseExpiry(presenceStatus, presenceAt) { component.onRefreshPresence() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DialogTopBar(
                component = component,
                state = state,
                title = title,
                syncStatus = syncStatus,
                presenceStatus = presenceStatus,
                presenceAt = presenceAt,
                presenceNow = presenceNow,
                showSearch = showSearch,
                onToggleSearch = {
                    if (showSearch) {
                        component.onClearSearch()
                        showSearch = false
                    } else {
                        showSearch = true
                    }
                },
                onOpenEmojiStatus = { packDocumentId.value = it },
                selectedMessageCount = selectedMessageIds.value.size,
                canForwardSelected = forwardableSelectedMessages.isNotEmpty() &&
                    forwardableSelectedMessages.size == selectedMessageIds.value.size,
                onClearSelectedMessages = { selectedMessageIds.value = emptyList() },
                onForwardSelectedMessages = {
                    component.onForwardMessages(forwardableSelectedMessages)
                    selectedMessageIds.value = emptyList()
                },
            )
        },
    ) { inner ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = inner.calculateTopPadding())
            .imePadding(),
    ) {
        AnimatedVisibility(
            visible = showSearch,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SearchField(
                query = state.searchQuery,
                onQueryChanged = component::onSearch,
                placeholder = stringResource(R.string.dialog_search),
                closeLabel = stringResource(R.string.dialog_search_clear),
                focusRequester = searchFocus,
                busy = state.searching,
                busyLabel = stringResource(R.string.dialog_inline_loading),
            )
        }
        AppStatusBanner(
            sync = if (state.messages.isEmpty() && state.loading) {
                AppSyncStatus.Hidden
            } else {
                syncStatus
            },
            error = state.error,
            onRetry = component::onRefresh,
        )
        CompositionLocalProvider(LocalDialogMedia provides component.mediaRepository) {
        if (state.composerPanel == ComposerPanels.ATTACH) {
            AttachSheet(
                hasGalleryAccess = galleryAccess,
                accessDenied = galleryAccessDenied,
                canSendPhotos = state.canSendPhotos,
                canSendFiles = state.canSendPlain || state.canSendPhotos,
                media = deviceMedia,
                mediaLoading = deviceMedia.isEmpty() && !deviceMediaFailed,
                mediaFailed = deviceMediaFailed,
                pickers = AttachSheetPickers(
                    gifs = state.savedGifs,
                    gifsLoaded = state.savedGifsLoaded,
                    gifsError = state.savedGifsError,
                    stickerSets = state.stickerSets,
                    loadedStickerPacks = state.loadedStickerPacks,
                    stickersLoaded = state.stickerSetsLoaded,
                    stickersError = state.stickerSetsError,
                    mediaRepository = component.mediaRepository,
                ),
                onRequestAccess = { galleryAccessLauncher.launch(AttachGalleryAccess.required()) },
                onRetryMedia = {
                    deviceMediaFailed = false
                    deviceMediaWanted = deviceMedia.size + AttachMediaStore.PageSize
                },
                onReloadPickers = component::onReloadPickerContent,
                onLoadMoreMedia = {
                    if (!deviceMediaComplete) {
                        deviceMediaFailed = false
                        deviceMediaWanted = deviceMedia.size + AttachMediaStore.PageSize
                    }
                },
                onSendMedia = { items ->
                    attachScope.launch {
                        val picked = items.mapNotNull { item ->
                            copyPickedMedia(
                                context,
                                item.uri,
                                item.kind,
                                if (item.kind == "video") "mp4" else "jpg",
                            )
                        }
                        sendPickedMedia(component, composer, picked, attachSingle = true)
                    }
                },
                onPickPhoto = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                    )
                },
                onPickVideo = {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
                onPickFile = {
                    filePicker.launch(arrayOf("*/*"))
                },
                onSendDocument = component::onSendSavedGif,
                onSendLocation = sendLocation,
                // The sheet closes itself exactly once; a toggle here would double-dismiss it.
                onDismiss = component::onCloseAttachSheet,
            )
        }
        if (locationPicker) {
            LocationPickerSheet(
                place = locationDraft?.let { GeoPlace(it.first, it.second) },
                locating = locating,
                mapTiles = component.mediaRepository?.mapTiles,
                onSend = {
                    val draft = locationDraft ?: return@LocationPickerSheet
                    component.onSendLocation(draft.first, draft.second)
                    locationPicker = false
                    locating = false
                },
                onDismiss = {
                    locationPicker = false
                    locating = false
                },
            )
        }
        EmojiStickerGifPanel(
            visible = state.composerPanel == ComposerPanels.EMOJI,
            tab = state.emojiTab,
            gifs = state.savedGifs,
            stickerSets = state.stickerSets,
            emojiSets = state.emojiSets,
            openPack = state.openStickerPack,
            loadedPacks = state.loadedStickerPacks,
            loadingPackIds = state.loadingStickerPackIds,
            failedPackIds = state.failedStickerPackIds,
            mediaRepository = component.mediaRepository,
            stickerLoaded = state.stickerSetsLoaded,
            stickerError = state.stickerSetsError,
            emojiLoaded = state.emojiSetsLoaded,
            gifsLoaded = state.savedGifsLoaded,
            gifsError = state.savedGifsError,
            onTab = component::onSetEmojiTab,
            onInsertEmoji = { glyph ->
                composer.value = insertComposerText(composer.value, glyph)
                component.onDraftChanged(composer.value.text)
            },
            onInsertCustomEmoji = { documentId ->
                val token = "![🙂](tg://emoji?id=$documentId)"
                composer.value = insertComposerText(composer.value, token)
                component.onDraftChanged(composer.value.text)
            },
            onOpenPack = { pack -> component.onOpenStickerPack(pack.id, pack.accessHash) },
            onSendDocument = component::onSendSavedGif,
            onDismiss = component::onToggleEmojiPanel,
            onPickerDocumentsVisible = component::onPickerDocumentsVisible,
            onPickerGifsVisible = component::onPickerGifsVisible,
            onPickerClosed = component::onPickerClosed,
        )
        }
        if (state.pinnedListOpen) {
            PinnedMessagesSheet(
                messages = state.pinnedMessages,
                pinnedIndex = state.pinnedIndex,
                senders = state.senders,
                mediaRepository = component.mediaRepository,
                onJump = component::onJumpToPinned,
                onDismiss = component::onClosePinnedList,
            )
        }
        val listId = peerListId.value
        if (listId != null) {
            val users = if (peerListKind.value == "poll") {
                state.pollVoters[listId]
            } else {
                state.reactionUsers[listId]
            }
            PeerListSheet(
                kind = peerListKind.value,
                users = users,
                poll = if (peerListKind.value == "poll") {
                    state.messages.firstOrNull { it.id.id == listId }?.poll
                } else {
                    null
                },
                initialFilter = peerListFilter.value,
                viewerAvatar = { viewer ->
                    viewer.avatarCacheKey?.let { component.mediaRepository?.cachedFile(it) }
                        ?: component.mediaRepository?.cachedAvatar(
                            PeerId(viewer.peerId.value),
                        )
                },
                onOpenProfile = { id ->
                    peerListId.value = null
                    peerListKind.value = null
                    peerListFilter.value = null
                    component.onOpenPeer(PeerId(id))
                },
                onDismiss = {
                    peerListId.value = null
                    peerListKind.value = null
                    peerListFilter.value = null
                },
            )
        }
        CompositionLocalProvider(LocalDialogMedia provides component.mediaRepository) {
        DialogHistoryPane(
            component = component,
            state = state,
            composer = composer,
            selectingMessageId = selectingMessageId,
            selectedMessageIds = selectedMessageIds,
            packDocumentId = packDocumentId,
            instantViewUrl = instantViewUrl,
            instantViewHash = instantViewHash,
            pendingDeleteId = pendingDeleteId,
            taskDraftFor = taskDraftFor,
            peerListId = peerListId,
            peerListKind = peerListKind,
            peerListFilter = peerListFilter,
            clipboard = clipboard,
            attachScope = attachScope,
        )
        DialogScreenDialogs(
            component = component,
            state = state,
            composer = composer,
            packDocumentId = packDocumentId,
            instantViewUrl = instantViewUrl,
            instantViewHash = instantViewHash,
            pendingDeleteId = pendingDeleteId,
            taskDraftFor = taskDraftFor,
            taskDraft = taskDraft,
        )
        }
    }
    }
}

