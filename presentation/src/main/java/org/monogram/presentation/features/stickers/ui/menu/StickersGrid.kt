package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.conversation.ui.StickerSetSheet
import org.monogram.presentation.features.stickers.ui.view.LocalIsScrolling
import org.monogram.presentation.features.stickers.ui.view.StickerItem
import org.monogram.presentation.features.stickers.ui.view.StickerSkeleton
import org.monogram.presentation.features.stickers.ui.view.shimmerEffect

@Composable
fun StickersView(
    onStickerSelected: (String) -> Unit,
    onSearchFocused: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    stickerRepository: StickerRepository = koinInject()
) {
    val stickerSets by stickerRepository.installedStickerSets.collectAsState(initial = emptyList())
    var selectedSetId by remember { mutableLongStateOf(-1L) } // -1 for recent stickers
    var recentStickers by remember { mutableStateOf<List<StickerModel>>(emptyList()) }
    var previewStickerSet by remember { mutableStateOf<StickerSetModel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var initialSectionApplied by remember { mutableStateOf(false) }
    var recentStickersLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var searchResultsStickers by remember { mutableStateOf<List<StickerModel>>(emptyList()) }
    var searchResultsSets by remember { mutableStateOf<List<StickerSetModel>>(emptyList()) }
    var searchEmojiHints by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        stickerRepository.loadInstalledStickerSets()
        recentStickers = stickerRepository.getRecentStickers()
        recentStickersLoaded = true
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            delay(300)
            debouncedSearchQuery = searchQuery
        }
    }

    LaunchedEffect(debouncedSearchQuery) {
        if (debouncedSearchQuery.length >= 2) {
            searchEmojiHints = stickerRepository.getStickerEmojiHints(debouncedSearchQuery)
            searchResultsStickers = stickerRepository.searchStickers(debouncedSearchQuery)
            searchResultsSets = stickerRepository.searchStickerSets(debouncedSearchQuery)
        } else {
            searchEmojiHints = emptyList()
            searchResultsStickers = emptyList()
            searchResultsSets = emptyList()
        }
    }

    val firstVisibleItemIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }

    LaunchedEffect(recentStickersLoaded, recentStickers, stickerSets, searchQuery) {
        if (initialSectionApplied || searchQuery.isNotEmpty() || !recentStickersLoaded) return@LaunchedEffect

        when {
            recentStickers.isNotEmpty() -> {
                selectedSetId = -1L
                gridState.scrollToItem(0)
                initialSectionApplied = true
            }

            stickerSets.isNotEmpty() -> {
                selectedSetId = stickerSets.first().id
                initialSectionApplied = true
            }
        }
    }

    LaunchedEffect(recentStickers, searchQuery, firstVisibleItemIndex) {
        if (searchQuery.isNotEmpty()) return@LaunchedEffect
        if (recentStickers.isNotEmpty() && firstVisibleItemIndex == 0 && selectedSetId != -1L) {
            selectedSetId = -1L
        }
    }

    val localSearchResults by remember(searchQuery, stickerSets) {
        derivedStateOf {
            buildLocalStickerFallback(
                query = searchQuery,
                stickerSets = stickerSets
            )
        }
    }
    val shouldUseLocalFallback = searchQuery.isNotBlank() && (
            debouncedSearchQuery.length < 2 ||
                    (searchResultsStickers.isEmpty() && searchResultsSets.isEmpty())
            )

    LaunchedEffect(firstVisibleItemIndex, stickerSets, recentStickers, searchQuery) {
        if (searchQuery.isNotEmpty() || !gridState.isScrollInProgress) return@LaunchedEffect

        var currentCount = 0
        if (recentStickers.isNotEmpty()) {
            if (firstVisibleItemIndex < recentStickers.size + 1) {
                selectedSetId = -1L
                return@LaunchedEffect
            }
            currentCount += recentStickers.size + 1
        }

        for (set in stickerSets) {
            if (firstVisibleItemIndex >= currentCount && firstVisibleItemIndex < currentCount + set.stickers.size + 1) {
                selectedSetId = set.id
                break
            }
            currentCount += set.stickers.size + 1
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StickerSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onFocusChanged = {
                isSearchFocused = it
                onSearchFocused(it || searchQuery.isNotEmpty())
            },
            isSearchMode = isSearchFocused || searchQuery.isNotEmpty()
        )

        AnimatedVisibility(
            visible = !isSearchFocused && searchQuery.isEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            StickerSetsRow(
                stickerSets = stickerSets,
                selectedSetId = selectedSetId,
                hasRecent = recentStickers.isNotEmpty(),
                onSetSelected = { id ->
                    selectedSetId = id
                    scope.launch {
                        var targetIndex = 0
                        if (id == -1L) {
                            gridState.animateScrollToItem(0)
                        } else {
                            if (recentStickers.isNotEmpty()) targetIndex += recentStickers.size + 1
                            for (set in stickerSets) {
                                if (set.id == id) {
                                    gridState.animateScrollToItem(targetIndex)
                                    break
                                }
                                targetIndex += set.stickers.size + 1
                            }
                        }
                    }
                }
            )
        }

        if (!isSearchFocused && searchQuery.isEmpty()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (stickerSets.isEmpty() && recentStickers.isEmpty()) {
                StickerGridSkeleton(contentPadding = contentPadding)
            } else {
                CompositionLocalProvider(LocalIsScrolling provides gridState.isScrollInProgress) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 64.dp),
                        state = gridState,
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
                        if (searchQuery.isNotEmpty()) {
                            if (searchEmojiHints.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    StickerEmojiHintsRow(
                                        hints = searchEmojiHints,
                                        onHintClick = { hint -> searchQuery = hint }
                                    )
                                }
                            }

                            if (searchResultsStickers.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    PackHeader(stringResource(R.string.stickers_header_all))
                                }
                                items(searchResultsStickers, key = { "search_sticker_${it.id}" }) { sticker ->
                                    StickerGridItem(sticker, onStickerSelected, {
                                        scope.launch {
                                            val set = stickerRepository.getStickerSet(sticker.id)
                                            previewStickerSet = set
                                        }
                                    })
                                }
                            }

                            searchResultsSets.forEach { set ->
                                item(key = "search_header_${set.id}", span = { GridItemSpan(maxLineSpan) }) {
                                    PackHeader(set.title, onClick = { previewStickerSet = set })
                                }
                                items(set.stickers, key = { "search_set_${set.id}_${it.id}" }) { sticker ->
                                    StickerGridItem(sticker, onStickerSelected, {
                                        scope.launch {
                                            previewStickerSet = set
                                        }
                                    })
                                }
                            }

                            if (shouldUseLocalFallback) {
                                localSearchResults.forEach { set ->
                                    item(
                                        key = "fallback_header_${set.id}",
                                        span = { GridItemSpan(maxLineSpan) }
                                    ) {
                                        PackHeader(set.title, onClick = { previewStickerSet = set })
                                    }
                                    items(
                                        set.stickers,
                                        key = { "fallback_set_${set.id}_${it.id}" }) { sticker ->
                                        StickerGridItem(sticker, onStickerSelected, {
                                            scope.launch {
                                                previewStickerSet = set
                                            }
                                        })
                                    }
                                }
                            }
                        } else {
                            if (recentStickers.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    PackHeader(stringResource(R.string.stickers_header_recent))
                                }
                                items(recentStickers, key = { "recent_${it.id}" }) { sticker ->
                                    StickerGridItem(sticker, onStickerSelected, {
                                        scope.launch {
                                            val set = stickerRepository.getStickerSet(sticker.id)
                                            previewStickerSet = set
                                        }
                                    })
                                }
                            }

                            stickerSets.forEach { set ->
                                item(key = "header_${set.id}", span = { GridItemSpan(maxLineSpan) }) {
                                    PackHeader(set.title, onClick = { previewStickerSet = set })
                                }
                                items(set.stickers, key = { "set_${set.id}_${it.id}" }) { sticker ->
                                    StickerGridItem(sticker, onStickerSelected, {
                                        scope.launch {
                                            val s = stickerRepository.getStickerSet(sticker.id)
                                            previewStickerSet = s
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewStickerSet?.let { set ->
        StickerSetSheet(
            stickerSet = set,
            onDismiss = { previewStickerSet = null },
            onStickerClick = { _, path ->
                onStickerSelected(path)
                previewStickerSet = null
            }
        )
    }
}

private fun buildLocalStickerFallback(
    query: String,
    stickerSets: List<StickerSetModel>
): List<StickerSetModel> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return emptyList()

    return stickerSets.mapNotNull { set ->
        val setMatches = set.title.contains(normalizedQuery, ignoreCase = true) ||
                set.name.contains(normalizedQuery, ignoreCase = true)
        val stickers = if (setMatches) {
            set.stickers
        } else {
            set.stickers.filter { sticker ->
                sticker.emoji.contains(normalizedQuery, ignoreCase = true)
            }
        }
        if (stickers.isEmpty()) {
            null
        } else {
            set.copy(stickers = stickers)
        }
    }
}

@Composable
fun PackHeader(
    title: String,
    onClick: (() -> Unit)? = null
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
fun StickerGridItem(
    sticker: StickerModel,
    onStickerSelected: (String) -> Unit,
    onStickerLongClick: (StickerModel) -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        StickerItem(
            sticker = sticker,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            onClick = { path -> onStickerSelected(path) },
            onLongClick = onStickerLongClick
        )
    }
}

@Composable
private fun StickerEmojiHintsRow(
    hints: List<String>,
    onHintClick: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        PackHeader(stringResource(R.string.stickers_search_suggestions))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(hints, key = { it }) { hint ->
                FilterChip(
                    selected = false,
                    onClick = { onHintClick(hint) },
                    label = { Text(hint) }
                )
            }
        }
    }
}

@Composable
fun StickerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
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
                onClick = {
                    onQueryChange("")
                    focusManager.clearFocus()
                },
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
                .onFocusChanged { onFocusChanged(it.isFocused) },
            placeholder = { Text(stringResource(R.string.stickers_search_placeholder), style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = if (!isSearchMode) {
                {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            } else null,
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = {
                        onQueryChange("")
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(20.dp))
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

@Composable
private fun StickerSetsSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(6) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .shimmerEffect()
            )
        }
    }
}

@Composable
private fun StickerGridSkeleton(
    contentPadding: PaddingValues
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp
        ),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false
    ) {
        items(20) {
            StickerSkeleton(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun StickerSetsRow(
    stickerSets: List<StickerSetModel>,
    selectedSetId: Long,
    hasRecent: Boolean,
    onSetSelected: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedSetId) {
        val index =
            if (selectedSetId == -1L) 0 else stickerSets.indexOfFirst { it.id == selectedSetId } + (if (hasRecent) 1 else 0)
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasRecent) {
            item {
                SetItem(
                    isSelected = selectedSetId == -1L,
                    onClick = { onSetSelected(-1L) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.StickyNote2,
                            contentDescription = stringResource(R.string.common_recent),
                            tint = if (selectedSetId == -1L) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        }

        items(
            items = stickerSets,
            key = { it.id }
        ) { set ->
            SetItem(
                isSelected = selectedSetId == set.id,
                onClick = { onSetSelected(set.id) },
                content = {
                    val firstSticker = set.stickers.firstOrNull()
                    if (firstSticker != null) {
                        StickerItem(
                            sticker = firstSticker,
                            modifier = Modifier.fillMaxSize(),
                            animate = false,
                            onClick = null
                        )
                    } else {
                        Text(
                            set.title.take(1),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedSetId == set.id) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }
}
