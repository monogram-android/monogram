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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.EmojiRepository
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
    emojiOnlyMode: Boolean = false,
    onSearchFocused: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    stickerRepository: StickerRepository = koinInject(),
    emojiRepository: EmojiRepository = koinInject(),
    appPreferences: AppPreferences = koinInject()
) {
    var standardEmojis by remember { mutableStateOf<List<String>>(emptyList()) }
    val customEmojiSets by stickerRepository.customEmojiStickerSets.collectAsState(initial = emptyList())
    var selectedSetId by remember { mutableLongStateOf(-1L) } // -1 for recent, -2 for standard
    val recentEmojis by emojiRepository.recentEmojis.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var previewStickerSet by remember { mutableStateOf<StickerSetModel?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var searchResultsEmojis by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchResultsCustomEmojis by remember { mutableStateOf<List<StickerModel>>(emptyList()) }

    val context = LocalContext.current
    val emojiStyle by appPreferences.emojiStyle.collectAsState()
    val emojiFontFamily = remember(context, emojiStyle) { getEmojiFontFamily(context, emojiStyle) }
    val filteredRecentEmojis = remember(recentEmojis, emojiOnlyMode) {
        if (emojiOnlyMode) recentEmojis.filter { it.sticker?.customEmojiId != null } else recentEmojis
    }
    val visibleStandardEmojis = remember(standardEmojis, emojiOnlyMode) {
        if (emojiOnlyMode) emptyList() else standardEmojis
    }

    LaunchedEffect(Unit) {
        standardEmojis = emojiRepository.getDefaultEmojis()
        stickerRepository.loadCustomEmojiStickerSets()
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
            searchResultsEmojis = if (emojiOnlyMode) {
                emptyList()
            } else {
                emojiRepository.searchEmojis(debouncedSearchQuery)
            }
            searchResultsCustomEmojis = emojiRepository.searchCustomEmojis(debouncedSearchQuery)
        } else {
            searchResultsEmojis = emptyList()
            searchResultsCustomEmojis = emptyList()
        }
    }

    val sectionOffsets = remember(filteredRecentEmojis, visibleStandardEmojis, customEmojiSets) {
        val offsets = mutableListOf<EmojiSectionOffset>()
        var cursor = 0

        if (filteredRecentEmojis.isNotEmpty()) {
            val count = filteredRecentEmojis.size + 1
            offsets += EmojiSectionOffset(id = -1L, startIndex = cursor, endExclusive = cursor + count)
            cursor += count
        }

        if (visibleStandardEmojis.isNotEmpty()) {
            val count = visibleStandardEmojis.size + 1
            offsets += EmojiSectionOffset(id = -2L, startIndex = cursor, endExclusive = cursor + count)
            cursor += count
        }

        customEmojiSets.forEach { set ->
            val count = set.stickers.size + 1
            offsets += EmojiSectionOffset(id = set.id, startIndex = cursor, endExclusive = cursor + count)
            cursor += count
        }

        offsets
    }

    val sectionStartById = remember(sectionOffsets) {
        sectionOffsets.associate { it.id to it.startIndex }
    }

    val localSearchFallbackResults = remember(debouncedSearchQuery, visibleStandardEmojis) {
        if (debouncedSearchQuery.isNotEmpty()) {
            visibleStandardEmojis.filter { it.contains(debouncedSearchQuery) }
        } else {
            emptyList()
        }
    }

    LaunchedEffect(sectionOffsets) {
        if (sectionOffsets.isNotEmpty() && sectionOffsets.none { it.id == selectedSetId }) {
            selectedSetId = sectionOffsets.first().id
        }
    }

    LaunchedEffect(gridState, searchQuery, sectionOffsets) {
        if (searchQuery.isNotEmpty() || sectionOffsets.isEmpty()) return@LaunchedEffect

        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstIndex ->
                val currentSectionId = sectionOffsets
                    .firstOrNull { firstIndex in it.startIndex until it.endExclusive }
                    ?.id
                    ?: sectionOffsets.lastOrNull()?.id

                if (currentSectionId != null && currentSectionId != selectedSetId) {
                    selectedSetId = currentSectionId
                }
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
                hasRecent = filteredRecentEmojis.isNotEmpty(),
                hasStandard = visibleStandardEmojis.isNotEmpty(),
                onSetSelected = { id ->
                    selectedSetId = id
                    scope.launch {
                        val targetIndex = sectionStartById[id] ?: return@launch
                        gridState.animateScrollToItem(targetIndex)
                    }
                }
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 0.5.dp
        )

        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalIsScrolling provides gridState.isScrollInProgress) {
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
                            itemsIndexed(searchResultsEmojis, key = { index, emoji -> "search_emoji_${index}_$emoji" }) { _, emoji ->
                                EmojiGridItem(emoji, emojiFontFamily) {
                                    onEmojiSelected(emoji, null)
                                    scope.launch { emojiRepository.addRecentEmoji(RecentEmojiModel(emoji)) }
                                }
                            }
                        }

                        if (searchResultsCustomEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_custom))
                            }
                            items(searchResultsCustomEmojis, key = { "search_custom_${it.id}" }) { sticker ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        ),
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

                        if (!emojiOnlyMode && searchResultsEmojis.isEmpty() && searchResultsCustomEmojis.isEmpty()) {
                            itemsIndexed(localSearchFallbackResults, key = { index, emoji -> "search_local_${index}_$emoji" }) { _, emoji ->
                                EmojiGridItem(emoji, emojiFontFamily) {
                                    onEmojiSelected(emoji, null)
                                    scope.launch { emojiRepository.addRecentEmoji(RecentEmojiModel(emoji)) }
                                }
                            }
                        }
                    } else {
                        // Recent Emojis
                        if (filteredRecentEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_recent))
                            }
                            itemsIndexed(
                                filteredRecentEmojis,
                                key = { index, item ->
                                    val stickerKey = item.sticker?.customEmojiId ?: item.sticker?.id
                                    "recent_${stickerKey ?: item.emoji}_$index"
                                }
                            ) { _, item ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        ),
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
                        if (visibleStandardEmojis.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                PackHeader(stringResource(R.string.emojis_header_standard))
                            }
                            itemsIndexed(
                                visibleStandardEmojis,
                                key = { index, emoji -> "standard_${emoji}_$index" }
                            ) { _, emoji ->
                                EmojiGridItem(emoji, emojiFontFamily) {
                                    onEmojiSelected(emoji, null)
                                    scope.launch { emojiRepository.addRecentEmoji(RecentEmojiModel(emoji)) }
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
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                                            shape = CircleShape
                                        ),
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
            .padding(horizontal = 32.dp, vertical = 4.dp),
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
                .heightIn(min = 44.dp)
                .onFocusChanged { onFocusChanged(it.isFocused) },
            placeholder = {
                Text(
                    text = stringResource(R.string.emojis_search_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            },
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
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

@Composable
fun EmojiSetsRow(
    customEmojiSets: List<StickerSetModel>,
    selectedSetId: Long,
    hasRecent: Boolean,
    hasStandard: Boolean,
    onSetSelected: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedSetId) {
        val index = when (selectedSetId) {
            -1L -> if (hasRecent) 0 else -1
            -2L -> when {
                hasRecent -> 1
                hasStandard -> 0
                else -> -1
            }

            else -> {
                val base = (if (hasRecent) 1 else 0) + (if (hasStandard) 1 else 0)
                customEmojiSets.indexOfFirst { it.id == selectedSetId }.takeIf { it >= 0 }?.let { base + it } ?: -1
            }
        }
        if (index >= 0) {
            listState.scrollToItem(index)
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
                            imageVector = Icons.Outlined.EmojiEmotions,
                            contentDescription = stringResource(R.string.common_recent),
                            tint = if (selectedSetId == -1L) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        }

        if (hasStandard) {
            item {
                SetItem(
                    isSelected = selectedSetId == -2L,
                    onClick = { onSetSelected(-2L) },
                    content = { Text("😀", fontSize = 22.sp) }
                )
            }
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

private data class EmojiSectionOffset(
    val id: Long,
    val startIndex: Int,
    val endExclusive: Int
)
