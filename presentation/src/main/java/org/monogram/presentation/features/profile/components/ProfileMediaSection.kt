package org.monogram.presentation.features.profile.components

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koin.compose.koinInject
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.presentation.R
import org.monogram.presentation.core.media.VideoStickerPlayer
import org.monogram.presentation.core.media.VideoType
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.core.ui.rememberShimmerBrush
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.conversation.ui.channel.formatViews
import org.monogram.presentation.features.profile.ProfileComponent
import org.monogram.presentation.features.profile.ProfileTabContentType
import org.monogram.presentation.features.profile.ProfileTabKey
import org.monogram.presentation.features.profile.ProfileTabSpec
import org.monogram.presentation.features.profile.isMemberTab
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.io.File

private const val LOAD_MORE_THRESHOLD = 40

@OptIn(ExperimentalFoundationApi::class)
fun LazyGridScope.profileMediaSection(
    state: ProfileComponent.State,
    tabs: List<ProfileTabSpec>,
    selectedTabKey: ProfileTabKey,
    onTabSelected: (ProfileTabKey) -> Unit,
    onMessageClick: (MessageModel) -> Unit,
    onMessageLongClick: (MessageModel) -> Unit,
    onLoadMore: () -> Unit,
    onMemberClick: (Long) -> Unit = {},
    onMemberLongClick: (Long) -> Unit = {},
    onChatClick: (Long) -> Unit = {},
    onLoadMedia: (MessageModel) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchDismissed: () -> Unit = {},
    onOpenActiveStory: (Int) -> Unit = {},
    onOpenPostedStory: (Int) -> Unit = {},
    onOpenArchive: () -> Unit = {}
) {
    if (tabs.isEmpty()) return

    val selectedIndex = tabs.indexOfFirst { it.key == selectedTabKey }.takeIf { it >= 0 } ?: 0
    val selectedTab = tabs.getOrElse(selectedIndex) { tabs.first() }
    val selectedMessageTab = state.messageTabState(selectedTab.key)
    val selectedMembersTab = state.memberTabState(selectedTab.key)

    stickyHeader {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(24.dp)
                        )
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        edgePadding = 4.dp,
                        divider = {},
                        indicator = {
                            Box(
                                Modifier
                                    .tabIndicatorOffset(selectedIndex)
                                    .fillMaxHeight()
                                    .padding(vertical = 4.dp)
                                    .zIndex(-1f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val selected = selectedIndex == index

                            Tab(
                                selected = selected,
                                onClick = { onTabSelected(tab.key) },
                                modifier = Modifier
                                    .height(44.dp)
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(24.dp)),
                                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = {
                                    Text(
                                        text = stringResource(tab.titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }

                if (selectedTab.key.isMemberTab() && selectedMembersTab.isSearchActive) {
                    ProfileMemberSearchField(
                        query = selectedMembersTab.searchQuery,
                        isSearching = selectedMembersTab.isSearching,
                        onQueryChange = onSearchQueryChanged,
                        onDismiss = onSearchDismissed
                    )
                }
            }
        }
    }

    when (selectedTab.key) {
        ProfileTabKey.STORIES -> storiesSection(
            state = state,
            onOpenActiveStory = onOpenActiveStory,
            onOpenPostedStory = onOpenPostedStory,
            onOpenArchive = onOpenArchive
        )

        ProfileTabKey.MEDIA -> mediaGrid(
            messages = state.mediaMessages,
            isLoading = selectedMessageTab.isLoadingInitial,
            canLoadMore = selectedMessageTab.canLoadMore,
            onLoadMore = onLoadMore,
            onMessageClick = onMessageClick,
            onLoadMedia = onLoadMedia
        )

        ProfileTabKey.MEMBERS,
        ProfileTabKey.ADMINS,
        ProfileTabKey.RESTRICTED,
        ProfileTabKey.BANNED -> membersList(
            members = selectedMembersTab.visibleItems,
            isLoading = selectedMembersTab.isLoadingInitial ||
                    (selectedMembersTab.isSearching && selectedMembersTab.visibleItems.isEmpty()),
            canLoadMore = selectedMembersTab.canLoadMore &&
                    !selectedMembersTab.isSearching &&
                    !selectedMembersTab.isSearchResultsVisible,
            onLoadMore = onLoadMore,
            onMemberClick = onMemberClick,
            onMemberLongClick = onMemberLongClick,
            emptyTextRes = if (selectedMembersTab.isSearchResultsVisible) {
                R.string.no_results_found
            } else {
                R.string.empty_members
            }
        )

        ProfileTabKey.SIMILAR -> chatsList(
            chats = state.relatedChats,
            isLoading = state.isSimilarChatsLoading,
            onChatClick = onChatClick
        )

        ProfileTabKey.FILES -> filesList(
            messages = state.fileMessages,
            isLoading = selectedMessageTab.isLoadingInitial,
            canLoadMore = selectedMessageTab.canLoadMore,
            onLoadMore = onLoadMore,
            onMessageClick = onMessageClick
        )

        ProfileTabKey.MUSIC -> musicList(
            messages = state.musicMessages,
            isLoading = selectedMessageTab.isLoadingInitial,
            canLoadMore = selectedMessageTab.canLoadMore,
            onLoadMore = onLoadMore,
            onMessageClick = onMessageClick
        )

        ProfileTabKey.VOICE -> voiceList(
            messages = state.voiceMessages,
            isLoading = selectedMessageTab.isLoadingInitial,
            canLoadMore = selectedMessageTab.canLoadMore,
            onLoadMore = onLoadMore,
            onMessageClick = onMessageClick
        )

        ProfileTabKey.LINKS -> linksList(
            messages = state.linkMessages,
            isLoading = selectedMessageTab.isLoadingInitial,
            canLoadMore = selectedMessageTab.canLoadMore,
            onLoadMore = onLoadMore,
            onMessageClick = onMessageClick
        )

        ProfileTabKey.GIFS -> gifsGrid(
            messages = state.gifMessages,
            isLoading = selectedMessageTab.isLoadingInitial,
            canLoadMore = selectedMessageTab.canLoadMore,
            onLoadMore = onLoadMore,
            onMessageClick = onMessageClick
        )
    }

    val itemCount = when (selectedTab.key) {
        ProfileTabKey.STORIES -> 0
        ProfileTabKey.MEDIA -> state.mediaMessages.size
        ProfileTabKey.MEMBERS,
        ProfileTabKey.ADMINS,
        ProfileTabKey.RESTRICTED,
        ProfileTabKey.BANNED -> selectedMembersTab.visibleItems.size

        ProfileTabKey.SIMILAR -> state.relatedChats.size
        ProfileTabKey.FILES -> state.fileMessages.size
        ProfileTabKey.MUSIC -> state.musicMessages.size
        ProfileTabKey.VOICE -> state.voiceMessages.size
        ProfileTabKey.LINKS -> state.linkMessages.size
        ProfileTabKey.GIFS -> state.gifMessages.size
    }
    val shouldAutoLoadMore = when (selectedTab.key) {
        ProfileTabKey.STORIES -> false
        ProfileTabKey.SIMILAR -> false
        else -> if (selectedTab.key.isMemberTab()) {
            selectedMembersTab.canLoadMore &&
                    !selectedMembersTab.isSearching &&
                    !selectedMembersTab.isSearchResultsVisible &&
                    !selectedMembersTab.isLoadingInitial &&
                    !selectedMembersTab.isLoadingNext &&
                    selectedMembersTab.visibleItems.isNotEmpty()
        } else {
            selectedMessageTab.canLoadMore &&
                    !selectedMessageTab.isLoadingInitial &&
                    !selectedMessageTab.isLoadingNext &&
                    selectedMessageTab.items.isNotEmpty()
        }
    }

    if (shouldAutoLoadMore) {
        item(span = { GridItemSpan(3) }, key = "loader_${selectedTab.key}_$itemCount") {
            LaunchedEffect(selectedTab.key, itemCount) {
                onLoadMore()
            }
            Spacer(modifier = Modifier.height(1.dp))
        }
    }

    val showMembersPaginationSkeleton =
        selectedTab.key.isMemberTab() &&
                selectedMembersTab.isLoadingNext &&
                selectedMembersTab.visibleItems.isNotEmpty()
    val showMediaPaginationSkeleton =
        !selectedTab.key.isMemberTab() &&
                selectedTab.key != ProfileTabKey.STORIES &&
                selectedMessageTab.isLoadingNext &&
                selectedMessageTab.items.isNotEmpty()

    if (showMembersPaginationSkeleton || showMediaPaginationSkeleton) {
        item(span = { GridItemSpan(3) }, key = "pagination_skeleton") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val shimmer = rememberShimmerBrush()
                if (showMembersPaginationSkeleton) {
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) {
                            MemberListSkeletonRow(shimmer = shimmer)
                        }
                    }
                } else {
                    when (selectedTab.contentType) {
                        ProfileTabContentType.MEDIA_GRID -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(shimmer)
                                    )
                                }
                            }
                        }
                        else -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                repeat(2) {
                                    MessageListSkeletonRow(shimmer = shimmer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.chatsList(
    chats: List<ChatModel>,
    isLoading: Boolean,
    onChatClick: (Long) -> Unit
) {
    val uniqueChats = chats.distinctBy { it.id }

    if (uniqueChats.isEmpty()) {
        item(span = { GridItemSpan(3) }, key = "similar_chats_empty") {
            if (isLoading) {
                val shimmer = rememberShimmerBrush()
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(4) {
                        MemberListSkeletonRow(shimmer = shimmer)
                    }
                }
            } else {
                EmptyState(stringResource(R.string.empty_similar_chats))
            }
        }
        return
    }

    itemsIndexed(
        uniqueChats,
        key = { _, chat -> "similar_${chat.id}" },
        span = { _, _ -> GridItemSpan(3) }
    ) { _, chat ->
        Column {
            ListItem(
                headlineContent = {
                    Text(
                        text = chat.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                supportingContent = {
                    val subtitle = when {
                        chat.memberCount > 0 -> pluralStringResource(
                            R.plurals.members_count_format,
                            chat.memberCount,
                            chat.memberCount
                        )

                        !chat.username.isNullOrBlank() -> "@${chat.username}"
                        !chat.description.isNullOrBlank() -> chat.description.orEmpty()
                        else -> stringResource(R.string.tab_similar)
                    }
                    Text(
                        text = subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    Avatar(
                        path = chat.avatarPath,
                        fallbackPath = chat.personalAvatarPath,
                        name = chat.title,
                        size = 42.dp,
                        isOnline = false
                    )
                },
                modifier = Modifier.clickable { onChatClick(chat.id) }
            )
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
        }
    }
}

@Composable
private fun ScrollableRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun ProfileMemberSearchField(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            ) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(stringResource(R.string.search_hint))
                    },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search)
                        )
                    },
                    trailingIcon = {
                        when {
                            isSearching -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }

                            query.isNotEmpty() -> {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.clear)
                                    )
                                }
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    )
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close_button)
                )
            }
        }
    }
}

private fun LazyGridScope.storiesSection(
    state: ProfileComponent.State,
    onOpenActiveStory: (Int) -> Unit,
    onOpenPostedStory: (Int) -> Unit,
    onOpenArchive: () -> Unit
) {
    val activeStories = state.activeStories.distinctBy { it.id }
    val postedStories = state.postedStories.distinctBy { it.id }
    val isCurrentUserProfile = state.user?.id != null && state.currentUser?.id == state.user?.id
    val canManageStories = isCurrentUserProfile || state.chat?.isAdmin == true

    if (state.isStoriesLoading && activeStories.isEmpty() && postedStories.isEmpty()) {
        item(span = { GridItemSpan(3) }, key = "stories_loading") {
            StoriesLoadingState()
        }
    } else if (activeStories.isEmpty() && postedStories.isEmpty()) {
        item(span = { GridItemSpan(3) }, key = "stories_empty") {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                EmptyState(stringResource(R.string.empty_stories))
            }
        }
    } else {
        if (activeStories.isNotEmpty()) {
            item(span = { GridItemSpan(3) }, key = "stories_active_header") {
                StorySectionHeader(title = stringResource(R.string.profile_stories_active_section))
            }
            item(span = { GridItemSpan(3) }, key = "stories_active_row") {
                StoryPreviewRow(
                    stories = activeStories,
                    onStoryClick = { story -> onOpenActiveStory(story.id) }
                )
            }
        }

        if (postedStories.isNotEmpty()) {
            item(span = { GridItemSpan(3) }, key = "stories_posted_row") {
                StoryPreviewRow(
                    stories = postedStories,
                    onStoryClick = { story -> onOpenPostedStory(story.id) }
                )
            }
        }
    }

    if (canManageStories) {
        item(span = { GridItemSpan(3) }, key = "stories_archive_spacer") {
            Spacer(modifier = Modifier.height(14.dp))
        }
        item(span = { GridItemSpan(3) }, key = "stories_archive") {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsTile(
                    icon = Icons.Rounded.Archive,
                    title = stringResource(R.string.story_archive),
                    subtitle = stringResource(R.string.profile_stories_archive_subtitle),
                    iconColor = MaterialTheme.colorScheme.secondary,
                    position = ItemPosition.STANDALONE,
                    onClick = onOpenArchive
                )
            }
        }
    }
}

@Composable
private fun StoriesLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp
        )
    }
}

@Composable
private fun StorySectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun StoryPreviewRow(
    stories: List<StoryModel>,
    onStoryClick: (StoryModel) -> Unit
) {
    ScrollableRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        stories.forEach { story ->
            StoryPreviewCard(
                story = story,
                onClick = { onStoryClick(story) }
            )
        }
    }
}

@Composable
private fun StoryPreviewCard(
    story: StoryModel,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val previewModel =
        remember(story.media.previewPath, story.media.path, story.media.minithumbnail) {
            story.media.previewPath ?: story.media.path ?: story.media.minithumbnail
        }
    val viewsText = remember(context, story.viewCount) { formatViews(context, story.viewCount) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(112.dp)
            .height(164.dp)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (previewModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(previewModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (story.media.type == org.monogram.domain.models.stories.StoryMediaType.VIDEO) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.media_type_video),
                        tint = Color.White,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            if (!story.isRead) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RemoveRedEye,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = viewsText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun LazyGridScope.membersList(
    members: List<GroupMemberModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMemberClick: (Long) -> Unit,
    onMemberLongClick: (Long) -> Unit,
    emptyTextRes: Int
) {
    val uniqueMembers = members.distinctBy { it.user.id }

    if (uniqueMembers.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "members_skeleton") {
                val shimmer = rememberShimmerBrush()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(8) {
                        MemberListSkeletonRow(shimmer = shimmer)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(emptyTextRes))
            }
        }
    } else {
        itemsIndexed(uniqueMembers, key = { _, member -> "member_${member.user.id}" }, span = { _, _ -> GridItemSpan(3) }) { index, member ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMembers.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            val user = member.user
            val title = listOfNotNull(user.firstName, user.lastName).joinToString(" ")
            val isVerified = user.isVerified

            ListItem(
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Verified,
                                contentDescription = stringResource(R.string.cd_verified),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (user.isSponsor) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = stringResource(R.string.cd_sponsor),
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFE53935)
                            )
                        }

                        if (!user.statusEmojiPath.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StickerImage(
                                path = user.statusEmojiPath!!,
                                modifier = Modifier.size(18.dp),
                                animate = false
                            )
                        } else if (user.isPremium) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF31A6FD)
                            )
                        }

                        if (member.rank != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                shape = CircleShape,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                Text(
                                    text = member.rank?:"",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                supportingContent = {
                    val context = LocalContext.current

                    val dateFormatManager: DateFormatManager = koinInject()
                    val timeFormat = dateFormatManager.getHourMinuteFormat()

                    val statusText = getUserStatusText(user, context, timeFormat)

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (user.userStatus == UserStatusType.ONLINE)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    Avatar(
                        path = user.avatarPath,
                        fallbackPath = user.personalAvatarPath,
                        name = user.firstName,
                        isOnline = user.userStatus == UserStatusType.ONLINE,
                        size = 40.dp,
                        modifier = Modifier.combinedClickable(
                            onClick = { onMemberClick(user.id) },
                            onLongClick = { onMemberLongClick(user.id) }
                        )
                    )
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onMemberClick(user.id) },
                    onLongClick = { onMemberLongClick(user.id) }
                )
            )
        }
    }
}

private fun LazyGridScope.mediaGrid(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit,
    onLoadMedia: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "media_skeleton") {
                MediaGridSkeleton(rows = 3)
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_media))
            }
        }
    } else {
        itemsIndexed(uniqueMessages, key = { _, msg -> "media_${msg.id}" }) { index, msg ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMessages.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onMessageClick(msg) }
            ) {
                val (path, isVideo) = when (val content = msg.content) {
                    is MessageContent.Photo -> content.path to false
                    is MessageContent.Video -> content.path to true
                    else -> null to false
                }

                val thumbnailPath = when (val content = msg.content) {
                    is MessageContent.Photo -> content.thumbnailPath
                    is MessageContent.Video -> content.thumbnailPath
                    else -> null
                }

                val minithumbnail = when (val content = msg.content) {
                    is MessageContent.Photo -> content.minithumbnail
                    is MessageContent.Video -> content.minithumbnail
                    else -> null
                }

                val context = LocalContext.current
                val isValidFile = path != null && File(path).exists()
                val isValidThumbnail = thumbnailPath != null && File(thumbnailPath).exists()

                if (isValidFile || isValidThumbnail || minithumbnail != null) {
                    AsyncImage(
                        model = remember(path, thumbnailPath, minithumbnail) {
                            val data = when {
                                isValidFile -> path
                                isValidThumbnail -> thumbnailPath
                                else -> minithumbnail
                            }
                            ImageRequest.Builder(context)
                                .data(data)
                                .size(300, 300)
                                .crossfade(true)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = rememberVectorPainter(Icons.Rounded.BrokenImage)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(rememberShimmerBrush()),
                        contentAlignment = Alignment.Center
                    ) {
                    }
                }

                if (!isValidFile && !isValidThumbnail) {
                    LaunchedEffect(msg.id) {
                        onLoadMedia(msg)
                    }
                }

                if (isVideo) {
                    val content = msg.content as MessageContent.Video
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.media_type_video),
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = formatDuration(content.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun LazyGridScope.musicList(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "music_skeleton") {
                val shimmer = rememberShimmerBrush()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(5) {
                        MessageListSkeletonRow(shimmer = shimmer)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_music))
            }
        }
    } else {
        itemsIndexed(
            uniqueMessages,
            key = { _, msg -> "music_${msg.id}" },
            span = { _, _ -> GridItemSpan(3) }) { index, msg ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMessages.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            val content = msg.content as? MessageContent.Document ?: return@itemsIndexed
            Column {
                ListItem(
                    headlineContent = {
                        Text(content.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text("${content.size / 1024} KB", maxLines = 1)
                    },
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { onMessageClick(msg) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

private fun LazyGridScope.voiceList(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "voice_skeleton") {
                val shimmer = rememberShimmerBrush()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(5) {
                        MessageListSkeletonRow(shimmer = shimmer)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_voice))
            }
        }
    } else {
        itemsIndexed(uniqueMessages, key = { _, msg -> "voice_${msg.id}" }, span = { _, _ -> GridItemSpan(3) }) { index, msg ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMessages.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            val (icon, labelRes, duration) = when (val content = msg.content) {
                is MessageContent.Voice -> Triple(Icons.Rounded.Mic, R.string.media_type_voice, content.duration)
                is MessageContent.VideoNote -> Triple(Icons.Rounded.Videocam, R.string.media_type_video_note, content.duration)
                else -> return@itemsIndexed
            }

            Column {
                ListItem(
                    headlineContent = { Text(stringResource(labelRes), fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(formatDuration(duration), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { onMessageClick(msg) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

private fun LazyGridScope.filesList(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "files_skeleton") {
                val shimmer = rememberShimmerBrush()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(5) {
                        MessageListSkeletonRow(shimmer = shimmer)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_files))
            }
        }
    } else {
        itemsIndexed(uniqueMessages, key = { _, msg -> "file_${msg.id}" }, span = { _, _ -> GridItemSpan(3) }) { index, msg ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMessages.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            when (val content = msg.content) {
                is MessageContent.Document -> {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(content.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text("${content.size / 1024} KB") },
                            leadingContent = {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onMessageClick(msg) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                else -> {}
            }
        }
    }
}

private fun LazyGridScope.linksList(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "links_skeleton") {
                val shimmer = rememberShimmerBrush()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    repeat(5) {
                        MessageListSkeletonRow(shimmer = shimmer)
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_links))
            }
        }
    } else {
        itemsIndexed(uniqueMessages, key = { _, msg -> "link_${msg.id}" }, span = { _, _ -> GridItemSpan(3) }) { index, msg ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMessages.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            val content = msg.content as? MessageContent.Text ?: return@itemsIndexed
            val context = LocalContext.current
            val matcher = android.util.Patterns.WEB_URL.matcher(content.text)
            val displayUrl = if (matcher.find()) matcher.group() else content.text

            Column {
                ListItem(
                    headlineContent = {
                        Text(displayUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    },
                    supportingContent = {
                        Text(content.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Rounded.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        try {
                            val url = if (!displayUrl.startsWith("http")) "https://$displayUrl" else displayUrl
                            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            onMessageClick(msg)
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}

private fun LazyGridScope.gifsGrid(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        if (isLoading) {
            item(span = { GridItemSpan(3) }, key = "gifs_skeleton") {
                MediaGridSkeleton(rows = 2)
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_gifs))
            }
        }
    } else {
        itemsIndexed(uniqueMessages, key = { _, msg -> "gif_${msg.id}" }) { index, msg ->

            // Trigger pre-loading
            if (canLoadMore && !isLoading && index >= uniqueMessages.size - LOAD_MORE_THRESHOLD) {
                LaunchedEffect(Unit) {
                    onLoadMore()
                }
            }

            val content = msg.content as? MessageContent.Gif ?: return@itemsIndexed
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .clickable { onMessageClick(msg) }
                    .background(Color.Black)
            ) {
                if (content.path != null) {
                    VideoStickerPlayer(
                        path = content.path!!,
                        type = VideoType.Gif,
                        modifier = Modifier.fillMaxSize(),
                        animate = true
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.media_type_gif),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = Color.White
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(rememberShimmerBrush())
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.PermMedia,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .alpha(0.3f),
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun MediaGridSkeleton(rows: Int) {
    val shimmer = rememberShimmerBrush()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmer)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberListSkeletonRow(shimmer: androidx.compose.ui.graphics.Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(shimmer)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.52f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .fillMaxWidth(0.34f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
        }
    }
}

@Composable
private fun MessageListSkeletonRow(shimmer: androidx.compose.ui.graphics.Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(shimmer)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.6f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .fillMaxWidth(0.42f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
        }
    }
}
