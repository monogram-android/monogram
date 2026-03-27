package org.monogram.presentation.features.profile.components

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.UserStatusType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType
import org.monogram.presentation.features.profile.ProfileComponent
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import java.io.File

private const val LOAD_MORE_THRESHOLD = 40

@OptIn(ExperimentalFoundationApi::class)
fun LazyGridScope.profileMediaSection(
    state: ProfileComponent.State,
    videoPlayerPool: VideoPlayerPool,
    onTabSelected: (Int) -> Unit,
    onMessageClick: (MessageModel) -> Unit,
    onMessageLongClick: (MessageModel) -> Unit,
    onLoadMore: () -> Unit,
    onMemberClick: (Long) -> Unit = {},
    onMemberLongClick: (Long) -> Unit = {},
    onLoadMedia: (MessageModel) -> Unit = {}
) {
    val isGroup = state.chat?.isGroup == true || state.chat?.isChannel == true
    val tabs = mutableListOf<@Composable () -> String>({ stringResource(R.string.tab_media) })
    if (isGroup) tabs.add { stringResource(R.string.tab_members) }
    tabs.addAll(listOf(
        { stringResource(R.string.tab_files) },
        { stringResource(R.string.tab_audio) },
        { stringResource(R.string.tab_voice) },
        { stringResource(R.string.tab_links) },
        { stringResource(R.string.tab_gifs) }
    ))

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
                    ScrollableRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        tabs.forEachIndexed { index, titleFunc ->
                            val selected = state.selectedTabIndex == index
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .padding(horizontal = 2.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .selectable(
                                        selected = selected,
                                        onClick = { onTabSelected(index) },
                                        role = Role.Tab
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = titleFunc(),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    item(span = { GridItemSpan(3) }) {
        LaunchedEffect(state.selectedTabIndex) {
            val shouldLoad = if (isGroup) {
                when (state.selectedTabIndex) {
                    0 -> state.mediaMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    1 -> state.members.isEmpty() && state.canLoadMoreMembers && !state.isLoadingMembers
                    2 -> state.fileMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    3 -> state.audioMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    4 -> state.voiceMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    5 -> state.linkMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    6 -> state.gifMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    else -> false
                }
            } else {
                when (state.selectedTabIndex) {
                    0 -> state.mediaMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    1 -> state.fileMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    2 -> state.audioMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    3 -> state.voiceMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    4 -> state.linkMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    5 -> state.gifMessages.isEmpty() && state.canLoadMoreMedia && !state.isLoadingMedia
                    else -> false
                }
            }
            if (shouldLoad) {
                onLoadMore()
            }
        }
    }

    val isMediaLoading = state.isLoadingMedia || state.isLoadingMoreMedia
    val canLoadMedia = state.canLoadMoreMedia

    if (isGroup) {
        when (state.selectedTabIndex) {
            0 -> mediaGrid(state.mediaMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick, onLoadMedia)
            1 -> membersList(state.members, videoPlayerPool, state.isLoadingMembers, state.canLoadMoreMembers, onLoadMore, onMemberClick, onMemberLongClick)
            2 -> filesList(state.fileMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            3 -> audioList(state.audioMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            4 -> voiceList(state.voiceMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            5 -> linksList(state.linkMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            6 -> gifsGrid(state.gifMessages, videoPlayerPool, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
        }
    } else {
        when (state.selectedTabIndex) {
            0 -> mediaGrid(state.mediaMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick, onLoadMedia)
            1 -> filesList(state.fileMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            2 -> audioList(state.audioMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            3 -> voiceList(state.voiceMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            4 -> linksList(state.linkMessages, isMediaLoading, canLoadMedia, onLoadMore, onMessageClick)
            5 -> gifsGrid(
                state.gifMessages,
                videoPlayerPool,
                isMediaLoading,
                canLoadMedia,
                onLoadMore,
                onMessageClick
            )
        }
    }

    val needsLoadingIndicator = if (isGroup && state.selectedTabIndex == 1) {
        state.canLoadMoreMembers && !state.isLoadingMembers && state.members.isNotEmpty()
    } else {
        state.canLoadMoreMedia && !state.isLoadingMoreMedia && !state.isLoadingMedia
    }

    if (needsLoadingIndicator) {
        item(span = { GridItemSpan(3) }, key = "loader") {
            LaunchedEffect(state.selectedTabIndex, state.mediaMessages.size, state.members.size) {
                onLoadMore()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
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

private fun LazyGridScope.membersList(
    members: List<GroupMemberModel>,
    videoPlayerPool: VideoPlayerPool,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMemberClick: (Long) -> Unit,
    onMemberLongClick: (Long) -> Unit
) {
    val uniqueMembers = members.distinctBy { it.user.id }

    if (uniqueMembers.isEmpty()) {
        if (!isLoading) {
            item(span = { GridItemSpan(3) }) {
                EmptyState(stringResource(R.string.empty_members))
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
                    val statusText = getUserStatusText(user, context)

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
                        videoPlayerPool = videoPlayerPool,
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
        item(span = { GridItemSpan(3) }) {
            EmptyState(stringResource(R.string.empty_media))
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

                val minithumbnail = when (val content = msg.content) {
                    is MessageContent.Photo -> content.minithumbnail
                    is MessageContent.Video -> content.minithumbnail
                    else -> null
                }

                val context = LocalContext.current
                val isValidFile = path != null && File(path).exists()

                if (isValidFile || minithumbnail != null) {
                    AsyncImage(
                        model = remember(path, minithumbnail) {
                            val data = if (isValidFile) path else minithumbnail
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
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (!isValidFile) {
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

private fun LazyGridScope.audioList(
    messages: List<MessageModel>,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        item(span = { GridItemSpan(3) }) {
            EmptyState(stringResource(R.string.empty_audio))
        }
    } else {
        itemsIndexed(uniqueMessages, key = { _, msg -> "audio_${msg.id}" }, span = { _, _ -> GridItemSpan(3) }) { index, msg ->

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
        item(span = { GridItemSpan(3) }) {
            EmptyState(stringResource(R.string.empty_voice))
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
        item(span = { GridItemSpan(3) }) {
            EmptyState(stringResource(R.string.empty_files))
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
        item(span = { GridItemSpan(3) }) {
            EmptyState(stringResource(R.string.empty_links))
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
    videoPlayerPool: VideoPlayerPool,
    isLoading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    onMessageClick: (MessageModel) -> Unit
) {
    val uniqueMessages = messages.distinctBy { it.id }

    if (uniqueMessages.isEmpty()) {
        item(span = { GridItemSpan(3) }) {
            EmptyState(stringResource(R.string.empty_gifs))
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
                        animate = true,
                        videoPlayerPool = videoPlayerPool
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
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp),
                        strokeWidth = 2.dp
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