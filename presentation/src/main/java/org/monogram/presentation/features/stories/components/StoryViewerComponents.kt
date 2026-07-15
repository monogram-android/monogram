package org.monogram.presentation.features.stories.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.monogram.domain.models.stories.StoryAvailableReactionModel
import org.monogram.domain.models.stories.StoryAvailableReactionsModel
import org.monogram.domain.models.stories.StoryInteractionActorType
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryOptionsModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryReactionUnavailabilityReasonModel
import org.monogram.domain.models.stories.StoryStealthModeModel
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.R
import org.monogram.presentation.features.stickers.ui.menu.ActionMenuPopup
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.menu.MenuToggleRow
import org.monogram.presentation.features.stickers.ui.menu.StickerEmojiMenu
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.presentation.features.stories.StoriesHostComponent
import org.monogram.presentation.features.stories.StoryErrorBanner
import org.monogram.presentation.features.stories.StoryHeaderInfoState
import org.monogram.presentation.features.stories.StoryHeaderSkeleton
import org.monogram.presentation.features.stories.StoryInteractionAvatar
import org.monogram.presentation.features.stories.StoryMetadataChip
import org.monogram.presentation.features.stories.StorySkeletonPlaceholder
import org.monogram.presentation.features.stories.StoryStealthAvailability
import org.monogram.presentation.features.stories.StoryTopIconButton
import org.monogram.presentation.features.stories.StoryViewerBorderColor
import org.monogram.presentation.features.stories.StoryViewerContentColor
import org.monogram.presentation.features.stories.StoryViewerMenuAction
import org.monogram.presentation.features.stories.StoryViewerMutedContentColor
import org.monogram.presentation.features.stories.StoryViewerSheetColor
import org.monogram.presentation.features.stories.StoryViewerSheetSurfaceColor
import org.monogram.presentation.features.stories.StoryViewerSheetSurfaceVariantColor
import org.monogram.presentation.features.stories.StoryViewerSource
import org.monogram.presentation.features.stories.buildStoryPositionText
import org.monogram.presentation.features.stories.buildStoryReactionSections
import org.monogram.presentation.features.stories.buildStoryViewerMenuActions
import org.monogram.presentation.features.stories.formatStoryPostedTime
import org.monogram.presentation.features.stories.resolveStoryStealthAvailability
import org.monogram.presentation.features.stories.storyInteractionTypeLabel
import org.monogram.presentation.features.stories.storyReactionLabel
import org.monogram.presentation.features.stories.storyViewerMenuIcon
import org.monogram.presentation.features.stories.storyViewerMenuTitle
import org.monogram.presentation.features.stories.storyViewerOverlayColor

@Composable
internal fun StoryViewerChromeComponent(
    state: StoriesHostComponent.State,
    story: StoryModel?,
    progress: Float,
    isVideo: Boolean,
    isVideoPaused: Boolean,
    isVideoMuted: Boolean,
    isVideoBuffering: Boolean,
    isVideoPlaying: Boolean,
    isMediaScaledToFill: Boolean,
    onBack: () -> Unit,
    onPauseToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onReactionClick: () -> Unit,
    onMediaScaleToggle: (Boolean) -> Unit,
    onProfileClick: (() -> Unit)?,
    onLinks: () -> Unit,
    onEdit: () -> Unit,
    onToggleProfileVisibility: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onStatistics: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onCopyMedia: () -> Unit,
    onCopyStoryLink: () -> Unit,
    onActivateStealthMode: () -> Unit
) {
    var showMenu by remember(story?.id, state.activeListType, state.canManageStories) {
        mutableStateOf(false)
    }
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
                onBack = onBack,
                showMoreButton = story != null,
                onMore = { showMenu = true },
                onProfileClick = onProfileClick
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                StoryViewerActionsPopup(
                    visible = showMenu,
                    story = story,
                    currentUserId = state.currentUserId,
                    isPremiumUser = state.isPremiumUser,
                    stealthMode = state.stealthMode,
                    storyOptions = state.storyOptions,
                    canManageStories = state.canManageStories,
                    viewerSource = state.viewerSource,
                    activeListType = state.activeListType,
                    isMediaScaledToFill = isMediaScaledToFill,
                    onDismiss = { showMenu = false },
                    onMediaScaleToggle = onMediaScaleToggle,
                    onDownload = onDownload,
                    onCopyMedia = onCopyMedia,
                    onCopyStoryLink = onCopyStoryLink,
                    onLinks = onLinks,
                    onEdit = onEdit,
                    onToggleProfileVisibility = onToggleProfileVisibility,
                    onArchive = onArchive,
                    onRestore = onRestore,
                    onStatistics = onStatistics,
                    onDelete = onDelete,
                    onActivateStealthMode = onActivateStealthMode
                )

                Column(
                    modifier = Modifier.align(Alignment.TopEnd),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                    color = StoryViewerContentColor
                                )
                            } else {
                                Icon(
                                    imageVector = if (isVideoPaused || !isVideoPlaying) {
                                        Icons.Rounded.PlayArrow
                                    } else {
                                        Icons.Rounded.Pause
                                    },
                                    contentDescription = null,
                                    tint = StoryViewerContentColor
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isVideo) {
                        StoryTopIconButton(
                            onClick = onMuteToggle,
                            contentDescription = stringResource(
                                if (isVideoMuted) {
                                    R.string.story_audio_unmute
                                } else {
                                    R.string.story_audio_mute
                                }
                            )
                        ) {
                            Icon(
                                imageVector = if (isVideoMuted) {
                                    Icons.AutoMirrored.Rounded.VolumeUp
                                } else {
                                    Icons.AutoMirrored.Rounded.VolumeOff
                                },
                                contentDescription = null,
                                tint = StoryViewerContentColor
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = story != null && (
                                story.canGetInteractions ||
                                        state.currentUserId != null && state.currentUserId != story.posterChatId
                                )
                    ) {
                        StoryTopIconButton(
                            onClick = onReactionClick,
                            contentDescription = stringResource(R.string.story_reactions_button)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = StoryViewerContentColor
                            )
                        }
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
                    color = storyViewerOverlayColor(alpha = 0.88f)
                ) {
                    Text(
                        text = story?.caption.orEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StoryViewerContentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryViewerHeader(
    state: StoriesHostComponent.State,
    onBack: () -> Unit,
    showMoreButton: Boolean,
    onMore: () -> Unit,
    onProfileClick: (() -> Unit)?
) {
    val context = LocalContext.current
    val showSkeleton = state.chatTitle.isBlank()
    val profileClickModifier = remember(showSkeleton, onProfileClick) {
        if (!showSkeleton && onProfileClick != null) {
            Modifier.clickable(onClick = onProfileClick)
        } else {
            Modifier
        }
    }
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
        color = storyViewerOverlayColor(alpha = 0.84f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .then(profileClickModifier)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (showSkeleton) {
                        StoryViewerContentColor.copy(alpha = 0.16f)
                    } else {
                        StoryViewerContentColor.copy(alpha = 0.12f)
                    },
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
                                tint = StoryViewerContentColor.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = headerInfo,
                        transitionSpec = {
                            (fadeIn() + slideInVertically { it / 4 }) togetherWith
                                    (fadeOut() + slideOutVertically { -it / 4 })
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
                                    color = StoryViewerContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = listOfNotNull(
                                        currentInfo.positionText.takeIf { it.isNotBlank() },
                                        currentInfo.postedAt.takeIf { it.isNotBlank() }
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = StoryViewerMutedContentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (showMoreButton) {
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.menu_more),
                        tint = StoryViewerContentColor
                    )
                }
            }

            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = StoryViewerContentColor
                )
            }
        }
    }
}

@Composable
private fun StoryViewerActionsPopup(
    visible: Boolean,
    story: StoryModel?,
    currentUserId: Long?,
    isPremiumUser: Boolean,
    stealthMode: StoryStealthModeModel,
    storyOptions: StoryOptionsModel,
    canManageStories: Boolean,
    viewerSource: StoryViewerSource,
    activeListType: StoryListType,
    isMediaScaledToFill: Boolean,
    onDismiss: () -> Unit,
    onMediaScaleToggle: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCopyMedia: () -> Unit,
    onCopyStoryLink: () -> Unit,
    onLinks: () -> Unit,
    onEdit: () -> Unit,
    onToggleProfileVisibility: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onStatistics: () -> Unit,
    onDelete: () -> Unit,
    onActivateStealthMode: () -> Unit
) {
    if (story == null) return

    val context = LocalContext.current
    val nowSeconds by produceState(initialValue = (System.currentTimeMillis() / 1000L).toInt()) {
        while (true) {
            value = (System.currentTimeMillis() / 1000L).toInt()
            delay(1_000)
        }
    }
    val stealthAvailability = remember(
        story.id,
        story.posterChatId,
        currentUserId,
        isPremiumUser,
        stealthMode.activeUntilDate,
        stealthMode.cooldownUntilDate,
        nowSeconds
    ) {
        resolveStoryStealthAvailability(
            isPremiumUser = isPremiumUser,
            currentUserId = currentUserId,
            story = story,
            stealthMode = stealthMode,
            nowSeconds = nowSeconds
        )
    }
    val menuActions = remember(story, canManageStories, activeListType, viewerSource) {
        buildStoryViewerMenuActions(
            story = story,
            canManageStories = canManageStories,
            activeListType = activeListType,
            viewerSource = viewerSource
        )
    }

    ActionMenuPopup(
        visible = visible,
        onDismiss = onDismiss,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 56.dp, end = 12.dp)
    ) {
        MenuToggleRow(
            icon = Icons.Rounded.Image,
            title = stringResource(R.string.story_media_fill_title),
            isChecked = isMediaScaledToFill,
            onCheckedChange = onMediaScaleToggle
        )

        if (stealthAvailability != StoryStealthAvailability.HIDDEN) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = StoryViewerBorderColor
            )
            val remainingValue = when (stealthAvailability) {
                StoryStealthAvailability.ACTIVE -> {
                    compactStoryDurationText(
                        (stealthMode.activeUntilDate - nowSeconds).coerceAtLeast(0)
                    )
                }

                StoryStealthAvailability.COOLDOWN -> {
                    compactStoryDurationText(
                        (stealthMode.cooldownUntilDate - nowSeconds).coerceAtLeast(0)
                    )
                }

                else -> null
            }
            val subtitle = when (stealthAvailability) {
                StoryStealthAvailability.AVAILABLE -> {
                    val past = compactStoryDurationText(storyOptions.stealthModePastPeriod)
                    val future = compactStoryDurationText(storyOptions.stealthModeFuturePeriod)
                    stringResource(R.string.story_stealth_mode_available, past, future)
                }

                StoryStealthAvailability.ACTIVE -> {
                    stringResource(R.string.story_stealth_mode_active)
                }

                StoryStealthAvailability.COOLDOWN -> {
                    stringResource(
                        R.string.story_stealth_mode_cooldown_until,
                        formatStoryPostedTime(context, stealthMode.cooldownUntilDate)
                    )
                }

                StoryStealthAvailability.HIDDEN -> null
            }
            MenuOptionRow(
                icon = Icons.Rounded.VisibilityOff,
                title = stringResource(R.string.story_stealth_mode_title),
                subtitle = subtitle,
                value = remainingValue,
                enabled = stealthAvailability == StoryStealthAvailability.AVAILABLE,
                onClick = {
                    onDismiss()
                    onActivateStealthMode()
                }
            )
        }

        if (menuActions.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = StoryViewerBorderColor
            )
        }

        menuActions.forEachIndexed { index, item ->
            if (index > 0 && menuActions[index - 1].group != item.group) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = StoryViewerBorderColor
                )
            }
            MenuOptionRow(
                icon = storyViewerMenuIcon(item.action),
                title = storyViewerMenuTitle(item.action, story),
                destructive = item.action == StoryViewerMenuAction.DELETE,
                onClick = {
                    onDismiss()
                    when (item.action) {
                        StoryViewerMenuAction.DOWNLOAD -> onDownload()
                        StoryViewerMenuAction.COPY_MEDIA -> onCopyMedia()
                        StoryViewerMenuAction.COPY_STORY_LINK -> onCopyStoryLink()
                        StoryViewerMenuAction.LINKS -> onLinks()
                        StoryViewerMenuAction.EDIT -> onEdit()
                        StoryViewerMenuAction.KEEP_ON_PROFILE,
                        StoryViewerMenuAction.REMOVE_FROM_PROFILE -> onToggleProfileVisibility()
                        StoryViewerMenuAction.ARCHIVE -> onArchive()
                        StoryViewerMenuAction.RESTORE -> onRestore()
                        StoryViewerMenuAction.STATISTICS -> onStatistics()
                        StoryViewerMenuAction.DELETE -> onDelete()
                    }
                }
            )
        }
    }
}

@Composable
private fun compactStoryDurationText(seconds: Int): String {
    if (seconds <= 0) {
        return pluralStringResource(R.plurals.story_duration_compact_minutes, 0, 0)
    }

    return if (seconds % 3600 == 0) {
        val hours = seconds / 3600
        pluralStringResource(R.plurals.story_duration_compact_hours, hours, hours)
    } else {
        val minutes = (seconds / 60).coerceAtLeast(1)
        pluralStringResource(R.plurals.story_duration_compact_minutes, minutes, minutes)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryLinksSheetComponent(
    urls: List<String>,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyLink: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = StoryViewerSheetColor,
        contentColor = StoryViewerContentColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.story_links_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                itemsIndexed(urls, key = { index, url -> "$index-$url" }) { index, url ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenLink(url) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(Icons.Rounded.Link, contentDescription = null)
                        },
                        headlineContent = {
                            Text(
                                text = url,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { onCopyLink(url) }) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.action_copy_clipboard)
                                )
                            }
                        }
                    )
                    if (index < urls.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = StoryViewerBorderColor
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryInteractionsSheetComponent(
    page: StoryInteractionPageModel?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onInteractionClick: (Long) -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = StoryViewerSheetColor,
        contentColor = StoryViewerContentColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.story_interactions_title),
                style = MaterialTheme.typography.headlineSmall
            )
            if (page != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        StoryMetadataChip(
                            icon = Icons.Rounded.PeopleAlt,
                            label = stringResource(
                                R.string.story_interactions_views_format,
                                page.totalCount
                            )
                        )
                    }
                    item {
                        StoryMetadataChip(
                            icon = Icons.Rounded.Share,
                            label = stringResource(
                                R.string.story_interactions_shares_format,
                                page.totalForwardCount
                            )
                        )
                    }
                    item {
                        StoryMetadataChip(
                            icon = Icons.Rounded.Favorite,
                            label = stringResource(
                                R.string.story_interactions_reactions_format,
                                page.totalReactionCount
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            when {
                isLoading && page == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                page == null || page.interactions.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.story_interactions_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = StoryViewerMutedContentColor
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            page.interactions,
                            key = { index, interaction ->
                                "${interaction.actorType}:${interaction.actorId}:${interaction.interactionDate}:$index"
                            }
                        ) { index, interaction ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    onInteractionClick(interaction.actorId)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = {
                                    StoryInteractionAvatar(
                                        avatarPath = interaction.actorAvatarPath,
                                        isChat = interaction.actorType == StoryInteractionActorType.CHAT
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = interaction.actorTitle
                                            ?: interaction.actorId.toString(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    StoryInteractionSupportingContent(
                                        reaction = interaction.reaction,
                                        label = storyInteractionTypeLabel(
                                            interaction.type,
                                            interaction.reaction
                                        ),
                                        postedAt = formatStoryPostedTime(
                                            context,
                                            interaction.interactionDate
                                        )
                                    )
                                }
                            )
                            if (index < page.interactions.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = StoryViewerBorderColor
                                )
                            }
                        }
                        if (page.canLoadMore) {
                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.story_interactions_load_more))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryReactionPickerSheetComponent(
    availableReactions: StoryAvailableReactionsModel?,
    selectedReaction: StoryReactionModel?,
    isLoading: Boolean,
    isPremiumUser: Boolean,
    onDismiss: () -> Unit,
    onReactionSelected: (StoryReactionModel) -> Unit
) {
    val stickerRepository: StickerRepository = koinInject()
    var showEmojiBrowser by rememberSaveable { mutableStateOf(false) }
    val sections = remember(availableReactions) { buildStoryReactionSections(availableReactions) }
    val unavailabilityReason = availableReactions?.unavailabilityReason
    val canSelectReactions = unavailabilityReason == null
    val canOpenCustomEmoji = availableReactions?.allowCustomEmoji == true &&
            isPremiumUser &&
            canSelectReactions

    if (showEmojiBrowser) {
        ModalBottomSheet(
            onDismissRequest = { showEmojiBrowser = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = StoryViewerSheetColor,
            contentColor = StoryViewerContentColor,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            StickerEmojiMenu(
                onStickerSelected = {},
                onEmojiSelected = { emoji, sticker ->
                    val reaction = sticker?.customEmojiId
                        ?.takeIf { it != 0L }
                        ?.let { StoryReactionModel(customEmojiId = it) }
                        ?: StoryReactionModel(emoji = emoji)
                    onReactionSelected(reaction)
                },
                onGifSelected = {},
                emojiOnlyMode = true,
                onSearchFocused = {},
                canSendStickers = false,
                stickerRepository = stickerRepository
            )
        }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = StoryViewerSheetColor,
        contentColor = StoryViewerContentColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.story_reaction_picker_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (availableReactions?.allowCustomEmoji == true) {
                StoryReactionPickerCustomEmojiButton(
                    enabled = canOpenCustomEmoji,
                    isPremiumUser = isPremiumUser,
                    selectedReaction = selectedReaction,
                    onClick = { showEmojiBrowser = true }
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            if (unavailabilityReason != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                ) {
                    Text(
                        text = storyReactionUnavailabilityLabel(unavailabilityReason),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            when {
                isLoading && availableReactions == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                sections.isEmpty() && availableReactions?.allowCustomEmoji != true -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.story_reaction_picker_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = StoryViewerMutedContentColor
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        sections.forEach { section ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                color = StoryViewerSheetSurfaceColor
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = stringResource(section.titleResId),
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StoryViewerMutedContentColor,
                                        textAlign = TextAlign.Start
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    StoryReactionPickerGrid(
                                        reactions = section.reactions,
                                        selectedReaction = selectedReaction,
                                        isPremiumUser = isPremiumUser,
                                        canSelectReactions = canSelectReactions,
                                        onReactionSelected = onReactionSelected
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
private fun StoryReactionPickerGrid(
    reactions: List<StoryAvailableReactionModel>,
    selectedReaction: StoryReactionModel?,
    isPremiumUser: Boolean,
    canSelectReactions: Boolean,
    onReactionSelected: (StoryReactionModel) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 560.dp -> 6
            maxWidth >= 440.dp -> 5
            maxWidth >= 320.dp -> 4
            else -> 3
        }
        val rows = remember(reactions, columns) { reactions.chunked(columns) }
        var expanded by rememberSaveable(reactions.size, columns) { mutableStateOf(false) }
        val visibleRows = remember(rows, expanded) {
            if (expanded || rows.size <= 3) rows else rows.take(3)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            visibleRows.forEach { rowItems ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(
                                if (rowItems.size == columns) 1f else rowItems.size / columns.toFloat()
                            )
                            .align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { item ->
                            StoryReactionPickerChip(
                                modifier = Modifier.weight(1f),
                                item = item,
                                selected = item.reaction == selectedReaction,
                                enabled = canSelectReactions &&
                                        (!item.needsPremium || isPremiumUser) &&
                                        !item.reaction.isPaid,
                                onClick = { onReactionSelected(item.reaction) }
                            )
                        }
                    }
                }
            }
            if (rows.size > 3) {
                StoryReactionPickerExpandButton(
                    expanded = expanded,
                    hiddenCount = reactions.size - visibleRows.flatten().size,
                    onClick = { expanded = !expanded }
                )
            }
        }
    }
}

@Composable
private fun StoryReactionPickerCustomEmojiButton(
    enabled: Boolean,
    isPremiumUser: Boolean,
    selectedReaction: StoryReactionModel?,
    onClick: () -> Unit
) {
    val isSelected = selectedReaction?.isCustomEmoji == true
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else if (enabled) {
            StoryViewerSheetSurfaceColor
        } else {
            StoryViewerSheetSurfaceVariantColor.copy(alpha = 0.72f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    StoryViewerSheetSurfaceVariantColor
                }
            ) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedReaction != null && selectedReaction.isCustomEmoji) {
                        StoryReactionVisual(
                            reaction = selectedReaction,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.EmojiEmotions,
                            contentDescription = null
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.story_reaction_picker_custom_emoji),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        StoryViewerContentColor
                    }
                )
                if (isSelected) {
                    Text(
                        text = storyReactionLabel(selectedReaction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                } else if (!isPremiumUser) {
                    Text(
                        text = stringResource(R.string.story_reaction_picker_custom_emoji_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = StoryViewerMutedContentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryReactionPickerExpandButton(
    expanded: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = StoryViewerSheetSurfaceVariantColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.statistics_show_less)
                } else {
                    "${stringResource(R.string.action_show_more)}${if (hiddenCount > 0) " ($hiddenCount)" else ""}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = StoryViewerMutedContentColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StoryReactionPickerChip(
    modifier: Modifier = Modifier,
    item: StoryAvailableReactionModel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .then(modifier)
            .clip(RoundedCornerShape(18.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else if (enabled) {
            StoryViewerSheetSurfaceVariantColor
        } else {
            StoryViewerSheetSurfaceVariantColor.copy(alpha = 0.72f)
        }
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StoryReactionVisual(
                    reaction = item.reaction,
                    modifier = Modifier.size(24.dp)
                )
                if (item.needsPremium) {
                    Text(
                        text = stringResource(R.string.story_reaction_picker_premium_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                        } else {
                            StoryViewerMutedContentColor.copy(alpha = 0.82f)
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryInteractionSupportingContent(
    reaction: StoryReactionModel?,
    label: String,
    postedAt: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (reaction?.customEmojiId != null) {
            StoryReactionVisual(
                reaction = reaction,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = "• $postedAt",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun StoryReactionVisual(
    reaction: StoryReactionModel,
    modifier: Modifier = Modifier
) {
    val customEmojiId = reaction.customEmojiId
    if (customEmojiId != null) {
        val stickerRepository: StickerRepository = koinInject()
        val path by stickerRepository.getCustomEmojiFile(customEmojiId)
            .collectAsState(initial = null)
        if (path != null) {
            StickerImage(
                path = path,
                modifier = modifier
            )
        } else {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = storyReactionLabel(reaction),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = storyReactionLabel(reaction),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun storyReactionUnavailabilityLabel(
    reason: StoryReactionUnavailabilityReasonModel
): String {
    return when (reason) {
        StoryReactionUnavailabilityReasonModel.ANONYMOUS_ADMINISTRATOR -> {
            stringResource(R.string.story_reaction_picker_unavailable_anonymous_admin)
        }

        StoryReactionUnavailabilityReasonModel.GUEST -> {
            stringResource(R.string.story_reaction_picker_unavailable_guest)
        }

        StoryReactionUnavailabilityReasonModel.RESTRICTED -> {
            stringResource(R.string.story_reaction_picker_unavailable_restricted)
        }
    }
}
