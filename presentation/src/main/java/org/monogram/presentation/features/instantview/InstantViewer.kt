package org.monogram.presentation.features.instantview

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.webapp.InstantViewModel
import org.monogram.domain.models.webapp.PageBlock
import org.monogram.domain.models.webapp.RichText
import org.monogram.domain.repository.MessageRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.chats.normalizeUrl
import org.monogram.presentation.features.instantview.components.*
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InstantViewer(
    url: String,
    videoPlayerPool: VideoPlayerPool,
    messageRepository: MessageRepository,
    onDismiss: () -> Unit,
    onOpenWebView: (String) -> Unit
) {
    val urlStack = remember { mutableStateListOf(url) }
    val currentUrl = urlStack.lastOrNull() ?: url

    var instantView by remember(currentUrl) { mutableStateOf<InstantViewModel?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var textSizeMultiplier by remember { mutableStateOf(1f) }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSettingsMenu by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

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
        if (showSettingsMenu) {
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
        LocalMessageRepository provides messageRepository
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
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
                                items(blocks) { block ->
                                    InstantViewBlock(block, textSizeMultiplier, videoPlayerPool)
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
                                clipboardManager.setText(AnnotatedString(currentUrl))
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
        }
    }
}

@Composable
fun InstantViewBlock(block: PageBlock, textSizeMultiplier: Float, videoPlayerPool: VideoPlayerPool) {
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
                Box(modifier = Modifier.clip(RoundedCornerShape(12.dp))) {
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
            var isPlaying by remember { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(block.video.width.toFloat() / block.video.height.toFloat())
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { isPlaying = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        AsyncVideoWithDownload(
                            path = block.video.path,
                            fileId = block.video.fileId,
                            modifier = Modifier.fillMaxSize(),
                            shouldLoop = block.isLooped,
                            contentScale = ContentScale.FillWidth,
                            videoPlayerPool = videoPlayerPool
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.instant_view_play_video),
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                .padding(12.dp),
                            tint = Color.White
                        )
                    }
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier, modifier = Modifier.padding(top = 8.dp))
            }
        }

        is PageBlock.AnimationBlock -> {
            var isPlaying by remember { mutableStateOf(block.needAutoplay) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(block.animation.width.toFloat() / block.animation.height.toFloat())
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { isPlaying = !isPlaying },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPlaying) {
                        AsyncVideoWithDownload(
                            path = block.animation.path,
                            fileId = block.animation.fileId,
                            modifier = Modifier.fillMaxSize(),
                            shouldLoop = true,
                            contentScale = ContentScale.FillWidth,
                            videoPlayerPool = videoPlayerPool
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.instant_view_play_animation),
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                .padding(8.dp),
                            tint = Color.White
                        )
                    }
                }
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
                        headlineContent = {
                            Text(block.audio.title ?: stringResource(R.string.instant_view_audio), fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(block.audio.performer ?: stringResource(R.string.instant_view_unknown_artist))
                        },
                        leadingContent = {
                            IconButton(
                                onClick = { /* TODO: Play audio */ },
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
                                    .clip(CircleShape),
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
                        InstantViewBlock(innerBlock, textSizeMultiplier, videoPlayerPool)
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
                            InstantViewBlock(innerBlock, textSizeMultiplier, videoPlayerPool)
                        }
                    }
                }
            }
        }

        is PageBlock.Cover -> InstantViewBlock(block.cover, textSizeMultiplier, videoPlayerPool)
        is PageBlock.Details -> {
            var isOpen by remember { mutableStateOf(block.isOpen) }
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
                                InstantViewBlock(innerBlock, textSizeMultiplier, videoPlayerPool)
                            }
                        }
                    }
                }
            }
        }

        is PageBlock.Collage -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.pageBlocks.forEach { innerBlock ->
                    InstantViewBlock(innerBlock, textSizeMultiplier, videoPlayerPool)
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier)
            }
        }

        is PageBlock.Slideshow -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.pageBlocks.forEach { innerBlock ->
                    InstantViewBlock(innerBlock, textSizeMultiplier, videoPlayerPool)
                }
                PageBlockCaptionView(block.caption, textSizeMultiplier)
            }
        }

        is PageBlock.Table -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                                        .weight(cell.colspan.toFloat())
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
                                            .clip(RoundedCornerShape(8.dp)),
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
