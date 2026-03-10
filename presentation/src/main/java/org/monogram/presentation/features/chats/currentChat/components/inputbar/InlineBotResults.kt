package org.monogram.presentation.features.chats.currentChat.components.inputbar

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.InlineQueryResultModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.repository.InlineBotResultsModel

private const val TAG = "InlineBotResults"

@Composable
fun InlineBotResults(
    inlineBotResults: InlineBotResultsModel?,
    isLoading: Boolean,
    onResultClick: (String) -> Unit,
    onSwitchPmClick: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val results = inlineBotResults?.results ?: emptyList()
    val nextOffset = inlineBotResults?.nextOffset

    LaunchedEffect(inlineBotResults) {
        if (inlineBotResults != null) {
            Log.d(TAG, "Results received: ${results.size} items. SwitchPM: ${inlineBotResults.switchPmText != null}")
            results.forEachIndexed { index, result ->
                Log.d(TAG, "Result[$index]: id=${result.id}, type=${result.type}, title=${result.title}")
            }
        }
    }

    if (results.isEmpty() && inlineBotResults?.switchPmText == null && !isLoading) {
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (inlineBotResults?.switchPmText != null && inlineBotResults.switchPmParameter != null) {
                SwitchPmButton(
                    text = inlineBotResults.switchPmText!!,
                    onClick = {
                        Log.d(TAG, "Switch PM clicked: ${inlineBotResults.switchPmParameter}")
                        onSwitchPmClick(inlineBotResults.switchPmParameter!!)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }

            Box(modifier = Modifier.weight(1f)) {
                if (results.isNotEmpty()) {
                    val isGrid = remember(results) {
                        val first = results.firstOrNull()
                        val type = first?.type?.lowercase() ?: ""
                        val hasText = results.any { !it.title.isNullOrBlank() || !it.description.isNullOrBlank() }

                        if (hasText) {
                            false
                        } else {
                            type.contains("photo") || type.contains("gif") ||
                                    type.contains("sticker") || type.contains("video") ||
                                    type.contains("animation")
                        }
                    }

                    if (isGrid) {
                        val firstType = remember(results) { results.firstOrNull()?.type?.lowercase() ?: "" }
                        val columns = when {
                            firstType.contains("sticker") -> 5
                            firstType.contains("photo") -> 3
                            else -> 3
                        }

                        val gridState = rememberLazyStaggeredGridState()
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                                lastVisibleItem != null && lastVisibleItem.index >= results.size - 5 && !isLoading && nextOffset != null
                            }
                        }

                        LaunchedEffect(shouldLoadMore) {
                            if (shouldLoadMore) {
                                Log.d(TAG, "Loading more results for grid...")
                                onLoadMore(nextOffset!!)
                            }
                        }

                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(columns),
                            contentPadding = PaddingValues(2.dp),
                            verticalItemSpacing = 2.dp,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize(),
                            state = gridState
                        ) {
                            itemsIndexed(results, key = { index, item -> "${item.id}_$index" }) { _, result ->
                                InlineBotMediaItem(
                                    result = result,
                                    onClick = {
                                        Log.d(TAG, "Media result clicked: ${result.id}")
                                        onResultClick(result.id)
                                    }
                                )
                            }
                        }
                    } else {
                        val listState = rememberLazyListState()
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                                lastVisibleItemIndex != null && lastVisibleItemIndex >= results.size - 5 && !isLoading && nextOffset != null
                            }
                        }

                        LaunchedEffect(shouldLoadMore) {
                            if (shouldLoadMore) {
                                Log.d(TAG, "Loading more results for list...")
                                onLoadMore(nextOffset!!)
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            state = listState
                        ) {
                            itemsIndexed(results, key = { index, item -> "${item.id}_$index" }) { index, result ->
                                InlineBotResultItem(
                                    result = result,
                                    onClick = {
                                        Log.d(TAG, "Result clicked: ${result.id}")
                                        onResultClick(result.id)
                                    }
                                )
                                if (index < results.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                    )
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchPmButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun rememberMediaModel(result: InlineQueryResultModel): Any? {
    val context = LocalContext.current
    val contentPath = when (val c = result.content) {
        is MessageContent.Photo -> if (c.isDownloading) null else c.path
        is MessageContent.Video -> if (c.isDownloading) null else c.path
        is MessageContent.Gif -> if (c.isDownloading) null else c.path
        is MessageContent.Sticker -> if (c.isDownloading) null else c.path
        is MessageContent.VideoNote -> if (c.isDownloading) null else c.path
        is MessageContent.Document -> if (c.isDownloading) null else c.path
        else -> null
    }

    return remember(contentPath, result.thumbUrl) {
        val data = if (!contentPath.isNullOrBlank()) contentPath else result.thumbUrl
        if (data == null) return@remember null

        ImageRequest.Builder(context)
            .data(data)
            .crossfade(true)
            .build()
    }
}

@Composable
private fun InlineBotMediaItem(
    result: InlineQueryResultModel,
    onClick: () -> Unit
) {
    val aspectRatio = remember(result.width, result.height) {
        if (result.width > 0 && result.height > 0) {
            (result.width.toFloat() / result.height.toFloat()).coerceIn(0.6f, 1.8f)
        } else {
            1f
        }
    }

    val mediaModel = rememberMediaModel(result)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = mediaModel,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        val type = result.type.lowercase()
        if (type.contains("gif") || type.contains("video") || type.contains("animation")) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (type.contains("gif") || type.contains("animation")) "GIF" else "VIDEO",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun InlineBotResultItem(
    result: InlineQueryResultModel,
    onClick: () -> Unit
) {
    val hasText = !result.title.isNullOrBlank() || !result.description.isNullOrBlank()
    val mediaModel = rememberMediaModel(result)

    if (!hasText && result.thumbUrl != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        ) {
            val aspectRatio = if (result.width > 0 && result.height > 0) {
                (result.width.toFloat() / result.height.toFloat()).coerceIn(0.5f, 2.5f)
            } else {
                16f / 9f
            }

            AsyncImage(
                model = mediaModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .aspectRatio(aspectRatio),
                contentScale = ContentScale.Crop
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (result.thumbUrl != null) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = mediaModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                if (!result.title.isNullOrBlank()) {
                    Text(
                        text = result.title.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (!result.description.isNullOrBlank()) {
                    Text(
                        text = result.description.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
