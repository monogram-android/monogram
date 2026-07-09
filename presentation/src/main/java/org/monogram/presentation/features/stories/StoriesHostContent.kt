package org.monogram.presentation.features.stories

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
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
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.presentation.R
import org.monogram.presentation.features.camera.CameraScreen
import org.monogram.presentation.features.chats.conversation.ui.inputbar.copyUriToTempPath
import org.monogram.presentation.features.chats.conversation.ui.inputbar.declaredPermissions
import org.monogram.presentation.features.chats.conversation.ui.inputbar.hasAllPermissions
import org.monogram.presentation.features.gallery.GalleryScreen
import org.monogram.presentation.features.viewers.VideoViewer
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun StoriesHostContent(component: StoriesHostComponent) {
    val state by component.state.collectAsState()
    if (!state.isVisible) return

    BackHandler(enabled = state.isVisible) {
        when {
            state.showInlineVideo -> component.dismissInlineVideo()
            state.showCamera -> component.dismissCamera()
            state.showMediaPicker -> component.dismissMediaPicker()
            else -> component.dismiss()
        }
    }

    StoriesOverlay(state = state, component = component)
}

@Composable
fun StoriesStrip(
    items: List<StoryStripItemUiModel>,
    onStoryClick: (Long, Int?) -> Unit,
    showAddStoryButton: Boolean = false,
    onAddStoryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showAddStoryButton && onAddStoryClick != null) {
            item(key = "story_add") {
                AddStoryStripTile(onClick = onAddStoryClick)
            }
        }
        items(items, key = { it.chatId }) { item ->
            StoryStripTile(
                title = item.title,
                avatarPath = item.avatarPath,
                hasUnread = item.activeStories.stories.any { !it.isRead },
                onClick = {
                    onStoryClick(
                        item.chatId,
                        item.activeStories.stories.firstOrNull()?.storyId
                    )
                }
            )
        }
    }
}

internal fun shouldShowStoriesStrip(
    selectedFolderId: Int,
    isSearchActive: Boolean
): Boolean {
    return !isSearchActive && (selectedFolderId == -1 || selectedFolderId == -2)
}

@Composable
private fun StoriesOverlay(
    state: StoriesHostComponent.State,
    component: StoriesHostComponent
) {
    AnimatedVisibility(
        visible = state.isVisible,
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f),
        enter = fadeIn(tween(180)) +
                scaleIn(
                    initialScale = 0.96f,
                    animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f)
                ) +
                slideInVertically(
                    initialOffsetY = { it / 10 },
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ),
        exit = fadeOut(tween(160)) +
                scaleOut(
                    targetScale = 0.98f,
                    animationSpec = tween(160, easing = FastOutSlowInEasing)
                ) +
                slideOutVertically(
                    targetOffsetY = { it / 14 },
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                )
    ) {
        AnimatedContent(
            targetState = state.mode,
            transitionSpec = {
                fadeIn(tween(180)) togetherWith fadeOut(tween(120))
            },
            label = "stories_mode_switch"
        ) { mode ->
            when (mode) {
                StoriesHostComponent.Mode.Viewer -> StoryViewerOverlay(state, component)
                StoriesHostComponent.Mode.Composer -> StoryComposerOverlay(state, component)
                StoriesHostComponent.Mode.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun StoryViewerOverlay(
    state: StoriesHostComponent.State,
    component: StoriesHostComponent
) {
    val story = state.currentStory
    val videoPath = story?.media?.path

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (
                story?.media?.type == StoryMediaType.VIDEO &&
                state.showInlineVideo &&
                !videoPath.isNullOrBlank()
            ) {
                VideoViewer(
                    path = videoPath,
                    onDismiss = component::dismissInlineVideo,
                    downloadUtils = org.koin.compose.koinInject()
                )
            } else {
                StoryViewerScaffold(state = state, component = component, story = story)
            }
        }
    }
}

@Composable
private fun StoryViewerScaffold(
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
                fadeIn(tween(110)) togetherWith fadeOut(tween(90))
            },
            contentKey = { current -> current.story?.id ?: "story-${current.viewerIndex}" },
            label = "story_viewer_page"
        ) { currentPage ->
            StoryMediaScene(
                context = context,
                state = state,
                page = currentPage,
                progress = currentProgress,
                isVideoMuted = isVideoMuted,
                isVideoPaused = isVideoPaused,
                restartPlaybackToken = restartPlaybackToken,
                onVideoMutedChange = { isVideoMuted = it },
                onVideoPausedChange = { isVideoPaused = it },
                onVideoProgress = { progressValue ->
                    currentProgress = progressValue.coerceIn(0f, 1f)
                },
                onVideoBufferingChange = { isVideoBuffering = it },
                onVideoPlayingChange = { isVideoPlaying = it },
                onVideoCompleted = advanceStory
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
                        })
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
                        })
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
            onBack = component::dismiss,
            onArchive = component::moveCurrentStoryToArchive,
            onRestore = component::restoreCurrentStoryFromArchive,
            onDelete = component::deleteCurrentStory,
            onPauseToggle = { isVideoPaused = !isVideoPaused },
            onMuteToggle = { isVideoMuted = !isVideoMuted },
            onDownload = {
                resolveStoryDownloadPath(story)?.let(downloadUtils::saveFileToDownloads)
            }
        )
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
    isVideoMuted: Boolean,
    isVideoPaused: Boolean,
    restartPlaybackToken: Int,
    onVideoMutedChange: (Boolean) -> Unit,
    onVideoPausedChange: (Boolean) -> Unit,
    onVideoProgress: (Float) -> Unit,
    onVideoBufferingChange: (Boolean) -> Unit,
    onVideoPlayingChange: (Boolean) -> Unit,
    onVideoCompleted: () -> Unit
) {
    val story = page.story
    when {
        story == null && page.isLoading -> {
            StoryMediaWaitingPlaceholder()
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
                    contentScale = ContentScale.Crop
                )
                if (page.isLoading) {
                    StoryMediaLoadingOverlay()
                }
            } else if (page.isLoading) {
                StoryMediaWaitingPlaceholder()
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
                    isMuted = isVideoMuted,
                    isPlaying = !isVideoPaused,
                    restartPlaybackToken = restartPlaybackToken,
                    onProgress = onVideoProgress,
                    onBufferingChange = onVideoBufferingChange,
                    onPlayingChange = onVideoPlayingChange,
                    onCompleted = onVideoCompleted
                )
            } else if (previewModel != null) {
                AsyncImage(
                    model = previewModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                StoryMediaLoadingOverlay()
            } else if (page.isLoading) {
                StoryMediaWaitingPlaceholder()
            } else {
                StoryUnavailablePlaceholder(
                    page.inlineError ?: stringResource(R.string.story_viewer_unavailable)
                )
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
}

@Composable
private fun StoryViewerChrome(
    state: StoriesHostComponent.State,
    story: StoryModel?,
    progress: Float,
    isVideo: Boolean,
    isVideoPaused: Boolean,
    isVideoMuted: Boolean,
    isVideoBuffering: Boolean,
    isVideoPlaying: Boolean,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onPauseToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onDownload: () -> Unit
) {
    val currentChatId = state.chatId
    val currentChatItems = remember(state.viewerItems, currentChatId) {
        state.viewerItems.filter { it.chatId == currentChatId }
    }
    val currentChatIndex = remember(state.viewerItems, currentChatId, state.viewerIndex) {
        val currentItem = state.viewerItems.getOrNull(state.viewerIndex)
        if (currentItem == null || currentChatId == null) {
            0
        } else {
            currentChatItems.indexOfFirst {
                it.chatId == currentItem.chatId && it.storyId == currentItem.storyId
            }.coerceAtLeast(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StoryViewerProgressRow(
                total = currentChatItems.size,
                currentIndex = currentChatIndex,
                currentProgress = progress
            )
            StoryViewerHeader(
                state = state,
                story = story,
                onBack = onBack,
                onArchive = onArchive,
                onRestore = onRestore,
                onDelete = onDelete
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(visible = isVideo) {
                        StoryTopIconButton(
                            onClick = onMuteToggle,
                            contentDescription = if (isVideoMuted) {
                                stringResource(R.string.menu_unmute)
                            } else {
                                stringResource(R.string.menu_mute)
                            }
                        ) {
                            Icon(
                                imageVector = if (isVideoMuted) {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    AnimatedVisibility(visible = isVideo) {
                        StoryTopIconButton(
                            onClick = onPauseToggle,
                            contentDescription = if (isVideoPaused || !isVideoPlaying) {
                                stringResource(R.string.action_play)
                            } else {
                                stringResource(R.string.action_pause)
                            }
                        ) {
                            if (isVideoBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = if (isVideoPaused || !isVideoPlaying) {
                                        Icons.Rounded.PlayArrow
                                    } else {
                                        Icons.Rounded.Pause
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                    StoryTopIconButton(
                        onClick = onDownload,
                        contentDescription = stringResource(R.string.action_download)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedVisibility(visible = state.inlineError != null) {
                StoryErrorBanner(message = state.inlineError.orEmpty())
            }

            AnimatedVisibility(visible = !story?.caption.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.38f)
                ) {
                    Text(
                        text = story?.caption.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryViewerHeader(
    state: StoriesHostComponent.State,
    story: StoryModel?,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val showSkeleton = state.chatTitle.isBlank()
    val selectedItem = state.viewerItems.getOrNull(state.viewerIndex)
    val headerInfo = remember(
        state.chatId,
        state.chatTitle,
        selectedItem?.storyId,
        selectedItem?.date,
        state.viewerIndex,
        state.viewerItems
    ) {
        StoryHeaderInfoState(
            title = state.chatTitle,
            storyId = selectedItem?.storyId,
            positionText = buildStoryPositionText(state),
            postedAt = selectedItem?.date?.let { formatStoryPostedTime(context, it) }.orEmpty()
        )
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.30f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (showSkeleton) Color.White.copy(alpha = 0.16f) else Color.White.copy(
                    alpha = 0.12f
                ),
                modifier = Modifier.size(38.dp)
            ) {
                if (showSkeleton) {
                    StorySkeletonPlaceholder()
                } else if (state.chatAvatarPath != null) {
                    AsyncImage(
                        model = state.chatAvatarPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = headerInfo,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically { it / 4 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically { -it / 4 })
                    },
                    label = "story_header_info"
                ) { currentInfo ->
                    if (showSkeleton) {
                        StoryHeaderSkeleton()
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentInfo.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    append(currentInfo.positionText)
                                    if (currentInfo.postedAt.isNotBlank()) {
                                        append(" • ")
                                        append(currentInfo.postedAt)
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (state.canManageStories) {
                IconButton(
                    onClick = if (state.activeListType == StoryListType.MAIN) onArchive else onRestore
                ) {
                    Icon(
                        imageVector = if (state.activeListType == StoryListType.MAIN) {
                            Icons.Rounded.Archive
                        } else {
                            Icons.Rounded.Restore
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            if (story?.canBeDeleted == true) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun StoryMetadataChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurface,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = false,
            borderColor = Color.Transparent
        )
    )
}

@Composable
private fun StoryUnavailablePlaceholder(message: String) {
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
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StoryErrorBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun rememberStoryImageModel(
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
private fun StoryMediaWaitingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun StoryMediaLoadingOverlay() {
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
private fun StoryViewerProgressRow(
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StoryComposerOverlay(
    state: StoriesHostComponent.State,
    component: StoriesHostComponent
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val capability = state.postCapability.toCapabilityPresentation()

    val galleryPermissions = remember {
        when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )

            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )

            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val fullGalleryPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val requestableGalleryPermissions = remember(context, galleryPermissions) {
        val declared = context.declaredPermissions()
        galleryPermissions.filter { it in declared }
    }
    val requestableFullGalleryPermissions = remember(context, fullGalleryPermissions) {
        val declared = context.declaredPermissions()
        fullGalleryPermissions.filter { it in declared }
    }

    fun hasPartialGalleryPermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasFullGalleryPermission(): Boolean {
        return requestableFullGalleryPermissions.isEmpty() ||
                context.hasAllPermissions(requestableFullGalleryPermissions)
    }

    var hasGalleryAccess by remember {
        mutableStateOf(hasFullGalleryPermission() || hasPartialGalleryPermission())
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasGalleryAccess = hasFullGalleryPermission() || hasPartialGalleryPermission()
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            component.showCamera()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.chatTitle.ifBlank { stringResource(R.string.story_compose_title) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.story_post_as,
                                state.chatTitle.ifBlank { stringResource(R.string.story_compose_title) }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::dismiss) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = component::openMediaPicker) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = component::openMediaPicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.story_change_media))
                    }

                    Button(
                        onClick = component::submitStory,
                        enabled = state.composerDraft.isValid &&
                                !state.isSubmitting &&
                                canPublishStory(state.postCapability),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            if (state.isSubmitting) {
                                stringResource(R.string.story_posting)
                            } else {
                                stringResource(R.string.story_publish)
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StoryComposerPreviewCard(
                state = state,
                onSelectMedia = component::openMediaPicker,
                onOpenCamera = {
                    if (hasCameraPermission) {
                        component.showCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )

            capability?.let { presentation ->
                StoryCapabilityCard(presentation = presentation)
            }

            state.inlineError?.let { message ->
                StoryErrorBanner(message = message)
            }

            StorySettingsCard(
                title = stringResource(R.string.story_settings_title),
                subtitle = stringResource(R.string.story_caption_supporting)
            ) {
                OutlinedTextField(
                    value = state.composerDraft.caption,
                    onValueChange = component::updateCaption,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.story_caption_label)) },
                    minLines = 2,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(12.dp))

                StoryPrivacySection(
                    selected = when (state.composerDraft.privacy.mode) {
                        StoryPrivacyMode.CONTACTS -> StoryPrivacyUi.CONTACTS
                        StoryPrivacyMode.CLOSE_FRIENDS -> StoryPrivacyUi.CLOSE_FRIENDS
                        else -> StoryPrivacyUi.EVERYONE
                    },
                    onSelect = component::updatePrivacy
                )

                Spacer(modifier = Modifier.height(12.dp))

                StoryDurationSection(
                    selectedSeconds = state.composerDraft.activePeriodSeconds,
                    onSelect = component::updateActivePeriod
                )

                Spacer(modifier = Modifier.height(12.dp))

                StorySwitchRow(
                    title = stringResource(R.string.story_keep_on_profile),
                    subtitle = stringResource(R.string.story_keep_on_profile_subtitle),
                    checked = state.composerDraft.keepOnProfile,
                    onCheckedChange = component::updateKeepOnProfile
                )
                Spacer(modifier = Modifier.height(10.dp))
                StorySwitchRow(
                    title = stringResource(R.string.story_protect_content),
                    subtitle = stringResource(R.string.story_protect_content_subtitle),
                    checked = state.composerDraft.protectContent,
                    onCheckedChange = component::updateProtectContent
                )
            }

            if (!state.composerDraft.widgetLink.isNullOrBlank()) {
                StorySettingsCard(
                    title = stringResource(R.string.story_widget_link_title),
                    subtitle = stringResource(R.string.story_widget_link_subtitle)
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(Icons.Rounded.Link, contentDescription = null)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.story_widget_link_title))
                        },
                        supportingContent = {
                            Text(
                                text = state.composerDraft.widgetLink.orEmpty(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    }

    if (state.showMediaPicker) {
        ModalBottomSheet(
            onDismissRequest = component::dismissMediaPicker,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            GalleryScreen(
                onMediaSelected = { uris ->
                    val first = uris.firstOrNull() ?: return@GalleryScreen
                    val path = context.copyUriToTempPath(first) ?: return@GalleryScreen
                    component.attachMedia(path, inferUriMediaType(first))
                },
                onDismiss = component::dismissMediaPicker,
                onCameraClick = {
                    if (hasCameraPermission) {
                        component.showCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                canSelectMedia = true,
                canUseCamera = true,
                canAttachFiles = false,
                canCreatePoll = false,
                canCreateChecklist = false,
                onAttachFileClick = {},
                onCreatePollClick = {},
                onCreateChecklistClick = {},
                attachBots = emptyList(),
                hasMediaAccess = hasGalleryAccess || hasFullGalleryPermission() || hasPartialGalleryPermission(),
                isPartialAccess = hasPartialGalleryPermission() && !hasFullGalleryPermission(),
                onPickFromOtherSources = {},
                onRequestMediaAccess = {
                    if (requestableGalleryPermissions.isNotEmpty()) {
                        galleryPermissionLauncher.launch(requestableGalleryPermissions.toTypedArray())
                    }
                },
                onAttachBotClick = {},
                modifier = Modifier.fillMaxHeight()
            )
        }
    }

    if (state.showCamera) {
        CameraScreen(
            onImageCaptured = { uri ->
                val path = context.copyUriToTempPath(uri)
                if (path != null) {
                    component.attachMedia(path, StoryMediaType.PHOTO)
                } else {
                    component.dismissCamera()
                }
            },
            onDismiss = component::dismissCamera
        )
    }
}

@Composable
private fun StoryComposerPreviewCard(
    state: StoriesHostComponent.State,
    onSelectMedia: () -> Unit,
    onOpenCamera: () -> Unit
) {
    val context = LocalContext.current
    val previewModel = rememberStoryImageModel(
        context = context,
        primaryPath = state.composerDraft.sourcePath.takeIf { it.isNotBlank() },
        fallbackPath = null,
        minithumbnail = null
    )

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 340.dp)
                    .aspectRatio(0.76f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onSelectMedia),
                contentAlignment = Alignment.Center
            ) {
                if (previewModel == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.story_pick_media),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.story_select_media_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AsyncImage(
                        model = previewModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.38f)
                                    )
                                )
                            )
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StoryMetadataChip(
                            icon = if (state.composerDraft.mediaType == StoryMediaType.VIDEO) {
                                Icons.Rounded.PlayArrow
                            } else {
                                Icons.Rounded.Image
                            },
                            label = if (state.composerDraft.mediaType == StoryMediaType.VIDEO) {
                                stringResource(R.string.media_type_video)
                            } else {
                                stringResource(R.string.media_type_photo)
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onSelectMedia,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gallery_title_attachments))
                }
                OutlinedButton(
                    onClick = onOpenCamera,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.permission_camera_title))
                }
            }
        }
    }
}

@Composable
private fun StorySettingsCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun StoryCapabilityCard(
    presentation: StoryCapabilityPresentation
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (presentation.isBlocking) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Text(
            text = presentation.message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (presentation.isBlocking) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            }
        )
    }
}

@Composable
private fun StoryStripTile(
    title: String,
    avatarPath: String?,
    hasUnread: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(
                width = if (hasUnread) 2.5.dp else 1.dp,
                color = if (hasUnread) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                }
            ),
            modifier = Modifier.size(70.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                if (avatarPath != null) {
                    AsyncImage(
                        model = avatarPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AddStoryStripTile(
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
            modifier = Modifier.size(70.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.story_create),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryPrivacySection(
    selected: StoryPrivacyUi,
    onSelect: (StoryPrivacyUi) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StoryPrivacyUi.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = when (option) {
                            StoryPrivacyUi.EVERYONE -> stringResource(R.string.story_privacy_everyone)
                            StoryPrivacyUi.CONTACTS -> stringResource(R.string.story_privacy_contacts)
                            StoryPrivacyUi.CLOSE_FRIENDS -> stringResource(R.string.story_privacy_close_friends)
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = when (option) {
                            StoryPrivacyUi.EVERYONE -> Icons.Rounded.Public
                            StoryPrivacyUi.CONTACTS -> Icons.Rounded.PeopleAlt
                            StoryPrivacyUi.CLOSE_FRIENDS -> Icons.Rounded.Favorite
                        },
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryDurationSection(
    selectedSeconds: Int,
    onSelect: (Int) -> Unit
) {
    val durations = listOf(
        6 * 60 * 60 to stringResource(R.string.story_duration_6h),
        12 * 60 * 60 to stringResource(R.string.story_duration_12h),
        24 * 60 * 60 to stringResource(R.string.story_duration_24h),
        48 * 60 * 60 to stringResource(R.string.story_duration_48h)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        durations.forEach { (seconds, label) ->
            FilterChip(
                selected = selectedSeconds == seconds,
                onClick = { onSelect(seconds) },
                label = { Text(text = label) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@Composable
private fun StorySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (checked) Icons.Rounded.Shield else Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun inferUriMediaType(uri: Uri): StoryMediaType {
    val normalized = uri.toString().lowercase()
    return if (
        normalized.endsWith(".mp4") ||
        normalized.endsWith(".mov") ||
        normalized.endsWith(".webm") ||
        normalized.endsWith(".mkv")
    ) {
        StoryMediaType.VIDEO
    } else {
        StoryMediaType.PHOTO
    }
}

@Composable
private fun StoryInlineVideoPlayer(
    path: String,
    previewModel: Any?,
    isMuted: Boolean,
    isPlaying: Boolean,
    restartPlaybackToken: Int,
    onProgress: (Float) -> Unit,
    onBufferingChange: (Boolean) -> Unit,
    onPlayingChange: (Boolean) -> Unit,
    onCompleted: () -> Unit
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
                    (exoPlayer.currentPosition.toFloat() / duration.toFloat()).coerceIn(
                        0f,
                        1f
                    )
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
                contentScale = ContentScale.Crop
            )
        }

        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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

private data class StoryViewerPageState(
    val story: StoryModel?,
    val viewerIndex: Int,
    val isLoading: Boolean,
    val inlineError: String?
)

@Composable
private fun StoryTopIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.34f)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
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

@Composable
private fun StoryHeaderSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StorySkeletonBar(
            modifier = Modifier
                .fillMaxWidth(0.42f)
                .height(14.dp)
        )
        StorySkeletonBar(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .height(10.dp)
        )
    }
}

@Composable
private fun StorySkeletonBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
    ) {
        StorySkeletonPlaceholder()
    }
}

@Composable
private fun StorySkeletonPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "story_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "story_skeleton_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = alpha))
    )
}

private data class StoryCapabilityPresentation(
    val message: String,
    val isBlocking: Boolean
)

internal fun resolveStoryAutoAdvanceDurationMs(story: StoryModel): Int {
    val durationSeconds = story.media.durationSeconds
    val baseDurationMs = when {
        durationSeconds != null && durationSeconds > 0 -> {
            (durationSeconds * 1000).roundToInt()
        }

        else -> 5_500
    }
    val captionBonusMs = if (story.caption.isBlank()) 0 else 1_500
    return (baseDurationMs + captionBonusMs).coerceAtLeast(2_500)
}

internal fun shouldRestartCurrentStoryFromPreviousTap(progress: Float): Boolean {
    return progress > 0.3f
}

private fun resolveStoryDownloadPath(story: StoryModel?): String? {
    return story?.media?.path?.takeIf { it.isNotBlank() }
        ?: story?.media?.previewPath?.takeIf { it.isNotBlank() }
}

private fun buildStoryPositionText(state: StoriesHostComponent.State): String {
    val currentChatId = state.chatId
    val totalInChat = state.viewerItems.count { it.chatId == currentChatId }.coerceAtLeast(1)
    val currentIndexInChat = state.viewerItems
        .take(state.viewerIndex + 1)
        .count { it.chatId == currentChatId }
        .coerceAtLeast(1)
    return "$currentIndexInChat/$totalInChat"
}

private fun formatStoryPostedTime(context: Context, dateSeconds: Int): String {
    return DateFormat.getTimeFormat(context).format(Date(dateSeconds * 1000L))
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private data class StoryHeaderInfoState(
    val title: String,
    val storyId: Int?,
    val positionText: String,
    val postedAt: String
)

internal fun canPublishStory(capability: StoryPostCapabilityModel?): Boolean {
    return capability == null || capability is StoryPostCapabilityModel.Allowed
}

@Composable
private fun StoryPostCapabilityModel?.toCapabilityPresentation(): StoryCapabilityPresentation? {
    return when (this) {
        null -> null
        is StoryPostCapabilityModel.Allowed -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_ready, remainingCount),
            isBlocking = false
        )

        StoryPostCapabilityModel.PremiumNeeded -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_premium),
            isBlocking = true
        )

        StoryPostCapabilityModel.BoostNeeded -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_boost),
            isBlocking = true
        )

        StoryPostCapabilityModel.ActiveStoryLimitExceeded -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_active_limit),
            isBlocking = true
        )

        is StoryPostCapabilityModel.WeeklyLimitExceeded -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_weekly_limit),
            isBlocking = true
        )

        is StoryPostCapabilityModel.MonthlyLimitExceeded -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_monthly_limit),
            isBlocking = true
        )

        is StoryPostCapabilityModel.LiveStoryActive -> StoryCapabilityPresentation(
            message = stringResource(R.string.story_capability_live_active),
            isBlocking = true
        )

        is StoryPostCapabilityModel.Unknown -> StoryCapabilityPresentation(
            message = message.ifBlank { stringResource(R.string.story_capability_unavailable) },
            isBlocking = true
        )
    }
}

private fun formatDurationLabel(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "%d:%02d".format(minutes, seconds)
    } else {
        "0:%02d".format(seconds)
    }
}
