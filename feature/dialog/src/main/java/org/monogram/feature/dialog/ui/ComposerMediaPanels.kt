package org.monogram.feature.dialog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.MonogramPlaceholder
import org.monogram.core.ui.components.SheetPanelHost
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.EmojiPanelLayout
import org.monogram.feature.dialog.GridPlacement
import org.monogram.feature.dialog.PanelBucket
import org.monogram.feature.dialog.PickerDocumentCell
import org.monogram.feature.dialog.PickerExpandCell
import org.monogram.feature.dialog.PickerGlyphCell
import org.monogram.feature.dialog.PickerGridCell
import org.monogram.feature.dialog.PickerHeaderCell
import org.monogram.feature.dialog.PickerPlaceholderCell
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.SystemEmojiCatalog
import org.monogram.feature.dialog.SystemEmojiCategory
import org.monogram.feature.dialog.SystemEmojiCategoryKind
import org.monogram.feature.dialog.emojiPanelLayout
import org.monogram.feature.dialog.panelLoops
import org.monogram.feature.dialog.panelPlaybackBand
import org.monogram.feature.dialog.stickerPanelCells
import org.monogram.network.http.MediaRepository
import java.io.File

internal val PickerTabClearance = 8.dp

internal val PickerTabInset = 48.dp + 8.dp + PickerTabClearance

@Composable
internal fun EmojiStickerGifPanel(
    visible: Boolean,
    tab: String,
    gifs: List<SavedGif>,
    stickerSets: List<StickerPack>,
    emojiSets: List<StickerPack>,
    openPack: StickerPack?,
    loadedPacks: Map<Long, StickerPack>,
    mediaRepository: MediaRepository?,
    stickerLoaded: Boolean = true,
    stickerError: Boolean = false,
    emojiLoaded: Boolean = true,
    loadingPackIds: Set<Long> = emptySet(),
    failedPackIds: Set<Long> = emptySet(),
    gifsLoaded: Boolean = true,
    gifsError: Boolean = false,
    onTab: (String) -> Unit,
    onInsertEmoji: (String) -> Unit,
    onInsertCustomEmoji: (Long) -> Unit,
    onOpenPack: (StickerPack) -> Unit,
    onSendDocument: (Long) -> Unit,
    onDismiss: () -> Unit,
    onPickerDocumentsVisible: (List<Long>, Set<Long>) -> Unit = { _, _ -> },
    onPickerGifsVisible: (List<SavedGif>, Set<Long>, Boolean) -> Unit = { _, _, _ -> },
    onPickerClosed: () -> Unit = {},
) {
    LaunchedEffect(visible) {
        if (!visible) onPickerClosed()
    }
    SheetPanelHost(visible = visible) { sheetVisible, onExited ->
        val tabs = listOf(
            ComposerPanels.TAB_EMOJI to stringResource(R.string.dialog_panel_emoji),
            ComposerPanels.TAB_STICKERS to stringResource(R.string.dialog_panel_stickers),
            ComposerPanels.TAB_GIFS to stringResource(R.string.dialog_saved_gifs),
        )
        val selected = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
        val container = MaterialTheme.colorScheme.surfaceContainerLow
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val panelHeight = (screenHeight * 0.52f).coerceIn(
            240.dp,
            minOf(520.dp, (screenHeight * 0.78f).coerceAtLeast(240.dp)),
        )
        val wide = LocalConfiguration.current.screenWidthDp >= 840
        AppModalSheet(
            onDismissRequest = onDismiss,
            containerColor = container,
            showDragHandle = true,
            padNavigationBars = false,
            padIme = false,
            visible = sheetVisible,
            onExited = onExited,
            bottomGap = composerSheetBottomGap(),
            maxWidth = if (wide) 520.dp else Dp.Unspecified,
        ) {
            val context = LocalContext.current
            val panelPool = remember {
                PanelPlayerPool(File(context.filesDir, "panel-frames"))
            }
            DisposableEffect(panelPool) {
                onDispose { panelPool.close() }
            }
            LaunchedEffect(sheetVisible) {
                if (!sheetVisible) panelPool.clear()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CompositionLocalProvider(
                        LocalPickerBottomInset provides PickerTabInset,
                        LocalPanelPool provides panelPool,
                    ) {
                        when (tab) {
                            ComposerPanels.TAB_STICKERS -> PackBrowser(
                                packs = stickerSets,
                                loadedPacks = loadedPacks,
                                loaded = stickerLoaded,
                                error = stickerError,
                                failedPackIds = failedPackIds,
                                onOpenPack = onOpenPack,
                                onSendDocument = onSendDocument,
                                onRetry = { onTab(ComposerPanels.TAB_STICKERS) },
                                onDocumentsVisible = onPickerDocumentsVisible,
                                modifier = Modifier.fillMaxSize(),
                            )

                            ComposerPanels.TAB_GIFS -> SavedGifBrowser(
                                gifs = gifs,
                                mediaRepository = mediaRepository,
                                loaded = gifsLoaded,
                                error = gifsError,
                                onSendDocument = onSendDocument,
                                onRetry = { onTab(ComposerPanels.TAB_GIFS) },
                                onGifsVisible = onPickerGifsVisible,
                            )

                            else -> SystemEmojiBrowser(
                                packs = emojiSets,
                                openPack = openPack?.takeIf { it.isEmoji },
                                loadedPacks = loadedPacks,
                                packsLoaded = emojiLoaded,
                                loadingPackIds = loadingPackIds,
                                failedPackIds = failedPackIds,
                                onInsertEmoji = onInsertEmoji,
                                onInsertCustomEmoji = onInsertCustomEmoji,
                                onOpenPack = onOpenPack,
                                onDocumentsVisible = onPickerDocumentsVisible,
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f)
                        .blockSheetDrag()
                        .padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                        .widthIn(min = 220.dp, max = 360.dp)
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        tabs.forEachIndexed { index, item ->
                            val isSelected = index == selected
                            Surface(
                                onClick = { onTab(item.first) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .semantics { contentDescription = item.second },
                                shape = RoundedCornerShape(22.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
                                } else {
                                    Color.Transparent
                                },
                                contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = item.second,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemEmojiBrowser(
    packs: List<StickerPack>,
    openPack: StickerPack?,
    loadedPacks: Map<Long, StickerPack> = emptyMap(),
    packsLoaded: Boolean = true,
    loadingPackIds: Set<Long> = emptySet(),
    failedPackIds: Set<Long> = emptySet(),
    onInsertEmoji: (String) -> Unit,
    onInsertCustomEmoji: (Long) -> Unit,
    onOpenPack: (StickerPack) -> Unit,
    onDocumentsVisible: (List<Long>, Set<Long>) -> Unit = { _, _ -> },
) {
    val categories by produceState(
        initialValue = SystemEmojiCatalog.cachedCategories().orEmpty(),
    ) {
        if (value.isEmpty()) {
            value = withContext(Dispatchers.Default) { SystemEmojiCatalog.categories() }
        }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var recentJoined by rememberSaveable { mutableStateOf("") }
    var expandedJoined by rememberSaveable { mutableStateOf("") }
    val recent = remember(recentJoined) {
        recentJoined.split('\u0000').filter { it.isNotEmpty() }
    }
    val expanded = remember(expandedJoined) {
        expandedJoined.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }
    val resolvedPacks = remember(loadedPacks, openPack) {
        if (openPack == null) loadedPacks else loadedPacks + (openPack.id to (loadedPacks[openPack.id]
            ?: openPack))
    }
    val layout = remember(
        recent,
        categories,
        packs,
        resolvedPacks,
        expanded,
        loadingPackIds,
        failedPackIds
    ) {
        emojiPanelLayout(
            recent = recent,
            categories = categories,
            packs = packs,
            loaded = resolvedPacks,
            expanded = expanded,
            loadingPackIds = loadingPackIds,
            failedPackIds = failedPackIds,
        )
    }
    var requestedPacks by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(expanded, packs, resolvedPacks) {
        val seen = requestedPacks.split(',').mapNotNull { it.toLongOrNull() }.toMutableSet()
        expanded.forEach { id ->
            if (id in seen) return@forEach
            if (resolvedPacks[id]?.previewDocumentIds?.isNotEmpty() == true) return@forEach
            seen += id
            packs.firstOrNull { it.id == id }?.let(onOpenPack)
        }
        requestedPacks = seen.joinToString(",")
    }
    var scrollPackId by remember { mutableStateOf<Long?>(null) }
    var scrollCategory by remember { mutableStateOf<SystemEmojiCategoryKind?>(null) }
    LaunchedEffect(scrollPackId, scrollCategory, layout) {
        val packIndex = scrollPackId?.let { layout.packStart[it] }
        val categoryIndex = scrollCategory?.let { layout.categoryStart[it] }
        val index = packIndex ?: categoryIndex ?: return@LaunchedEffect
        gridState.animateScrollToItem(index)
        scrollPackId = null
        scrollCategory = null
    }
    val anchor by remember(layout, gridState) {
        derivedStateOf {
            var category: SystemEmojiCategoryKind? = null
            var packId: Long? = null
            val last = gridState.firstVisibleItemIndex.coerceAtMost(layout.cells.lastIndex)
            for (index in 0..last) {
                val cell = layout.cells.getOrNull(index) as? PickerHeaderCell ?: continue
                if (cell.category != null) {
                    category = cell.category
                    packId = null
                }
                if (cell.packId != null) {
                    packId = cell.packId
                    category = null
                }
            }
            category to packId
        }
    }
    val playback = rememberPanelGridPlayback(gridState)
    Column(modifier = Modifier.fillMaxSize()) {
        EmojiPreviewRow(
            categories = categories,
            packs = packs,
            loadedPacks = resolvedPacks,
            packsLoading = !packsLoaded,
            selectedCategory = anchor.first,
            selectedPackId = anchor.second,
            onCategory = { kind -> scrollCategory = kind },
            onPack = { pack ->
                if (pack.id !in expanded) {
                    expandedJoined = (expanded + pack.id).joinToString(",")
                }
                scrollPackId = pack.id
                onOpenPack(pack)
            },
            onPrefetchPack = onOpenPack,
            rowScrolling = playback.scrolling,
        )
        if (categories.isEmpty() && packs.isEmpty()) {
            PickerSkeleton(kind = PickerSkeletonKind.Emoji)
        } else {
            EmojiPanelGrid(
                layout = layout,
                gridState = gridState,
                playback = playback,
                onInsertEmoji = { glyph ->
                    val next = (listOf(glyph) + recent.filterNot { it == glyph }).take(32)
                    recentJoined = next.joinToString("\u0000")
                    onInsertEmoji(glyph)
                },
                onInsertCustomEmoji = onInsertCustomEmoji,
                onRetryPack = { packId -> packs.firstOrNull { it.id == packId }?.let(onOpenPack) },
                onTogglePack = { packId ->
                    val next = if (packId in expanded) expanded - packId else expanded + packId
                    expandedJoined = next.joinToString(",")
                    if (packId !in expanded) packs.firstOrNull { it.id == packId }?.let(onOpenPack)
                },
                onDocumentsVisible = onDocumentsVisible,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmojiPreviewRow(
    categories: List<SystemEmojiCategory>,
    packs: List<StickerPack>,
    loadedPacks: Map<Long, StickerPack> = emptyMap(),
    packsLoading: Boolean = false,
    selectedCategory: SystemEmojiCategoryKind?,
    selectedPackId: Long?,
    onCategory: (SystemEmojiCategoryKind) -> Unit,
    onPack: (StickerPack) -> Unit,
    onPrefetchPack: (StickerPack) -> Unit,
    rowScrolling: Boolean,
) {
    val rowState = rememberLazyListState()
    val packIndexOffset =
        categories.size + if (categories.isNotEmpty() && packs.isNotEmpty()) 1 else 0
    val visibleChipPacks by remember(packs, rowState, packIndexOffset) {
        derivedStateOf {
            val visible = rowState.layoutInfo.visibleItemsInfo
            val fromRow = visible.mapNotNull { info ->
                packs.getOrNull(info.index - packIndexOffset)
            }
            fromRow.take(8).ifEmpty { packs.take(8) }
        }
    }
    LaunchedEffect(visibleChipPacks) {
        visibleChipPacks.forEach(onPrefetchPack)
    }
    val chipSettled = rememberSettledIdle(rowScrolling || rowState.isScrollInProgress)
    val animations = panelAnimationsEnabled()
    LazyRow(
        state = rowState,
        modifier = Modifier
            .fillMaxWidth()
            .height(PickerMetrics.ChipRowHeight.dp),
        contentPadding = PickerChipPadding(),
        horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(categories, key = { "category:${it.kind}" }) { category ->
            PreviewButton(
                selected = selectedPackId == null && selectedCategory == category.kind,
                description = emojiCategoryLabel(category.kind),
                onClick = { onCategory(category.kind) },
            ) {
                Text(text = category.icon, style = MaterialTheme.typography.headlineSmall)
            }
        }
        when {
            packs.isNotEmpty() -> {
                if (categories.isNotEmpty()) {
                    item(key = "pack-gap") {
                        Spacer(modifier = Modifier.width(PickerMetrics.GridSpacing.dp))
                    }
                }
                items(packs, key = { "pack:${it.id}" }) { pack ->
                    val selected = selectedPackId == pack.id
                    PackPreviewButton(
                        pack = loadedPacks[pack.id] ?: pack,
                        selected = selected,
                        playback = chipPlayback(
                            selected = selected,
                            settled = chipSettled,
                            animations = animations,
                            bucket = PanelBucket.TabStrip,
                        ),
                        onClick = { onPack(pack) },
                    )
                }
            }

            packsLoading -> items(
                count = PickerMetrics.PlaceholderChips,
                key = { "pack-placeholder:$it" },
            ) {
                MonogramPlaceholder(
                    modifier = Modifier.size(PickerMetrics.ChipSize.dp),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
    }
}

@Composable
private fun EmojiPanelGrid(
    layout: EmojiPanelLayout,
    gridState: LazyGridState,
    playback: PanelGridPlayback,
    onInsertEmoji: (String) -> Unit,
    onInsertCustomEmoji: (Long) -> Unit,
    onRetryPack: (Long) -> Unit,
    onTogglePack: (Long) -> Unit,
    onDocumentsVisible: (List<Long>, Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = layout.cells
    val documentIds = remember(cells) { cells.mapNotNull { it.documentId } }
    ReportPickerVisible(itemsKey = documentIds, gridState = gridState) { min, max, _ ->
        onDocumentsVisible(documentIds, visibleDocumentIds(cells, min, max))
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PickerMetrics.EmojiCell.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PickerGridPadding(),
        horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
    ) {
        items(
            count = cells.size,
            key = { index -> cells[index].key },
            span = { index ->
                if (cells[index].span) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
        ) { index ->
            when (val cell = cells[index]) {
                is PickerHeaderCell -> PickerSectionTitle(
                    text = when {
                        cell.recent -> stringResource(R.string.dialog_emoji_recent)
                        cell.category != null -> emojiCategoryLabel(cell.category)
                        else -> cell.title
                    },
                    onClick = cell.packId?.let { packId -> { onTogglePack(packId) } },
                )

                is PickerGlyphCell -> Box(
                    modifier = Modifier
                        .size(PickerMetrics.EmojiCell.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onInsertEmoji(cell.glyph) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = cell.glyph, style = MaterialTheme.typography.headlineMedium)
                }

                is PickerDocumentCell -> PickerDocumentButton(
                    documentId = cell.documentId,
                    cell = PickerMetrics.EmojiPackCell,
                    playback = playback.cell(index, PanelBucket.KeyboardEmoji),
                    onClick = { onInsertCustomEmoji(cell.documentId) },
                )

                is PickerExpandCell -> {
                    val title = cells.filterIsInstance<PickerHeaderCell>()
                        .firstOrNull { it.packId == cell.packId }
                        ?.title
                        .orEmpty()
                    Box(
                        modifier = Modifier
                            .size(PickerMetrics.EmojiPackCell.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onTogglePack(cell.packId) }
                            .semantics { contentDescription = title },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "+", style = MaterialTheme.typography.titleLarge)
                    }
                }

                is PickerPlaceholderCell -> Box(
                    modifier = Modifier
                        .size(PickerMetrics.EmojiPackCell.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = cell.failed) { onRetryPack(cell.packId) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (cell.failed) {
                        Text(
                            text = stringResource(R.string.dialog_retry_load),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        MonogramPlaceholder(
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialTheme.shapes.small,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PackBrowser(
    packs: List<StickerPack>,
    loadedPacks: Map<Long, StickerPack>,
    loaded: Boolean,
    error: Boolean,
    failedPackIds: Set<Long> = emptySet(),
    onOpenPack: (StickerPack) -> Unit,
    onSendDocument: (Long) -> Unit,
    onRetry: () -> Unit,
    onDocumentsVisible: (List<Long>, Set<Long>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (packs.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            when {
                !loaded -> PickerSkeleton(kind = PickerSkeletonKind.Stickers)
                error -> PickerStatus(
                    text = stringResource(R.string.dialog_sticker_sets_error),
                    onRetry = onRetry,
                )

                else -> Text(
                    text = stringResource(R.string.dialog_sticker_sets_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val gridState = rememberLazyGridState()
    val previewState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cells = remember(packs, loadedPacks, failedPackIds) {
        stickerPanelCells(packs, loadedPacks, failedPackIds)
    }
    val packStart = remember(cells) {
        cells.mapIndexedNotNull { index, cell ->
            (cell as? PickerHeaderCell)?.packId?.let { it to index }
        }.toMap()
    }
    val selectedId by remember(cells, gridState) {
        derivedStateOf {
            val last = gridState.firstVisibleItemIndex.coerceAtMost(cells.lastIndex)
            var packId: Long? = null
            for (index in 0..last) {
                val header = cells.getOrNull(index) as? PickerHeaderCell ?: continue
                if (header.packId != null) packId = header.packId
            }
            packId
        }
    }
    val playback = rememberPanelGridPlayback(gridState)
    val visiblePacks by remember(packs, cells, gridState) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf packs.take(1)
            val minIndex = visible.minOf { it.index }
            val maxIndex = visible.maxOf { it.index }
            val ids = cells.subList(
                minIndex.coerceIn(0, cells.size),
                (maxIndex + 1).coerceIn(0, cells.size),
            ).mapNotNull { cell ->
                when (cell) {
                    is PickerHeaderCell -> cell.packId
                    is PickerDocumentCell -> cell.packId
                    is PickerPlaceholderCell -> cell.packId
                    else -> null
                }
            }.toSet()
            packs.filter { it.id in ids }
        }
    }
    val visibleChipPacks by remember(packs, previewState) {
        derivedStateOf {
            val visible = previewState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) packs.take(8) else {
                visible.mapNotNull { info -> packs.getOrNull(info.index) }
            }
        }
    }
    LaunchedEffect(visiblePacks, visibleChipPacks) {
        (visiblePacks + visibleChipPacks).distinctBy { it.id }.forEach(onOpenPack)
    }
    val documentIds = remember(cells) { cells.mapNotNull { it.documentId } }
    ReportPickerVisible(
        itemsKey = documentIds,
        gridState = gridState,
    ) { min, max, _ ->
        onDocumentsVisible(documentIds, visibleDocumentIds(cells, min, max))
    }
    val chipSettled = rememberSettledIdle(playback.scrolling || previewState.isScrollInProgress)
    val animations = panelAnimationsEnabled()
    Column(modifier = modifier) {
        LazyRow(
            state = previewState,
            modifier = Modifier
                .fillMaxWidth()
                .height(PickerMetrics.ChipRowHeight.dp),
            contentPadding = PickerChipPadding(),
            horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(packs, key = { it.id }) { pack ->
                val selected = selectedId == pack.id
                PackPreviewButton(
                    pack = loadedPacks[pack.id] ?: pack,
                    selected = selected,
                    playback = chipPlayback(
                        selected,
                        chipSettled,
                        animations,
                        PanelBucket.TabStrip
                    ),
                    onClick = {
                        onOpenPack(pack)
                        packStart[pack.id]?.let { index ->
                            scope.launch { gridState.animateScrollToItem(index) }
                        }
                    },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PickerMetrics.StickerCell.dp),
            state = gridState,
            modifier = Modifier.weight(1f),
            contentPadding = PickerGridPadding(top = PickerMetrics.GridPadding.dp),
            horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
            verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        ) {
            items(
                count = cells.size,
                key = { index -> cells[index].key },
                span = { index ->
                    if (cells[index].span) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
            ) { index ->
                when (val cell = cells[index]) {
                    is PickerHeaderCell -> PickerSectionTitle(
                        text = cell.title.ifBlank {
                            packs.firstOrNull { it.id == cell.packId }?.shortName.orEmpty()
                        },
                    )

                    is PickerDocumentCell -> PickerDocumentButton(
                        documentId = cell.documentId,
                        cell = PickerMetrics.StickerCell,
                        playback = playback.cell(index, PanelBucket.KeyboardSticker),
                        onClick = { onSendDocument(cell.documentId) },
                    )

                    is PickerPlaceholderCell -> {
                        val pack = packs.firstOrNull { it.id == cell.packId }
                        Box(
                            modifier = Modifier
                                .size(PickerMetrics.StickerCell.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(enabled = cell.failed && pack != null) {
                                    pack?.let(onOpenPack)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cell.failed) {
                                Text(
                                    text = stringResource(R.string.dialog_retry_load),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            } else {
                                MonogramPlaceholder(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun PackPreviewButton(
    pack: StickerPack,
    selected: Boolean,
    playback: PanelCellPlayback,
    onClick: () -> Unit,
) {
    val fallback = pack.title.trim().let { title ->
        if (title.isEmpty()) "•" else title.substring(0, title.offsetByCodePoints(0, 1))
    }
    val previewId = pack.previewDocumentIds.firstOrNull()
    PreviewButton(
        selected = selected,
        description = pack.title.ifBlank { pack.shortName },
        onClick = onClick,
    ) {
        if (previewId != null) {
            CustomEmojiGlyph(
                documentId = previewId,
                fallback = fallback,
                size = 36.dp,
                compact = true,
                playback = playback,
            )
        } else {
            Text(text = fallback, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PreviewButton(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(PickerMetrics.ChipSize.dp)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun SavedGifBrowser(
    gifs: List<SavedGif>,
    mediaRepository: MediaRepository?,
    loaded: Boolean,
    error: Boolean,
    onSendDocument: (Long) -> Unit,
    onRetry: () -> Unit,
    onGifsVisible: (List<SavedGif>, Set<Long>, Boolean) -> Unit = { _, _, _ -> },
) {
    if (gifs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                !loaded -> PickerSkeleton(kind = PickerSkeletonKind.Gifs)
                error -> PickerStatus(
                    text = stringResource(R.string.dialog_saved_gifs_error),
                    onRetry = onRetry,
                )

                else -> Text(
                    text = stringResource(R.string.dialog_saved_gifs_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val gridState = rememberLazyGridState()
    val playback = rememberPanelGridPlayback(gridState)
    ReportPickerVisible(
        itemsKey = gifs.map { it.documentId },
        gridState = gridState,
        idle = playback.settled,
    ) { min, max, settled ->
        val visible = gifs.subList(min.coerceAtLeast(0), (max + 1).coerceIn(0, gifs.size))
            .map { it.documentId }
            .toSet()
        onGifsVisible(gifs, visible, settled)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PickerMetrics.GifCell.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PickerGridPadding(top = PickerMetrics.GridPadding.dp),
        horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
    ) {
        itemsIndexed(gifs, key = { _, gif -> gif.documentId }) { index, gif ->
            SavedGifCell(
                gif = gif,
                mediaRepository = mediaRepository,
                onClick = { onSendDocument(gif.documentId) },
                thumbOnly = false,
                panelLoop = playback.plays(index),
            )
        }
    }
}

@Composable
internal fun PickerStatus(
    text: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRetry)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.dialog_retry_load),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun emojiCategoryLabel(kind: SystemEmojiCategoryKind): String = stringResource(
    when (kind) {
        SystemEmojiCategoryKind.Smileys -> R.string.dialog_emoji_category_smileys
        SystemEmojiCategoryKind.People -> R.string.dialog_emoji_category_people
        SystemEmojiCategoryKind.Nature -> R.string.dialog_emoji_category_nature
        SystemEmojiCategoryKind.Food -> R.string.dialog_emoji_category_food
        SystemEmojiCategoryKind.Activities -> R.string.dialog_emoji_category_activities
        SystemEmojiCategoryKind.Travel -> R.string.dialog_emoji_category_travel
        SystemEmojiCategoryKind.Objects -> R.string.dialog_emoji_category_objects
        SystemEmojiCategoryKind.Symbols -> R.string.dialog_emoji_category_symbols
        SystemEmojiCategoryKind.Flags -> R.string.dialog_emoji_category_flags
    },
)

private data class PanelGridPlayback(
    val band: IntRange,
    val scrolling: Boolean,
    val settled: Boolean,
    val animations: Boolean,
) {
    fun plays(index: Int): Boolean = panelLoops(index, band, scrolling, settled, animations)

    @Composable
    fun cell(index: Int, bucket: PanelBucket): PanelCellPlayback {
        val inBand = index in band
        val latched = rememberBandLatch(inBand)
        val moving = scrolling || !settled
        return PanelCellPlayback(
            vpxLoop = !moving && animations && inBand,
            tgsLoop = !moving && animations && latched,
            bucket = bucket,
            decode = !moving && inBand,
        )
    }
}

@Composable
private fun rememberPanelGridPlayback(gridState: LazyGridState): PanelGridPlayback {
    val scrolling = gridState.isScrollInProgress
    val settled = rememberSettledIdle(scrolling)
    val animations = panelAnimationsEnabled()
    val band by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            panelPlaybackBand(
                info.visibleItemsInfo.map { item ->
                    GridPlacement(item.index, item.row, item.column)
                },
                info.totalItemsCount,
            )
        }
    }
    return PanelGridPlayback(band, scrolling, settled, animations)
}

private fun chipPlayback(
    selected: Boolean,
    settled: Boolean,
    animations: Boolean,
    bucket: PanelBucket,
): PanelCellPlayback = PanelCellPlayback(
    vpxLoop = selected && settled && animations,
    tgsLoop = selected && settled && animations,
    bucket = bucket,
    decode = selected && settled,
)

@Composable
private fun PickerSectionTitle(
    text: String,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
private fun PickerDocumentButton(
    documentId: Long,
    cell: Int,
    playback: PanelCellPlayback,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(cell.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CustomEmojiGlyph(
            documentId = documentId,
            size = (cell - PickerMetrics.GridSpacing).dp,
            compact = true,
            playback = playback,
        )
    }
}

private fun visibleDocumentIds(cells: List<PickerGridCell>, min: Int, max: Int): Set<Long> {
    if (cells.isEmpty() || max < min) return emptySet()
    val start = min.coerceIn(0, cells.lastIndex)
    val end = max.coerceIn(0, cells.lastIndex)
    return cells.subList(start, end + 1).mapNotNull { it.documentId }.toSet()
}

@Composable
private fun ReportPickerVisible(
    itemsKey: Any?,
    gridState: LazyGridState,
    idle: Boolean = true,
    onRange: (Int, Int, Boolean) -> Unit,
) {
    val current = rememberUpdatedState(onRange)
    val idleState = rememberUpdatedState(idle)
    LaunchedEffect(itemsKey, gridState) {
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val min = if (visible.isEmpty()) 0 else visible.minOf { it.index }
            val max = if (visible.isEmpty()) -1 else visible.maxOf { it.index }
            Triple(min, max, idleState.value)
        }.collect { (min, max, settled) -> current.value(min, max, settled) }
    }
}
