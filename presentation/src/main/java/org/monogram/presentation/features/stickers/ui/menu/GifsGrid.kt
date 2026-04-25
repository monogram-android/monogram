package org.monogram.presentation.features.stickers.ui.menu

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.monogram.domain.models.GifModel
import org.monogram.domain.repository.GifRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.media.VideoStickerPlayer
import org.monogram.presentation.core.media.VideoType
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun GifsView(
    onGifSelected: (GifModel) -> Unit,
    onSearchFocused: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    gifRepository: GifRepository = koinInject()
) {
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var savedGifs by remember { mutableStateOf<List<GifModel>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<GifModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        savedGifs = gifRepository.getSavedGifs()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            isLoading = true
            delay(300)
            debouncedSearchQuery = searchQuery
        } else {
            debouncedSearchQuery = ""
            searchResults = emptyList()
            isLoading = false
        }
    }

    LaunchedEffect(debouncedSearchQuery) {
        if (debouncedSearchQuery.isNotEmpty()) {
            searchResults = gifRepository.searchGifs(debouncedSearchQuery)
            isLoading = false
        } else {
            searchResults = emptyList()
            isLoading = false
        }
    }

    val gifsToShow = remember(searchQuery, searchResults, savedGifs) {
        if (searchQuery.isNotEmpty()) {
            searchResults
        } else {
            savedGifs
        }
    }

    val visibleRange by remember {
        derivedStateOf {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                IntRange.EMPTY
            } else {
                visibleItems.first().index..visibleItems.last().index
            }
        }
    }
    val preloadRange by remember {
        derivedStateOf {
            if (visibleRange == IntRange.EMPTY) {
                IntRange.EMPTY
            } else {
                val start = maxOf(visibleRange.first - GIF_PRELOAD_BACKWARD_ITEMS, 0)
                val end = visibleRange.last + GIF_PRELOAD_FORWARD_ITEMS
                start..end
            }
        }
    }

    val onGifClick: (GifModel) -> Unit = remember(onGifSelected) {
        { gif: GifModel ->
            onGifSelected(gif)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GifSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearchFocused = {
                isFocused = it
                onSearchFocused(it)
            },
            onClearQuery = { searchQuery = "" },
            isSearchMode = isFocused
        )

        AnimatedVisibility(
            visible = !isFocused && searchQuery.isEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = stringResource(R.string.gifs_header_recent_saved),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 0.5.dp,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (isLoading) {
            GifGridSkeleton(contentPadding = contentPadding)
        } else {
            if (gifsToShow.isEmpty() && searchQuery.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.gifs_no_results), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp
                    ),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        items = gifsToShow,
                        key = { _, gif -> "${gif.inlineQueryId ?: 0L}_${gif.id}_${gif.fileId}_${gif.thumbFileId ?: 0L}" },
                        contentType = { _, _ -> "GifItem" }
                    ) { index, gif ->
                        GifItem(
                            gif = gif,
                            gifRepository = gifRepository,
                            onGifSelected = onGifClick,
                            shouldPlay = index in visibleRange,
                            shouldPreload = index in preloadRange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GifGridSkeleton(
    contentPadding: PaddingValues
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp
        ),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        userScrollEnabled = false
    ) {
        items(20) {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .shimmerEffect()
            )
        }
    }
}

@Composable
fun GifSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchFocused: (Boolean) -> Unit,
    onClearQuery: () -> Unit,
    isSearchMode: Boolean = false
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSearchMode) {
            IconButton(
                onClick = { focusManager.clearFocus() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .onFocusChanged { onSearchFocused(it.isFocused) },
            placeholder = { Text(stringResource(R.string.gifs_search_placeholder), style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = if (!isSearchMode) {
                {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else null,
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_clear),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}


@UnstableApi
@Composable
fun GifItem(
    gif: GifModel,
    gifRepository: GifRepository,
    onGifSelected: (GifModel) -> Unit,
    shouldPlay: Boolean = true,
    shouldPreload: Boolean = true
) {
    val gifIdentity = remember(gif.inlineQueryId, gif.id, gif.fileId, gif.thumbFileId) {
        "${gif.inlineQueryId ?: 0L}:${gif.id}:${gif.fileId}:${gif.thumbFileId ?: 0L}"
    }

    val gifPath by produceState<String?>(
        initialValue = null,
        key1 = gifIdentity,
        key2 = shouldPreload
    ) {
        value = null
        if (!shouldPreload) return@produceState

        gifRepository.getGifFile(gif).collect { resolvedPath ->
            value = resolvedPath
            Log.d(
                GIF_GRID_TAG,
                "gifPath identity=$gifIdentity fileId=${gif.fileId} thumbFileId=${gif.thumbFileId} path=$resolvedPath"
            )
        }
    }
    val thumbPath by produceState<String?>(
        initialValue = null,
        key1 = gifIdentity,
        key2 = shouldPreload
    ) {
        value = null
        val thumbFileId = gif.thumbFileId
        if (!shouldPreload || thumbFileId == null) return@produceState

        gifRepository.getGifThumbnailFile(thumbFileId).collect { resolvedPath ->
            value = resolvedPath
            Log.d(
                GIF_GRID_TAG,
                "thumbPath identity=$gifIdentity fileId=${gif.fileId} thumbFileId=$thumbFileId path=$resolvedPath"
            )
        }
    }

    val state = when {
        shouldPlay && gifPath != null -> GifState.Video
        thumbPath != null -> GifState.Thumbnail
        gifPath != null -> GifState.Ready
        else -> GifState.Loading
    }

    LaunchedEffect(gifIdentity, gifPath, thumbPath, shouldPlay, shouldPreload, state) {
        Log.d(
            GIF_GRID_TAG,
            "bind identity=$gifIdentity inlineQueryId=${gif.inlineQueryId} fileId=${gif.fileId} " +
                    "thumbFileId=${gif.thumbFileId} shouldPlay=$shouldPlay shouldPreload=$shouldPreload " +
                    "state=$state gifPath=$gifPath thumbPath=$thumbPath"
        )
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
                Log.d(
                    GIF_GRID_TAG,
                    "click identity=$gifIdentity inlineQueryId=${gif.inlineQueryId} fileId=${gif.fileId} " +
                            "thumbFileId=${gif.thumbFileId} gifPath=$gifPath thumbPath=$thumbPath"
                )
                onGifSelected(gif)
            }
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "GifContentTransition"
        ) { targetState ->
            when (targetState) {
                GifState.Video -> {
                    VideoStickerPlayer(
                        path = gifPath!!,
                        type = VideoType.Gif,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        animate = shouldPlay,
                        fileId = gif.fileId.toInt(),
                        thumbnailData = thumbPath
                    )
                }
                GifState.Thumbnail -> {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thumbPath)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(modifier = Modifier.shimmerEffect())
                        }
                    )
                }
                GifState.Ready -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    )
                }
                GifState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shimmerEffect()
                    )
                }
            }
        }
    }
}

private enum class GifState {
    Loading,
    Thumbnail,
    Ready,
    Video
}

private const val GIF_GRID_TAG = "GifGrid"
private const val GIF_PRELOAD_FORWARD_ITEMS = 8
private const val GIF_PRELOAD_BACKWARD_ITEMS = 2
