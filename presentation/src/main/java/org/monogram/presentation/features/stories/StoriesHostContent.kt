package org.monogram.presentation.features.stories

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import org.monogram.domain.models.stories.StoryAreaTypeModel
import org.monogram.domain.models.stories.StoryAvailableReactionModel
import org.monogram.domain.models.stories.StoryAvailableReactionsModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryInteractionTypeModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryStealthModeModel
import org.monogram.presentation.R
import org.monogram.presentation.features.viewers.VideoViewer
import java.util.Date
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

internal val StoryViewerContentColor = Color(0xFFF5F7FB)
internal val StoryViewerMutedContentColor = Color(0xB8F5F7FB)
internal val StoryViewerBorderColor = Color(0x26F5F7FB)
internal val StoryViewerSheetColor = Color(0xFF12161F)
internal val StoryViewerSheetSurfaceColor = Color(0xFF1A2028)
internal val StoryViewerSheetSurfaceVariantColor = Color(0xFF262D3A)

internal fun storyViewerOverlayColor(alpha: Float = 0.78f): Color {
    return Color(0xFF12161F).copy(alpha = alpha)
}

@Composable
fun StoriesHostContent(component: StoriesHostComponent) {
    val state by component.state.collectAsState()
    if (!state.isVisible) return

    BackHandler(enabled = state.isVisible) {
        when {
            state.showInlineVideo -> component.dismissInlineVideo()
            state.showCamera -> component.dismissCamera()
            state.showMediaPicker -> component.dismissMediaPicker()
            state.isStoryReactionPickerVisible -> component.dismissStoryReactionPicker()
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f)
    ) {
        when (state.mode) {
            StoriesHostComponent.Mode.Viewer -> StoryViewerOverlay(state, component)
            StoriesHostComponent.Mode.Composer -> StoryComposerOverlay(state, component)
            StoriesHostComponent.Mode.Hidden -> Unit
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
        contentColor = StoryViewerContentColor
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
    StoryViewerScaffoldComponent(
        state = state,
        component = component,
        story = story
    )
}

@Composable
internal fun StoryViewerChrome(
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
    StoryViewerChromeComponent(
        state = state,
        story = story,
        progress = progress,
        isVideo = isVideo,
        isVideoPaused = isVideoPaused,
        isVideoMuted = isVideoMuted,
        isVideoBuffering = isVideoBuffering,
        isVideoPlaying = isVideoPlaying,
        isMediaScaledToFill = isMediaScaledToFill,
        onBack = onBack,
        onPauseToggle = onPauseToggle,
        onMuteToggle = onMuteToggle,
        onReactionClick = onReactionClick,
        onMediaScaleToggle = onMediaScaleToggle,
        onProfileClick = onProfileClick,
        onLinks = onLinks,
        onEdit = onEdit,
        onToggleProfileVisibility = onToggleProfileVisibility,
        onArchive = onArchive,
        onRestore = onRestore,
        onStatistics = onStatistics,
        onDelete = onDelete,
        onDownload = onDownload,
        onCopyMedia = onCopyMedia,
        onCopyStoryLink = onCopyStoryLink,
        onActivateStealthMode = onActivateStealthMode
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryLinksSheet(
    urls: List<String>,
    onDismiss: () -> Unit,
    onOpenLink: (String) -> Unit,
    onCopyLink: (String) -> Unit
) {
    StoryLinksSheetComponent(
        urls = urls,
        onDismiss = onDismiss,
        onOpenLink = onOpenLink,
        onCopyLink = onCopyLink
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryInteractionsSheet(
    page: StoryInteractionPageModel?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onInteractionClick: (Long) -> Unit
) {
    StoryInteractionsSheetComponent(
        page = page,
        isLoading = isLoading,
        onDismiss = onDismiss,
        onLoadMore = onLoadMore,
        onInteractionClick = onInteractionClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryReactionPickerSheet(
    availableReactions: StoryAvailableReactionsModel?,
    selectedReaction: StoryReactionModel?,
    isLoading: Boolean,
    isPremiumUser: Boolean,
    onDismiss: () -> Unit,
    onReactionSelected: (StoryReactionModel) -> Unit
) {
    StoryReactionPickerSheetComponent(
        availableReactions = availableReactions,
        selectedReaction = selectedReaction,
        isLoading = isLoading,
        isPremiumUser = isPremiumUser,
        onDismiss = onDismiss,
        onReactionSelected = onReactionSelected
    )
}

@Composable
internal fun StoryInteractionAvatar(
    avatarPath: String?,
    isChat: Boolean
) {
    Surface(
        shape = CircleShape,
        color = StoryViewerSheetSurfaceVariantColor,
        modifier = Modifier.size(40.dp)
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
                    imageVector = if (isChat) Icons.Rounded.PeopleAlt else Icons.Rounded.Person,
                    contentDescription = null,
                    tint = StoryViewerMutedContentColor
                )
            }
        }
    }
}

@Composable
internal fun StoryMetadataChip(
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
            disabledContainerColor = StoryViewerSheetSurfaceVariantColor.copy(alpha = 0.82f),
            disabledLabelColor = StoryViewerContentColor,
            disabledLeadingIconContentColor = StoryViewerMutedContentColor
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = false,
            borderColor = Color.Transparent
        )
    )
}

@Composable
internal fun StoryErrorBanner(message: String) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StoryComposerOverlay(
    state: StoriesHostComponent.State,
    component: StoriesHostComponent
) {
    StoryComposerOverlayComponent(
        state = state,
        component = component
    )
}

@Composable
private fun StorySettingsCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    StorySettingsCardComponent(title = title, subtitle = subtitle, content = content)
}

@Composable
private fun StoryCapabilityCard(
    presentation: StoryCapabilityPresentation
) {
    StoryCapabilityCardComponent(presentation = presentation)
}

@Composable
private fun StoryStripTile(
    title: String,
    avatarPath: String?,
    hasUnread: Boolean,
    onClick: () -> Unit
) {
    StoryStripTileComponent(
        title = title,
        avatarPath = avatarPath,
        hasUnread = hasUnread,
        onClick = onClick
    )
}

@Composable
private fun AddStoryStripTile(
    onClick: () -> Unit
) {
    AddStoryStripTileComponent(onClick = onClick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryPrivacySection(
    selected: StoryPrivacyUi,
    onSelect: (StoryPrivacyUi) -> Unit
) {
    StoryPrivacySectionComponent(selected = selected, onSelect = onSelect)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryDurationSection(
    selectedSeconds: Int,
    onSelect: (Int) -> Unit
) {
    StoryDurationSectionComponent(selectedSeconds = selectedSeconds, onSelect = onSelect)
}

@Composable
private fun StorySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    StorySwitchRowComponent(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

internal fun inferUriMediaType(uri: Uri): StoryMediaType {
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
internal fun formatStoryDurationLabel(seconds: Int): String {
    return when (seconds) {
        6 * 60 * 60 -> stringResource(R.string.story_duration_6h)
        12 * 60 * 60 -> stringResource(R.string.story_duration_12h)
        24 * 60 * 60 -> stringResource(R.string.story_duration_24h)
        48 * 60 * 60 -> stringResource(R.string.story_duration_48h)
        else -> "${(seconds / 3600f).roundToInt()}h"
    }
}

internal data class StoryViewerMenuItem(
    val action: StoryViewerMenuAction,
    val group: StoryViewerMenuGroup
)

internal enum class StoryViewerMenuAction {
    DOWNLOAD,
    COPY_MEDIA,
    COPY_STORY_LINK,
    LINKS,
    EDIT,
    KEEP_ON_PROFILE,
    REMOVE_FROM_PROFILE,
    ARCHIVE,
    RESTORE,
    STATISTICS,
    DELETE
}

internal enum class StoryViewerMenuGroup {
    CONTENT,
    MANAGE,
    DESTRUCTIVE
}

@Composable
internal fun StoryTopIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = storyViewerOverlayColor(alpha = 0.82f)
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
internal fun StoryHeaderSkeleton() {
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
internal fun StorySkeletonBar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
    ) {
        StorySkeletonPlaceholder()
    }
}

@Composable
internal fun StorySkeletonPlaceholder(modifier: Modifier = Modifier) {
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

internal data class StoryCapabilityPresentation(
    val message: String,
    val isBlocking: Boolean
)

internal enum class StoryStealthAvailability {
    HIDDEN,
    AVAILABLE,
    ACTIVE,
    COOLDOWN
}

internal const val STORY_MEDIA_ASPECT_RATIO = 9f / 16f

internal fun resolveStoryStealthAvailability(
    isPremiumUser: Boolean,
    currentUserId: Long?,
    story: StoryModel?,
    stealthMode: StoryStealthModeModel,
    nowSeconds: Int
): StoryStealthAvailability {
    if (!isPremiumUser || story == null || currentUserId == null || story.posterChatId == currentUserId) {
        return StoryStealthAvailability.HIDDEN
    }

    return when {
        stealthMode.isActiveAt(nowSeconds) -> StoryStealthAvailability.ACTIVE
        stealthMode.isCoolingDownAt(nowSeconds) -> StoryStealthAvailability.COOLDOWN
        else -> StoryStealthAvailability.AVAILABLE
    }
}

@Composable
internal fun storyAreaPrimaryLabel(areaType: StoryAreaTypeModel): String {
    return when (areaType) {
        is StoryAreaTypeModel.Link -> stringResource(R.string.story_links_title)
        is StoryAreaTypeModel.Location -> areaType.label
        is StoryAreaTypeModel.Venue -> areaType.title
        is StoryAreaTypeModel.Message -> stringResource(R.string.story_area_message)
        is StoryAreaTypeModel.UpgradedGift -> areaType.giftName.ifBlank {
            stringResource(R.string.story_area_gift)
        }

        is StoryAreaTypeModel.Weather -> stringResource(
            R.string.story_area_weather_format,
            areaType.emoji,
            areaType.temperature.roundToInt()
        )

        is StoryAreaTypeModel.SuggestedReaction -> storyReactionLabel(areaType.reaction)
    }
}

@Composable
internal fun storyAreaSecondaryLabel(areaType: StoryAreaTypeModel): String? {
    return when (areaType) {
        is StoryAreaTypeModel.Link -> stringResource(R.string.story_area_link_hint)
        is StoryAreaTypeModel.Location -> stringResource(R.string.story_area_location)
        is StoryAreaTypeModel.Venue -> areaType.address?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.story_area_venue)

        is StoryAreaTypeModel.Message -> stringResource(R.string.story_area_message_hint)
        is StoryAreaTypeModel.UpgradedGift -> stringResource(R.string.story_area_gift)
        is StoryAreaTypeModel.Weather -> null
        is StoryAreaTypeModel.SuggestedReaction -> areaType.totalCount.takeIf { it > 0 }?.toString()
    }
}

internal fun storyReactionLabel(reaction: StoryReactionModel): String {
    return when {
        reaction.emoji != null -> reaction.emoji.orEmpty()
        reaction.customEmojiId != null -> "✨"
        reaction.isPaid -> "⭐"
        else -> "❤"
    }
}

internal fun formatCompactStoryCount(count: Int): String {
    val absoluteCount = count.toLong().absoluteValue
    if (absoluteCount < 1_000L) return count.toString()

    val formatted = when {
        absoluteCount < 1_000_000L -> formatCompactStoryCountValue(absoluteCount, 1_000L, "K")
        else -> formatCompactStoryCountValue(absoluteCount, 1_000_000L, "M")
    }

    return if (count < 0) "-$formatted" else formatted
}

private fun formatCompactStoryCountValue(
    count: Long,
    divisor: Long,
    suffix: String
): String {
    val whole = count / divisor
    val remainder = count % divisor
    val decimalStep = divisor / 10L
    val decimal = if (decimalStep == 0L) 0L else remainder / decimalStep

    return if (whole >= 100L || decimal == 0L) {
        "$whole$suffix"
    } else {
        "$whole.$decimal$suffix"
    }
}

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

internal fun resolveStoryDownloadPath(story: StoryModel?): String? {
    return story?.media?.path?.takeIf { it.isNotBlank() }
        ?: story?.media?.previewPath?.takeIf { it.isNotBlank() }
}

internal fun buildStoryViewerMenuActions(
    story: StoryModel?,
    canManageStories: Boolean,
    activeListType: StoryListType,
    viewerSource: StoryViewerSource
): List<StoryViewerMenuItem> {
    if (story == null) return emptyList()

    return buildList {
        if (resolveStoryDownloadPath(story) != null) {
            add(
                StoryViewerMenuItem(
                    action = StoryViewerMenuAction.DOWNLOAD,
                    group = StoryViewerMenuGroup.CONTENT
                )
            )
        }
        if (resolveStoryDownloadPath(story) != null) {
            add(
                StoryViewerMenuItem(
                    action = StoryViewerMenuAction.COPY_MEDIA,
                    group = StoryViewerMenuGroup.CONTENT
                )
            )
        }
        add(
            StoryViewerMenuItem(
                action = StoryViewerMenuAction.COPY_STORY_LINK,
                group = StoryViewerMenuGroup.CONTENT
            )
        )
        if (story.linkUrls.isNotEmpty()) {
            add(
                StoryViewerMenuItem(
                    action = StoryViewerMenuAction.LINKS,
                    group = StoryViewerMenuGroup.CONTENT
                )
            )
        }
        if (story.canBeEdited && resolveStoryEditableMediaPath(story) != null) {
            add(
                StoryViewerMenuItem(
                    action = StoryViewerMenuAction.EDIT,
                    group = StoryViewerMenuGroup.MANAGE
                )
            )
        }
        if (story.canToggleIsPostedToChatPage) {
            add(
                StoryViewerMenuItem(
                    action = if (story.isPostedToChatPage) {
                        StoryViewerMenuAction.REMOVE_FROM_PROFILE
                    } else {
                        StoryViewerMenuAction.KEEP_ON_PROFILE
                    },
                    group = StoryViewerMenuGroup.MANAGE
                )
            )
        }
        if (canManageStories && viewerSource == StoryViewerSource.ACTIVE) {
            add(
                StoryViewerMenuItem(
                    action = if (activeListType == StoryListType.MAIN) {
                        StoryViewerMenuAction.ARCHIVE
                    } else {
                        StoryViewerMenuAction.RESTORE
                    },
                    group = StoryViewerMenuGroup.MANAGE
                )
            )
        }
        if (story.canGetStatistics) {
            add(
                StoryViewerMenuItem(
                    action = StoryViewerMenuAction.STATISTICS,
                    group = StoryViewerMenuGroup.MANAGE
                )
            )
        }
        if (story.canBeDeleted) {
            add(
                StoryViewerMenuItem(
                    action = StoryViewerMenuAction.DELETE,
                    group = StoryViewerMenuGroup.DESTRUCTIVE
                )
            )
        }
    }
}

@Composable
internal fun storyViewerMenuTitle(
    action: StoryViewerMenuAction,
    story: StoryModel?
): String {
    return when (action) {
        StoryViewerMenuAction.DOWNLOAD -> stringResource(R.string.action_download)
        StoryViewerMenuAction.COPY_MEDIA -> stringResource(
            if (story?.media?.type == StoryMediaType.VIDEO && !story?.media?.path.isNullOrBlank()) {
                R.string.action_copy_frame
            } else {
                R.string.action_copy_image
            }
        )

        StoryViewerMenuAction.COPY_STORY_LINK -> stringResource(R.string.action_copy_link)
        StoryViewerMenuAction.LINKS -> stringResource(R.string.story_links_title)
        StoryViewerMenuAction.EDIT -> stringResource(R.string.action_edit)
        StoryViewerMenuAction.KEEP_ON_PROFILE -> stringResource(R.string.story_keep_on_profile)
        StoryViewerMenuAction.REMOVE_FROM_PROFILE -> stringResource(R.string.story_remove_from_profile)
        StoryViewerMenuAction.ARCHIVE -> stringResource(R.string.story_archive)
        StoryViewerMenuAction.RESTORE -> stringResource(R.string.action_restore)
        StoryViewerMenuAction.STATISTICS -> stringResource(R.string.story_statistics_title)
        StoryViewerMenuAction.DELETE -> stringResource(R.string.menu_delete)
    }
}

internal fun storyViewerMenuIcon(
    action: StoryViewerMenuAction
): androidx.compose.ui.graphics.vector.ImageVector {
    return when (action) {
        StoryViewerMenuAction.DOWNLOAD -> Icons.Rounded.Download
        StoryViewerMenuAction.COPY_MEDIA -> Icons.Rounded.ContentCopy
        StoryViewerMenuAction.COPY_STORY_LINK -> Icons.Rounded.Link
        StoryViewerMenuAction.LINKS -> Icons.Rounded.Link
        StoryViewerMenuAction.EDIT -> Icons.Rounded.Edit
        StoryViewerMenuAction.KEEP_ON_PROFILE -> Icons.Rounded.Person
        StoryViewerMenuAction.REMOVE_FROM_PROFILE -> Icons.Rounded.Person
        StoryViewerMenuAction.ARCHIVE -> Icons.Rounded.Archive
        StoryViewerMenuAction.RESTORE -> Icons.Rounded.Restore
        StoryViewerMenuAction.STATISTICS -> Icons.Rounded.BarChart
        StoryViewerMenuAction.DELETE -> Icons.Rounded.Delete
    }
}

@Composable
internal fun storyInteractionTypeLabel(
    type: StoryInteractionTypeModel,
    reaction: StoryReactionModel?
): String {
    return when (type) {
        StoryInteractionTypeModel.VIEW -> when {
            reaction == null -> stringResource(R.string.story_interaction_view)
            reaction.emoji != null || reaction.isPaid -> {
                stringResource(
                    R.string.story_interaction_view_with_reaction,
                    storyReactionLabel(reaction)
                )
            }

            else -> stringResource(R.string.story_interaction_view_with_reaction_generic)
        }

        StoryInteractionTypeModel.FORWARD -> stringResource(R.string.story_interaction_forward)
        StoryInteractionTypeModel.REPOST -> stringResource(R.string.story_interaction_repost)
    }
}

internal data class StoryReactionSectionModel(
    val titleResId: Int,
    val reactions: List<StoryAvailableReactionModel>
)

internal fun buildStoryReactionSections(
    availableReactions: StoryAvailableReactionsModel?
): List<StoryReactionSectionModel> {
    availableReactions ?: return emptyList()
    val consumed = linkedSetOf<StoryReactionModel>()

    fun unique(source: List<StoryAvailableReactionModel>): List<StoryAvailableReactionModel> {
        return source.filter { item -> consumed.add(item.reaction) }
    }

    return buildList {
        unique(availableReactions.topReactions)
            .takeIf { it.isNotEmpty() }
            ?.let { add(StoryReactionSectionModel(R.string.story_reaction_picker_top, it)) }
        unique(availableReactions.recentReactions)
            .takeIf { it.isNotEmpty() }
            ?.let { add(StoryReactionSectionModel(R.string.story_reaction_picker_recent, it)) }
        unique(availableReactions.popularReactions)
            .takeIf { it.isNotEmpty() }
            ?.let { add(StoryReactionSectionModel(R.string.story_reaction_picker_popular, it)) }
    }
}

internal fun buildStoryPositionText(state: StoriesHostComponent.State): String {
    val currentChatId = state.chatId ?: return ""
    val totalInChat = state.viewerItems.count { it.chatId == currentChatId }
    if (totalInChat <= 1) return ""
    val currentIndexInChat = state.viewerItems
        .take(state.viewerIndex + 1)
        .count { it.chatId == currentChatId }
        .coerceAtLeast(1)
    return "$currentIndexInChat/$totalInChat"
}

internal fun formatStoryPostedTime(context: Context, dateSeconds: Int): String {
    return DateFormat.getTimeFormat(context).format(Date(dateSeconds * 1000L))
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

internal data class StoryHeaderInfoState(
    val title: String,
    val storyId: Int?,
    val positionText: String,
    val postedAt: String
)

internal fun canPublishStory(capability: StoryPostCapabilityModel?): Boolean {
    return capability == null || capability is StoryPostCapabilityModel.Allowed
}

@Composable
internal fun StoryPostCapabilityModel?.toCapabilityPresentation(): StoryCapabilityPresentation? {
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
