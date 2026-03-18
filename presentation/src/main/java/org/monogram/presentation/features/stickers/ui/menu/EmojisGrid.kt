package org.monogram.presentation.features.stickers.ui.menu

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.features.chats.currentChat.components.StickerSetSheet
import org.monogram.presentation.features.chats.currentChat.components.chats.getEmojiFontFamily
import org.monogram.presentation.features.stickers.ui.view.LocalIsScrolling
import org.monogram.presentation.features.stickers.ui.view.StickerItem

@Composable
fun EmojisGrid(
    onEmojiSelected: (String, StickerModel?) -> Unit,
    onSearchFocused: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    stickerRepository: StickerRepository = koinInject(),
    appPreferences: AppPreferences = koinInject()
) {
    var standardEmojis by remember { mutableStateOf<List<String>>(emptyList()) }
    val customEmojiSets by stickerRepository.customEmojiStickerSets.collectAsState(initial = emptyList())
    var selectedSetId by remember { mutableLongStateOf(-1L) } // -1 for recent, -2 for standard
    val recentEmojis by stickerRepository.recentEmojis.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var previewStickerSet by remember { mutableStateOf<StickerSetModel?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var searchResultsEmojis by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchResultsCustomEmojis by remember { mutableStateOf<List<StickerModel>>(emptyList()) }

    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }

    LaunchedEffect(Unit) {
        standardEmojis = stickerRepository.getDefaultEmojis()
        stickerRepository.loadCustomEmojiStickerSets()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            delay(300)
            searchResultsEmojis = stickerRepository.searchEmojis(searchQuery)
            searchResultsCustomEmojis = stickerRepository.searchCustomEmojis(searchQuery)
        } else {
            searchResultsEmojis = emptyList()
            searchResultsCustomEmojis = emptyList()
        }
    }

    // Sync selected tab with scroll position
    val firstVisibleItemIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, customEmojiSets, recentEmojis, standardEmojis, searchQuery) {
        if (searchQuery.isNotEmpty() || !gridState.isScrollInProgress) return@LaunchedEffect

        var currentCount = 0
        if (recentEmojis.isNotEmpty()) {
            if (firstVisibleItemIndex < recentEmojis.size + 1) {
                selectedSetId = -1L
                return@LaunchedEffect
            }
            currentCount += recentEmojis.size + 1
        }

        if (standardEmojis.isNotEmpty()) {
            if (firstVisibleItemIndex >= currentCount && firstVisibleItemIndex < currentCount + standardEmojis.size + 1) {
                selectedSetId = -2L
                return@LaunchedEffect
            }
            currentCount += standardEmojis.size + 1
        }

        for (set in customEmojiSets) {
            if (firstVisibleItemIndex >= currentCount && firstVisibleItemIndex < currentCount + set.stickers.size + 1) {
                selectedSetId = set.id
                break
            }
            currentCount += set.stickers.size + 1
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EmojiSearchBar(
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
            EmojiSetsRow(
                customEmojiSets = customEmojiSets,
                selectedSetId = selectedSetId,
                onSetSelected = { id ->
                    selectedSetId = id
                    scope.launch {
                        var targetIndex = 0
                        if (id == -1L) {
                            gridState.animateScrollToItem(0)
                        } else if (id == -2L) {
                            if (recentEmojis.isNotEmpty()) targetIndex += recentEmojis.size + 1
                            gridState.animateScrollToItem(targetIndex)
                        } else {
                            if (recentEmojis.isNotEmpty()) targetIndex += recentEmojis.size + 1
                            if (standardEmojis.isNotEmpty()) targetIndex += standardEmojis.size + 1
                            for (set in customEmojiSets) {
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

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 0.5.dp
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val isScrollingFast by rememberIsScrollingFast(gridState)

            CompositionLocalProvider(LocalIsScrolling provides (gridState.isScrollInProgress && isScrollingFast)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 48.dp),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp
                    ),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (searchQuery.isNotEmpty()) {
                        if (searchResultsEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_all))
                            }
                            items(searchResultsEmojis) { emoji ->
                                EmojiGridItem(emoji, emojiFontFamily) {
                                    onEmojiSelected(emoji, null)
                                    scope.launch { stickerRepository.addRecentEmoji(RecentEmojiModel(emoji)) }
                                }
                            }
                        }

                        if (searchResultsCustomEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_custom))
                            }
                            items(searchResultsCustomEmojis) { sticker ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    StickerItem(
                                        sticker = sticker,
                                        modifier = Modifier.size(36.dp),
                                        onClick = { _ -> onEmojiSelected(sticker.emoji, sticker) }
                                    )
                                }
                            }
                        }

                        if (searchResultsEmojis.isEmpty() && searchResultsCustomEmojis.isEmpty()) {
                            val filtered = standardEmojis.filter { it.contains(searchQuery) }
                            items(filtered) { emoji ->
                                EmojiGridItem(emoji, emojiFontFamily) {
                                    onEmojiSelected(emoji, null)
                                    scope.launch { stickerRepository.addRecentEmoji(RecentEmojiModel(emoji)) }
                                }
                            }
                        }
                    } else {
                        // Recent Emojis
                        if (recentEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_recent))
                            }
                            items(recentEmojis) { item ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val sticker = item.sticker
                                    if (sticker != null) {
                                        StickerItem(
                                            sticker = sticker,
                                            modifier = Modifier.size(36.dp),
                                            onClick = { onEmojiSelected(item.emoji, item.sticker) }
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable { onEmojiSelected(item.emoji, null) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(item.emoji, fontSize = 28.sp, fontFamily = emojiFontFamily)
                                        }
                                    }
                                }
                            }
                        }

                        // Standard Emojis
                        if (standardEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_standard))
                            }
                            items(standardEmojis) { emoji ->
                                EmojiGridItem(emoji, emojiFontFamily) {
                                    onEmojiSelected(emoji, null)
                                    scope.launch { stickerRepository.addRecentEmoji(RecentEmojiModel(emoji)) }
                                }
                            }
                        }

                        // Custom Emoji Sets
                        customEmojiSets.forEach { set ->
                            item(key = "header_${set.id}", span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(set.title, onClick = { previewStickerSet = set })
                            }
                            items(set.stickers, key = { "custom_${set.id}_${it.id}" }) { sticker ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    StickerItem(
                                        sticker = sticker,
                                        modifier = Modifier.size(36.dp),
                                        onClick = { _ -> onEmojiSelected(sticker.emoji, sticker) }
                                    )
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
            onStickerClick = { path ->
                val sticker = set.stickers.find { it.path == path }
                onEmojiSelected(sticker?.emoji ?: "", sticker)
                previewStickerSet = null
            }
        )
    }
}

@Composable
private fun EmojiGridItem(
    emoji: String,
    fontFamily: FontFamily,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 28.sp, fontFamily = fontFamily)
    }
}

@Composable
fun EmojiSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    isSearchMode: Boolean = false
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            placeholder = { Text(stringResource(R.string.emojis_search_placeholder), style = MaterialTheme.typography.bodyMedium) },
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
fun EmojiSetsRow(
    customEmojiSets: List<StickerSetModel>,
    selectedSetId: Long,
    onSetSelected: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedSetId) {
        val index = when (selectedSetId) {
            -1L -> 0
            -2L -> 1
            else -> customEmojiSets.indexOfFirst { it.id == selectedSetId } + 2
        }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            SetItem(
                isSelected = selectedSetId == -1L,
                onClick = { onSetSelected(-1L) },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = stringResource(R.string.common_recent),
                        tint = if (selectedSetId == -1L) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            )
        }

        item {
            SetItem(
                isSelected = selectedSetId == -2L,
                onClick = { onSetSelected(-2L) },
                content = { Text("😀", fontSize = 22.sp) }
            )
        }

        items(
            items = customEmojiSets,
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

@Composable
fun rememberIsScrollingFast(state: LazyGridState, thresholdItemsPerSecond: Float = 6f): State<Boolean> {
    val isFast = remember { mutableStateOf(false) }
    
    LaunchedEffect(state) {
        var lastIndex = state.firstVisibleItemIndex
        var lastTime = System.nanoTime()
        
        while (true) {
            if (state.isScrollInProgress) {
                val currentIndex = state.firstVisibleItemIndex
                val now = System.nanoTime()
                val dt = (now - lastTime) / 1_000_000_000f

                if (dt > 0.1f) {
                     val itemsMoved = kotlin.math.abs(currentIndex - lastIndex)
                     val speed = itemsMoved / dt
                     isFast.value = speed > thresholdItemsPerSecond
                     
                     lastIndex = currentIndex
                     lastTime = now
                }
            } else {
                if (isFast.value) isFast.value = false
                lastIndex = state.firstVisibleItemIndex
                lastTime = System.nanoTime()
            }
            delay(50)
        }
    }
    return isFast
}
