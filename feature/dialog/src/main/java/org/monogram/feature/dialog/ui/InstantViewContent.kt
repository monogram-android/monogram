package org.monogram.feature.dialog.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.InstantViewPages
import org.monogram.core.models.PeerId
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.ExpressiveDefaults
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.SearchField
import org.monogram.core.ui.loading.MonogramBusyButton
import org.monogram.core.ui.loading.MonogramLinearProgress
import org.monogram.core.ui.loading.MonogramLoadingContained
import org.monogram.core.ui.loading.MonogramLoadingHeroSize
import org.monogram.core.ui.menu.AppMenuDivider
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository

internal val LocalInstantViewMediaOpen = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
internal fun InstantViewScreen(
    controller: InstantViewController,
    mediaRepository: MediaRepository?,
    onDismiss: () -> Unit,
    onOpenPeer: (PeerId) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val appearance by AppearanceSettings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }

    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTextSize by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showArticleText by remember { mutableStateOf(false) }
    var viewerKey by remember { mutableStateOf<String?>(null) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(controller) { controller.load() }

    val page = state.page
    val url = page?.url.orEmpty()
    val scale = ivScale(appearance.ivFontSize)
    val titleOffset = if (page?.let(InstantViewPages::shouldShowPageTitle) == true) 1 else 0
    val headerItems = if (page == null) 0 else titleOffset

    val shareText: (String) -> Unit = remember(context, page) {
        { body ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_SUBJECT, page?.title)
            }
            runCatching {
                context.startActivity(
                    Intent.createChooser(
                        send,
                        context.getString(R.string.dialog_instant_view_share)
                    ),
                )
            }
        }
    }
    val copyText: (String) -> Unit = remember(clipboard) {
        { body ->
            scope.launch {
                runCatching {
                    clipboard.setClipEntry(
                        ClipEntry(
                            android.content.ClipData.newPlainText(
                                "text",
                                body
                            )
                        )
                    )
                }
            }
            Unit
        }
    }

    LaunchedEffect(state.matches, state.searchIndex, headerItems) {
        state.currentMatch?.let { match ->
            listState.animateScrollToItem(match + headerItems)
        }
    }
    LaunchedEffect(state.pendingAnchor, page?.url) {
        val name = state.pendingAnchor ?: return@LaunchedEffect
        val current = page ?: return@LaunchedEffect
        InstantViewPages.findAnchorIndex(current.blocks, name)?.let { index ->
            listState.animateScrollToItem(index + headerItems)
        }
        controller.consumeAnchor()
    }
    LaunchedEffect(showSearch) {
        if (showSearch) searchFocus.requestFocus()
    }

    val sheetsVisible = showTextSize || showOutline || showArticleText
    val predictiveDismiss = viewerKey == null && !showSearch && !state.canGoBack && !sheetsVisible

    fun closeSearch() {
        showSearch = false
        controller.setSearch("")
        keyboard?.hide()
    }

    fun handleBack() {
        when {
            viewerKey != null -> viewerKey = null
            showSearch -> closeSearch()
            state.canGoBack -> controller.pop()
            else -> onDismiss()
        }
    }

    PredictiveBackHandler(enabled = predictiveDismiss) { events ->
        try {
            events.collect { event ->
                backProgress = event.progress.coerceIn(0f, 1f)
            }
            backProgress = 0f
            onDismiss()
        } catch (cancelled: CancellationException) {
            val from = backProgress
            scope.launch {
                val anim = Animatable(from)
                anim.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) {
                    backProgress = value
                }
            }
            throw cancelled
        }
    }
    BackHandler(enabled = !predictiveDismiss && !sheetsVisible) { handleBack() }

    val direction = if (page?.rtl == true) LayoutDirection.Rtl else LayoutDirection.Ltr
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barInset = statusTop + IvSpacing.BarHeight
    val contentLayer = rememberGraphicsLayer()
    val measured = remember { mutableMapOf<Int, Int>() }
    val lastProgress = remember { floatArrayOf(0f) }
    LaunchedEffect(page?.url) {
        measured.clear()
        lastProgress[0] = 0f
    }
    val density = LocalDensity.current
    val viewportWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val blurRadiusPx = with(density) { 22.dp.toPx() }
    val frost = MaterialTheme.colorScheme.surface
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val bleed = !showSearch && page?.blocks?.firstOrNull().let {
        it is InstantViewBlock.Cover || it is InstantViewBlock.Photo
    }
    val blockEstimates = remember(page?.url, viewportWidthPx, scale, density.density) {
        page?.blocks?.let {
            ivBlockHeightEstimates(it, viewportWidthPx, scale, density.density)
        }.orEmpty()
    }
    val progress by remember(blockEstimates) {
        derivedStateOf {
            readingProgress(listState, measured, lastProgress) { index ->
                blockEstimates.getOrElse(index) { 0f }
            }
        }
    }

    val activeHeading by remember(state.outline, headerItems) {
        derivedStateOf {
            val target = listState.firstVisibleItemIndex - headerItems
            state.outline.lastOrNull { it.index <= target }?.index
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        LocalInstantViewMediaOpen provides { key -> viewerKey = key },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f * backProgress.coerceIn(0f, 1f))),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = backProgress.coerceIn(0f, 1f)
                        translationX = size.width * 0.28f * p
                        scaleX = 1f - 0.12f * p
                        scaleY = 1f - 0.12f * p
                        transformOrigin = TransformOrigin.Center
                        shape = RoundedCornerShape(18.dp * p)
                        clip = p > 0.001f
                        alpha = 1f - 0.2f * p
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            contentLayer.record { this@drawWithContent.drawContent() }
                            drawContent()
                        },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        when {
                            state.loading && page == null -> Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MonogramLoadingContained(size = MonogramLoadingHeroSize)
                            }

                            page == null -> InstantViewUnavailable(
                                noInstantView = state.error != null && state.error.isNullOrBlank(),
                                onRetry = { scope.launch { controller.retry() } },
                                onOpenOriginal = { runCatching { uriHandler.openUri(url) } },
                                onDismiss = onDismiss,
                            )

                            else -> {
                                val highlight = state.currentMatch
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        top = if (showSearch || bleed) 0.dp else barInset,
                                        bottom = 96.dp,
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(IvSpacing.BlockGap),
                                ) {
                                    if (titleOffset == 1) {
                                        item(key = "iv-page-title", contentType = "title") {
                                            IvMeasure {
                                                Text(
                                                    text = page.title.orEmpty(),
                                                    modifier = Modifier.padding(
                                                        top = if (bleed) 16.dp else 4.dp,
                                                    ),
                                                    style = ivTextStyle("title", 0, scale),
                                                )
                                            }
                                        }
                                    }
                                    itemsIndexed(
                                        items = page.blocks,
                                        key = { index, block ->
                                            InstantViewPages.blockStableKey(
                                                index,
                                                block
                                            )
                                        },
                                        contentType = { _, block -> block.contentTypeKey() },
                                    ) { index, block ->
                                        val fullBleed = block is InstantViewBlock.Cover
                                        Box(Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier
                                                    .align(Alignment.TopCenter)
                                                    .widthIn(max = IvSpacing.ReadableWidth)
                                                    .fillMaxWidth()
                                                    .padding(top = ivSectionGap(block))
                                                    .then(
                                                        if (fullBleed) {
                                                            Modifier
                                                        } else {
                                                            Modifier.padding(horizontal = IvSpacing.Gutter)
                                                        },
                                                    )
                                                    .then(
                                                        if (highlight == index) {
                                                            Modifier
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(
                                                                    MaterialTheme.colorScheme.secondaryContainer
                                                                        .copy(alpha = 0.55f),
                                                                )
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                            ) {
                                                InstantViewBlockContent(
                                                    block = block,
                                                    scale = scale,
                                                    query = state.search,
                                                    mediaRepository = mediaRepository,
                                                    onOpenUrl = { target ->
                                                        scope.launch {
                                                            if (!controller.openLink(target)) {
                                                                runCatching {
                                                                    uriHandler.openUri(
                                                                        target
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onOpenPeer = onOpenPeer,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                InstantViewFrostedBackdrop(
                    barInset = barInset,
                    contentLayer = contentLayer,
                    canBlur = canBlur,
                    blurRadiusPx = blurRadiusPx,
                    frost = frost,
                    scrimmed = bleed,
                )

                if (showSearch) {
                    InstantViewSearchBar(
                        query = state.search,
                        matchCount = state.matchCount,
                        matchIndex = state.searchIndex,
                        topPadding = statusTop,
                        focusRequester = searchFocus,
                        onQueryChange = controller::setSearch,
                        onPrevious = controller::previousMatch,
                        onNext = controller::nextMatch,
                        onClose = { closeSearch() },
                    )
                } else {
                    InstantViewTopBar(
                        title = page?.siteName ?: page?.title
                        ?: stringResource(R.string.dialog_instant_view),
                        hasPage = page != null,
                        showContents = state.outline.isNotEmpty(),
                        onBack = {
                            if (state.canGoBack) controller.pop() else onDismiss()
                        },
                        onSearch = { showSearch = true },
                        onContents = { showOutline = true },
                        showMenu = showMenu,
                        onMenuVisibleChange = { showMenu = it },
                        menu = {
                            AppMenuSurface {
                                AppMenuGroup {
                                    AppMenuItem(
                                        text = stringResource(R.string.dialog_instant_view_font),
                                        icon = Icons.Outlined.FormatSize,
                                        onClick = {
                                            showMenu = false
                                            showTextSize = true
                                        },
                                    )
                                    AppMenuItem(
                                        text = stringResource(R.string.dialog_instant_view_text),
                                        icon = Icons.Outlined.SelectAll,
                                        onClick = {
                                            showMenu = false
                                            showArticleText = true
                                        },
                                    )
                                }
                                AppMenuDivider()
                                AppMenuGroup {
                                    AppMenuItem(
                                        text = stringResource(R.string.dialog_instant_view_copy_link),
                                        icon = Icons.Outlined.Link,
                                        onClick = {
                                            showMenu = false
                                            copyText(page?.url ?: url)
                                        },
                                    )
                                    AppMenuItem(
                                        text = stringResource(R.string.dialog_instant_view_share),
                                        icon = Icons.Outlined.Share,
                                        onClick = {
                                            showMenu = false
                                            shareText(page?.url ?: url)
                                        },
                                    )
                                    AppMenuItem(
                                        text = stringResource(R.string.dialog_instant_view_open_original),
                                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                        onClick = {
                                            showMenu = false
                                            runCatching { uriHandler.openUri(page?.url ?: url) }
                                        },
                                    )
                                }
                                AppMenuDivider()
                                AppMenuGroup {
                                    AppMenuItem(
                                        text = stringResource(R.string.dialog_instant_view_reload),
                                        icon = Icons.Outlined.Refresh,
                                        onClick = {
                                            showMenu = false
                                            scope.launch { controller.reloadCurrent() }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }

                if (state.loading && page != null) {
                    MonogramLinearProgress(
                        visible = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = barInset),
                    )
                } else {
                    InstantViewReadingProgress(
                        progress = { progress },
                        modifier = Modifier.padding(top = barInset - IvSpacing.ProgressHeight),
                    )
                }

                if (page != null && state.error != null) {
                    AppStatusBanner(
                        text = stringResource(R.string.dialog_instant_view_error),
                        failed = true,
                        progress = false,
                        onRetry = { scope.launch { controller.retry() } },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = barInset + 4.dp),
                    )
                }

                InstantViewScrollToTop(
                    visible = { progress > 0.08f && page != null },
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = IvSpacing.Gutter, bottom = 28.dp),
                )
            }

            viewerKey?.let { key ->
                InstantViewMediaViewer(key, mediaRepository) { viewerKey = null }
            }

            if (page != null) {
                InstantViewTextSizeSheet(
                    visible = showTextSize,
                    fontSize = appearance.ivFontSize,
                    previewText = remember(page) { ivPreviewText(page) },
                    onFontSizeChange = { AppearanceSettings.setIvFontSize(it) },
                    onDismiss = { showTextSize = false },
                )
                InstantViewOutlineSheet(
                    visible = showOutline,
                    outline = state.outline,
                    readingMinutes = state.readingMinutes,
                    activeIndex = activeHeading,
                    onSelect = { blockIndex ->
                        showOutline = false
                        scope.launch { listState.animateScrollToItem(blockIndex + headerItems) }
                    },
                    onDismiss = { showOutline = false },
                )
                InstantViewArticleTextSheet(
                    visible = showArticleText,
                    text = remember(page) { InstantViewPages.plainText(page.blocks) },
                    onCopy = { copyText(it) },
                    onShare = { shareText(it) },
                    onDismiss = { showArticleText = false },
                )
            }
        }
    }
}

@Composable
private fun IvMeasure(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IvSpacing.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = IvSpacing.ReadableWidth)
                .fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun InstantViewReadingProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    val fraction = progress().coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IvSpacing.ProgressHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(IvSpacing.ProgressHeight)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun InstantViewScrollToTop(
    visible: () -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = visible()
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                Icons.Outlined.VerticalAlignTop,
                contentDescription = stringResource(R.string.dialog_instant_view_to_top),
            )
        }
    }
}

@Composable
private fun InstantViewFrostedBackdrop(
    barInset: Dp,
    contentLayer: GraphicsLayer,
    canBlur: Boolean,
    blurRadiusPx: Float,
    frost: Color,
    scrimmed: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barInset)
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    if (canBlur) {
                        renderEffect = BlurEffect(blurRadiusPx, blurRadiusPx, TileMode.Clamp)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                }
                .drawWithContent { drawLayer(contentLayer) },
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to frost.copy(alpha = if (canBlur) 0.72f else 0.96f),
                        1f to frost.copy(alpha = if (canBlur) 0.46f else 0.92f),
                    ),
                ),
        )
        if (scrimmed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.3f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}

@Composable
private fun InstantViewTopBar(
    title: String,
    hasPage: Boolean,
    showContents: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onContents: () -> Unit,
    showMenu: Boolean,
    onMenuVisibleChange: (Boolean) -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            .height(IvSpacing.BarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.dialog_instant_view_back),
            )
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasPage) {
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.dialog_instant_view_search),
                )
            }
            if (showContents) {
                IconButton(onClick = onContents) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Toc,
                        contentDescription = stringResource(R.string.dialog_instant_view_contents),
                    )
                }
            }
            Box {
                IconButton(onClick = { onMenuVisibleChange(true) }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.dialog_instant_view_more),
                    )
                }
                AppMenuPopup(
                    expanded = showMenu,
                    onDismiss = { onMenuVisibleChange(false) },
                    alignToAnchorEnd = true,
                ) {
                    menu()
                }
            }
        }
    }
}

@Composable
private fun InstantViewSearchBar(
    query: String,
    matchCount: Int,
    matchIndex: Int,
    topPadding: Dp,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .height(IvSpacing.BarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.dialog_instant_view_search_close),
            )
        }
        SearchField(
            query = query,
            onQueryChanged = onQueryChange,
            placeholder = stringResource(R.string.dialog_instant_view_search),
            closeLabel = stringResource(R.string.dialog_instant_view_search_clear),
            modifier = Modifier.weight(1f),
            focusRequester = focusRequester,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
        )
        if (query.isNotBlank()) {
            Text(
                text = if (matchCount == 0) {
                    "0"
                } else {
                    "${matchIndex.coerceIn(0, matchCount - 1) + 1}/$matchCount"
                },
                style = ExpressiveDefaults.tabularLabel(),
                color = if (matchCount == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            IconButton(onClick = onPrevious, enabled = matchCount > 0) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.dialog_instant_view_search_previous),
                )
            }
            IconButton(onClick = onNext, enabled = matchCount > 0) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.dialog_instant_view_search_next),
                )
            }
        }
    }
}

@Composable
private fun InstantViewUnavailable(
    noInstantView: Boolean,
    onRetry: () -> Unit,
    onOpenOriginal: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (noInstantView) {
                        R.string.dialog_instant_view_empty
                    } else {
                        R.string.dialog_instant_view_error
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.dialog_instant_view_error_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_instant_view_back))
                }
                TextButton(onClick = onOpenOriginal) {
                    Text(stringResource(R.string.dialog_instant_view_open_original))
                }
                MonogramBusyButton(
                    text = stringResource(R.string.dialog_instant_view_retry),
                    onClick = onRetry,
                    busy = false,
                )
            }
        }
    }
}

private fun readingProgress(
    state: LazyListState,
    measured: MutableMap<Int, Int>,
    lastProgress: FloatArray,
    estimate: (Int) -> Float,
): Float {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (visible.isEmpty() || total == 0) return lastProgress[0]
    if (!state.canScrollBackward) {
        lastProgress[0] = 0f
        return 0f
    }
    if (!state.canScrollForward) {
        lastProgress[0] = 1f
        return 1f
    }
    if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
        lastProgress[0] = 0f
        return 0f
    }
    visible.forEach { measured[it.index] = it.size }
    val firstVisible = visible.minByOrNull { it.index } ?: return lastProgress[0]
    val first = firstVisible.index
    var content = 0f
    var above = 0f
    for (index in 0 until total) {
        val height = measured[index]?.toFloat()?.coerceAtLeast(estimate(index)) ?: estimate(index)
        content += height
        if (index < first) above += height
    }
    val spacing = info.mainAxisItemSpacing.toFloat().coerceAtLeast(0f)
    val scrolled = (above + spacing * first + info.beforeContentPadding - firstVisible.offset)
        .coerceAtLeast(0f)
    val scrollable = (content + spacing * (total - 1).coerceAtLeast(0) +
        info.beforeContentPadding + info.afterContentPadding - info.viewportSize.height)
        .coerceAtLeast(1f)
    val value = (scrolled / scrollable).coerceIn(0f, 1f)
    lastProgress[0] = value
    return value
}


private fun ivBlockHeightEstimates(
    blocks: List<InstantViewBlock>,
    viewportWidth: Int,
    scale: Float,
    density: Float,
    depth: Int = 0,
): List<Float> {
    if (viewportWidth <= 0) return blocks.map { 0f }
    fun dp(value: Float) = value * density
    fun lines(text: String): Float =
        (text.length / (viewportWidth / dp(8.4f * scale)).coerceAtLeast(10f)).coerceAtLeast(1f)

    fun mediaHeight(width: Int, height: Int, min: Float, max: Float): Float {
        val ratio = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
        return viewportWidth / ratio.coerceIn(min, max)
    }

    fun nested(items: List<InstantViewBlock>): Float =
        if (depth >= 4) {
            0f
        } else {
            ivBlockHeightEstimates(items, viewportWidth, scale, density, depth + 1).sum()
        }
    return blocks.map { block ->
        when (block) {
            is InstantViewBlock.Text -> {
                val size = when (block.kind) {
                    "title" -> 23f
                    "subtitle" -> 20f
                    "heading" -> when (block.level) {
                        1 -> 20f
                        2 -> 17f
                        3 -> 15f
                        else -> 13f
                    }

                    "pre" -> 14f
                    "kicker", "authorDate", "footer" -> 14f
                    else -> 16f
                }
                lines(block.text) * dp(size * scale * 1.45f) + dp(14f)
            }

            is InstantViewBlock.Quote ->
                lines(block.text) * dp(15f * scale * 1.45f) + dp(20f) + nested(block.blocks)

            is InstantViewBlock.ListBlock ->
                dp(30f) * block.items.sumOf { it.text.length / 40 + 1 } +
                        nested(block.items.flatMap { it.blocks })

            is InstantViewBlock.Table -> dp(40f) * block.rows.size.coerceAtLeast(1)
            is InstantViewBlock.Details -> dp(52f) + nested(block.blocks)
            is InstantViewBlock.Photo -> mediaHeight(
                block.width,
                block.height,
                0.5f,
                2.2f
            ) + dp(20f)

            is InstantViewBlock.Document ->
                if (block.kind == "video") {
                    mediaHeight(block.width, block.height, 0.5f, 2.4f) + dp(20f)
                } else {
                    dp(84f)
                }

            is InstantViewBlock.Cover -> {
                val inner = block.block
                if (inner is InstantViewBlock.Photo) {
                    mediaHeight(inner.width, inner.height, 0.5f, 2.2f) + dp(20f)
                } else {
                    nested(listOf(inner)) + dp(20f)
                }
            }

            is InstantViewBlock.Embed -> mediaHeight(
                block.width ?: 0,
                block.height ?: 0,
                0.6f,
                2.2f
            )

            is InstantViewBlock.EmbedPost -> dp(70f) + nested(block.blocks)
            is InstantViewBlock.MediaGroup -> nested(block.items).coerceAtLeast(dp(160f))
            is InstantViewBlock.Channel -> dp(70f)
            is InstantViewBlock.Related -> dp(30f) + dp(76f) * block.articles.size.coerceAtLeast(1)
            is InstantViewBlock.Map -> dp(200f)
            is InstantViewBlock.Math -> dp(60f)
            is InstantViewBlock.Buttons -> dp(46f)
            is InstantViewBlock.Anchor,
            InstantViewBlock.Divider,
            InstantViewBlock.Unsupported,
                -> dp(18f)
        }
    }
}

internal fun ivDisplayHost(page: InstantViewPage?): String? {
    val raw = page?.displayUrl?.takeIf { it.isNotBlank() } ?: page?.url?.takeIf { it.isNotBlank() }
    val stripped = raw?.substringAfter("://", raw.orEmpty())
    return stripped
        ?.substringBefore('/')
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.takeIf { it.isNotBlank() }
}

private fun ivPreviewText(page: InstantViewPage): String {
    val paragraphs = page.blocks.asSequence()
        .filterIsInstance<InstantViewBlock.Text>()
        .filter { it.kind == "paragraph" || it.kind == "subtitle" }
        .map { it.text.trim() }
        .filter { it.isNotEmpty() }
        .take(IV_PREVIEW_SAMPLE_BLOCKS)
        .toList()
    return paragraphs.maxByOrNull { it.length }
        ?: page.description?.takeIf { it.isNotBlank() }
        ?: page.title?.takeIf { it.isNotBlank() }
        ?: page.blocks.asSequence()
            .filterIsInstance<InstantViewBlock.Text>()
            .map { it.text.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
}

private const val IV_PREVIEW_SAMPLE_BLOCKS = 6

internal fun InstantViewBlock.contentTypeKey(): String = when (this) {
    is InstantViewBlock.Text -> "text:${kind}"
    is InstantViewBlock.Quote -> "quote"
    is InstantViewBlock.ListBlock -> "list"
    is InstantViewBlock.Table -> "table"
    is InstantViewBlock.Details -> "details"
    is InstantViewBlock.Photo -> "photo"
    is InstantViewBlock.Document -> "document:${kind}"
    is InstantViewBlock.Cover -> "cover"
    is InstantViewBlock.Embed -> "embed"
    is InstantViewBlock.EmbedPost -> "embedPost"
    is InstantViewBlock.MediaGroup -> "media:${kind}"
    is InstantViewBlock.Channel -> "channel"
    is InstantViewBlock.Related -> "related"
    is InstantViewBlock.Map -> "map"
    is InstantViewBlock.Math -> "math"
    is InstantViewBlock.Buttons -> "buttons"
    is InstantViewBlock.Anchor -> "anchor"
    InstantViewBlock.Divider -> "divider"
    InstantViewBlock.Unsupported -> "unsupported"
}
