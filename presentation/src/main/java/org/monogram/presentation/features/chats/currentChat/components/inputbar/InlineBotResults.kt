package org.monogram.presentation.features.chats.currentChat.components.inputbar

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.InlineQueryResultModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.repository.InlineBotResultsModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.namespacedCacheKey

private enum class InlineResultsMode {
    Loading,
    Empty,
    Grid,
    List
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineBotResults(
    inlineBotResults: InlineBotResultsModel?,
    isInlineMode: Boolean,
    isLoading: Boolean,
    onResultClick: (String) -> Unit,
    onSwitchPmClick: (String) -> Unit,
    onLoadMore: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val results = inlineBotResults?.results.orEmpty()
    val nextOffset = inlineBotResults?.nextOffset?.takeIf { it.isNotBlank() }
    val mode = when {
        isLoading && results.isEmpty() -> InlineResultsMode.Loading
        results.isEmpty() -> InlineResultsMode.Empty
        results.shouldUseMediaGrid() -> InlineResultsMode.Grid
        else -> InlineResultsMode.List
    }

    if (!isInlineMode && results.isEmpty() && inlineBotResults?.switchPmText == null && !isLoading) {
        return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val switchPmText = inlineBotResults?.switchPmText
            val switchPmParameter = inlineBotResults?.switchPmParameter
            if (switchPmText != null && switchPmParameter != null) {
                SwitchPmButton(
                    text = switchPmText,
                    onClick = { onSwitchPmClick(switchPmParameter) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                    },
                    label = "InlineResultsMode"
                ) { currentMode ->
                    when (currentMode) {
                        InlineResultsMode.Loading -> InlineLoadingPlaceholder()
                        InlineResultsMode.Empty -> EmptyResultsPlaceholder()
                        InlineResultsMode.Grid -> InlineMediaResultsGrid(
                            results = results,
                            nextOffset = nextOffset,
                            isLoading = isLoading,
                            onResultClick = onResultClick,
                            onLoadMore = onLoadMore
                        )

                        InlineResultsMode.List -> InlineResultsList(
                            results = results,
                            nextOffset = nextOffset,
                            isLoading = isLoading,
                            onResultClick = onResultClick,
                            onLoadMore = onLoadMore
                        )
                    }
                }

                if (isLoading && results.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineMediaResultsGrid(
    results: List<InlineQueryResultModel>,
    nextOffset: String?,
    isLoading: Boolean,
    onResultClick: (String) -> Unit,
    onLoadMore: (String) -> Unit
) {
    val gridState = rememberLazyGridState()
    val isStickerGrid = results.firstOrNull()?.normalizedType() == "sticker"

    var lastRequestedOffset by remember { mutableStateOf<String?>(null) }
    val shouldLoadMore by remember(results, isLoading, nextOffset) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= results.size - 4 && !isLoading && nextOffset != null
        }
    }

    LaunchedEffect(shouldLoadMore, nextOffset) {
        val offset = nextOffset
        if (shouldLoadMore && offset != null && offset != lastRequestedOffset) {
            lastRequestedOffset = offset
            onLoadMore(offset)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minCellWidth = if (isStickerGrid) 74.dp else 112.dp
        val maxColumns = if (isStickerGrid) 8 else 5
        val columns = (maxWidth / minCellWidth).toInt().coerceIn(2, maxColumns)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
            state = gridState
        ) {
            itemsIndexed(results, key = { index, item -> "${item.type}:${item.id}:$index" }) { index, result ->
                InlineResultAnimatedItem(index = index) {
                    InlineBotMediaItem(
                        result = result,
                        onClick = { onResultClick(result.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineResultsList(
    results: List<InlineQueryResultModel>,
    nextOffset: String?,
    isLoading: Boolean,
    onResultClick: (String) -> Unit,
    onLoadMore: (String) -> Unit
) {
    val listState = rememberLazyListState()
    var lastRequestedOffset by remember { mutableStateOf<String?>(null) }
    val shouldLoadMore by remember(results, isLoading, nextOffset) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= results.size - 4 && !isLoading && nextOffset != null
        }
    }

    LaunchedEffect(shouldLoadMore, nextOffset) {
        val offset = nextOffset
        if (shouldLoadMore && offset != null && offset != lastRequestedOffset) {
            lastRequestedOffset = offset
            onLoadMore(offset)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp),
        state = listState
    ) {
        itemsIndexed(results, key = { index, item -> "${item.type}:${item.id}:$index" }) { index, result ->
            InlineResultAnimatedItem(index = index) {
                InlineBotResultItem(
                    result = result,
                    onClick = { onResultClick(result.id) }
                )
            }
            if (index < results.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
private fun EmptyResultsPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.no_results_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InlineLoadingPlaceholder() {
    val transition = rememberInfiniteTransition(label = "InlineLoading")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "InlineLoadingAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(4) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun InlineResultAnimatedItem(
    index: Int,
    content: @Composable () -> Unit
) {
    var visible by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180, delayMillis = (index.coerceAtMost(6) * 18))) +
                scaleIn(animationSpec = tween(220), initialScale = 0.97f)
    ) {
        content()
    }
}

@Composable
private fun SwitchPmButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun rememberMediaModel(result: InlineQueryResultModel): Any? {
    val context = LocalContext.current
    val contentPath = when (val content = result.content) {
        is MessageContent.Photo -> content.path
        is MessageContent.Video -> content.path
        is MessageContent.Gif -> content.path
        is MessageContent.Sticker -> content.path
        is MessageContent.VideoNote -> content.path
        is MessageContent.Document -> content.path
        else -> null
    }

    return remember(contentPath, result.thumbUrl) {
        val data = if (!contentPath.isNullOrBlank()) contentPath else result.thumbUrl
        if (data == null) return@remember null
        val cacheKey = namespacedCacheKey("inline_result:${result.id}", data)

        ImageRequest.Builder(context)
            .data(data)
            .apply {
                cacheKey?.let {
                    memoryCacheKey(it)
                    diskCacheKey(it)
                }
            }
            .crossfade(true)
            .build()
    }
}

@Composable
private fun InlineBotMediaItem(
    result: InlineQueryResultModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val aspectRatio = remember(result.width, result.height, result.normalizedType()) {
        val type = result.normalizedType()
        if (type == "sticker") {
            1f
        } else if (result.width > 0 && result.height > 0) {
            (result.width.toFloat() / result.height.toFloat()).coerceIn(0.7f, 1.6f)
        } else {
            1f
        }
    }
    val type = result.normalizedType()
    val mediaModel = rememberMediaModel(result)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        InlineMediaPlaceholder(
            type = type,
            modifier = Modifier.matchParentSize()
        )

        if (mediaModel != null) {
            AsyncImage(
                model = mediaModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (type == "sticker") ContentScale.Fit else ContentScale.Crop
            )
        }

        if (type == "gif" || type == "video") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (type == "gif") stringResource(R.string.media_type_gif) else stringResource(R.string.media_type_video),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun InlineBotResultItem(
    result: InlineQueryResultModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mediaModel = rememberMediaModel(result)
    val title = result.title?.takeIf { it.isNotBlank() } ?: resultTypeLabel(result)
    val description = result.description?.takeIf { it.isNotBlank() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (mediaModel != null || result.isVisualResult()) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                InlineMediaPlaceholder(
                    type = result.normalizedType(),
                    modifier = Modifier.matchParentSize()
                )
                if (mediaModel != null) {
                    AsyncImage(
                        model = mediaModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InlineMediaPlaceholder(
    type: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "InlinePlaceholder")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950),
            repeatMode = RepeatMode.Reverse
        ),
        label = "InlinePlaceholderAlpha"
    )

    val icon = if (type == "video" || type == "gif") {
        Icons.Rounded.PlayArrow
    } else {
        Icons.Rounded.Image
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun resultTypeLabel(result: InlineQueryResultModel): String {
    return when (result.normalizedType()) {
        "photo" -> stringResource(R.string.media_type_photo)
        "video" -> stringResource(R.string.media_type_video)
        "sticker" -> stringResource(R.string.media_type_sticker)
        "voice" -> stringResource(R.string.media_type_voice)
        "gif" -> stringResource(R.string.media_type_gif)
        "location" -> stringResource(R.string.media_type_location)
        else -> stringResource(R.string.media_type_message)
    }
}

private fun List<InlineQueryResultModel>.shouldUseMediaGrid(): Boolean {
    if (isEmpty()) return false
    val hasNamedMedia = any { result ->
        result.isVisualResult() && (!result.title.isNullOrBlank() || !result.description.isNullOrBlank())
    }
    if (hasNamedMedia) return false

    val visualItems = count { it.isVisualResult() }
    return visualItems >= size * 0.7f
}

private fun InlineQueryResultModel.isVisualResult(): Boolean {
    return when (normalizedType()) {
        "photo", "video", "gif", "sticker" -> true
        else -> false
    }
}

private fun InlineQueryResultModel.normalizedType(): String {
    val source = type.lowercase()
    return when {
        "sticker" in source -> "sticker"
        "animation" in source || "gif" in source -> "gif"
        "photo" in source -> "photo"
        "video" in source -> "video"
        "voice" in source -> "voice"
        "location" in source || "venue" in source -> "location"
        else -> "message"
    }
}
