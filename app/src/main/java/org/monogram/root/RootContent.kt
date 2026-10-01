package org.monogram.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.FaultyDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.Direction
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimatable
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimator
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.R
import org.monogram.core.common.PerfLog
import org.monogram.core.models.canSelectAsForwardRecipient
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.collectWhenActive
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.media.LocalPictureInPictureActive
import org.monogram.core.ui.media.MediaMotion
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MiniPlayerBar
import org.monogram.core.ui.media.MiniPlayerRestoreViewer
import org.monogram.core.ui.media.mediaViewerMotionEnabled
import org.monogram.core.ui.media.showsMiniPlayer
import org.monogram.core.ui.perf.RecompositionProbe
import org.monogram.feature.auth.ui.AuthContent
import org.monogram.feature.chats.ui.ChatsContent
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.ui.DialogContent
import org.monogram.feature.profile.ui.ProfileContent
import org.monogram.feature.settings.ui.SettingsContent
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalDecomposeApi::class, FaultyDecomposeApi::class)
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    val stack by component.stack.subscribeAsState()
    org.monogram.feature.dialog.ui.AudioMessagePlaybackScope(
        enabled = stack.active.instance !is RootComponent.Child.Auth,
    ) { togglePlayback -> RootPlaybackContent(component, modifier, togglePlayback) }
}

@Composable
@OptIn(ExperimentalDecomposeApi::class, FaultyDecomposeApi::class)
private fun RootPlaybackContent(
    component: RootComponent,
    modifier: Modifier,
    togglePlayback: () -> Unit
) {
    RecompositionProbe("AppRoot")
    val currentStack by component.stack.subscribeAsState()
    val playbackContext = LocalContext.current
    val playbackSession = remember(playbackContext) { MediaPlaybackHolder.session(playbackContext) }
    val authenticated = currentStack.active.instance !is RootComponent.Child.Auth
    val rootLifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val showMessagePlayback = authenticated && playbackSession.isMessagePlayback &&
            rootLifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
            !LocalPictureInPictureActive.current &&
            playbackSession.surface != MediaSurface.VIEWER &&
            playbackSession.surface != MediaSurface.PIP &&
            currentStack.active.instance !is RootComponent.Child.Dialog
    val openPlaybackMessage: () -> Unit = {
        val item = playbackSession.current
        val chatId = item?.sourceChatId
        if (chatId != null) {
            if (item.isVideoNote) playbackSession.returnNoteInline(item.id)
            component.openChatForPlayback(chatId, item.sourceMessageId ?: 0)
        }
    }
    val activeDialog by rememberUpdatedState((currentStack.active.instance as? RootComponent.Child.Dialog)?.component)
    MessagePlaybackLifecycle(playbackSession)
    val home = (currentStack.items.firstOrNull { it.instance is RootComponent.Child.Home }
        ?.instance as? RootComponent.Child.Home)?.component
    // Derived once per stack change instead of two list scans on every recomposition.
    val dialog by remember {
        derivedStateOf {
            (currentStack.items.lastOrNull { it.instance is RootComponent.Child.Dialog }
                ?.instance as? RootComponent.Child.Dialog)?.component
        }
    }
    val selectedChatId by remember {
        derivedStateOf {
            (currentStack.items.lastOrNull { it.configuration is RootComponent.Config.Dialog }
                ?.configuration as? RootComponent.Config.Dialog)?.chatId
        }
    }
    val homeState = rememberSaveableStateHolder()
    val recipient by rememberUpdatedState((currentStack.active.instance as? RootComponent.Child.Recipients)?.component)
    val homeContent = remember {
        movableContentOf<HomeComponent, Long?, Boolean> { homeComponent, selected, listActive ->
            homeState.SaveableStateProvider("home") {
                HomeContent(homeComponent, selected, listActive, recipient)
            }
        }
    }
    val dialogState = rememberSaveableStateHolder()
    val dialogContent = remember {
        movableContentOf<DialogComponent> { component ->
            // Key by full dialog identity: a forum topic shares the chat id.
            dialogState.SaveableStateProvider(component.stateKey) {
                CompositionLocalProvider(
                    org.monogram.feature.dialog.ui.LocalMessageConversationActive provides (component === activeDialog),
                ) { DialogContent(component) }
            }
        }
    }
    val layoutDirection = LocalLayoutDirection.current
    val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
    val motion = mediaViewerMotionEnabled()
    val gestureDispatcher = remember { com.arkivanov.essenty.backhandler.BackDispatcher() }
    val backHandler = remember(component, gestureDispatcher) {
        GestureBackHandler(component.backHandler, gestureDispatcher)
    }
    val animation = remember(backHandler, direction, motion) {
        val spec =
            tween<Float>(durationMillis = if (motion) 280 else 0, easing = FastOutSlowInEasing)
        val settingsSlide = fade(animationSpec = spec) + slide(animationSpec = spec)
        val screenSlide = stackAnimator(animationSpec = spec) { factor, animDirection, content ->
            val alphaProgress = 1f - factor
            content(
                Modifier.graphicsLayer {
                    val width = size.width
                    translationX = when (animDirection) {
                        Direction.ENTER_FRONT -> width * factor
                        Direction.EXIT_FRONT -> width * factor
                        Direction.ENTER_BACK -> width * factor * 0.25f
                        Direction.EXIT_BACK -> width * factor
                    }
                    alpha = alphaProgress
                }
            )
        }
        predictiveBackAnimation<RootComponent.Config, RootComponent.Child>(
            backHandler = backHandler,
            fallbackAnimation = stackAnimation { child, other, _ ->
                val settingsNav =
                    child.configuration is RootComponent.Config.Settings ||
                            other.configuration is RootComponent.Config.Settings
                if (settingsNav) settingsSlide else screenSlide
            },
            selector = { event, _, _ ->
                predictiveBackAnimatable(
                    initialBackEvent = event,
                    exitModifier = { progress, _ ->
                        Modifier.graphicsLayer { translationX = size.width * progress }
                    },
                    enterModifier = { progress, _ ->
                        Modifier.graphicsLayer {
                            translationX = -size.width * 0.25f * (1f - progress)
                        }
                    },
                )
            },
            onBack = component::onBack,
        )
    }
    val defaultUriHandler = LocalUriHandler.current
    val telegramUriHandler = remember(component, defaultUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (!component.openTelegramUri(uri)) defaultUriHandler.openUri(uri)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides telegramUriHandler) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    PerfLog.noteUpdateFrame()
                },
        ) {
            val density = LocalDensity.current
            val fold = rememberFoldLayout()
            val foldListMaxWidthDp = fold.hinge
                ?.takeIf { fold.mode == FoldLayoutMode.VERTICAL_BOOK }
                ?.let { (it.left / density.density).roundToInt() - 8 }
                ?.takeIf { it >= AppearanceSettings.MIN_LIST_PANE_WIDTH }
            val foldHingeGapDp = fold.hinge
                ?.takeIf { fold.mode == FoldLayoutMode.VERTICAL_BOOK }
                ?.let { (it.width / density.density).roundToInt().coerceAtLeast(14) }
            val tabletop = fold.mode == FoldLayoutMode.HORIZONTAL_TABLETOP && fold.hinge != null
            val tabletopListHeightDp = fold.hinge
                ?.takeIf { tabletop }
                ?.let { (it.top / density.density).roundToInt() - 8 }
                ?.takeIf { it >= 240 }
            val expanded =
                home != null && (maxWidth >= 840.dp || foldListMaxWidthDp != null || tabletopListHeightDp != null)
            val appearance by AppearanceSettings.state.collectAsState()
            val defaultListWidthDp = (maxWidth * 0.35f).value.roundToInt()
                .coerceIn(AppearanceSettings.MIN_LIST_PANE_WIDTH, 420)
            val maxListWidthDp = (foldListMaxWidthDp ?: AppearanceSettings.MAX_LIST_PANE_WIDTH)
                .coerceAtMost((maxWidth.value * 0.5f).roundToInt())
                .coerceAtLeast(AppearanceSettings.MIN_LIST_PANE_WIDTH)
            var dragWidthDp by remember { mutableIntStateOf(0) }
            val listWidthDp = when {
                dragWidthDp > 0 -> dragWidthDp.coerceIn(
                    AppearanceSettings.MIN_LIST_PANE_WIDTH,
                    maxListWidthDp
                )

                appearance.listPaneWidth > 0 ->
                    appearance.listPaneWidth.coerceIn(
                        AppearanceSettings.MIN_LIST_PANE_WIDTH,
                        maxListWidthDp
                    )

                else -> defaultListWidthDp.coerceAtMost(maxListWidthDp)
            }
            val liveListWidthDp by rememberUpdatedState(listWidthDp)
            SideEffect { component.setListDetailVisible(expanded) }
            // Compact chat list stays composed under overlays so Coil thumbs don't restart on back.
            val compactHome = !expanded && home != null
            val homeCovered =
                compactHome && currentStack.active.instance !is RootComponent.Child.Home && recipient == null
            val dialogCovered =
                dialog != null && currentStack.active.instance !is RootComponent.Child.Dialog
            Layout(
                content = {

                    if (expanded) {
                        Box(
                            Modifier
                                .then(
                                    if (tabletopListHeightDp != null) Modifier
                                        .fillMaxWidth()
                                        .height(tabletopListHeightDp.dp) else Modifier
                                        .width(
                                            listWidthDp.dp
                                        )
                                        .fillMaxHeight()
                                )
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            key(home) { homeContent(home, selectedChatId, true) }
                        }
                        Box(
                            modifier = Modifier
                                .then(
                                    if (tabletopListHeightDp != null) Modifier
                                        .fillMaxWidth()
                                        .height(
                                            ((fold.hinge.height / density.density).roundToInt()
                                                .coerceAtLeast(14)).dp
                                        ) else Modifier
                                        .width((foldHingeGapDp ?: 14).dp)
                                        .fillMaxHeight()
                                )
                                .pointerInput(maxListWidthDp) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragWidthDp = liveListWidthDp },
                                        onDragEnd = {
                                            // Read the dragged state, not the captured composition value:
                                            // the pointerInput block is not restarted while the drag runs.
                                            if (dragWidthDp > 0) AppearanceSettings.setListPaneWidth(
                                                dragWidthDp
                                            )
                                            dragWidthDp = 0
                                        },
                                        onHorizontalDrag = { change, delta ->
                                            change.consume()
                                            dragWidthDp =
                                                (dragWidthDp + (delta / density.density).roundToInt())
                                                    .coerceIn(
                                                        AppearanceSettings.MIN_LIST_PANE_WIDTH,
                                                        maxListWidthDp
                                                    )
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val dragging = dragWidthDp > 0
                            val nudge = remember { Animatable(0f) }
                            LaunchedEffect(expanded) {
                                if (!expanded) return@LaunchedEffect
                                nudge.animateTo(5f, tween(260))
                                nudge.animateTo(0f, tween(260))
                            }
                            Box(
                                Modifier
                                    .width(4.dp)
                                    .height(40.dp)
                                    .offset(x = nudge.value.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(
                                        if (dragging) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (expanded) {
                                    Modifier
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = 32.dp,
                                                topEnd = 32.dp,
                                                bottomStart = 32.dp,
                                                bottomEnd = 32.dp,
                                            )
                                        )
                                } else {
                                    Modifier
                                },
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        if (homeCovered) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .semantics { hideFromAccessibility() },
                            ) {
                                CompositionLocalProvider(LocalMediaAnimationEnabled provides false) {
                                    homeContent(home, selectedChatId, false)
                                }
                            }
                        }
                        val coveredDialog = dialog
                        if (coveredDialog != null && dialogCovered && !(recipient != null && expanded)) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .semantics { hideFromAccessibility() },
                            ) {
                                key(coveredDialog) { dialogContent(coveredDialog) }
                            }
                        }
                        Children(
                            stack = component.stack,
                            modifier = Modifier
                                .fillMaxSize()
                                .chatBackGesture(
                                    gestureDispatcher, layoutDirection,
                                    prioritizeChildren = { true },
                                ) {
                                    !expanded && when (component.stack.value.active.instance) {
                                        is RootComponent.Child.Dialog, is RootComponent.Child.Profile,
                                        is RootComponent.Child.Settings -> true

                                        else -> false
                                    }
                                },
                            animation = animation,
                        ) { child ->
                            when (val instance = child.instance) {
                                is RootComponent.Child.Recipients -> if (expanded && recipient != null) {
                                    val sourceDialog = dialog
                                    if (sourceDialog == null) {
                                        EmptyDetailContent()
                                    } else {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .clearAndSetSemantics { }) {
                                            key(sourceDialog) { dialogContent(sourceDialog) }
                                            Box(
                                                Modifier
                                                    .fillMaxSize()
                                                    .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                                            }
                                                        }
                                                    })
                                        }
                                    }
                                } else Box(Modifier.fillMaxSize())

                                is RootComponent.Child.Auth -> AuthContent(instance.component)
                                is RootComponent.Child.Home -> if (expanded) {
                                    EmptyDetailContent()
                                } else if (compactHome) {
                                    Box(Modifier.fillMaxSize())
                                } else {
                                    homeContent(instance.component, selectedChatId, true)
                                }

                                is RootComponent.Child.Dialog -> if (dialogCovered) {
                                    Box(Modifier.fillMaxSize())
                                } else {
                                    key(instance.component) { dialogContent(instance.component) }
                                }

                                is RootComponent.Child.Profile -> ProfileContent(instance.component)
                                is RootComponent.Child.Settings -> SettingsContent(
                                    component = instance.component,
                                    gestureDispatcher = gestureDispatcher,
                                    folders = rememberSettingsFolderHost(instance.folders),
                                )
                            }
                        }
                        if (compactHome && !homeCovered) {
                            Box(Modifier.fillMaxSize()) {
                                homeContent(home, selectedChatId, true)
                            }
                        }
                        var restoreMiniPlayer by remember { mutableStateOf(false) }
                        if (restoreMiniPlayer) {
                            val context = LocalContext.current
                            val session = remember(context) { MediaPlaybackHolder.session(context) }
                            MiniPlayerRestoreViewer(
                                session = session,
                                onDismiss = { restoreMiniPlayer = false },
                            )
                        }
                        PlaybackBar(
                            visible = recipient == null && currentStack.active.instance !is RootComponent.Child.Dialog &&
                                    !restoreMiniPlayer,
                            onExpand = { restoreMiniPlayer = true },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                if (measurables.size == 1) {
                    val content = measurables[0].measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) { content.place(0, 0) }
                } else {
                    val listMax =
                        if (tabletopListHeightDp != null) constraints.maxWidth else (listWidthDp * density.density).roundToInt()
                    val listHeight =
                        if (tabletopListHeightDp != null) (tabletopListHeightDp * density.density).roundToInt() else constraints.maxHeight
                    val list = measurables[0].measure(Constraints(0, listMax, 0, listHeight))
                    val dividerMax =
                        if (tabletopListHeightDp != null) constraints.maxWidth else (foldHingeGapDp?.let { (it * density.density).roundToInt() }
                            ?: (14 * density.density).roundToInt())
                    val dividerHeight =
                        if (tabletopListHeightDp != null) (fold.hinge.height / density.density).roundToInt()
                            .coerceAtLeast(14) else constraints.maxHeight
                    val divider =
                        measurables[1].measure(Constraints(0, dividerMax, 0, dividerHeight))
                    val detailTop =
                        if (tabletopListHeightDp != null) list.height + divider.height else 0
                    val detailLeft =
                        if (tabletopListHeightDp != null) 0 else list.width + divider.width
                    val detail = measurables[2].measure(
                        Constraints(
                            0,
                            (constraints.maxWidth - detailLeft).coerceAtLeast(0),
                            0,
                            (constraints.maxHeight - detailTop).coerceAtLeast(0),
                        )
                    )
                    if (tabletopListHeightDp != null) {
                        val y = detailTop
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            list.place(0, 0)
                            divider.place(0, list.height)
                            detail.place(0, y)
                        }
                    } else {
                        val x = list.width + divider.width
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            list.place(0, 0)
                            divider.place(list.width, 0)
                            detail.place(x, 0)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = showMessagePlayback,
                enter = slideInVertically(
                    initialOffsetY = { height -> height },
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) + fadeIn(animationSpec = tween(180)),
                exit = slideOutVertically(
                    targetOffsetY = { height -> height },
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(140)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                org.monogram.core.ui.media.MessagePlaybackBar(
                    session = playbackSession,
                    onOpenMessage = openPlaybackMessage,
                    onToggle = togglePlayback,
                    roundBottomCorners = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
            if (showMessagePlayback) {
                org.monogram.core.ui.media.FloatingVideoNote(
                    session = playbackSession, onOpenMessage = openPlaybackMessage,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 56.dp),
                )
            }
            UpdatePromptSheet(
                controller = component.appUpdate,
                enabled = currentStack.active.instance !is RootComponent.Child.Auth,
            )
        }
    }
}

@Composable
private fun EmptyDetailContent() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.select_chat),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomeContent(
    component: HomeComponent,
    selectedChatId: Long?,
    listActive: Boolean,
    recipient: RecipientPickerComponent?,
) {
    val foldersState = collectWhenActive(component.folders.state, listActive)
    val chatsState = collectWhenActive(component.chats.state, listActive)
    val selection = recipient?.state?.collectAsStateWithLifecycle()?.value
    val focusManager = LocalFocusManager.current
    LaunchedEffect(recipient) { focusManager.clearFocus() }
    LaunchedEffect(recipient, selection?.done) {
        if (selection?.done == true) recipient.onClose()
    }
    ChatsContent(
        component = component.chats,
        folders = foldersState.folders,
        selectedChatId = selectedChatId,
        listActive = listActive,
        selectingRecipient = recipient != null,
        onCancelSelection = { recipient?.onClose() },
        recipientIds = selection?.selected.orEmpty(),
        recipientSelectionEnabled = selection?.started != true,
        canSelectRecipient = { chat ->
            chat.canSelectAsForwardRecipient(
                requiresPhotos = recipient?.request?.needsPhotoSendRight == true,
                selfPeerId = chatsState.self?.id,
            )
        },
        onToggleRecipient = { id -> recipient?.onToggle(id) },
        selectionBottomBar = {
            if (recipient != null && selection != null) RecipientSelectionBar(recipient, selection)
        },
        modifier = Modifier
            .fillMaxSize()
            .then(if (recipient != null) Modifier.imePadding() else Modifier),
    )
}

@Composable
private fun PlaybackBar(
    visible: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    val motion = mediaViewerMotionEnabled()
    val pictureInPicture = LocalPictureInPictureActive.current
    AnimatedVisibility(
        visible = visible && session.showsMiniPlayer(pictureInPicture),
        enter = slideInVertically(MediaMotion.spatial(motion)) { it } + fadeIn(
            MediaMotion.effects(
                motion
            )
        ),
        exit = slideOutVertically(MediaMotion.spatial(motion)) { it } + fadeOut(
            MediaMotion.quick(
                motion
            )
        ),
        modifier = modifier,
    ) {
        MiniPlayerBar(
            session = session,
            onExpand = onExpand,
            onStop = { session.stop() },
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(20.dp),
        )
    }
}
