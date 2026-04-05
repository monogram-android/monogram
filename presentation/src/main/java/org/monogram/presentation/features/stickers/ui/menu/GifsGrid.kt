package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun GifsView(
    onGifSelected: (GifModel) -> Unit,
    onSearchFocused: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    stickerRepository: StickerRepository,
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
                        key = { _, gif -> gif.id },
                        contentType = { _, _ -> "GifItem" }
                    ) { index, gif ->
                        GifItem(
                            gif = gif,
                            gifRepository = gifRepository,
                            stickerRepository = stickerRepository,
                            onGifSelected = onGifClick,
                            animate = index in visibleRange
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
    stickerRepository: StickerRepository,
    onGifSelected: (GifModel) -> Unit,
    animate: Boolean = true
) {
    var gifPath by remember { mutableStateOf<String?>(null) }
    var thumbPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gif, animate) {
        if (animate) {
            gifRepository.getGifFile(gif).collect {
                gifPath = it
            }
        }
    }

    LaunchedEffect(gif.thumbFileId) {
        if (gif.thumbFileId != null) {
            stickerRepository.getStickerFile(gif.thumbFileId!!).collect {
                thumbPath = it
            }
        }
    }

    val state = when {
        gifPath != null -> GifState.Video
        thumbPath != null -> GifState.Thumbnail
        else -> GifState.Loading
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable {
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
                        animate = animate,
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
    Video
}
