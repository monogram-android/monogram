package org.monogram.core.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.monogram.core.ui.components.MediaPreviewWindow

/**
 * Retains [MediaAlbumState] across recompositions, updating content while preserving
 * the current album index.
 */
@Composable
fun rememberAlbumState(items: List<MediaViewerItem>, startIndex: Int = 0): MediaAlbumState {
    val state = remember {
        MediaAlbumState(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }
    LaunchedEffect(items) {
        if (state.items !== items) state.update(items, state.index)
    }
    return state
}

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
    CompositionLocalProvider(LocalMediaViewerMotion provides motion) {
        MediaViewerTheme {
        MediaPreviewWindow(onDismiss = onDismiss) {
            Box(Modifier.fillMaxSize().background(MediaViewerTokens.Letterbox)) {
                MediaViewerShell(
                    album = album,
                    onDismiss = onDismiss,
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
