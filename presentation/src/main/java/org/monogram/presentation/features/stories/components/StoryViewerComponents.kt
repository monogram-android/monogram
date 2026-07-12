package org.monogram.presentation.features.stories

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.stories.StoryAreaTypeModel
import org.monogram.domain.models.stories.StoryInteractionActorType
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.presentation.R
import org.monogram.presentation.features.stickers.ui.menu.ActionMenuPopup
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.menu.MenuToggleRow

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
    onLinks: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onStatistics: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit
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
                onMore = { showMenu = true }
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                StoryViewerActionsPopup(
                    visible = showMenu,
                    story = story,
                    canManageStories = state.canManageStories,
                    activeListType = state.activeListType,
                    isMediaScaledToFill = isMediaScaledToFill,
                    onDismiss = { showMenu = false },
                    onMediaScaleToggle = onMediaScaleToggle,
                    onDownload = onDownload,
                    onLinks = onLinks,
                    onEdit = onEdit,
                    onArchive = onArchive,
                    onRestore = onRestore,
                    onStatistics = onStatistics,
                    onDelete = onDelete
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
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = story?.canGetInteractions == true || story?.areas?.any {
                            it.type is StoryAreaTypeModel.SuggestedReaction
                        } == true
                    ) {
                        StoryTopIconButton(
                            onClick = onReactionClick,
                            contentDescription = stringResource(R.string.story_reactions_button)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
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
    onBack: () -> Unit,
    showMoreButton: Boolean,
    onMore: () -> Unit
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

            if (showMoreButton) {
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.menu_more),
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
private fun StoryViewerActionsPopup(
    visible: Boolean,
    story: StoryModel?,
    canManageStories: Boolean,
    activeListType: StoryListType,
    isMediaScaledToFill: Boolean,
    onDismiss: () -> Unit,
    onMediaScaleToggle: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onLinks: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onStatistics: () -> Unit,
    onDelete: () -> Unit
) {
    if (story == null) return

    val menuActions = remember(story, canManageStories, activeListType) {
        buildStoryViewerMenuActions(
            story = story,
            canManageStories = canManageStories,
            activeListType = activeListType
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

        if (menuActions.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
        }

        menuActions.forEachIndexed { index, item ->
            if (index > 0 && menuActions[index - 1].group != item.group) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            }
            MenuOptionRow(
                icon = storyViewerMenuIcon(item.action),
                title = storyViewerMenuTitle(item.action),
                destructive = item.action == StoryViewerMenuAction.DELETE,
                onClick = {
                    onDismiss()
                    when (item.action) {
                        StoryViewerMenuAction.DOWNLOAD -> onDownload()
                        StoryViewerMenuAction.LINKS -> onLinks()
                        StoryViewerMenuAction.EDIT -> onEdit()
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
        containerColor = MaterialTheme.colorScheme.surface
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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
    onLoadMore: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    Text(
                                        text = buildString {
                                            append(
                                                storyInteractionTypeLabel(
                                                    interaction.type,
                                                    interaction.reaction
                                                )
                                            )
                                            append(" • ")
                                            append(
                                                formatStoryPostedTime(
                                                    context,
                                                    interaction.interactionDate
                                                )
                                            )
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            if (index < page.interactions.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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
