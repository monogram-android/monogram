package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.feature.dialog.R

internal val TileGap = 2.dp
internal const val TileColumns = 3
private const val PeekTileRows = 2
private const val PlaceholderTiles = TileColumns * PeekTileRows
private const val PrefetchRows = 2
private val CheckCircleSize = 22.dp
private const val TileLoaderDelayMs = 150L
private const val TileCheckScale = 0.8f
private const val TileCheckDurationMs = 120

@Composable
internal fun GalleryBody(
    media: List<DeviceMediaItem>,
    loading: Boolean,
    failed: Boolean,
    selection: List<DeviceMediaItem>,
    tileSizePx: Int,
    bottomPadding: Dp,
    onToggle: (DeviceMediaItem) -> Unit,
    onLongPress: (DeviceMediaItem) -> Unit,
    onRetry: () -> Unit,
    onPrefetch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPlaceholders = media.isEmpty() && loading
    if (media.isEmpty() && !showPlaceholders) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if (failed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.dialog_attach_media_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.dialog_retry))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.dialog_attach_media_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val selectedUris = remember(selection) { selection.mapTo(mutableSetOf()) { it.uri.toString() } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, media.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= media.size - TileColumns * PrefetchRows) onPrefetch()
            }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(TileColumns),
        state = gridState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalArrangement = Arrangement.spacedBy(TileGap),
        contentPadding = PaddingValues(bottom = bottomPadding + 12.dp, top = 8.dp),
    ) {
        if (showPlaceholders) {
            items(PlaceholderTiles) {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    MonogramLoading(size = 20.dp, status = null)
                }
            }
            return@LazyVerticalGrid
        }
        itemsIndexed(items = media, key = { _, item -> item.uri }) { _, item ->
            MediaTile(
                item = item,
                tileSizePx = tileSizePx,
                isSelected = item.uri.toString() in selectedUris,
                onToggle = { onToggle(item) },
                onLongPress = { onLongPress(item) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: DeviceMediaItem,
    tileSizePx: Int,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var retry by remember(item.uri) { mutableIntStateOf(0) }
    var state by remember(item.uri) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val failed = state is AsyncImagePainter.State.Error
    val loading = state is AsyncImagePainter.State.Empty ||
        state is AsyncImagePainter.State.Loading
    var showLoader by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(TileLoaderDelayMs)
            showLoader = true
        } else {
            showLoader = false
        }
    }
    val request = remember(item.uri, retry, tileSizePx, context) {
        ImageRequest.Builder(context)
            .data(item.uri)
            // Decode at tile size and keep the default cache key (the media id) unless retrying.
            .size(tileSizePx)
            .apply { if (retry > 0) memoryCacheKey("${item.uri}#$retry") }
            .build()
    }
    val onImageState = remember { { value: AsyncImagePainter.State -> state = value } }
    val label = stringResource(
        if (item.kind == "video") R.string.dialog_attach_video else R.string.dialog_attach_photo,
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.extraSmall)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = { if (failed) retry++ else onToggle() },
                onLongClick = { if (failed) retry++ else onLongPress() },
            )
            .semantics {
                contentDescription = label
                selected = isSelected
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = onImageState,
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (showLoader) MonogramLoading(size = 20.dp, status = null)
            }
        }
        if (failed) {
            Box(
                modifier = Modifier.fillMaxSize().background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (item.kind == "video" && !failed) {
            VideoChip(
                seconds = item.durationSeconds,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        }
        AnimatedVisibility(
            visible = isSelected,
            enter = scaleIn(
                animationSpec = tween(TileCheckDurationMs),
                initialScale = TileCheckScale,
            ) + fadeIn(tween(TileCheckDurationMs)),
            exit = scaleOut(tween(TileCheckDurationMs)) + fadeOut(tween(TileCheckDurationMs)),
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(CheckCircleSize)
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .border(2.dp, scheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (pressed && !failed) {
            Box(modifier = Modifier.fillMaxSize().background(scheme.onSurface.copy(alpha = 0.08f)))
        }
    }
}

@Composable
private fun VideoChip(seconds: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = mediaDurationLabel(seconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}
