@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.stickers

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.monogram.domain.models.StickerSetModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.*
import org.monogram.presentation.features.stickers.ui.view.LocalIsScrolling
import org.monogram.presentation.features.webapp.MiniAppViewer
import kotlin.math.abs

private enum class StickerTab(val titleRes: Int, val icon: ImageVector) {
    Stickers(R.string.stickers_tab, Icons.AutoMirrored.Rounded.StickyNote2),
    Emoji(R.string.emoji_tab, Icons.Rounded.EmojiEmotions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickersContent(component: StickersComponent) {
    val state by component.state.subscribeAsState()
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var showClearRecentStickersSheet by remember { mutableStateOf(false) }
    var showClearRecentEmojisSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery.isBlank()) {
            debouncedSearchQuery = ""
        } else {
            delay(300)
            debouncedSearchQuery = state.searchQuery
        }
    }

    val orangeColor = Color(0xFFF9AB00)
    val tealColor = Color(0xFF00BFA5)

    val stickersListState = rememberLazyListState()
    val emojisListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.stickers_emoji_header),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = component::onBackClicked) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background
                        )
                    )

                    Row(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                CircleShape
                            )
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StickerTab.entries.forEachIndexed { index, tab ->
                            val selected = (state.selectedTabIndex == index)
                            val count = if (index == 0) state.stickerSets.size else state.emojiSets.size

                            val backgroundColor by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                label = "tabBackground"
                            )
                            val contentColor by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "tabContent"
                            )

                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(backgroundColor)
                                    .selectable(
                                        selected = selected,
                                        onClick = { component.onTabSelected(index) },
                                        role = Role.Tab
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        tab.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = contentColor
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = contentColor,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (count > 0) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "($count)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = contentColor.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
            ) {
                AnimatedContent(
                    targetState = state.selectedTabIndex,
                    transitionSpec = {
                        fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                                fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                    },
                    label = "TabContent"
                ) { tabIndex ->
                    if (state.isLoading && ((tabIndex == 0 && state.stickerSets.isEmpty()) || (tabIndex == 1 && state.emojiSets.isEmpty()))) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ContainedLoadingIndicator()
                        }
                    } else {
                        when (tabIndex) {
                            0 -> {
                                val filteredSets = remember(state.stickerSets, debouncedSearchQuery) {
                                    if (debouncedSearchQuery.isEmpty()) state.stickerSets
                                    else state.stickerSets.filter {
                                        it.title.contains(
                                            debouncedSearchQuery,
                                            ignoreCase = true
                                        )
                                    }
                                }
                                GenericStickerList(
                                    sets = filteredSets,
                                    archivedSets = state.archivedStickerSets,
                                    recentHeader = stringResource(R.string.recent_stickers_header),
                                    setsHeader = stringResource(R.string.sticker_sets_header),
                                    archivedHeader = stringResource(R.string.archived_stickers_header),
                                    addStickersTitle = stringResource(R.string.add_own_stickers_title),
                                    addStickersSubtitle = stringResource(R.string.add_own_stickers_subtitle),
                                    clearRecentTitle = stringResource(R.string.clear_recent_stickers_title),
                                    clearRecentSubtitle = stringResource(R.string.clear_recent_stickers_subtitle),
                                    clearRecentIcon = Icons.AutoMirrored.Rounded.StickyNote2,
                                    clearRecentIconColor = orangeColor,
                                    onClearRecent = { showClearRecentStickersSheet = true },
                                    onSetClick = component::onStickerSetClicked,
                                    onSetToggle = component::onToggleStickerSet,
                                    onSetArchive = component::onArchiveStickerSet,
                                    onMoveSet = component::onMoveStickerSet,
                                    listState = stickersListState,
                                    searchQuery = state.searchQuery,
                                    onSearchQueryChange = component::onSearchQueryChanged,
                                    emptyText = if (debouncedSearchQuery.isEmpty()) stringResource(R.string.no_sticker_sets_installed) else stringResource(
                                        R.string.no_stickers_found_format,
                                        debouncedSearchQuery
                                    ),
                                    onAddStickers = component::onAddStickersClicked
                                )
                            }

                            1 -> {
                                val filteredSets = remember(state.emojiSets, debouncedSearchQuery) {
                                    if (debouncedSearchQuery.isEmpty()) state.emojiSets
                                    else state.emojiSets.filter {
                                        it.title.contains(
                                            debouncedSearchQuery,
                                            ignoreCase = true
                                        )
                                    }
                                }
                                GenericStickerList(
                                    sets = filteredSets,
                                    archivedSets = state.archivedEmojiSets,
                                    recentHeader = stringResource(R.string.recent_emojis_header),
                                    setsHeader = stringResource(R.string.emoji_packs_header),
                                    archivedHeader = stringResource(R.string.archived_emojis_header),
                                    addStickersTitle = stringResource(R.string.add_own_emoji_title),
                                    addStickersSubtitle = stringResource(R.string.add_own_emoji_subtitle),
                                    clearRecentTitle = stringResource(R.string.clear_recent_emojis_title_settings),
                                    clearRecentSubtitle = stringResource(R.string.clear_recent_emojis_subtitle_settings),
                                    clearRecentIcon = Icons.Rounded.EmojiEmotions,
                                    clearRecentIconColor = tealColor,
                                    onClearRecent = { showClearRecentEmojisSheet = true },
                                    onSetClick = component::onStickerSetClicked,
                                    onSetToggle = component::onToggleStickerSet,
                                    onSetArchive = component::onArchiveStickerSet,
                                    onMoveSet = component::onMoveStickerSet,
                                    listState = emojisListState,
                                    searchQuery = state.searchQuery,
                                    onSearchQueryChange = component::onSearchQueryChanged,
                                    emptyText = if (debouncedSearchQuery.isEmpty()) stringResource(R.string.no_emoji_packs_installed) else stringResource(
                                        R.string.no_emojis_found_format,
                                        debouncedSearchQuery
                                    ),
                                    onAddStickers = component::onAddStickersClicked
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.miniAppUrl != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            if (state.miniAppUrl != null && state.miniAppName != null) {
                MiniAppViewer(
                    chatId = state.miniAppBotUserId,
                    botUserId = state.miniAppBotUserId,
                    baseUrl = state.miniAppUrl!!,
                    botName = state.miniAppName!!,
                    webAppRepository = koinInject(),
                    onDismiss = { component.onDismissMiniApp() }
                )
            }
        }

        if (showClearRecentStickersSheet) {
            ConfirmationSheet(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.clear_recent_stickers_title),
                description = stringResource(R.string.clear_recent_stickers_confirmation),
                confirmText = stringResource(R.string.action_clear_recent_stickers),
                onConfirm = {
                    component.onClearRecentStickers()
                    showClearRecentStickersSheet = false
                },
                onDismiss = { showClearRecentStickersSheet = false }
            )
        }

        if (showClearRecentEmojisSheet) {
            ConfirmationSheet(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.clear_recent_emojis_title_settings),
                description = stringResource(R.string.clear_recent_emojis_confirmation),
                confirmText = stringResource(R.string.action_clear_recent_emojis),
                onConfirm = {
                    component.onClearRecentEmojis()
                    showClearRecentEmojisSheet = false
                },
                onDismiss = { showClearRecentEmojisSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericStickerList(
    sets: List<StickerSetModel>,
    archivedSets: List<StickerSetModel>,
    recentHeader: String,
    setsHeader: String,
    archivedHeader: String,
    addStickersTitle: String,
    addStickersSubtitle: String,
    clearRecentTitle: String,
    clearRecentSubtitle: String,
    clearRecentIcon: ImageVector,
    clearRecentIconColor: Color,
    onClearRecent: () -> Unit,
    onSetClick: (StickerSetModel) -> Unit,
    onSetToggle: (StickerSetModel) -> Unit,
    onSetArchive: (StickerSetModel) -> Unit,
    onMoveSet: (Int, Int) -> Unit,
    listState: LazyListState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    emptyText: String,
    onAddStickers: () -> Unit
) {
    val isScrolling = listState.isScrollInProgress
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var draggedItemId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var initialDragStartOffset by remember { mutableStateOf(0) }
    var initialPointerY by remember { mutableStateOf(0f) }
    var totalDragDistance by remember { mutableStateOf(0f) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    var autoScrollVelocity by remember { mutableStateOf(0f) }

    CompositionLocalProvider(LocalIsScrolling provides isScrolling) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearch = { },
                    active = false,
                    onActiveChange = { },
                    placeholder = { Text(stringResource(R.string.search_packs_placeholder)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.action_search))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {}
            }

            item {
                SettingsTile(
                    icon = Icons.Rounded.Add,
                    title = addStickersTitle,
                    subtitle = addStickersSubtitle,
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = ItemPosition.STANDALONE,
                    onClick = onAddStickers
                )
            }

            item {
                SectionHeader(recentHeader)
            }
            item {
                SettingsTile(
                    icon = clearRecentIcon,
                    title = clearRecentTitle,
                    subtitle = clearRecentSubtitle,
                    iconColor = clearRecentIconColor,
                    position = ItemPosition.STANDALONE,
                    onClick = onClearRecent
                )
            }

            if (sets.isNotEmpty()) {
                item {
                    SectionHeader(setsHeader)
                }
                itemsIndexed(sets, key = { _, set -> set.id }) { index, set ->
                    val currentSets by rememberUpdatedState(sets)
                    val currentIndex by rememberUpdatedState(index)
                    val currentOnMoveSet by rememberUpdatedState(onMoveSet)

                    val isDragging = draggedItemId == set.id
                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 0.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "elevation"
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.02f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "scale"
                    )

                    val position = when {
                        sets.size == 1 -> ItemPosition.STANDALONE
                        index == 0 -> ItemPosition.TOP
                        index == sets.size - 1 -> ItemPosition.BOTTOM
                        else -> ItemPosition.MIDDLE
                    }
                    val effectivePosition = if (isDragging) ItemPosition.STANDALONE else position

                    Box(
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .then(
                                if (isDragging) {
                                    Modifier
                                } else {
                                    Modifier.animateItem(
                                        fadeInSpec = null,
                                        placementSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        ),
                                        fadeOutSpec = null
                                    )
                                }
                            )
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                                scaleX = scale
                                scaleY = scale
                            }
                            .shadow(elevation, shape = getItemShape(effectivePosition))
                            .pointerInput(searchQuery) {
                                if (searchQuery.isNotEmpty()) return@pointerInput

                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        draggedItemId = set.id
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                        val itemInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == set.id }
                                        initialDragStartOffset = itemInfo?.offset ?: 0
                                        initialPointerY = offset.y + (itemInfo?.offset ?: 0)
                                        totalDragDistance = 0f
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        totalDragDistance += dragAmount.y

                                        val currentItemInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == set.id } ?: return@detectDragGesturesAfterLongPress

                                        val targetY = initialDragStartOffset + totalDragDistance
                                        dragOffset = targetY - currentItemInfo.offset

                                        val draggedCenter = targetY + currentItemInfo.size / 2

                                        val targetItem = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { item ->
                                                draggedCenter > item.offset &&
                                                        draggedCenter < item.offset + item.size &&
                                                        item.index >= 5
                                            }

                                        if (targetItem != null) {
                                            val targetIndex = targetItem.index - 5
                                            if (targetIndex != currentIndex && targetIndex in currentSets.indices) {
                                                currentOnMoveSet(currentIndex, targetIndex)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }

                                        val viewPortHeight = listState.layoutInfo.viewportSize.height
                                        val topThreshold = 100.dp.toPx()
                                        val bottomThreshold = viewPortHeight - 100.dp.toPx()
                                        val pointerY = initialPointerY + totalDragDistance

                                        if (pointerY < topThreshold) {
                                            val intensity = ((topThreshold - pointerY) / topThreshold).coerceIn(0f, 1f)
                                            autoScrollVelocity = -(6f + (18f * intensity))
                                        } else if (pointerY > bottomThreshold) {
                                            val intensity =
                                                ((pointerY - bottomThreshold) / topThreshold).coerceIn(0f, 1f)
                                            autoScrollVelocity = 6f + (18f * intensity)
                                        } else {
                                            autoScrollVelocity = 0f
                                            autoScrollJob?.cancel()
                                            autoScrollJob = null
                                        }

                                        if (autoScrollJob == null && abs(autoScrollVelocity) > 0f) {
                                            autoScrollJob = scope.launch {
                                                while (abs(autoScrollVelocity) > 0f) {
                                                    listState.scrollBy(autoScrollVelocity)
                                                    delay(16)
                                                }
                                            }.also { job ->
                                                job.invokeOnCompletion {
                                                    if (autoScrollJob === job) autoScrollJob = null
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggedItemId = null
                                        dragOffset = 0f
                                        totalDragDistance = 0f
                                        autoScrollVelocity = 0f
                                        autoScrollJob?.cancel()
                                        autoScrollJob = null
                                    },
                                    onDragCancel = {
                                        draggedItemId = null
                                        dragOffset = 0f
                                        totalDragDistance = 0f
                                        autoScrollVelocity = 0f
                                        autoScrollJob?.cancel()
                                        autoScrollJob = null
                                    }
                                )
                            }
                    ) {
                        StickerSetItem(
                            stickerSet = set,
                            position = effectivePosition,
                            onClick = { onSetClick(set) },
                            onToggle = { onSetToggle(set) },
                            onArchive = { onSetArchive(set) },
                            showReorder = searchQuery.isEmpty()
                        )
                    }
                }
            } else {
                item {
                    EmptyState(emptyText)
                }
            }

            if (archivedSets.isNotEmpty() && searchQuery.isEmpty()) {
                item {
                    SectionHeader(archivedHeader)
                }
                itemsIndexed(archivedSets, key = { _, set -> "archived_${set.id}" }) { index, set ->
                    val position = when {
                        archivedSets.size == 1 -> ItemPosition.STANDALONE
                        index == 0 -> ItemPosition.TOP
                        index == archivedSets.size - 1 -> ItemPosition.BOTTOM
                        else -> ItemPosition.MIDDLE
                    }
                    StickerSetItem(
                        stickerSet = set,
                        position = position,
                        onClick = { onSetClick(set) },
                        onToggle = { onSetToggle(set) },
                        onArchive = { onSetArchive(set) },
                        showReorder = false
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 24.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}