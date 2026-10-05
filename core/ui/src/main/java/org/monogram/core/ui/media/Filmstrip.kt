package org.monogram.core.ui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Interpolatable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.monogram.core.ui.R
import org.monogram.core.ui.components.mediaTime

/** Filmstrip thumbnails row for browsing album items. */
@Composable
internal fun Filmstrip(
    items: List<MediaViewerItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    selectedIds: Set<String> = emptySet(),
    onLongPress: (Int) -> Unit = {},
) {
    val mixed = items.any { it.isVideo } && items.any { !it.isVideo }
    val motion = mediaViewerMotionEnabled()
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, items.size, motion) {
        if (currentIndex !in items.indices) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
        if (!visible) {
            if (motion) listState.animateScrollToItem(currentIndex)
            else listState.scrollToItem(currentIndex)
        }
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            val marked = item.id in selectedIds
            val selected = index == currentIndex || marked
            val label = albumItemLabel(item, index, items.size)
            val scale by animateFloatAsState(
                if (selected) 1.12f else 1f,
                MediaMotion.spatial(motion),
                label = "filmstripScale",
            )
            val cellAlpha by animateFloatAsState(
                if (selected) 1f else 0.6f,
                MediaMotion.effects(motion),
                label = "filmstripAlpha",
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .semantics {
                        this.selected = selected
                        contentDescription = label
                    }
                    .combinedClickable(
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                        onLongClick = { onLongPress(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AlbumThumbnail(
                    item = item,
                    mixed = mixed,
                    selected = selected,
                    marked = marked,
                    modifier = Modifier.size(56.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = cellAlpha
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumOverview(
    album: MediaAlbumState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = album.items
    val mixed = items.any { it.isVideo } && items.any { !it.isVideo }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = album.index.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            state = gridState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                val marked = album.selection.contains(item.id)
                val selected = index == album.index || marked
                val label = albumItemLabel(item, index, items.size)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .semantics {
                            this.selected = selected
                            contentDescription = label
                        }
                        .combinedClickable(
                            role = Role.Tab,
                            onClick = {
                                onSelect(index)
                                onDismiss()
                            },
                            onLongClick = { album.toggleSelection(item.id) },
                        ),
                ) {
                    AlbumThumbnail(
                        item = item,
                        mixed = mixed,
                        selected = selected,
                        marked = marked,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlbumThumbnail(
    item: MediaViewerItem,
    mixed: Boolean,
    selected: Boolean,
    marked: Boolean,
    modifier: Modifier = Modifier,
) {
    val motion = mediaViewerMotionEnabled()
    val shapeProgress by animateFloatAsState(
        if (selected) 1f else 0f,
        MediaMotion.spatial(motion),
        label = "albumThumbnailShape",
    )
    val shape = Interpolatable.lerp(
        MaterialTheme.shapes.large,
        MaterialTheme.shapes.extraLarge,
        shapeProgress.coerceIn(0f, 1f),
    ) as Shape
    Box(
        modifier = modifier
            .clearAndSetSemantics {}
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                if (selected) 2.dp else 0.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape,
            ),
    ) {
        // The model has no reveal state: spoiler thumbnails stay hidden.
        if (!item.spoiler) {
            item.preview?.let { preview ->
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val progress = item.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        val loading = !item.failed && (item.loading || (progress != null && progress < 1f))
        if (item.isVideo && !item.spoiler && !item.failed && !loading) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center).size(18.dp),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (item.hdr != MediaHdr.None) FilmstripBadge("HDR")
            if (item.forceLoop) FilmstripBadge("GIF")
            else if (mixed) {
                FilmstripBadge(
                    stringResource(
                        if (item.isVideo) R.string.media_badge_video else R.string.media_badge_photo,
                    ),
                )
            }
        }
        if (item.failed || item.spoiler) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    imageVector = if (item.failed) Icons.Default.ErrorOutline else Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.padding(2.dp).size(18.dp),
                )
            }
        }
        if (loading) {
            val indicatorModifier = if (item.spoiler) {
                Modifier.align(Alignment.BottomStart).padding(3.dp).size(20.dp)
            } else {
                Modifier.align(Alignment.Center).size(28.dp)
            }
            if (progress != null) {
                LoadingIndicator(progress = { progress }, modifier = indicatorModifier)
            } else {
                LoadingIndicator(modifier = indicatorModifier)
            }
        }
        if (marked) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp),
            )
        }
        if (item.isVideo && item.durationSeconds != null) {
            FilmstripBadge(
                text = mediaTime(item.durationSeconds * 1000L),
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
            )
        }
    }
}

@Composable
private fun albumItemLabel(item: MediaViewerItem, index: Int, count: Int): String {
    val kind = stringResource(
        if (item.isVideo) R.string.media_badge_video_lower else R.string.media_badge_photo_lower,
    )
    val sender = item.senderName ?: stringResource(R.string.media_sender_unknown)
    val label = stringResource(
        R.string.media_viewer_album_item,
        kind,
        index + 1,
        count,
        sender,
        item.dateLabel ?: "",
    )
    if (!item.failed) return label
    val error = stringResource(
        if (item.isVideo) R.string.media_video_error else R.string.media_image_error,
    )
    return "$label, $error"
}

@Composable
private fun FilmstripBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}
