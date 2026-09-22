package org.monogram.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import org.monogram.core.models.canSelectAsForwardRecipient
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.collectWhenActive
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.FaultyDecomposeApi
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.media.LocalPictureInPictureActive
import org.monogram.core.ui.media.MediaMotion
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MiniPlayerBar
import org.monogram.core.ui.media.MiniPlayerRestoreViewer
import org.monogram.core.ui.media.showsMiniPlayer
import org.monogram.core.ui.media.mediaViewerMotionEnabled
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.plus
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimatable
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.R
import org.monogram.core.common.PerfLog
import org.monogram.core.ui.perf.RecompositionProbe
import org.monogram.feature.auth.ui.AuthContent
import org.monogram.feature.chats.ui.ChatsContent
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.ui.DialogContent
import org.monogram.feature.profile.ui.ProfileContent
import org.monogram.feature.settings.ui.SettingsContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.rememberUpdatedState

@Composable
@OptIn(ExperimentalDecomposeApi::class, FaultyDecomposeApi::class)
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    RecompositionProbe("AppRoot")
    val currentStack by component.stack.subscribeAsState()
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
                DialogContent(component)
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
        val spec = tween<Float>(durationMillis = if (motion) 280 else 0, easing = FastOutSlowInEasing)
        val settingsSlide = fade(animationSpec = spec) + slide(animationSpec = spec)
        predictiveBackAnimation<RootComponent.Config, RootComponent.Child>(
            backHandler = backHandler,
            fallbackAnimation = stackAnimation { child, other, _ ->
                val settingsNav =
                    child.configuration is RootComponent.Config.Settings ||
                        other.configuration is RootComponent.Config.Settings
                if (settingsNav) settingsSlide else fade()
            },
            selector = { event, _, _ ->
                predictiveBackAnimatable(
                    initialBackEvent = event,
                    exitModifier = { progress, _ ->
                        Modifier.graphicsLayer { translationX = size.width * progress * direction }
                    },
                    enterModifier = { progress, _ ->
                        Modifier.graphicsLayer {
                            translationX = -size.width * 0.25f * (1f - progress) * direction
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
        modifier.fillMaxSize().drawWithContent {
            drawContent()
            PerfLog.noteUpdateFrame()
        },
    ) {
        val expanded = maxWidth >= 840.dp && home != null
        val appearance by AppearanceSettings.state.collectAsState()
        val density = LocalDensity.current
        val defaultListWidthDp = (maxWidth * 0.35f).value.roundToInt()
            .coerceIn(AppearanceSettings.MIN_LIST_PANE_WIDTH, 420)
        val maxListWidthDp = AppearanceSettings.MAX_LIST_PANE_WIDTH
            .coerceAtMost((maxWidth.value * 0.5f).roundToInt())
            .coerceAtLeast(AppearanceSettings.MIN_LIST_PANE_WIDTH)
        var dragWidthDp by remember { mutableStateOf(0) }
        val listWidthDp = when {
            dragWidthDp > 0 -> dragWidthDp.coerceIn(AppearanceSettings.MIN_LIST_PANE_WIDTH, maxListWidthDp)
            appearance.listPaneWidth > 0 ->
                appearance.listPaneWidth.coerceIn(AppearanceSettings.MIN_LIST_PANE_WIDTH, maxListWidthDp)
            else -> defaultListWidthDp.coerceAtMost(maxListWidthDp)
        }
        val liveListWidthDp by rememberUpdatedState(listWidthDp)
        SideEffect { component.setListDetailVisible(expanded) }
        // Compact chat list stays composed under overlays so Coil thumbs don't restart on back.
        val compactHome = !expanded && home != null
        val homeCovered = compactHome && currentStack.active.instance !is RootComponent.Child.Home && recipient == null
        val dialogCovered = dialog != null && currentStack.active.instance !is RootComponent.Child.Dialog
        Row(
            Modifier
                .fillMaxSize(),
        ) {
            if (expanded && home != null) {
                Box(
                    Modifier
                        .width(listWidthDp.dp)
                        .fillMaxHeight()
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    key(home) { homeContent(home, selectedChatId, true) }
                }
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .fillMaxHeight()
                        .pointerInput(maxListWidthDp) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragWidthDp = liveListWidthDp },
                                onDragEnd = {
                                    // Read the dragged state, not the captured composition value:
                                    // the pointerInput block is not restarted while the drag runs.
                                    if (dragWidthDp > 0) AppearanceSettings.setListPaneWidth(dragWidthDp)
                                    dragWidthDp = 0
                                },
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    dragWidthDp = (dragWidthDp + (delta / density.density).roundToInt())
                                        .coerceIn(AppearanceSettings.MIN_LIST_PANE_WIDTH, maxListWidthDp)
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
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (expanded) {
                            Modifier
                                .padding(end = 8.dp, top = 8.dp, bottom = 8.dp)
                                .clip(MaterialTheme.shapes.extraLarge)
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
                    modifier = Modifier.fillMaxSize()
                        .chatBackGesture(gestureDispatcher, layoutDirection,
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
                                Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                                    key(sourceDialog) { dialogContent(sourceDialog) }
                                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
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
        }
    }
    }
}

@Composable
private fun EmptyDetailContent() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
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
        modifier = Modifier.fillMaxSize().then(if (recipient != null) Modifier.imePadding() else Modifier),
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
        enter = slideInVertically(MediaMotion.spatial(motion)) { it } + fadeIn(MediaMotion.effects(motion)),
        exit = slideOutVertically(MediaMotion.spatial(motion)) { it } + fadeOut(MediaMotion.quick(motion)),
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
