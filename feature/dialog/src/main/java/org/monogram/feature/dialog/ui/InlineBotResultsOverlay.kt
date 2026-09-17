package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.monogram.core.models.InlineBotResult
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.SavedGif
import org.monogram.feature.dialog.InlineBotQuery
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingContained
import org.monogram.core.ui.loading.MonogramLoadingHeroSize
import org.monogram.core.ui.loading.MonogramLoadingListSize

@Composable
internal fun InlineBotResultsOverlay(
    query: InlineBotQuery,
    page: InlineBotResults?,
    loading: Boolean,
    error: Boolean,
    mediaRepository: MediaRepository?,
    onSelect: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = composerPanelHeight()),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = "@${query.username}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            when {
                loading && page == null -> {
                    MonogramLoadingContained(
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.CenterHorizontally),
                        size = MonogramLoadingHeroSize,
                    )
                    Text(
                        text = stringResource(R.string.dialog_inline_loading),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error && page == null -> InlinePageStatus(
                    loading = false,
                    error = true,
                    onRetry = onRetry,
                )
                page == null -> Text(
                    text = stringResource(R.string.dialog_inline_empty),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                page.results.isEmpty() -> {
                    if (!page.nextOffset.isNullOrBlank()) {
                        LaunchedEffect(page.nextOffset, loading, error) {
                            if (!loading && !error) onLoadMore()
                        }
                        when {
                            loading -> MonogramLoading(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.CenterHorizontally),
                                size = MonogramLoadingListSize,
                            )
                            error -> InlinePageStatus(
                                loading = false,
                                error = true,
                                onRetry = onLoadMore,
                            )
                            else -> Unit
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.dialog_inline_empty),
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                page.gallery -> {
                    val gridState = rememberLazyGridState()
                    InlinePagingEffect(
                        hasNext = !page.nextOffset.isNullOrBlank(),
                        loading = loading,
                        error = error,
                        visibleLastIndex = {
                            gridState.layoutInfo.visibleItemsInfo
                                .asSequence()
                                .map { it.index }
                                .filter { it < page.results.size }
                                .maxOrNull() ?: -1
                        },
                        itemCount = page.results.size,
                        onLoadMore = onLoadMore,
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(88.dp),
                        state = gridState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(page.results, key = { it.id }) { item ->
                            InlineResultThumb(
                                item = item,
                                mediaRepository = mediaRepository,
                                onClick = { onSelect(item.id) },
                            )
                        }
                        if (loading || error) {
                            item(
                                key = "inline-page-status",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                InlinePageStatus(
                                    loading = loading,
                                    error = error,
                                    onRetry = onLoadMore,
                                )
                            }
                        }
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    InlinePagingEffect(
                        hasNext = !page.nextOffset.isNullOrBlank(),
                        loading = loading,
                        error = error,
                        visibleLastIndex = {
                            listState.layoutInfo.visibleItemsInfo
                                .asSequence()
                                .map { it.index }
                                .filter { it < page.results.size }
                                .maxOrNull() ?: -1
                        },
                        itemCount = page.results.size,
                        onLoadMore = onLoadMore,
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(page.results, key = { it.id }) { item ->
                            ListItem(
                                leadingContent = {
                                    InlineResultThumb(
                                        item = item,
                                        mediaRepository = mediaRepository,
                                        onClick = { onSelect(item.id) },
                                    )
                                },
                                supportingContent = item.description?.takeIf { it.isNotBlank() }?.let {
                                    { Text(it) }
                                },
                                modifier = Modifier.clickable { onSelect(item.id) },
                            ) {
                                Text(item.title?.ifBlank { item.kind } ?: item.kind)
                            }
                        }
                        if (loading || error) {
                            item(key = "inline-page-status") {
                                InlinePageStatus(
                                    loading = loading,
                                    error = error,
                                    onRetry = onLoadMore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val InlinePrefetchItems = 6

@Composable
private fun InlinePagingEffect(
    hasNext: Boolean,
    loading: Boolean,
    error: Boolean,
    visibleLastIndex: () -> Int,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    val shouldLoadMore by remember(hasNext, loading, error, itemCount) {
        derivedStateOf {
            hasNext && !loading && !error && itemCount > 0 &&
                visibleLastIndex() >= itemCount - InlinePrefetchItems
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
}

@Composable
private fun InlinePageStatus(
    loading: Boolean,
    error: Boolean,
    onRetry: () -> Unit,
) {
    when {
        loading -> MonogramLoading(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        )
        error -> Text(
            text = stringResource(R.string.dialog_inline_error),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRetry)
                .padding(12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun InlineResultThumb(
    item: InlineBotResult,
    mediaRepository: MediaRepository?,
    onClick: () -> Unit,
) {
    val lookupId = item.previewLookupId()
    val cacheKey = item.thumbCacheKey
    when {
        !cacheKey.isNullOrBlank() && cacheKey.isHttpUrl() -> HttpInlineThumb(
            url = cacheKey,
            label = item.title?.ifBlank { null } ?: item.kind,
            onClick = onClick,
        )
        lookupId != null && !cacheKey.isNullOrBlank() -> SavedGifCell(
            gif = SavedGif(
                documentId = lookupId,
                cacheKey = cacheKey,
                thumbCacheKey = cacheKey,
            ),
            mediaRepository = mediaRepository,
            onClick = onClick,
            thumbOnly = false,
        )
        else -> Box(
            modifier = Modifier
                .size(88.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.title?.ifBlank { null } ?: item.kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun String.isHttpUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

@Composable
private fun HttpInlineThumb(
    url: String,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = label,
            modifier = Modifier.size(88.dp),
            contentScale = ContentScale.Crop,
        )
    }
}
