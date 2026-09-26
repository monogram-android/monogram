package org.monogram.core.ui.media

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.core.ui.R
import kotlin.math.absoluteValue

/** Chrome auto-hide delay, per the Telegram convention. */
private const val CHROME_HIDE_MILLIS = 3_000L

/**
 * The one media viewer shell. Photos and videos are page types, album membership is
 * the pager data source, and background playback is the same session on another surface.
 */
@Composable
fun MediaViewerShell(
    album: MediaAlbumState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onIndexChange: (Int) -> Unit = {},
    onRequestItem: (MediaViewerItem) -> Unit = {},
    actions: MediaViewerActions = MediaViewerActions(),
    session: MediaPlaybackSession? = null,
    chatKey: String = "",
    headerTitle: String? = null,
    startChromeVisible: Boolean = true,
    allowVerticalDismiss: Boolean = true,
    testTagPrefix: String = "media-video",
    /** Accessibility label bound to the media page root. */
    mediaLabel: String? = null,
    /** Null keeps the per-chat preference; used by single-item callers. */
    startMutedOverride: Boolean? = null,
    isFirstOpen: Boolean = true,
    onFirstOpenConsumed: () -> Unit = {},
) {
    if (album.items.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Reserve caption slot height if any album item has a caption to keep layout height stable.
    val albumHasCaption = remember(album.items) { album.items.any { !it.caption.isNullOrBlank() } }
    val touchExploration = remember(context) {
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager)
            ?.isTouchExplorationEnabled == true
    }
    val motion = mediaViewerMotionEnabled()

    val pagerState = rememberPagerState(initialPage = album.index.coerceIn(0, album.items.lastIndex)) { album.items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(startChromeVisible) }
    var captionOpen by rememberSaveable { mutableStateOf(false) }
    var detent by rememberSaveable { mutableStateOf(CaptionDetent.COLLAPSED) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var rootHeight by remember { mutableFloatStateOf(1f) }

    val currentIndex = pagerState.currentPage.coerceIn(0, album.items.lastIndex)
    val current = album.items[currentIndex]

    // The pager owns the album position; the caller just observes it.
    LaunchedEffect(pagerState.currentPage, album.items) {
        val page = pagerState.currentPage.coerceIn(0, album.items.lastIndex)
        album.index = page
        onIndexChange(page)
        onRequestItem(album.items[page])
        album.items.getOrNull(page + 1)?.let(onRequestItem)
    }
    LaunchedEffect(album.index, album.items.size) {
        if (album.index != pagerState.currentPage && album.index in album.items.indices) {
            pagerState.scrollToPage(album.index)
        }
    }

    // Video pages mount the shared session; photos pause it but keep it warm.
    LaunchedEffect(current.id) {
        val target = session ?: return@LaunchedEffect
        val previousItem = target.queue.getOrNull(target.index)
        val previousWasPlayingVideo = previousItem?.isVideo == true && target.playing
        // Album inheritance: a video after a playing video keeps its mute state, a video
        // after a photo starts muted. The very first open uses the caller's preference.
        val initialMute = if (isFirstOpen) {
            startMutedOverride ?: MediaViewerPrefs.muted(context, chatKey)
        } else {
            !previousWasPlayingVideo
        }
        target.setQueue(
            album.items,
            currentIndex,
            autoplay = current.isVideo,
            startMuted = initialMute,
            // After the first open the user's mute choice and the album inheritance rule win.
            preserveUserMute = !isFirstOpen,
        )
        target.attachSurface(MediaSurface.VIEWER)
        if (isFirstOpen) onFirstOpenConsumed()
    }
    // Sync pager position when player queue transitions.
    LaunchedEffect(session, pagerState) {
        val target = session ?: return@LaunchedEffect
        target.onVideoQueueStep = { position -> scope.launch { pagerState.animateScrollToPage(position) } }
    }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }
    val pageIsVideo by rememberUpdatedState(current.isVideo)
    DisposableEffect(session) {
        onDispose {
            session?.onVideoQueueStep = null
            if (session?.audioOnly == true || pageIsVideo) {
                session?.attachSurface(MediaSurface.MINI_PLAYER)
            } else {
                session?.stop()
            }
        }
    }

    // Auto-hide chrome during playback when menus and captions are closed.
    var overflowOpen by remember { mutableStateOf(false) }
    val chromeAutoHide = current.isVideo &&
        !touchExploration &&
        !captionOpen &&
        !overflowOpen &&
        session?.playing == true
    var chromeTouchedAt by remember { mutableIntStateOf(0) }
    LaunchedEffect(chromeVisible, chromeAutoHide, chromeTouchedAt) {
        if (!chromeVisible || !chromeAutoHide) return@LaunchedEffect
        delay(CHROME_HIDE_MILLIS)
        chromeVisible = false
    }

    val dismiss: () -> Unit = {
        session?.holdPosition()
        if (session != null && !pageIsVideo && !session.audioOnly) {
            session.stop()
        }
        if (allowVerticalDismiss) onDismiss() else chromeVisible = false
    }

    val settledDrag by animateFloatAsState(
        targetValue = dragDistance,
        animationSpec = if (dragging) snap() else MediaMotion.spatial(motion),
        label = "dragSettle",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onSizeChanged { rootHeight = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationY = settledDrag
                alpha = 1f - (settledDrag.absoluteValue / rootHeight * 0.45f).coerceIn(0f, 0.45f)
            },
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = album.items.getOrNull(page) ?: return@HorizontalPager
            val active = page == currentIndex
            when {
                item.isVideo && session != null -> MediaVideoPage(
                    item = item,
                    session = session,
                    active = active,
                    chromeVisible = chromeVisible,
                    playing = session.playing && active,
                    onToggleChrome = { chromeVisible = !chromeVisible; chromeTouchedAt++ },
                    onDragProgress = { dragging = true; dragDistance = it },
                    onDragEnd = { shouldDismiss, swipeUp ->
                        dragging = false
                        if (shouldDismiss && allowVerticalDismiss) dismiss()
                        else if (swipeUp && actions.canPictureInPicture && active) actions.onEnterPictureInPicture()
                        else dragDistance = 0f
                    },
                    onDoubleTapSeek = { delta -> session.seekBy(delta) },
                    onHoldSpeed = { fast ->
                        // Restore whatever speed the user picked, not a hard-coded 1x.
                        if (fast) {
                            speedBeforeHold = session.speed
                            session.changeSpeed(2f)
                        } else {
                            session.changeSpeed(speedBeforeHold)
                        }
                    },
                    testTagPrefix = testTagPrefix,
                    label = pageLabel(current, currentIndex, album, mediaLabel),
                )
                else -> MediaPhotoPage(
                    item = item,
                    onToggleChrome = { chromeVisible = !chromeVisible; chromeTouchedAt++ },
                    onDragProgress = { dragging = true; dragDistance = it },
                    onDragEnd = { shouldDismiss ->
                        dragging = false
                        if (shouldDismiss && allowVerticalDismiss) dismiss() else dragDistance = 0f
                    },
                    label = pageLabel(current, currentIndex, album, mediaLabel),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(if (motion) 180 else 0)) + slideInVertically(tween(if (motion) 180 else 0)) { -it / 6 },
            exit = fadeOut(tween(if (motion) 160 else 0)) + slideOutVertically(tween(if (motion) 160 else 0)) { -it / 6 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            MediaTopBar(
                title = headerTitle ?: current.senderName.orEmpty(),
                subtitle = subtitleFor(album, current, currentIndex),
                onClose = dismiss,
            )
        }

        // Caption, timeline, transport and filmstrip share ONE contained surface, so the
        // caption reads as part of the viewer chrome instead of a strip floating over the
        // media. Everything here hides together with the chrome.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(if (motion) 200 else 0)) +
                    slideInVertically(MediaMotion.spatial(motion)) { it / 4 },
                exit = fadeOut(tween(if (motion) 160 else 0)) +
                    slideOutVertically(MediaMotion.spatial(motion)) { it / 4 },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().animateContentSize(MediaMotion.quick(motion))) {
                    if (!current.isVideo && !captionOpen) {
                        PhotoActionToolbar(
                            item = current,
                            actions = actions,
                            albumSize = album.count,
                            session = session,
                            chatKey = chatKey,
                            onOpenCaption = { captionOpen = true; detent = CaptionDetent.HALF },
                            onOverflowChange = { open ->
                                overflowOpen = open
                                if (open) chromeTouchedAt++
                            },
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                            ),
                        ) {
                            ViewerCaptionRow(
                                caption = if (captionOpen) null else current.caption,
                                reserved = albumHasCaption,
                                onExpand = { captionOpen = true; detent = CaptionDetent.HALF },
                            )
                            AnimatedContent(
                                targetState = current.isVideo && session != null,
                                transitionSpec = {
                                    fadeIn(tween(if (motion) 200 else 0)) togetherWith
                                        fadeOut(tween(if (motion) 120 else 0))
                                },
                                label = "controlSet",
                            ) { videoControls ->
                                if (videoControls && session != null) {
                                    VideoControlCluster(
                                        session = session,
                                        chatKey = chatKey,
                                        item = current,
                                        actions = actions,
                                        onOpenCaption = { captionOpen = true; detent = CaptionDetent.HALF },
                                        onScrubStart = { chromeVisible = true; chromeTouchedAt++ },
                                        onOverflowChange = { open ->
                                            overflowOpen = open
                                            if (open) chromeTouchedAt++
                                        },
                                    )
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                            if (album.count > 1 && !captionOpen) {
                                Filmstrip(
                                    items = album.items,
                                    currentIndex = currentIndex,
                                    onSelect = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (current.failed && current.source == null && actions.canRetry) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.media_video_error),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { actions.onRetry(current) }) {
                            Text(stringResource(R.string.media_video_retry))
                        }
                        TextButton(onClick = { actions.onOpenExternally(current) }) {
                            Text(stringResource(R.string.media_action_open_externally))
                        }
                    }
                }
            }
        }

        val wideLayout = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
        MediaCaptionSheet(
            visible = captionOpen,
            detent = detent,
            item = current,
            index = currentIndex,
            albumSize = album.count,
            actions = actions,
            sideSheet = wideLayout,
            onDetentChange = { detent = it },
            onCollapse = { captionOpen = false },
            modifier = if (wideLayout) {
                Modifier.align(Alignment.CenterEnd)
            } else {
                Modifier.align(Alignment.BottomCenter)
            },
        )
    }
}

/** Formats accessibility description for a media page item. */
@Composable
private fun pageLabel(
    item: MediaViewerItem,
    index: Int,
    album: MediaAlbumState,
    explicit: String?,
): String {
    if (explicit != null) return explicit
    val kind = stringResource(
        if (item.isVideo) R.string.media_badge_video_lower else R.string.media_badge_photo_lower,
    )
    val sender = item.senderName ?: stringResource(R.string.media_sender_unknown)
    val date = item.dateLabel ?: ""
    return stringResource(R.string.media_viewer_album_item, kind, index + 1, album.count, sender, date)
}

private fun subtitleFor(album: MediaAlbumState, item: MediaViewerItem, index: Int): String {
    val parts = mutableListOf<String>()
    item.dateLabel?.takeIf { it.isNotBlank() }?.let(parts::add)
    if (album.count > 1) parts += "${index + 1}/${album.count}"
    if (item.isVideo) parts += "video"
    return parts.joinToString(" · ")
}
