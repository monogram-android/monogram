package org.monogram.feature.dialog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.monogram.core.models.ForumTopic
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.MessageListSkeleton
import org.monogram.core.ui.components.UnreadBadge
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicsContent(
    component: DialogComponent,
    state: DialogStore.State,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalDialogMedia provides component.mediaRepository) {
        val title = state.title.ifBlank { stringResource(R.string.dialog_title, state.chatId.value) }
        val sync = when {
            state.loadingTopics && state.topics.isEmpty() -> AppSyncStatus.Connecting
            state.loadingTopics -> AppSyncStatus.LoadingMore
            else -> AppSyncStatus.Hidden
        }
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.statusBars,
                    title = {
                        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = component::onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.dialog_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = inner.calculateTopPadding()),
            ) {
                AppStatusBanner(
                    sync = sync,
                    error = state.error,
                    onRetry = component::onRefresh,
                )
                TopicsList(
                    topics = state.topics,
                    loading = state.loadingTopics && state.topics.isEmpty(),
                    empty = !state.loadingTopics && state.topics.isEmpty() && state.error == null,
                    hasMore = state.hasMoreTopics,
                    onOpen = component::onOpenTopic,
                    onLoadMore = component::onLoadMoreTopics,
                )
            }
        }
    }
}

@Composable
internal fun TopicsList(
    topics: List<ForumTopic>,
    loading: Boolean,
    empty: Boolean,
    hasMore: Boolean,
    onOpen: (ForumTopic) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loading -> MessageListSkeleton(modifier = modifier.fillMaxSize())
        empty -> Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.dialog_topics_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, hasMore, topics.size) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= topics.lastIndex - 3
                }.collect { nearEnd ->
                    if (nearEnd && hasMore) onLoadMore()
                }
            }
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(topics, key = { it.id }) { topic ->
                    TopicRow(topic = topic, onClick = { onOpen(topic) })
                }
            }
        }
    }
}

@Composable
private fun TopicRow(
    topic: ForumTopic,
    onClick: () -> Unit,
) {
    val unreadLabel = buildString {
        if (topic.unreadCount > 0) {
            append(stringResource(R.string.dialog_topic_unread, topic.unreadCount))
        }
        if (topic.unreadMentionsCount > 0) {
            if (isNotEmpty()) append(" · ")
            append(stringResource(R.string.dialog_topic_mentions, topic.unreadMentionsCount))
        }
    }
    val description = buildString {
        append(topic.title)
        if (topic.pinned) {
            append(" · ")
            append(stringResource(R.string.dialog_topic_pinned))
        }
        if (topic.closed) {
            append(" · ")
            append(stringResource(R.string.dialog_topic_closed))
        }
        if (unreadLabel.isNotEmpty()) {
            append(" · ")
            append(unreadLabel)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            TopicIcon(topic)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (topic.pinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (topic.closed) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val preview = topic.lastMessagePreview
            if (!preview.isNullOrBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (topic.unreadMentionsCount > 0) {
                Text(
                    text = "@${topic.unreadMentionsCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            UnreadBadge(count = topic.unreadCount, muted = false)
        }
    }
}

@Composable
internal fun TopicIcon(topic: ForumTopic) {
    val emojiId = topic.iconEmojiId
    if (emojiId != null && emojiId != 0L) {
        CustomEmojiGlyph(documentId = emojiId, size = 28.dp)
        return
    }
    val fill = Color(0xFF000000L or (topic.iconColor.toLong() and 0xFFFFFF))
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = topic.title.firstOrNull()?.uppercaseChar()?.toString() ?: "#",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TopicsListPreview() {
    MonogramTheme {
        TopicsList(
            topics = listOf(
                ForumTopic(id = 1, title = "General", unreadCount = 2, pinned = true),
                ForumTopic(
                    id = 8,
                    title = "Bugs",
                    unreadCount = 4,
                    unreadMentionsCount = 1,
                    lastMessagePreview = "Need a repro",
                    iconColor = 0x6FB9F0,
                ),
                ForumTopic(id = 9, title = "Closed", closed = true),
            ),
            loading = false,
            empty = false,
            hasMore = false,
            onOpen = {},
            onLoadMore = {},
        )
    }
}
