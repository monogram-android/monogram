package org.monogram.core.ui.media

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import org.monogram.core.ui.R

class MediaViewerSharedState internal constructor(val scope: SharedTransitionScope) {
    internal var overlay by mutableStateOf<(@Composable () -> Unit)?>(null)
    internal var currentId by mutableStateOf<String?>(null)
    internal var motion by mutableStateOf(true)
    internal var sourceOwners by mutableStateOf<Map<String, List<Any>>>(emptyMap())
    internal val sourceKeys: Set<String> get() = sourceOwners.keys
    internal var viewerOwner: Any? = null
}

val LocalMediaViewerSharedState = staticCompositionLocalOf<MediaViewerSharedState?> { null }
internal val LocalMediaViewerVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
internal val LocalMediaViewerRootOverlay = staticCompositionLocalOf { false }

fun mediaSharedBoundsKey(messageKey: String): String = "media-$messageKey"

/** Both the mosaic and full-window overlay must be descendants of this one layout. */
@Composable
fun MediaViewerSharedRoot(content: @Composable () -> Unit) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val state = remember(this) { MediaViewerSharedState(this) }
        val modal = state.overlay != null
        val focusManager = LocalFocusManager.current
        LaunchedEffect(modal) { if (modal) focusManager.clearFocus(force = true) }
        CompositionLocalProvider(LocalMediaViewerSharedState provides state) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .focusProperties { canFocus = !modal }
                        .then(if (modal) Modifier.clearAndSetSemantics {} else Modifier)
                        .pointerInput(modal) {
                            if (modal) awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                ) { content() }
                state.overlay?.let { overlay ->
                    val title = stringResource(R.string.media_viewer_label)
                    Box(Modifier.fillMaxSize().semantics { paneTitle = title }) { overlay() }
                }
            }
        }
    }
}

/** Publishes content at the full-window root without creating a second viewer or session. */
@Composable
fun MediaViewerOverlay(content: @Composable () -> Unit) {
    val root = LocalMediaViewerSharedState.current
    if (root == null) {
        content()
        return
    }
    val latest by rememberUpdatedState(content)
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val overlay = remember {
        movableContentOf {
            CompositionLocalProvider(locals) {
                CompositionLocalProvider(LocalMediaViewerRootOverlay provides true) { latest() }
            }
        }
    }
    SideEffect { root.overlay = overlay }
    DisposableEffect(root, overlay) {
        onDispose {
            if (root.overlay === overlay) root.overlay = null
        }
    }
}

@Composable
fun Modifier.mediaMosaicSharedBounds(messageKey: String): Modifier {
    val root = LocalMediaViewerSharedState.current ?: return this
    val owner = remember(root, messageKey) { Any() }
    DisposableEffect(root, messageKey, owner) {
        root.sourceOwners = root.sourceOwners + (messageKey to (root.sourceOwners[messageKey].orEmpty() + owner))
        onDispose {
            val remaining = root.sourceOwners[messageKey].orEmpty() - owner
            root.sourceOwners = if (remaining.isEmpty()) root.sourceOwners - messageKey
            else root.sourceOwners + (messageKey to remaining)
        }
    }
    // Retained navigation panes can contain the same message: only one may be a source.
    if (root.sourceOwners[messageKey]?.firstOrNull() !== owner) return this
    val visible = root.currentId != messageKey
    // An independent visibility transition keeps the measured mosaic cell in place on exit.
    val transition = updateTransition(
        if (visible) EnterExitState.Visible else EnterExitState.PostExit,
        label = "mosaicMediaVisibility",
    )
    val visibility = remember(transition) {
        object : AnimatedVisibilityScope {
            override val transition = transition
        }
    }
    return mediaSharedBounds(messageKey, root, visibility)
}

@Composable
internal fun Modifier.mediaViewerSharedBounds(messageKey: String, active: Boolean): Modifier {
    if (!active) return this
    val root = LocalMediaViewerSharedState.current ?: return this
    val visibility = LocalMediaViewerVisibility.current ?: return this
    return mediaSharedBounds(messageKey, root, visibility)
}

@Composable
private fun Modifier.mediaSharedBounds(
    messageKey: String,
    root: MediaViewerSharedState,
    visibility: AnimatedVisibilityScope,
): Modifier {
    val motion = mediaViewerMotionEnabled() && root.motion
    val spatial = MediaMotion.spatial<androidx.compose.ui.geometry.Rect>(motion)
    val effects = MediaMotion.effects<Float>(motion)
    return with(root.scope) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(mediaSharedBoundsKey(messageKey)),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ -> spatial },
            enter = fadeIn(effects),
            exit = fadeOut(effects),
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = 1f,
        )
    }
}
