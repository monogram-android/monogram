package org.monogram.core.ui.media

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.monogram.core.ui.R
import org.monogram.core.ui.components.mediaTime

/** Filmstrip thumbnails row for browsing album items. */
@Composable
internal fun Filmstrip(items: List<MediaViewerItem>, currentIndex: Int, onSelect: (Int) -> Unit) {
    val mixed = items.any { it.isVideo } && items.any { !it.isVideo }
    val motion = mediaViewerMotionEnabled()
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, items.size) {
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
        if (!visible) listState.animateScrollToItem(currentIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val index = items.indexOf(item)
            val selected = index == currentIndex
            val artSize by animateDpAsState(
                if (selected) 64.dp else 46.dp,
                MediaMotion.quick(motion),
                label = "filmstripArt",
            )
            val corner by animateDpAsState(
                if (selected) 18.dp else 10.dp,
                MediaMotion.quick(motion),
                label = "filmstripCorner",
            )
            val cellAlpha by animateFloatAsState(
                if (selected) 1f else 0.6f,
                MediaMotion.quick(motion),
                label = "filmstripAlpha",
            )
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .selectable(selected = selected, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(artSize)
                        .graphicsLayer { alpha = cellAlpha }
                        .clip(RoundedCornerShape(corner))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(
                            if (selected) 2.dp else 0.dp,
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(corner),
                        ),
                ) {
                    item.preview?.let { preview ->
                        AsyncImage(
                            model = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (item.isVideo) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.media_thumb_video),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.Center).size(18.dp),
                        )
                    }
                    if (mixed) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (item.isVideo) R.string.media_badge_video else R.string.media_badge_photo,
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (item.isVideo && item.durationSeconds != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                        ) {
                            Text(
                                text = mediaTime(item.durationSeconds * 1000L),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
