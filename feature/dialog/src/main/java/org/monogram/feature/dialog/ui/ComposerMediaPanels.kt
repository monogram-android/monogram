package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.SheetPanelHost
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.PickerMediaPreload
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.SystemEmojiCatalog
import org.monogram.feature.dialog.SystemEmojiCategory
import org.monogram.feature.dialog.SystemEmojiCategoryKind
import org.monogram.network.http.MediaRepository
import org.monogram.core.ui.components.MonogramPlaceholder

internal val PickerTabClearance = 72.dp

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
    onPickerGifsVisible: (List<SavedGif>, Set<Long>) -> Unit = { _, _ -> },
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
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        AppModalSheet(
            onDismissRequest = onDismiss,
            containerColor = container,
            showDragHandle = true,
            padNavigationBars = false,
            padIme = false,
            visible = sheetVisible,
            onExited = onExited,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                            onSendDocument = onSendDocument,
                            onDocumentsVisible = onPickerDocumentsVisible,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f)
                        .padding(start = 24.dp, end = 24.dp, bottom = 8.dp + navBottom)
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
    onSendDocument: (Long) -> Unit,
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
    var selectedPackId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Remember which pack was already asked for, so a restored selection is fetched once and a
    // loaded pack is never re-requested in a loop.
    var requestedPackId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedPackId, packs) {
        val id = selectedPackId ?: return@LaunchedEffect
        if (id == requestedPackId) return@LaunchedEffect
        if (loadedPacks[id]?.previewDocumentIds?.isNotEmpty() == true) return@LaunchedEffect
        requestedPackId = id
        packs.firstOrNull { it.id == id }?.let(onOpenPack)
    }
    val starts = remember(categories) {
        var next = 0
        categories.associate { category ->
            val start = next
            next += 1 + category.glyphs.size
            category.kind to start
        }
    }
    val visibleCategory by remember(categories, starts, gridState) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            categories.lastOrNull { starts.getValue(it.kind) <= first }?.kind
                ?: categories.firstOrNull()?.kind
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        EmojiPreviewRow(
            categories = categories,
            packs = packs,
            loadedPacks = loadedPacks,
            packsLoading = !packsLoaded,
            selectedCategory = visibleCategory.takeIf { selectedPackId == null },
            selectedPackId = selectedPackId,
            onCategory = { kind ->
                selectedPackId = null
                starts[kind]?.let { index -> scope.launch { gridState.animateScrollToItem(index) } }
            },
            onPack = { pack ->
                selectedPackId = pack.id
                requestedPackId = pack.id
                onOpenPack(pack)
            },
            onPrefetchPack = onOpenPack,
        )
        if (selectedPackId == null) {
            if (categories.isEmpty()) {
                PickerSkeleton(kind = PickerSkeletonKind.Emoji)
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    SystemEmojiGrid(
                        categories = categories,
                        state = gridState,
                        onInsertEmoji = onInsertEmoji,
                    )
                }
            }
        } else {
            val packId = selectedPackId
            val selectedPack = packId?.let { id ->
                loadedPacks[id] ?: openPack?.takeIf { it.id == id }
            }
            val packChip = packs.firstOrNull { it.id == packId }
            val packFailed = packId != null && packId in failedPackIds
            val packLoading = packId != null && packId in loadingPackIds
            Box(modifier = Modifier.weight(1f)) {
                when {
                    selectedPack != null && selectedPack.previewDocumentIds.isNotEmpty() ->
                        DocumentGrid(
                            ids = selectedPack.previewDocumentIds,
                            cellSize = PickerMetrics.EmojiPackCell,
                            onClick = onInsertCustomEmoji,
                            onDocumentsVisible = onDocumentsVisible,
                        )

                    packFailed -> PickerStatus(
                        text = stringResource(R.string.dialog_pack_error),
                        onRetry = { packChip?.let(onOpenPack) },
                    )

                    // The pack is on its way: show the emoji cells it is about to fill.
                    packLoading || selectedPack == null ->
                        PickerSkeleton(kind = PickerSkeletonKind.EmojiPack)

                    else -> PickerStatus(
                        text = stringResource(R.string.dialog_pack_empty),
                        onRetry = { packChip?.let(onOpenPack) },
                    )
                }
            }
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
) {
    val rowState = rememberLazyListState()
    val packIndexOffset = categories.size + if (categories.isNotEmpty() && packs.isNotEmpty()) 1 else 0
    val visibleChipPacks by remember(packs, rowState, packIndexOffset) {
        derivedStateOf {
            val visible = rowState.layoutInfo.visibleItemsInfo
            val fromRow = visible.mapNotNull { info ->
                packs.getOrNull(info.index - packIndexOffset)
            }
            fromRow.ifEmpty { packs.take(8) }
        }
    }
    LaunchedEffect(visibleChipPacks) {
        visibleChipPacks.forEach(onPrefetchPack)
    }
    CompositionLocalProvider(LocalMediaAnimationEnabled provides true) {
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
                        PackPreviewButton(
                            pack = loadedPacks[pack.id] ?: pack,
                            selected = selectedPackId == pack.id,
                            onClick = { onPack(pack) },
                        )
                    }
                }
                // Emoji packs are still on their way: keep their slots so the row does not shift.
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
}

@Composable
private fun SystemEmojiGrid(
    categories: List<SystemEmojiCategory>,
    state: LazyGridState,
    onInsertEmoji: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(PickerMetrics.EmojiCell.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PickerGridPadding(),
    ) {
        categories.forEach { category ->
            item(
                key = "heading:${category.kind}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    text = emojiCategoryLabel(category.kind),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
            items(
                items = category.glyphs,
                key = { "${category.kind}:$it" },
            ) { glyph ->
                Box(
                    modifier = Modifier
                        .size(PickerMetrics.EmojiCell.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onInsertEmoji(glyph) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = glyph, style = MaterialTheme.typography.headlineMedium)
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
    val starts = remember(packs, loadedPacks) {
        var next = 0
        packs.associate { pack ->
            val start = next
            next += 1 + (loadedPacks[pack.id]?.previewDocumentIds?.size ?: pack.count.coerceAtLeast(1))
            pack.id to start
        }
    }
    val selectedId by remember(packs, starts, gridState) {
        derivedStateOf {
            packs.lastOrNull { starts.getValue(it.id) <= gridState.firstVisibleItemIndex }?.id
        }
    }
    val visiblePacks by remember(packs, starts, loadedPacks, gridState) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf packs.take(12)
            val minIndex = visible.minOf { it.index }
            val maxIndex = visible.maxOf { it.index } + 12
            packs.filter { pack ->
                val start = starts.getValue(pack.id)
                val count = 1 + (
                    loadedPacks[pack.id]?.previewDocumentIds?.size ?: pack.count.coerceAtLeast(1)
                )
                start + count > minIndex && start <= maxIndex
            }
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
    val slots = remember(packs, loadedPacks) { PickerMediaPreload.packSlots(packs, loadedPacks) }
    val documentIds = remember(slots) { slots.mapNotNull { it } }
    ReportPickerVisible(
        itemsKey = documentIds,
        gridState = gridState,
    ) { min, max ->
        onDocumentsVisible(documentIds, PickerMediaPreload.visibleIds(slots, min, max))
    }
    Column(modifier = modifier) {
        CompositionLocalProvider(LocalMediaAnimationEnabled provides true) {
            LazyRow(
                state = previewState,
                modifier = Modifier.fillMaxWidth().height(PickerMetrics.ChipRowHeight.dp),
                contentPadding = PickerChipPadding(),
                horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(packs, key = { it.id }) { pack ->
                    PackPreviewButton(
                        pack = loadedPacks[pack.id] ?: pack,
                        selected = selectedId == pack.id,
                        onClick = {
                            onOpenPack(pack)
                            scope.launch { gridState.animateScrollToItem(starts.getValue(pack.id)) }
                        },
                    )
                }
            }
        }
        CompositionLocalProvider(LocalMediaAnimationEnabled provides true) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(PickerMetrics.StickerCell.dp),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PickerGridPadding(top = PickerMetrics.GridPadding.dp),
                horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
                verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
            ) {
                packs.forEach { pack ->
                    val documents = loadedPacks[pack.id]?.previewDocumentIds
                    val failed = documents.isNullOrEmpty() && pack.id in failedPackIds
                    item(key = "pack:${pack.id}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = pack.title.ifBlank { pack.shortName },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    items(
                        count = documents?.size ?: pack.count.coerceAtLeast(1),
                        key = { index -> "${pack.id}:$index" },
                    ) { index ->
                        val id = documents?.getOrNull(index)
                        Box(
                            modifier = Modifier.size(PickerMetrics.StickerCell.dp)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(enabled = id != null || failed) {
                                    if (id != null) onSendDocument(id) else onOpenPack(pack)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                id != null -> CustomEmojiGlyph(
                                    documentId = id,
                                    size = (PickerMetrics.StickerCell - PickerMetrics.GridSpacing).dp,
                                    compact = true,
                                )
                                // Pack contents failed to load: the cell itself retries the pack.
                                failed -> Text(
                                    text = stringResource(R.string.dialog_retry_load),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                // Stickers are on their way: keep their exact slots shimmering.
                                else -> MonogramPlaceholder(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackPreviewButton(
    pack: StickerPack,
    selected: Boolean,
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
private fun DocumentGrid(
    ids: List<Long>,
    cellSize: Int,
    onClick: (Long) -> Unit,
    onDocumentsVisible: (List<Long>, Set<Long>) -> Unit = { _, _ -> },
) {
    val gridState = rememberLazyGridState()
    ReportPickerVisible(itemsKey = ids, gridState = gridState) { min, max ->
        onDocumentsVisible(ids, ids.subList(min.coerceAtLeast(0), (max + 1).coerceIn(0, ids.size)).toSet())
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cellSize.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PickerGridPadding(),
        horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
    ) {
        items(ids, key = { it }) { id ->
            CompositionLocalProvider(LocalMediaAnimationEnabled provides true) {
                Box(
                    modifier = Modifier
                        .size(cellSize.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onClick(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    CustomEmojiGlyph(
                        documentId = id,
                        size = (cellSize - PickerMetrics.GridSpacing).dp,
                        compact = true,
                    )
                }
            }
        }
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
    onGifsVisible: (List<SavedGif>, Set<Long>) -> Unit = { _, _ -> },
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
    ReportPickerVisible(itemsKey = gifs.map { it.documentId }, gridState = gridState) { min, max ->
        val visible = gifs.subList(min.coerceAtLeast(0), (max + 1).coerceIn(0, gifs.size))
            .map { it.documentId }
            .toSet()
        onGifsVisible(gifs, visible)
    }
    CompositionLocalProvider(LocalMediaAnimationEnabled provides true) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PickerMetrics.GifCell.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PickerGridPadding(top = PickerMetrics.GridPadding.dp),
            horizontalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
            verticalArrangement = Arrangement.spacedBy(PickerMetrics.GridSpacing.dp),
        ) {
            items(gifs, key = { it.documentId }) { gif ->
                SavedGifCell(
                    gif = gif,
                    mediaRepository = mediaRepository,
                    onClick = { onSendDocument(gif.documentId) },
                    thumbOnly = false,
                )
            }
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

@Composable
private fun ReportPickerVisible(
    itemsKey: Any?,
    gridState: LazyGridState,
    onRange: (Int, Int) -> Unit,
) {
    LaunchedEffect(itemsKey, gridState) {
        onRange(0, -1)
        snapshotFlow {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) 0 to -1
            else visible.minOf { it.index } to visible.maxOf { it.index }
        }.collect { (min, max) -> onRange(min, max) }
    }
}
