package org.monogram.feature.dialog.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository

private val HandleTopPadding = 16.dp
private const val SheetBackdropBlurPx = 40
private val FabSize = 56.dp
private const val BodyCrossfadeMs = 150
private const val TallSheetFraction = 0.92f

/** Sheet body a dock slot can switch to; every entry maps to an existing picker. */
internal enum class AttachBody { Gallery }

/** Static data for the emoji family bodies; both browsers already exist in the composer panel. */
internal data class AttachSheetPickers(
    val gifs: List<SavedGif> = emptyList(),
    val gifsLoaded: Boolean = true,
    val gifsError: Boolean = false,
    val stickerSets: List<StickerPack> = emptyList(),
    val loadedStickerPacks: Map<Long, StickerPack> = emptyMap(),
    val stickersLoaded: Boolean = true,
    val stickersError: Boolean = false,
    val mediaRepository: MediaRepository? = null,
)

/**
 * Attach sheet: one capsule dock over a media body. The dock is pinned to the visible bottom of the
 * sheet at every offset, so the peek is handle + two tile rows + dock and dragging up grows the grid
 * without ever pulling the dock off screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    hasGalleryAccess: Boolean,
    accessDenied: Boolean,
    canSendPhotos: Boolean,
    canSendFiles: Boolean,
    media: List<DeviceMediaItem>,
    mediaLoading: Boolean,
    mediaFailed: Boolean,
    pickers: AttachSheetPickers,
    onRequestAccess: () -> Unit,
    onRetryMedia: () -> Unit,
    onReloadPickers: () -> Unit,
    onLoadMoreMedia: () -> Unit,
    onSendMedia: (List<DeviceMediaItem>) -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onPickFile: () -> Unit,
    onSendLocation: () -> Unit,
    onSendDocument: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetView = LocalView.current
    DisposableEffect(sheetView) {
        val window = (sheetView.parent as? DialogWindowProvider)?.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(SheetBackdropBlurPx)
        }
        onDispose {
            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setBackgroundBlurRadius(0)
            }
        }
    }
    val retryMedia = rememberUpdatedState(onRetryMedia)
    val loadMoreMedia = rememberUpdatedState(onLoadMoreMedia)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    var body by rememberSaveable { mutableStateOf(AttachBody.Gallery.name) }
    var selection by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }
    // Runs [after], then closes the sheet once. [onDismiss] must be idempotent so the scope's
    // sheet state and the host's panel state cannot disagree and leave a scrim behind.
    val leave: (() -> Unit) -> Unit = remember(scope, sheetState, onDismiss) {
        { after: () -> Unit ->
            after()
            onDismiss()
            scope.launch { sheetState.hide() }
        }
    }
    val current = AttachBody.entries.firstOrNull { it.name == body } ?: AttachBody.Gallery
    val toggleSelection: (DeviceMediaItem) -> Unit = remember {
        { item -> selection = selection.toggle(item) }
    }
    val startSelection: (DeviceMediaItem) -> Unit = remember {
        { item -> if (selection.isEmpty()) selection = listOf(item) }
    }
    val retryPage = remember { { retryMedia.value() } }
    val prefetchNextPage = remember { { loadMoreMedia.value() } }
    val wraps = current == AttachBody.Gallery && !hasGalleryAccess

    ModalBottomSheet(
        onDismissRequest = { leave {} },
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        // Matches the all-pins sheet: the thread behind a modal sheet must not stay readable.
        // design decision (not in M3): M3's sheet scrim ships at 0.32 alpha.
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
        // The handle lives inside the body so the pinned dock maths stay in the same coordinate
        // space; M3's own slot stays empty and there is exactly one handle.
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            // The permission hand-off and the fallback itself crossfade instead of popping.
            Crossfade(
                targetState = wraps,
                animationSpec = tween(BodyCrossfadeMs),
                label = "attachFallback",
                modifier = Modifier.fillMaxWidth(),
            ) { wrapState ->
                if (wrapState) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AttachDragHandle()
                        NoAccessBody(
                            denied = accessDenied,
                            canSendPhotos = canSendPhotos,
                            canSendFiles = canSendFiles,
                            onRequestAccess = onRequestAccess,
                            onPickPhoto = { leave(onPickPhoto) },
                            onPickVideo = { leave(onPickVideo) },
                            onPickFile = { leave(onPickFile) },
                            onSendLocation = { leave(onSendLocation) },
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        AttachDock(
                            current = current,
                            canSendPhotos = canSendPhotos,
                            canSendFiles = canSendFiles,
                            onSelectGallery = { body = AttachBody.Gallery.name },
                            onPickFile = { leave(onPickFile) },
                            onSendLocation = { leave(onSendLocation) },
                        )
                    }
                    return@Crossfade
                }
                val dockBlock = DockTopPadding + AttachDockHeight + navBarInset()
                val tileSizePx = with(density) {
                    ((maxWidth - TileGap * (TileColumns - 1)) / TileColumns).roundToPx()
                }
                val tallHeight = maxHeight * TallSheetFraction
                val tallHeightPx = with(density) { tallHeight.toPx() }
                val containerHeightPx = with(density) { maxHeight.toPx() }
                Box(modifier = Modifier.fillMaxWidth().height(tallHeight)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sheetVisibleSlice(sheetState, containerHeightPx, tallHeightPx)
                            .clipToBounds(),
                    ) {
                        AttachDragHandle()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Body swaps crossfade instead of popping.
                            Crossfade(
                                targetState = current,
                                animationSpec = tween(BodyCrossfadeMs),
                                label = "attachBody",
                                modifier = Modifier.fillMaxSize(),
                            ) { bodyState ->
                                when (bodyState) {
                                    AttachBody.Gallery -> GalleryBody(
                                        media = media,
                                        loading = mediaLoading,
                                        failed = mediaFailed,
                                        selection = selection,
                                        tileSizePx = tileSizePx,
                                        bottomPadding = dockBlock,
                                        onToggle = toggleSelection,
                                        onLongPress = startSelection,
                                        onRetry = retryPage,
                                        onPrefetch = prefetchNextPage,
                                        modifier = Modifier.fillMaxSize(),
                                    )


                                }
                            }
                            if (current == AttachBody.Gallery && hasGalleryAccess) {
                                SendFab(
                                    count = selection.size,
                                    onClick = { leave { onSendMedia(selection) } },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = DockInset, bottom = dockBlock + 12.dp),
                                )
                            }
                            AttachDock(
                                current = current,
                                canSendPhotos = canSendPhotos,
                                canSendFiles = canSendFiles,
                                onSelectGallery = { body = AttachBody.Gallery.name },
                                onPickFile = { leave(onPickFile) },
                                onSendLocation = { leave(onSendLocation) },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.sheetVisibleSlice(
    state: SheetState,
    containerHeightPx: Float,
    capPx: Float,
): Modifier = layout { measurable, constraints ->
    val offsetPx = runCatching { state.requireOffset() }.getOrDefault(containerHeightPx / 2f)
    val visiblePx = (containerHeightPx - offsetPx).coerceIn(0f, capPx).toInt()
    val placeable = measurable.measure(
        constraints.copy(minHeight = 0, maxHeight = visiblePx.coerceAtLeast(0)),
    )
    layout(constraints.maxWidth, visiblePx) { placeable.place(0, 0) }
}

@Composable
private fun AttachDragHandle() {
    Box(
        // 48dp touch target for the 32x4dp handle.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HandleTopPadding + 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Composable
private fun SendFab(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn(animationSpec = motion.fastSpatialSpec(), initialScale = 0.8f) +
            fadeIn(animationSpec = motion.fastEffectsSpec()),
        exit = scaleOut(animationSpec = motion.fastSpatialSpec()) +
            fadeOut(animationSpec = motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        BadgedBox(badge = { Badge { Text(count.toString()) } }) {
            FloatingActionButton(
                onClick = onClick,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(FabSize),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = stringResource(R.string.dialog_send),
                )
            }
        }
    }
}

private fun List<DeviceMediaItem>.toggle(item: DeviceMediaItem): List<DeviceMediaItem> =
    if (any { it.uri == item.uri }) filterNot { it.uri == item.uri } else this + item
