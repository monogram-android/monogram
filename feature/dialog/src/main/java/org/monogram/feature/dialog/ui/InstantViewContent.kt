package org.monogram.feature.dialog.ui

import android.content.Intent
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.Outcome
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewCaption
import org.monogram.core.models.InstantViewFetchDecision
import org.monogram.core.models.InstantViewLink
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.InstantViewPages
import org.monogram.core.models.InstantViewRichText
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.feature.dialog.R
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import java.io.File
import org.monogram.core.ui.loading.MonogramCircularProgress
import org.monogram.core.ui.loading.MonogramLoadingContained
import org.monogram.core.ui.loading.MonogramLoadingHeroSize

/** Tapping a page photo opens the modal viewer instead of only inline stills. */
internal val LocalInstantViewMediaOpen = staticCompositionLocalOf<(String) -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstantViewScreen(
    controller: InstantViewController,
    mediaRepository: MediaRepository?,
    onDismiss: () -> Unit,
    onOpenPeer: (PeerId) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val url = state.page?.url.orEmpty()
    val appearance by AppearanceSettings.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    var showSearch by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var viewerKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(controller) { controller.load() }
    LaunchedEffect(state.searchIndex, state.search) {
        val matches = state.matches
        if (matches.isNotEmpty()) {
            val titleOffset = if (state.page?.let(InstantViewPages::shouldShowPageTitle) == true) 1 else 0
            listState.animateScrollToItem(
                matches[state.searchIndex.coerceIn(matches.indices)] + titleOffset,
            )
        }
    }
    LaunchedEffect(state.pendingAnchor, state.page?.url) {
        val name = state.pendingAnchor ?: return@LaunchedEffect
        val page = state.page ?: return@LaunchedEffect
        val index = InstantViewPages.findAnchorIndex(page.blocks, name)
        if (index != null) {
            val titleOffset = if (InstantViewPages.shouldShowPageTitle(page)) 1 else 0
            listState.animateScrollToItem(index + titleOffset)
        }
        controller.consumeAnchor()
    }
    BackHandler {
        when {
            viewerKey != null -> viewerKey = null
            state.canGoBack -> controller.pop()
            else -> onDismiss()
        }
    }
    val page = state.page
    val direction = if (page?.rtl == true) LayoutDirection.Rtl else LayoutDirection.Ltr
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barInset = statusTop + 64.dp
    val contentLayer = rememberGraphicsLayer()
    val blurRadiusPx = with(LocalDensity.current) { 22.dp.toPx() }
    val frost = MaterialTheme.colorScheme.surface
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
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
                    .drawWithContent {
                        contentLayer.record { this@drawWithContent.drawContent() }
                        drawContent()
                    },
            ) {
            Column(Modifier.fillMaxSize()) {
                if (showSearch) {
                    TextField(
                        value = state.search,
                        onValueChange = controller::setSearch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = barInset),
                        placeholder = { Text(stringResource(R.string.dialog_instant_view_search)) },
                        trailingIcon = {
                            if (state.matches.isNotEmpty()) {
                                TextButton(onClick = controller::nextMatch) {
                                    Text(stringResource(R.string.dialog_instant_view_search_next))
                                }
                            }
                        },
                        singleLine = true,
                    )
                }
                when {
                    state.loading && page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MonogramLoadingContained(size = MonogramLoadingHeroSize)
                    }
                    state.error != null && page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (state.error.isNullOrBlank()) {
                                    stringResource(R.string.dialog_instant_view_empty)
                                } else {
                                    stringResource(R.string.dialog_instant_view_error)
                                },
                            )
                            TextButton(onClick = { scope.launch { controller.retry() } }) {
                                Text(stringResource(R.string.dialog_instant_view_retry))
                            }
                        }
                    }
                    page != null -> {
                        val scale = appearance.ivFontSize / 16f
                        val highlight = state.matches.getOrNull(state.searchIndex)
                        val showPageTitle = InstantViewPages.shouldShowPageTitle(page)
                        val bleed = !showSearch && page.blocks.firstOrNull().let {
                            it is InstantViewBlock.Cover || it is InstantViewBlock.Photo
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = if (showSearch || bleed) 8.dp else barInset,
                                bottom = 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (showPageTitle) {
                                item(key = "page-title") {
                                    Text(
                                        text = page.title.orEmpty(),
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = (28 * scale).sp),
                                    )
                                }
                            }
                            itemsIndexed(page.blocks, key = { index, block -> InstantViewPages.blockStableKey(index, block) }) { index, block ->
                                val marked = highlight == index
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (marked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                        .padding(
                                            horizontal = if (block is InstantViewBlock.Cover) 0.dp else 20.dp,
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
                                                    runCatching { uriHandler.openUri(target) }
                                                }
                                            }
                                        },
                                        onOpenPeer = onOpenPeer,
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
            }
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
                                renderEffect = BlurEffect(
                                    blurRadiusPx,
                                    blurRadiusPx,
                                    TileMode.Clamp,
                                )
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                        }
                        .drawWithContent { drawLayer(contentLayer) },
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(frost.copy(alpha = if (canBlur) 0.42f else 0.92f)),
                )
            }
            TopAppBar(
                title = {
                    Text(
                        text = page?.siteName ?: page?.title ?: stringResource(R.string.dialog_instant_view),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.canGoBack) controller.pop() else onDismiss()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.dialog_instant_view_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.dialog_instant_view_search),
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.dialog_instant_view_more),
                            )
                        }
                        AppMenuPopup(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            alignToAnchorEnd = true,
                        ) {
                            AppMenuSurface {
                                AppMenuGroup {
                                AppMenuItem(
                                    text = stringResource(R.string.dialog_instant_view_font),
                                    icon = Icons.Outlined.FormatSize,
                                    onClick = { showFont = !showFont },
                                )
                                if (showFont) {
                                    Slider(
                                        value = appearance.ivFontSize.toFloat(),
                                        onValueChange = { AppearanceSettings.setIvFontSize(it.toInt()) },
                                        valueRange = AppearanceSettings.MIN_IV_FONT_SIZE.toFloat()..
                                            AppearanceSettings.MAX_IV_FONT_SIZE.toFloat(),
                                        steps = AppearanceSettings.MAX_IV_FONT_SIZE - AppearanceSettings.MIN_IV_FONT_SIZE - 1,
                                        modifier = Modifier
                                            .width(240.dp)
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                AppMenuItem(
                                    text = stringResource(R.string.dialog_instant_view_share),
                                    icon = Icons.Outlined.Share,
                                    onClick = {
                                        showMenu = false
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, page?.url ?: url)
                                            putExtra(Intent.EXTRA_SUBJECT, page?.title)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                share,
                                                context.getString(R.string.dialog_instant_view_share),
                                            ),
                                        )
                                    },
                                )
                                AppMenuItem(
                                    text = stringResource(R.string.dialog_instant_view_copy_link),
                                    icon = Icons.Outlined.ContentCopy,
                                    onClick = {
                                        showMenu = false
                                        val link = page?.url ?: url
                                        scope.launch {
                                            runCatching {
                                                clipboard.setClipEntry(
                                                    ClipEntry(
                                                        android.content.ClipData.newPlainText("link", link),
                                                    ),
                                                )
                                            }
                                        }
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
                            }
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
            viewerKey?.let { key ->
                InstantViewMediaViewer(key, mediaRepository) { viewerKey = null }
            }
        }
    }
}
