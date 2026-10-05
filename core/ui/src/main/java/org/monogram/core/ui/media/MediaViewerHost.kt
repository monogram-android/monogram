package org.monogram.core.ui.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.monogram.core.ui.components.MediaPreviewWindow

/**
 * Retains [MediaAlbumState] across recompositions, updating content while preserving
 * the current album index.
 */
@Composable
fun rememberAlbumState(items: List<MediaViewerItem>, startIndex: Int = 0): MediaAlbumState {
    val state = rememberSaveable(saver = albumStateSaver(items)) {
        MediaAlbumState(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(items) {
        if (state.items !== items) state.update(items)
    }
    return state
}

internal fun albumStateSaver(items: List<MediaViewerItem>) =
    androidx.compose.runtime.saveable.listSaver<MediaAlbumState, Any>(
        save = { state -> listOf(state.index) + state.selection.ids.toList() },
        restore = { saved ->
            MediaAlbumState(items, saved.first() as Int).apply {
                saved.drop(1).filterIsInstance<String>().forEach(::toggleSelection)
            }
        },
    )

/**
 * Dialog host for the media viewer. Owns the black letterbox surface, the shared
 * playback session and the caption/actions plumbing so every caller only supplies
 * album data.
 */
@Composable
fun MediaViewerHost(
    album: MediaAlbumState,
    onDismiss: () -> Unit,
    actions: MediaViewerActions = MediaViewerActions(),
    chatKey: String = "",
    headerTitle: String? = null,
    startMutedOverride: Boolean? = null,
    session: MediaPlaybackSession? = null,
    onRequestItem: (MediaViewerItem) -> Unit = {},
    onIndexChange: (Int) -> Unit = {},
    motion: Boolean = true,
    mediaLabel: String? = null,
    testTagPrefix: String = "media-video",
) {
    if (album.items.isEmpty()) return
    // Survives recreation: re-entering the same viewer session must not re-apply the
    // caller's initial mute preference over the user's own choice.
    var firstOpen by rememberSaveable { mutableStateOf(true) }
    val resolvedSession = session
    val sharedRoot = LocalMediaViewerSharedState.current.takeIf { LocalMediaViewerRootOverlay.current }
    val visibility = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (!closing) closing = true }
    val latestDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(closing) { visibility.targetState = !closing }
    var dismissDelivered by remember { mutableStateOf(false) }
    LaunchedEffect(visibility.isIdle, visibility.currentState, sharedRoot?.scope?.isTransitionActive, closing) {
        if (closing && !dismissDelivered && visibility.isIdle && !visibility.currentState && sharedRoot?.scope?.isTransitionActive != true) {
            dismissDelivered = true
            latestDismiss()
        }
    }
    val viewerOwner = remember { Any() }
    DisposableEffect(sharedRoot, viewerOwner) {
        onDispose {
            if (sharedRoot != null && sharedRoot.viewerOwner === viewerOwner) {
                sharedRoot.currentId = null
                sharedRoot.viewerOwner = null
            }
        }
    }
    SideEffect {
        sharedRoot?.viewerOwner = viewerOwner
        sharedRoot?.motion = motion
        sharedRoot?.currentId = album.current?.id.takeUnless { closing }
    }
    CompositionLocalProvider(
        LocalMediaViewerMotion provides motion,
        LocalMediaViewerSharedState provides sharedRoot,
    ) {
        MediaViewerTheme {
            ViewerPresentationWindow(onDismiss = dismiss, inline = sharedRoot != null) {
                val effects = MediaMotion.effects<Float>(mediaViewerMotionEnabled())
                val spatial = MediaMotion.spatial<Float>(mediaViewerMotionEnabled())
                val matched = sharedRoot != null && album.current?.id in sharedRoot.sourceKeys
                MediaViewerWindowEffects(
                    hdr = album.current?.hdr ?: MediaHdr.None,
                    protectedContent = album.current?.protectedContent == true,
                )
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = if (matched) fadeIn(effects) else fadeIn(effects) + scaleIn(spatial, initialScale = 0.94f),
                    exit = if (matched) fadeOut(effects) else fadeOut(effects) + scaleOut(spatial, targetScale = 0.94f),
                ) {
                    CompositionLocalProvider(LocalMediaViewerVisibility provides this) {
                        Box(Modifier.fillMaxSize().background(MediaViewerTokens.Letterbox)) {
                            MediaViewerShell(
                                album = album,
                                onDismiss = dismiss,
                                modifier = Modifier
                                    .then(if (closing) Modifier.clearAndSetSemantics {} else Modifier)
                                    .pointerInput(closing) {
                                        if (closing) awaitPointerEventScope {
                                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                        }
                                    },
                                onIndexChange = onIndexChange,
                                onRequestItem = onRequestItem,
                                actions = actions,
                                session = resolvedSession,
                                chatKey = chatKey,
                                headerTitle = headerTitle,
                                testTagPrefix = testTagPrefix,
                                mediaLabel = mediaLabel,
                                startMutedOverride = startMutedOverride,
                                onFirstOpenConsumed = { firstOpen = false },
                                isFirstOpen = firstOpen,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerPresentationWindow(
    onDismiss: () -> Unit,
    inline: Boolean,
    content: @Composable () -> Unit,
) {
    if (inline) {
        val context = LocalContext.current
        val window = remember(context) { context.viewerActivity()?.window }
        val controller = remember(window) {
            window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        }
        val lightStatus = remember(window) { controller?.isAppearanceLightStatusBars }
        val lightNavigation = remember(window) { controller?.isAppearanceLightNavigationBars }
        SideEffect {
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
        }
        DisposableEffect(window) {
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                lightStatus?.let { controller?.isAppearanceLightStatusBars = it }
                lightNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
            }
        }
        Box(Modifier.fillMaxSize(), content = { content() })
    } else {
        MediaPreviewWindow(onDismiss = onDismiss, dismissOnBackPress = false) { content() }
    }
}

@Composable
private fun MediaViewerWindowEffects(hdr: MediaHdr, protectedContent: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = remember(context) { context.viewerActivity() }
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    val windows = remember(activity, dialogWindow) {
        listOfNotNull(activity?.window, dialogWindow).distinct().map(::ViewerWindowState)
    }
    val hdrRequested by rememberUpdatedState(hdr != MediaHdr.None)
    val secureRequested by rememberUpdatedState(protectedContent)

    fun updateWindows() {
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val display = view.display
        val hdrAvailable = Build.VERSION.SDK_INT >= 34 && display != null &&
            display.isHdr && display.hdrSdrRatio > 1f
        windows.forEach { it.apply(hdrRequested && started && hdrAvailable, secureRequested) }
    }

    SideEffect { updateWindows() }
    DisposableEffect(windows, lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> updateWindows() }
        lifecycle.addObserver(observer)
        updateWindows()
        onDispose {
            lifecycle.removeObserver(observer)
            windows.forEach { it.restore() }
        }
    }
}

private fun Context.viewerActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}

private class ViewerWindowState(private val window: Window) {
    private val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private val colorMode = if (Build.VERSION.SDK_INT >= 26) window.colorMode else 0

    fun apply(hdr: Boolean, secure: Boolean) {
        if (Build.VERSION.SDK_INT >= 26) {
            val requestedMode = if (hdr) android.content.pm.ActivityInfo.COLOR_MODE_HDR else sdrColorMode()
            if (window.colorMode != requestedMode) {
                window.colorMode = requestedMode
                android.util.Log.d("MediaViewerHDR", "window colorMode=$requestedMode")
            }
        }
        if (secure || wasSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun restore() {
        if (Build.VERSION.SDK_INT >= 26) window.colorMode = sdrColorMode()
        if (wasSecure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun sdrColorMode(): Int = if (colorMode == android.content.pm.ActivityInfo.COLOR_MODE_HDR) {
        android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
    } else {
        colorMode
    }
}
