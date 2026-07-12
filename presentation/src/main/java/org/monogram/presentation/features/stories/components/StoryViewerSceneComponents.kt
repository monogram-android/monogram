package org.monogram.presentation.features.stories

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import org.monogram.domain.models.stories.StoryAreaTypeModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.presentation.R
import org.monogram.presentation.features.profile.components.StatisticsViewer
import java.io.File

@Composable
internal fun StoryViewerScaffoldComponent(
    state: StoriesHostComponent.State,
    component: StoriesHostComponent,
    story: StoryModel?
) {
    val context = LocalContext.current
    val downloadUtils: org.monogram.presentation.core.util.IDownloadUtils =
        org.koin.compose.koinInject()
    var currentProgress by remember(story?.id) { mutableFloatStateOf(0f) }
    var restartPlaybackToken by remember(story?.id) { mutableStateOf(0) }
    var isVideoMuted by remember(story?.id) { mutableStateOf(false) }
    var isVideoPaused by remember(story?.id) { mutableStateOf(false) }
    var isVideoBuffering by remember(story?.id) { mutableStateOf(story?.media?.type == StoryMediaType.VIDEO) }
    var isVideoPlaying by remember(story?.id) { mutableStateOf(false) }
    var isLinksSheetVisible by remember(story?.id) { mutableStateOf(false) }
    var playerView by remember(story?.id) { mutableStateOf<PlayerView?>(null) }
    var selectedSuggestedReaction by remember(story?.id) {
        mutableStateOf<org.monogram.domain.models.stories.StoryReactionModel?>(
            null
        )
    }
    val isMediaScaledToFill = state.isStoryMediaStretchEnabled
    val advanceStory by rememberUpdatedState(newValue = {
        if (state.canGoNext) {
            component.nextStory()
        } else {
            component.dismiss()
        }
    })

    LaunchedEffect(story?.id, restartPlaybackToken, state.isLoading, story?.media?.type) {
        currentProgress = 0f
        if (story == null || state.isLoading || story.media.type != StoryMediaType.PHOTO) return@LaunchedEffect
        val totalDurationMs = resolveStoryAutoAdvanceDurationMs(story)
        val startMs = System.currentTimeMillis()
        while (currentProgress < 1f) {
            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            currentProgress = (elapsedMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
            if (currentProgress >= 1f) break
            delay(16)
        }
        advanceStory()
    }

    val pageState = remember(story, state.viewerIndex, state.isLoading, state.inlineError) {
        StoryViewerPageState(
            story = story,
            viewerIndex = state.viewerIndex,
            isLoading = state.isLoading,
            inlineError = state.inlineError
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        StoryViewerSystemBars()
        StoryViewerBackground()
        StoryStatusBarScrim()

        AnimatedContent(
            targetState = pageState,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            contentKey = { current -> current.story?.id ?: "story-${current.viewerIndex}" },
            label = "story_viewer_page"
        ) { currentPage ->
            StoryMediaScene(
                context = context,
                state = state,
                page = currentPage,
                progress = currentProgress,
                isMediaScaledToFill = isMediaScaledToFill,
                isVideoMuted = isVideoMuted,
                isVideoPaused = isVideoPaused,
                restartPlaybackToken = restartPlaybackToken,
                onVideoProgress = { progressValue ->
                    currentProgress = progressValue.coerceIn(0f, 1f)
                },
                onVideoBufferingChange = { isVideoBuffering = it },
                onVideoPlayingChange = { isVideoPlaying = it },
                onVideoCompleted = advanceStory,
                onStoryAreaClick = { areaType ->
                    when (areaType) {
                        is StoryAreaTypeModel.Link -> component.openStoryLink(areaType.url)
                        is StoryAreaTypeModel.SuggestedReaction -> {
                            selectedSuggestedReaction = areaType.reaction
                            component.setStoryReaction(areaType.reaction)
                        }

                        else -> Unit
                    }
                },
                selectedReaction = selectedSuggestedReaction,
                onPlayerViewChanged = { playerView = it }
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        enabled = story != null,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            if (shouldRestartCurrentStoryFromPreviousTap(currentProgress) || !state.canGoPrevious) {
                                restartPlaybackToken += 1
                                isVideoPaused = false
                            } else {
                                component.previousStory()
                            }
                        }
                    )
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        enabled = story != null,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            if (state.canGoNext) component.nextStory() else component.dismiss()
                        }
                    )
            )
        }

        StoryViewerChrome(
            state = state,
            story = story,
            progress = currentProgress,
            isVideo = story?.media?.type == StoryMediaType.VIDEO,
            isVideoPaused = isVideoPaused,
            isVideoMuted = isVideoMuted,
            isVideoBuffering = isVideoBuffering,
            isVideoPlaying = isVideoPlaying,
            isMediaScaledToFill = isMediaScaledToFill,
            onBack = component::dismiss,
            onPauseToggle = { isVideoPaused = !isVideoPaused },
            onMuteToggle = { isVideoMuted = !isVideoMuted },
            onReactionClick = {
                if (story?.canGetInteractions == true) {
                    component.showStoryInteractions()
                } else {
                    val suggestedReaction = story?.areas
                        ?.firstNotNullOfOrNull { area ->
                            area.type as? StoryAreaTypeModel.SuggestedReaction
                        }
                    suggestedReaction?.let {
                        selectedSuggestedReaction = it.reaction
                        component.setStoryReaction(it.reaction)
                    }
                }
            },
            onMediaScaleToggle = component::setStoryMediaStretchEnabled,
            onProfileClick = state.chatId?.let { chatId -> { component.openProfile(chatId) } },
            onLinks = { isLinksSheetVisible = true },
            onEdit = component::editCurrentStory,
            onArchive = component::moveCurrentStoryToArchive,
            onRestore = component::restoreCurrentStoryFromArchive,
            onStatistics = component::showStoryStatistics,
            onDelete = component::deleteCurrentStory,
            onDownload = {
                resolveStoryDownloadPath(story)?.let(downloadUtils::saveFileToDownloads)
            },
            onCopyMedia = {
                when {
                    story == null -> Unit
                    story.media.type == StoryMediaType.PHOTO -> {
                        resolveStoryDownloadPath(story)?.let(downloadUtils::copyImageToClipboard)
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                        val surfaceView = playerView?.videoSurfaceView as? SurfaceView
                        if (surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
                            val bitmap = createBitmap(surfaceView.width, surfaceView.height)
                            PixelCopy.request(
                                surfaceView,
                                bitmap,
                                { result ->
                                    if (result == PixelCopy.SUCCESS) {
                                        downloadUtils.copyBitmapToClipboard(bitmap)
                                    } else {
                                        resolveStoryDownloadPath(story)
                                            ?.let(downloadUtils::copyImageToClipboard)
                                    }
                                },
                                Handler(Looper.getMainLooper())
                            )
                        } else {
                            resolveStoryDownloadPath(story)?.let(downloadUtils::copyImageToClipboard)
                        }
                    }

                    else -> {
                        resolveStoryDownloadPath(story)?.let(downloadUtils::copyImageToClipboard)
                    }
                }
            },
            onCopyStoryLink = component::copyCurrentStoryLink
        )

        if (isLinksSheetVisible && !story?.linkUrls.isNullOrEmpty()) {
            StoryLinksSheet(
                urls = story.linkUrls,
                onDismiss = { isLinksSheetVisible = false },
                onOpenLink = component::openStoryLink,
                onCopyLink = component::copyStoryLink
            )
        }

        if (state.isStoryStatisticsVisible) {
            StatisticsViewer(
                title = stringResource(R.string.story_statistics_title),
                data = if (state.isStoryStatisticsLoading) null else state.storyStatistics,
                onDismiss = component::dismissStoryStatistics
            )
        }

        if (state.isStoryInteractionsVisible) {
            StoryInteractionsSheet(
                page = state.storyInteractionsPage,
                isLoading = state.isStoryInteractionsLoading,
                onDismiss = component::dismissStoryInteractions,
                onLoadMore = component::loadMoreStoryInteractions,
                onInteractionClick = component::openProfile
            )
        }
    }
}

@Composable
private fun StoryViewerBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF090A0E),
                        Color(0xFF10131B),
                        Color(0xFF06070B)
                    )
                )
            )
    )
}

@Composable
private fun StoryMediaScene(
    context: Context,
    state: StoriesHostComponent.State,
    page: StoryViewerPageState,
    progress: Float,
    isMediaScaledToFill: Boolean,
    isVideoMuted: Boolean,
    isVideoPaused: Boolean,
    restartPlaybackToken: Int,
    onVideoProgress: (Float) -> Unit,
    onVideoBufferingChange: (Boolean) -> Unit,
    onVideoPlayingChange: (Boolean) -> Unit,
    onVideoCompleted: () -> Unit,
    onStoryAreaClick: (StoryAreaTypeModel) -> Unit,
    selectedReaction: org.monogram.domain.models.stories.StoryReactionModel?,
    onPlayerViewChanged: (PlayerView?) -> Unit
) {
    val story = page.story
    val mediaContentScale = if (isMediaScaledToFill) ContentScale.Crop else ContentScale.Fit
    val videoResizeMode = if (isMediaScaledToFill) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    } else {
        AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerAspectRatio = maxWidth / maxHeight
        val viewportModifier = if (isMediaScaledToFill) {
            Modifier.fillMaxSize()
        } else {
            val fitModifier = if (containerAspectRatio > STORY_MEDIA_ASPECT_RATIO) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(STORY_MEDIA_ASPECT_RATIO)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(STORY_MEDIA_ASPECT_RATIO)
            }
            Modifier
                .align(Alignment.Center)
                .then(fitModifier)
        }

        Box(modifier = viewportModifier) {
            when {
                story == null && page.isLoading -> {
                    StoryMediaWaitingPlaceholder(
                        showLoadingText = state.showStoryMediaLoadingMessage
                    )
                }

                story == null -> {
                    StoryUnavailablePlaceholder(
                        page.inlineError ?: stringResource(R.string.story_viewer_unavailable)
                    )
                }

                story.media.type == StoryMediaType.PHOTO -> {
                    val storyImageModel = rememberStoryImageModel(
                        context = context,
                        primaryPath = story.media.path,
                        fallbackPath = story.media.previewPath,
                        minithumbnail = story.media.minithumbnail
                    )
                    if (storyImageModel != null) {
                        AsyncImage(
                            model = storyImageModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = mediaContentScale
                        )
                        if (page.isLoading) {
                            StoryMediaLoadingOverlay(
                                showLoadingText = state.showStoryMediaLoadingMessage
                            )
                        }
                    } else if (page.isLoading) {
                        StoryMediaWaitingPlaceholder(
                            showLoadingText = state.showStoryMediaLoadingMessage
                        )
                    } else {
                        StoryUnavailablePlaceholder(
                            page.inlineError ?: stringResource(R.string.story_viewer_unavailable)
                        )
                    }
                }

                else -> {
                    val previewModel = rememberStoryImageModel(
                        context = context,
                        primaryPath = story.media.previewPath,
                        fallbackPath = story.media.path,
                        minithumbnail = story.media.minithumbnail
                    )
                    if (!story.media.path.isNullOrBlank()) {
                        StoryInlineVideoPlayer(
                            path = story.media.path.orEmpty(),
                            previewModel = previewModel,
                            previewContentScale = mediaContentScale,
                            resizeMode = videoResizeMode,
                            isMuted = isVideoMuted,
                            isPlaying = !isVideoPaused,
                            restartPlaybackToken = restartPlaybackToken,
                            onProgress = onVideoProgress,
                            onBufferingChange = onVideoBufferingChange,
                            onPlayingChange = onVideoPlayingChange,
                            onCompleted = onVideoCompleted,
                            onPlayerViewChanged = onPlayerViewChanged
                        )
                    } else if (previewModel != null) {
                        AsyncImage(
                            model = previewModel,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = mediaContentScale
                        )
                        StoryMediaLoadingOverlay(
                            showLoadingText = state.showStoryMediaLoadingMessage
                        )
                    } else if (page.isLoading) {
                        StoryMediaWaitingPlaceholder(
                            showLoadingText = state.showStoryMediaLoadingMessage
                        )
                    } else {
                        StoryUnavailablePlaceholder(
                            page.inlineError ?: stringResource(R.string.story_viewer_unavailable)
                        )
                    }
                }
            }

        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.40f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.58f)
                        )
                    )
                )
        )

        if (story != null && story.areas.isNotEmpty()) {
            StoryAreaOverlays(
                story = story,
                onAreaClick = onStoryAreaClick,
                selectedReaction = selectedReaction
            )
        }
    }
}

@Composable
private fun StoryAreaOverlays(
    story: StoryModel,
    onAreaClick: (StoryAreaTypeModel) -> Unit,
    selectedReaction: org.monogram.domain.models.stories.StoryReactionModel?
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        story.areas.forEachIndexed { index, area ->
            val baseWidth =
                maxOf(maxWidth * (area.position.widthPercentage.toFloat() / 100f), 72.dp)
            val baseHeight =
                maxOf(maxHeight * (area.position.heightPercentage.toFloat() / 100f), 34.dp)
            val width: androidx.compose.ui.unit.Dp
            val height: androidx.compose.ui.unit.Dp
            val cornerRadius: androidx.compose.ui.unit.Dp

            if (area.type is StoryAreaTypeModel.SuggestedReaction) {
                val diameter = maxOf(baseHeight, 58.dp)
                width = diameter
                height = diameter
                cornerRadius = diameter / 2
            } else {
                width = baseWidth
                height = baseHeight
                cornerRadius = maxOf(
                    maxWidth * (area.position.cornerRadiusPercentage.toFloat() / 100f),
                    12.dp
                )
            }

            val offsetX = (maxWidth * (area.position.xPercentage.toFloat() / 100f)) - (width / 2)
            val offsetY =
                (maxHeight * (area.position.yPercentage.toFloat() / 100f)) - (height / 2)

            StoryAreaChip(
                modifier = Modifier
                    .padding(0.dp)
                    .graphicsLayer { rotationZ = area.position.rotationAngle.toFloat() }
                    .offset(x = offsetX, y = offsetY)
                    .size(width = width, height = height),
                areaType = area.type,
                cornerRadius = cornerRadius,
                onClick = { onAreaClick(area.type) },
                key = "${story.id}-$index",
                isSelected = (area.type as? StoryAreaTypeModel.SuggestedReaction)?.reaction == selectedReaction
            )
        }
    }
}

@Composable
private fun StoryAreaChip(
    modifier: Modifier,
    areaType: StoryAreaTypeModel,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    key: String,
    isSelected: Boolean
) {
    val isClickable = remember(key, areaType) {
        areaType is StoryAreaTypeModel.Link || areaType is StoryAreaTypeModel.SuggestedReaction
    }
    val selectedScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        label = "story_area_selected_scale"
    )
    val backgroundColor = remember(key, areaType) {
        when (areaType) {
            is StoryAreaTypeModel.Link -> Color.White.copy(alpha = 0.18f)
            is StoryAreaTypeModel.SuggestedReaction -> {
                if (areaType.isDark) Color.Black.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.24f)
            }

            is StoryAreaTypeModel.Weather -> Color(areaType.backgroundColorArgb).copy(alpha = 0.88f)
            is StoryAreaTypeModel.Location -> Color(0xCC0F7A5B)
            is StoryAreaTypeModel.Venue -> Color(0xCC155EEF)
            is StoryAreaTypeModel.Message -> Color(0xCC7A4B1A)
            is StoryAreaTypeModel.UpgradedGift -> Color(0xCC6A34D7)
        }
    }
    val contentColor = remember(key, areaType) {
        when (areaType) {
            is StoryAreaTypeModel.SuggestedReaction -> {
                if (areaType.isDark) Color.White else Color.Black
            }

            is StoryAreaTypeModel.Weather -> Color.White
            else -> Color.White
        }
    }

    if (areaType is StoryAreaTypeModel.SuggestedReaction) {
        Box(
            modifier = modifier
                .graphicsLayer {
                    scaleX = selectedScale
                    scaleY = selectedScale
                }
                .then(if (isClickable) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(14.dp)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
                        } else {
                            backgroundColor.copy(alpha = 0.42f)
                        },
                        CircleShape
                    )
            )
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                } else {
                    backgroundColor
                },
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
                    } else {
                        Color.White.copy(alpha = 0.14f)
                    }
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .size(14.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = Color.White
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = storyReactionLabel(areaType.reaction),
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor,
                            maxLines = 1
                        )
                        Text(
                            text = formatCompactStoryCount(areaType.totalCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.84f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    } else {
        Surface(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .then(if (isClickable) Modifier.clickable(onClick = onClick) else Modifier),
            shape = RoundedCornerShape(cornerRadius),
            color = backgroundColor,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StoryAreaLeading(areaType = areaType, contentColor = contentColor)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = storyAreaPrimaryLabel(areaType),
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    storyAreaSecondaryLabel(areaType)?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryAreaLeading(
    areaType: StoryAreaTypeModel,
    contentColor: Color
) {
    when (areaType) {
        is StoryAreaTypeModel.SuggestedReaction -> {
            Text(
                text = storyReactionLabel(areaType.reaction),
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1
            )
        }

        is StoryAreaTypeModel.Weather -> {
            Text(
                text = areaType.emoji,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                maxLines = 1
            )
        }

        else -> {
            Icon(
                imageVector = when (areaType) {
                    is StoryAreaTypeModel.Link -> Icons.Rounded.Link
                    is StoryAreaTypeModel.Location -> Icons.Rounded.Public
                    is StoryAreaTypeModel.Venue -> Icons.Rounded.Public
                    is StoryAreaTypeModel.Message -> Icons.Rounded.Share
                    is StoryAreaTypeModel.UpgradedGift -> Icons.Rounded.Image
                },
                contentDescription = null,
                tint = contentColor
            )
        }
    }
}

@Composable
internal fun StoryUnavailablePlaceholder(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f)
        ) {
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun rememberStoryImageModel(
    context: Context,
    primaryPath: String?,
    fallbackPath: String?,
    minithumbnail: ByteArray?
): Any? {
    val resolvedPath = remember(primaryPath, fallbackPath) {
        listOfNotNull(
            primaryPath?.takeIf { it.isNotBlank() },
            fallbackPath?.takeIf { it.isNotBlank() }
        ).firstOrNull()
    }

    if (resolvedPath == null && minithumbnail != null && minithumbnail.isNotEmpty()) {
        return remember(minithumbnail) {
            ImageRequest.Builder(context)
                .data(minithumbnail)
                .build()
        }
    }

    resolvedPath ?: return null

    return remember(resolvedPath) {
        if (
            resolvedPath.startsWith("http://") ||
            resolvedPath.startsWith("https://") ||
            resolvedPath.startsWith("content:") ||
            resolvedPath.startsWith("file:")
        ) {
            resolvedPath
        } else {
            val file = File(resolvedPath)
            if (file.exists()) {
                ImageRequest.Builder(context)
                    .data(file)
                    .memoryCacheKey("${file.absolutePath}:${file.lastModified()}:${file.length()}")
                    .diskCacheKey("${file.absolutePath}:${file.lastModified()}:${file.length()}")
                    .build()
            } else {
                ImageRequest.Builder(context)
                    .data(resolvedPath)
                    .build()
            }
        }
    }
}

@Composable
private fun StoryMediaWaitingPlaceholder(
    showLoadingText: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
        }
        if (showLoadingText) {
            StoryMediaLoadingBadge(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp),
                showText = true
            )
        }
    }
}

@Composable
private fun StoryMediaLoadingOverlay(
    showLoadingText: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.onPrimary
        )
        if (showLoadingText) {
            StoryMediaLoadingBadge(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp),
                showText = true
            )
        }
    }
}

@Composable
private fun StoryMediaLoadingBadge(
    modifier: Modifier = Modifier,
    showText: Boolean
) {
    val transition = rememberInfiniteTransition(label = "story_loading_badge")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "story_loading_badge_alpha"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(18.dp)
                .background(
                    Color.Black.copy(alpha = pulseAlpha),
                    RoundedCornerShape(22.dp)
                )
        )
        Surface(
            modifier = Modifier.animateContentSize(),
            shape = RoundedCornerShape(if (showText) 22.dp else 18.dp),
            color = Color.Black.copy(alpha = 0.30f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = if (showText) 14.dp else 10.dp,
                    vertical = 10.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                if (showText) {
                    Text(
                        text = stringResource(R.string.story_media_loading),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryStatusBarScrim() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.50f),
                        Color.Black.copy(alpha = 0.24f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
internal fun StoryViewerProgressRow(
    total: Int,
    currentIndex: Int,
    currentProgress: Float
) {
    if (total <= 0) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(total) { index ->
            val progress = when {
                index < currentIndex -> 1f
                index == currentIndex -> currentProgress.coerceIn(0f, 1f)
                else -> 0f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
    }
}

@Composable
internal fun StoryInlineVideoPlayer(
    path: String,
    previewModel: Any?,
    previewContentScale: ContentScale,
    resizeMode: Int,
    isMuted: Boolean,
    isPlaying: Boolean,
    restartPlaybackToken: Int,
    onProgress: (Float) -> Unit,
    onBufferingChange: (Boolean) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onCompleted: () -> Unit,
    onPlayerViewChanged: (PlayerView?) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    val mediaUri = remember(path) { resolveVideoUri(path) }
    var completed by remember(path) { mutableStateOf(false) }

    val exoPlayer = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = if (isMuted) 0f else 1f
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
        }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                onPlayingChange(isPlayingState)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                onBufferingChange(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED && !completed) {
                    completed = true
                    onProgress(1f)
                    currentOnCompleted()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onBufferingChange(false)
                onPlayingChange(false)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        exoPlayer.addListener(listener)

        onDispose {
            onPlayerViewChanged(null)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        if (isPlaying) {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                completed = false
                exoPlayer.seekTo(0)
            }
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(exoPlayer, restartPlaybackToken) {
        completed = false
        exoPlayer.seekTo(0)
        onProgress(0f)
        if (isPlaying) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            val duration = exoPlayer.duration
            if (duration > 0L) {
                onProgress(
                    (exoPlayer.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                )
            }
            delay(40)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (previewModel != null) {
            AsyncImage(
                model = previewModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = previewContentScale
            )
        }

        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.resizeMode = resizeMode
                    player = exoPlayer
                }.also(onPlayerViewChanged)
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = resizeMode
                onPlayerViewChanged(view)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun resolveVideoUri(path: String): Uri {
    return when {
        path.startsWith("content:") ||
                path.startsWith("file:") ||
                path.startsWith("http://") ||
                path.startsWith("https://") -> Uri.parse(path)

        else -> Uri.fromFile(File(path))
    }
}

@Composable
private fun StoryViewerSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context.findActivity()
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousStatusBarColor = window.statusBarColor

            controller.isAppearanceLightStatusBars = false
            window.statusBarColor = Color.Black.copy(alpha = 0.40f).toArgb()

            onDispose {
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                window.statusBarColor = previousStatusBarColor
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private data class StoryViewerPageState(
    val story: StoryModel?,
    val viewerIndex: Int,
    val isLoading: Boolean,
    val inlineError: String?
)
