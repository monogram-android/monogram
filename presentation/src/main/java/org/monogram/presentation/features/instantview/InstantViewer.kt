@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.instantview

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFormat
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.WebPage
import org.monogram.domain.models.webapp.InstantViewModel
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.PageBlockCaption
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.repository.FileRepository
import org.monogram.domain.repository.MessageRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.chats.normalizeUrl
import org.monogram.presentation.features.instantview.components.AsyncImageWithDownload
import org.monogram.presentation.features.instantview.components.AsyncVideoWithDownload
import org.monogram.presentation.features.instantview.components.LocalFileRepository
import org.monogram.presentation.features.instantview.components.LocalOnUrlClick
import org.monogram.presentation.features.instantview.components.PageBlockCaptionView
import org.monogram.presentation.features.instantview.components.RichTextView
import org.monogram.presentation.features.instantview.components.containsText
import org.monogram.presentation.features.instantview.components.renderRichText
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.ImageViewer
import org.monogram.presentation.features.viewers.VideoViewer
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InstantViewer(
    url: String,
    messageRepository: MessageRepository,
    fileRepository: FileRepository,
    onDismiss: () -> Unit,
    onOpenWebView: (String) -> Unit
) {
    val urlStack = remember { mutableStateListOf(url) }
    val currentUrl = urlStack.lastOrNull() ?: url

    var instantView by remember(currentUrl) { mutableStateOf<InstantViewModel?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var textSizeMultiplier by remember { mutableFloatStateOf(1f) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var fullscreenMedia by remember { mutableStateOf<InstantViewFullscreenMedia?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val downloadUtils: IDownloadUtils = koinInject()
    val scope = rememberCoroutineScope()

    val openPhotoFullscreen: (WebPage.Photo, PageBlockCaption) -> Unit = { photo, caption ->
        scope.launch {
            fileRepository.resolvePathForViewer(photo.fileId, photo.path)?.let { path ->
                fullscreenMedia = InstantViewFullscreenMedia.Image(
                    path = path,
                    caption = caption.renderedTextOrNull()
                )
            }
        }
    }
    val openVideoFullscreen: (String?, Int, Boolean, String?) -> Unit =
        { path, fileId, supportsStreaming, caption ->
            scope.launch {
                val resolvedPath = fileRepository.resolvePathForViewer(fileId, path)
                if (!resolvedPath.isNullOrBlank() || (supportsStreaming && fileId != 0)) {
                    fullscreenMedia = InstantViewFullscreenMedia.Video(
                        path = resolvedPath.orEmpty(),
                        caption = caption,
                        fileId = fileId,
                        supportsStreaming = supportsStreaming
                    )
                }
            }
        }
    val openFileExternally: (String?, Int) -> Unit = { path, fileId ->
        scope.launch {
            fileRepository.resolvePathForViewer(fileId, path)?.let(downloadUtils::openFile)
        }
    }

    LaunchedEffect(url) {
        if (urlStack.isEmpty() || urlStack.first() != url) {
            urlStack.clear()
            urlStack.add(url)
        }
    }

    LaunchedEffect(currentUrl) {
        isLoading = true
        val result = messageRepository.getWebPageInstantView(currentUrl)
        if (result == null) {
            onOpenWebView(normalizeUrl(currentUrl))
            if (urlStack.size > 1) {
                urlStack.removeAt(urlStack.size - 1)
            } else {
                onDismiss()
            }
        } else {
            instantView = result
        }
        isLoading = false
    }

    BackHandler(onBack = {
        if (fullscreenMedia != null) {
            fullscreenMedia = null
        } else if (showSettingsMenu) {
            showSettingsMenu = false
        } else if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else if (urlStack.size > 1) {
            urlStack.removeAt(urlStack.size - 1)
        } else {
            onDismiss()
        }
    })

    CompositionLocalProvider(
        LocalOnUrlClick provides { newUrl -> urlStack.add(newUrl) },
        LocalFileRepository provides fileRepository
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                topBar = {
                    AnimatedContent(
                        targetState = isSearching,
                        transitionSpec = {
                            if (targetState) {
                                (fadeIn() + slideInVertically { -it / 4 }).togetherWith(fadeOut())
                            } else {
                                fadeIn().togetherWith(fadeOut() + slideOutVertically { -it / 4 })
                            }
                        },
                        label = "TopBarSearchTransition"
                    ) { active ->
                        if (active) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                SearchBar(
                                    inputField = {
                                        SearchBarDefaults.InputField(
                                            query = searchQuery,
                                            onQueryChange = { searchQuery = it },
                                            onSearch = {},
                                            expanded = false,
                                            onExpandedChange = {},
                                            placeholder = { Text(stringResource(R.string.instant_view_search_placeholder)) },
                                            leadingIcon = {
                                                IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                                                    Icon(
                                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                                        contentDescription = stringResource(R.string.instant_view_back)
                                                    )
                                                }
                                            },
                                            trailingIcon = {
                                                if (searchQuery.isNotEmpty()) {
                                                    IconButton(onClick = { searchQuery = "" }) {
                                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.instant_view_clear))
                                                    }
                                                }
                                            }
                                        )
                                    },
                                    expanded = false,
                                    onExpandedChange = {},
                                    shape = RoundedCornerShape(24.dp),
                                    colors = SearchBarDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        dividerColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {}
                            }
                        } else {
                            CenterAlignedTopAppBar(
                                title = {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    ) {
                                        Text(
                                            text = instantView?.pageBlocks?.filterIsInstance<PageBlock.Title>()
                                                ?.firstOrNull()?.title?.let { renderRichText(it).text }
                                                ?: stringResource(R.string.instant_view_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            modifier = Modifier.basicMarquee()
                                        )
                                        val displayUrl = instantView?.url ?: currentUrl
                                        Text(
                                            text = displayUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.basicMarquee()
                                        )
                                    }
                                },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        if (urlStack.size > 1) {
                                            urlStack.removeAt(urlStack.size - 1)
                                        } else {
                                            onDismiss()
                                        }
                                    }) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.instant_view_back))
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { showSettingsMenu = !showSettingsMenu }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.instant_view_more))
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                                ),
                                scrollBehavior = scrollBehavior
                            )
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    if (isLoading) {
                        ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (instantView != null) {
                        val blocks = remember(instantView, searchQuery) {
                            if (searchQuery.isEmpty()) {
                                instantView!!.pageBlocks
                            } else {
                                instantView!!.pageBlocks.filter { it.containsText(searchQuery) }
                            }
                        }

                        SelectionContainer {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                itemsIndexed(
                                    items = blocks,
                                    key = { index, block -> instantViewBlockKey(index, block) }
                                ) { _, block ->
                                    InstantViewBlock(
                                        block = block,
                                        textSizeMultiplier = textSizeMultiplier,
                                        searchQuery = searchQuery,
                                        onOpenPhotoFullscreen = openPhotoFullscreen,
                                        onOpenVideoFullscreen = openVideoFullscreen,
                                        onOpenFileExternally = openFileExternally
                                    )
                                }
                                item {
                                    Spacer(modifier = Modifier.height(48.dp))
                                    if (instantView!!.viewCount > 0) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Rounded.Visibility,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.instant_view_views_count, instantView!!.viewCount),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(32.dp))
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showSettingsMenu,
                enter = fadeIn(tween(150)) + scaleIn(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
                    initialScale = 0.8f,
                    transformOrigin = TransformOrigin(1f, 0f)
                ),
                exit = fadeOut(tween(150)) + scaleOut(
                    animationSpec = tween(150),
                    targetScale = 0.9f,
                    transformOrigin = TransformOrigin(1f, 0f)
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 56.dp, end = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showSettingsMenu = false },
                    contentAlignment = Alignment.TopEnd
                ) {
                    ViewerSettingsDropdown {
                        MenuOptionRow(
                            icon = Icons.Rounded.ContentCopy,
                            title = stringResource(R.string.instant_view_copy_link),
                            onClick = {
                                clipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText("", currentUrl)
                                )
                                showSettingsMenu = false
                            }
                        )
                        MenuOptionRow(
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            title = stringResource(R.string.instant_view_open_in_browser),
                            onClick = {
                                onOpenWebView(normalizeUrl(currentUrl))
                                showSettingsMenu = false
                            }
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.Search,
                            title = stringResource(R.string.instant_view_search),
                            onClick = {
                                isSearching = true
                                showSettingsMenu = false
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                        MenuOptionRow(
                            icon = Icons.Rounded.TextFormat,
                            title = stringResource(R.string.instant_view_text_size),
                            value = "${(textSizeMultiplier * 100).toInt()}%",
                            onClick = {
                                textSizeMultiplier = when (textSizeMultiplier) {
                                    0.75f -> 1.0f
                                    1.0f -> 1.25f
                                    1.25f -> 1.5f
                                    else -> 0.75f
                                }
                            }
                        )
                    }
                }
            }

            when (val media = fullscreenMedia) {
                is InstantViewFullscreenMedia.Image -> {
                    ImageViewer(
                        images = listOf(media.path),
                        onDismiss = { fullscreenMedia = null },
                        captions = listOf(media.caption),
                        downloadUtils = downloadUtils,
                        showImageNumber = false
                    )
                }

                is InstantViewFullscreenMedia.Video -> {
                    VideoViewer(
                        path = media.path,
                        onDismiss = { fullscreenMedia = null },
                        caption = media.caption,
                        fileId = media.fileId,
                        supportsStreaming = media.supportsStreaming,
                        downloadUtils = downloadUtils
                    )
                }

                null -> Unit
            }
        }
    }
}

private fun instantViewBlockKey(index: Int, block: PageBlock): String {
    return "${block::class.qualifiedName}:${block.hashCode()}:$index"
}

@Composable
fun InstantViewBlock(
    block: PageBlock,
    textSizeMultiplier: Float,
    searchQuery: String = "",
    onOpenPhotoFullscreen: (WebPage.Photo, PageBlockCaption) -> Unit = { _, _ -> },
    onOpenVideoFullscreen: (String?, Int, Boolean, String?) -> Unit = { _, _, _, _ -> },
    onOpenFileExternally: (String?, Int) -> Unit = { _, _ -> }
) {
    val onUrlClick = LocalOnUrlClick.current
    LocalUriHandler.current
    when (block) {
        is PageBlock.Title -> RichTextView(
            richText = block.title,
            style = MaterialTheme.typography.displaySmall,
            textSizeMultiplier = textSizeMultiplier,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        is PageBlock.Subtitle -> RichTextView(
            richText = block.subtitle,
            style = MaterialTheme.typography.headlineSmall,
            textSizeMultiplier = textSizeMultiplier,
            color = MaterialTheme.colorScheme.primary
        )

        is PageBlock.AuthorDate -> {
            val dateStr = if (block.publishDate > 0) {
                val date = Date(block.publishDate.toLong() * 1000)
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
            } else null

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RichTextView(
                    richText = block.author,
                    style = MaterialTheme.typography.labelLarge,
                    textSizeMultiplier = textSizeMultiplier,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (dateStr != null) {
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = MaterialTheme.typography.labelLarge.fontSize * textSizeMultiplier
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is PageBlock.Header -> RichTextView(
            richText = block.header,
            style = MaterialTheme.typography.titleLarge,
            textSizeMultiplier = textSizeMultiplier,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.onSurface
        )

        is PageBlock.Subheader -> RichTextView(
            richText = block.subheader,
            style = MaterialTheme.typography.titleMedium,
            textSizeMultiplier = textSizeMultiplier,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurface
        )

        is PageBlock.Kicker -> RichTextView(
            richText = block.kicker,
            style = MaterialTheme.typography.labelLarge,
            textSizeMultiplier = textSizeMultiplier,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        is PageBlock.Paragraph -> RichTextView(
            richText = block.text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 32.sp),
            textSizeMultiplier = textSizeMultiplier,
            color = MaterialTheme.colorScheme.onSurface
        )

        is PageBlock.Preformatted -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp)
        ) {
            RichTextView(
                richText = block.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                textSizeMultiplier = textSizeMultiplier,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        is PageBlock.Footer -> RichTextView(
            richText = block.footer,
            style = MaterialTheme.typography.bodySmall,
            textSizeMultiplier = textSizeMultiplier,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is PageBlock.Divider -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )

        is PageBlock.PhotoBlock -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenPhotoFullscreen(block.photo, block.caption) }
                ) {
                    AsyncImageWithDownload(
                        path = block.photo.path,
                        fileId = block.photo.fileId,
                        minithumbnail = block.photo.minithumbnail,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 8.dp))
            }
        }

        is PageBlock.VideoBlock -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                InstantViewPlayablePreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(block.video.width.toFloat() / block.video.height.toFloat())
                        .clip(RoundedCornerShape(12.dp)),
                    stateKey = "video:${block.hashCode()}",
                    durationSeconds = block.video.duration,
                    previewPath = block.video.thumbnailPath,
                    previewFileId = block.video.thumbnailFileId,
                    previewMiniThumbnail = block.video.minithumbnail,
                    mediaPath = block.video.path,
                    mediaFileId = block.video.fileId,
                    shouldLoop = block.isLooped,
                    supportsAudioToggle = true,
                    allowScrubbing = true,
                    onOpenFullscreen = {
                        onOpenVideoFullscreen(
                            block.video.path,
                            block.video.fileId,
                            block.video.supportsStreaming,
                            block.caption.renderedTextOrNull()
                        )
                    }
                )
                PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 8.dp))
            }
        }

        is PageBlock.AnimationBlock -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                InstantViewPlayablePreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(block.animation.width.toFloat() / block.animation.height.toFloat())
                        .clip(RoundedCornerShape(12.dp)),
                    stateKey = "animation:${block.hashCode()}",
                    durationSeconds = block.animation.duration,
                    previewPath = block.animation.thumbnailPath,
                    previewFileId = block.animation.thumbnailFileId,
                    previewMiniThumbnail = block.animation.minithumbnail,
                    mediaPath = block.animation.path,
                    mediaFileId = block.animation.fileId,
                    shouldLoop = true,
                    supportsAudioToggle = false,
                    allowScrubbing = true,
                    initiallyActive = block.needAutoplay,
                    onOpenFullscreen = {
                        onOpenVideoFullscreen(
                            block.animation.path,
                            block.animation.fileId,
                            false,
                            block.caption.renderedTextOrNull()
                        )
                    }
                )
                PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 8.dp))
            }
        }

        is PageBlock.AudioBlock -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp
            ) {
                Column {
                    ListItem(
                        modifier = Modifier.clickable {
                            onOpenFileExternally(block.audio.path, block.audio.fileId)
                        },
                        headlineContent = {
                            Text(block.audio.title ?: stringResource(R.string.instant_view_audio), fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(block.audio.performer ?: stringResource(R.string.instant_view_unknown_artist))
                        },
                        leadingContent = {
                            IconButton(
                                onClick = {
                                    onOpenFileExternally(
                                        block.audio.path,
                                        block.audio.fileId
                                    )
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.instant_view_play))
                            }
                        },
                        trailingContent = {
                            val duration = block.audio.duration
                            Text(
                                "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    PageBlockCaptionView(
                        block.caption,
                        textSizeMultiplier,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        is PageBlock.Embedded -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (block.width > 0 && block.height > 0) block.width.toFloat() / block.height.toFloat() else 16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onUrlClick(normalizeUrl(block.url)) },
                    contentAlignment = Alignment.Center
                ) {
                    block.posterPhoto?.let { photo ->
                        AsyncImageWithDownload(
                            path = photo.path,
                            fileId = photo.fileId,
                            minithumbnail = photo.minithumbnail,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.instant_view_open),
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            .padding(8.dp),
                        tint = Color.White
                    )
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 8.dp))
            }
        }

        is PageBlock.EmbeddedPost -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        block.authorPhoto?.let { photo ->
                            AsyncImageWithDownload(
                                path = photo.path,
                                fileId = photo.fileId,
                                minithumbnail = photo.minithumbnail,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { onOpenPhotoFullscreen(photo, block.caption) },
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column {
                            Text(
                                text = block.author,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (block.date > 0) {
                                val date = Date(block.date.toLong() * 1000)
                                val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
                                Text(
                                    text = dateStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    block.pageBlocks.forEach { innerBlock ->
                        InstantViewBlock(
                            block = innerBlock,
                            textSizeMultiplier = textSizeMultiplier,
                            searchQuery = searchQuery,
                            onOpenPhotoFullscreen = onOpenPhotoFullscreen,
                            onOpenVideoFullscreen = onOpenVideoFullscreen,
                            onOpenFileExternally = onOpenFileExternally
                        )
                    }
                    PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        is PageBlock.BlockQuote -> {
            Row(modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    RichTextView(
                        richText = block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textSizeMultiplier = textSizeMultiplier,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (block.credit !is RichText.Plain || (block.credit as RichText.Plain).text.isNotEmpty()) {
                        RichTextView(
                            richText = block.credit,
                            style = MaterialTheme.typography.labelMedium,
                            textSizeMultiplier = textSizeMultiplier,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        is PageBlock.PullQuote -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RichTextView(
                    richText = block.text,
                    style = MaterialTheme.typography.headlineSmall,
                    textSizeMultiplier = textSizeMultiplier,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (block.credit !is RichText.Plain || (block.credit as RichText.Plain).text.isNotEmpty()) {
                    RichTextView(
                        richText = block.credit,
                        style = MaterialTheme.typography.labelMedium,
                        textSizeMultiplier = textSizeMultiplier,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }

        is PageBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = if (item.label.isNotEmpty()) "${item.label} " else "• ",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * textSizeMultiplier
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.pageBlocks.forEach { innerBlock ->
                            InstantViewBlock(
                                block = innerBlock,
                                textSizeMultiplier = textSizeMultiplier,
                                searchQuery = searchQuery,
                                onOpenPhotoFullscreen = onOpenPhotoFullscreen,
                                onOpenVideoFullscreen = onOpenVideoFullscreen,
                                onOpenFileExternally = onOpenFileExternally
                            )
                        }
                    }
                }
            }
        }

        is PageBlock.Cover -> InstantViewBlock(
            block = block.cover,
            textSizeMultiplier = textSizeMultiplier,
            searchQuery = searchQuery,
            onOpenPhotoFullscreen = onOpenPhotoFullscreen,
            onOpenVideoFullscreen = onOpenVideoFullscreen,
            onOpenFileExternally = onOpenFileExternally
        )
        is PageBlock.Details -> {
            val shouldForceOpen =
                searchQuery.isNotBlank() && block.pageBlocks.any { it.containsText(searchQuery) }
            var isOpen by rememberSaveable(block.hashCode()) { mutableStateOf(block.isOpen || shouldForceOpen) }

            LaunchedEffect(shouldForceOpen) {
                if (shouldForceOpen) isOpen = true
            }

            Surface(
                modifier = Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessLow)),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isOpen = !isOpen }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            RichTextView(
                                richText = block.header,
                                style = MaterialTheme.typography.titleMedium,
                                textSizeMultiplier = textSizeMultiplier,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            imageVector = if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (isOpen) stringResource(R.string.instant_view_collapse) else stringResource(R.string.instant_view_expand),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isOpen) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            block.pageBlocks.forEach { innerBlock ->
                                InstantViewBlock(
                                    block = innerBlock,
                                    textSizeMultiplier = textSizeMultiplier,
                                    searchQuery = searchQuery,
                                    onOpenPhotoFullscreen = onOpenPhotoFullscreen,
                                    onOpenVideoFullscreen = onOpenVideoFullscreen,
                                    onOpenFileExternally = onOpenFileExternally
                                )
                            }
                        }
                    }
                }
            }
        }

        is PageBlock.Collage -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.pageBlocks.forEach { innerBlock ->
                    InstantViewBlock(
                        block = innerBlock,
                        textSizeMultiplier = textSizeMultiplier,
                        searchQuery = searchQuery,
                        onOpenPhotoFullscreen = onOpenPhotoFullscreen,
                        onOpenVideoFullscreen = onOpenVideoFullscreen,
                        onOpenFileExternally = onOpenFileExternally
                    )
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier)
            }
        }

        is PageBlock.Slideshow -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.pageBlocks.forEach { innerBlock ->
                    InstantViewBlock(
                        block = innerBlock,
                        textSizeMultiplier = textSizeMultiplier,
                        searchQuery = searchQuery,
                        onOpenPhotoFullscreen = onOpenPhotoFullscreen,
                        onOpenVideoFullscreen = onOpenVideoFullscreen,
                        onOpenFileExternally = onOpenFileExternally
                    )
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier)
            }
        }

        is PageBlock.Table -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (block.caption !is RichText.Plain || (block.caption as RichText.Plain).text.isNotEmpty()) {
                        RichTextView(
                            richText = block.caption,
                            style = MaterialTheme.typography.titleSmall,
                            textSizeMultiplier = textSizeMultiplier,
                            modifier = Modifier.padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    block.cells.forEachIndexed { index, row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                Box(
                                    modifier = Modifier
                                        .widthIn(min = 120.dp * cell.colspan)
                                        .border(
                                            width = if (block.isBordered) 1.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                        .background(
                                            if (cell.isHeader) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                                        )
                                        .padding(8.dp)
                                ) {
                                    RichTextView(
                                        richText = cell.text,
                                        style = if (cell.isHeader) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                                        textSizeMultiplier = textSizeMultiplier,
                                        fontWeight = if (cell.isHeader) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        if (index < block.cells.size - 1 && !block.isBordered) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        is PageBlock.RelatedArticles -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RichTextView(
                    richText = block.header,
                    style = MaterialTheme.typography.titleMedium,
                    textSizeMultiplier = textSizeMultiplier,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                block.articles.forEach { article ->
                    Card(
                        onClick = { onUrlClick(normalizeUrl(article.url)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = article.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontSize = MaterialTheme.typography.titleSmall.fontSize * textSizeMultiplier
                                    ),
                                    maxLines = 2,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            supportingContent = if (article.description.isNotEmpty()) {
                                {
                                    Text(
                                        text = article.description,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = MaterialTheme.typography.bodySmall.fontSize * textSizeMultiplier
                                        ),
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else null,
                            leadingContent = article.photo?.let { photo ->
                                {
                                    AsyncImageWithDownload(
                                        path = photo.path,
                                        fileId = photo.fileId,
                                        minithumbnail = photo.minithumbnail,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                onOpenPhotoFullscreen(
                                                    photo,
                                                    PageBlockCaption(
                                                        RichText.Plain(article.title),
                                                        RichText.Plain(article.author)
                                                    )
                                                )
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }

        is PageBlock.MapBlock -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.instant_view_map_prefix, block.location.latitude, block.location.longitude))
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 8.dp))
            }
        }

        is PageBlock.Anchor -> { /* Invisible anchor */
        }

        is PageBlock.ChatLink -> {
            FilledTonalButton(
                onClick = { onUrlClick("https://t.me/${block.username}") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private sealed interface InstantViewFullscreenMedia {
    data class Image(val path: String, val caption: String?) : InstantViewFullscreenMedia
    data class Video(
        val path: String,
        val caption: String?,
        val fileId: Int,
        val supportsStreaming: Boolean
    ) : InstantViewFullscreenMedia
}

private fun PageBlockCaption.renderedTextOrNull(): String? {
    val text = buildString {
        renderRichText(this@renderedTextOrNull.text).text.takeIf { it.isNotBlank() }?.let(::append)
        renderRichText(this@renderedTextOrNull.credit).text.takeIf { it.isNotBlank() }
            ?.let { credit ->
                if (isNotEmpty()) append("\n")
                append(credit)
            }
    }
    return text.ifBlank { null }
}

@Composable
private fun InstantViewPlayablePreview(
    stateKey: String,
    durationSeconds: Int,
    previewPath: String?,
    previewFileId: Int,
    previewMiniThumbnail: ByteArray?,
    mediaPath: String?,
    mediaFileId: Int,
    modifier: Modifier = Modifier,
    shouldLoop: Boolean = true,
    supportsAudioToggle: Boolean = false,
    allowScrubbing: Boolean = true,
    initiallyActive: Boolean = false,
    onOpenFullscreen: () -> Unit
) {
    var isActive by rememberSaveable(stateKey) { mutableStateOf(initiallyActive) }
    var isPaused by rememberSaveable(stateKey) { mutableStateOf(false) }
    var isMuted by rememberSaveable(stateKey) { mutableStateOf(true) }
    var showControls by rememberSaveable(stateKey) { mutableStateOf(true) }
    var playbackPositionMs by rememberSaveable(stateKey) { mutableLongStateOf(0L) }
    var durationMs by rememberSaveable(stateKey) { mutableLongStateOf(durationSeconds * 1000L) }
    var hasEnded by rememberSaveable(stateKey) { mutableStateOf(false) }
    var hasShownPlaybackUi by rememberSaveable(stateKey) { mutableStateOf(initiallyActive) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    var visibleFraction by remember { mutableFloatStateOf(1f) }
    val view = LocalView.current
    val isVisibleEnough = visibleFraction >= 0.35f
    val effectiveAnimate = isActive && !isPaused && !hasEnded && isVisibleEnough && !isScrubbing

    LaunchedEffect(isActive, isPaused, hasEnded, showControls, isScrubbing, isVisibleEnough) {
        if (isActive && !isPaused && !hasEnded && showControls && !isScrubbing && isVisibleEnough) {
            delay(2200)
            showControls = false
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val windowHeight = view.height.toFloat().coerceAtLeast(1f)
                val visibleTop = bounds.top.coerceAtLeast(0f)
                val visibleBottom = bounds.bottom.coerceAtMost(windowHeight)
                val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0f)
                val totalHeight = bounds.height.coerceAtLeast(1f)
                visibleFraction = (visibleHeight / totalHeight).coerceIn(0f, 1f)
            }
            .pointerInput(isActive, isPaused) {
                detectTapGestures(
                    onTap = {
                        if (!isActive) {
                            isActive = true
                            isPaused = false
                            hasEnded = false
                            showControls = true
                            hasShownPlaybackUi = true
                        } else {
                            showControls = !showControls
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (isActive) {
            AsyncVideoWithDownload(
                path = mediaPath,
                fileId = mediaFileId,
                modifier = Modifier.fillMaxSize(),
                shouldLoop = shouldLoop,
                animate = effectiveAnimate,
                volume = if (supportsAudioToggle && !isMuted) 1f else 0f,
                startPositionMs = playbackPositionMs,
                onProgressUpdate = { playbackPositionMs = it },
                onDurationKnown = { durationMs = it.coerceAtLeast(0L) },
                onPlaybackEnded = {
                    hasEnded = true
                    isPaused = true
                    showControls = true
                    playbackPositionMs = durationMs
                },
                reportProgress = true,
                contentScale = ContentScale.FillWidth
            )
        } else {
            AsyncImageWithDownload(
                path = previewPath,
                fileId = previewFileId,
                minithumbnail = previewMiniThumbnail,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (!isActive) Color.Black.copy(alpha = 0.22f)
                    else if (showControls || isPaused) Color.Black.copy(alpha = 0.28f)
                    else Color.Transparent
                )
        )

        if (!hasShownPlaybackUi) {
            InstantViewMediaBadge(
                label = formatMediaDuration((durationMs / 1000L).toInt()),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }

        AnimatedVisibility(
            visible = !isActive || showControls || isPaused,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onOpenFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = stringResource(R.string.instant_view_open),
                        tint = Color.White
                    )
                }

                if (isActive) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 12.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (supportsAudioToggle) {
                            IconButton(
                                onClick = { isMuted = !isMuted },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                    contentDescription = if (isMuted) "Unmute preview" else "Mute preview",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                FilledIconButton(
                    onClick = {
                        if (!isActive) {
                            isActive = true
                            isPaused = false
                            hasEnded = false
                            if (playbackPositionMs >= durationMs && durationMs > 0L) {
                                playbackPositionMs = 0L
                            }
                        } else if (hasEnded) {
                            playbackPositionMs = 0L
                            hasEnded = false
                            isPaused = false
                        } else {
                            isPaused = !isPaused
                        }
                        showControls = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = when {
                            hasEnded -> Icons.Rounded.Replay
                            isActive && !isPaused -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = when {
                            hasEnded -> "Replay preview"
                            isActive && !isPaused -> "Pause preview"
                            else -> "Play preview"
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val displayPositionMs =
                            if (isScrubbing) (scrubProgress * durationMs.toFloat()).toLong() else playbackPositionMs
                        InstantViewMediaBadge(
                            label = formatMediaDuration((displayPositionMs / 1000L).toInt())
                        )
                        InstantViewMediaBadge(label = formatMediaDuration((durationMs / 1000L).toInt()))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (allowScrubbing) {
                        Slider(
                            value = if (isScrubbing) scrubProgress else previewProgress(
                                playbackPositionMs,
                                durationMs
                            ),
                            onValueChange = {
                                isScrubbing = true
                                scrubProgress = it
                                showControls = true
                            },
                            onValueChangeFinished = {
                                playbackPositionMs = (durationMs * scrubProgress).toLong()
                                hasEnded = false
                                isPaused = false
                                isScrubbing = false
                            },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                            )
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { previewProgress(playbackPositionMs, durationMs) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.28f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstantViewMediaBadge(label: String, modifier: Modifier = Modifier) {
    if (label.isBlank()) return

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun previewProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

private fun formatMediaDuration(durationSeconds: Int): String {
    if (durationSeconds <= 0) return ""
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private suspend fun FileRepository.resolvePathForViewer(
    fileId: Int,
    initialPath: String?
): String? {
    if (!initialPath.isNullOrBlank()) return initialPath
    if (fileId == 0) return null
    val cachedPath = getFilePath(fileId)
    if (!cachedPath.isNullOrBlank()) return cachedPath

    downloadFile(fileId)

    val completedPath = withTimeoutOrNull(60_000L) {
        fileDownloadFlow
            .filterIsInstance<FileDownloadEvent.Completed>()
            .filter { it.fileId == fileId }
            .mapNotNull { event -> event.path.takeIf { it.isNotEmpty() } }
            .first()
    }

    return completedPath ?: getFilePath(fileId)
}
