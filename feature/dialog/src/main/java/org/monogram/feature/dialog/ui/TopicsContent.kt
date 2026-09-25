package org.monogram.feature.dialog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.monogram.core.models.ForumIo
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
                    canHideGeneral = ForumIo.canHideGeneral(state.canManageTopics),
                    onOpen = component::onOpenTopic,
                    onLoadMore = component::onLoadMoreTopics,
                    onToggleHidden = component::onToggleTopicHidden,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TopicsList(
    topics: List<ForumTopic>,
    loading: Boolean,
    empty: Boolean,
    hasMore: Boolean,
    onOpen: (ForumTopic) -> Unit,
    onLoadMore: () -> Unit,
    onToggleHidden: (Int, Boolean) -> Unit = { _, _ -> },
    canHideGeneral: Boolean = false,
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
            val density = LocalDensity.current
            val hiddenTopics = remember(topics) {
                ForumIo.sortForumTopics(topics).filter { it.hidden }
            }
            val visibleTopics = remember(topics) {
                ForumIo.sortForumTopics(topics).filter { !it.hidden }
            }
            val hiddenCount = hiddenTopics.size
            var pullState by remember { mutableStateOf(ForumIo.ArchivePullState()) }
            LaunchedEffect(hiddenCount) {
                if (hiddenCount <= 0) pullState = ForumIo.ArchivePullState()
            }
            val listState = rememberLazyListState()
            val rowHeightPx = with(density) { 72.dp.toPx() }
            val snapPx = with(density) { 48.dp.toPx() }
            val peekPx = remember { Animatable(0f) }
            val peekTarget = ForumIo.archivePeekPx(
                hiddenCount = hiddenCount,
                revealed = pullState.revealed,
                pulledPx = pullState.pulledPx,
                rowHeightPx = rowHeightPx,
            )
            LaunchedEffect(peekTarget, pullState.revealed, pullState.pulledPx) {
                if (ForumIo.archivePullFollowsFinger(pullState)) {
                    peekPx.snapTo(peekTarget)
                } else {
                    peekPx.animateTo(
                        peekTarget,
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
            }
            val pull = remember(hiddenCount, snapPx) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val atTop = listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        val before = pullState
                        val next = ForumIo.archivePullOnScroll(
                            state = before,
                            atTop = atTop,
                            hiddenCount = hiddenCount,
                            deltaY = available.y,
                            snapPx = snapPx,
                        )
                        if (next != before) pullState = next
                        val consumed = when {
                            next.revealed && !before.revealed -> available.y
                            next.pulledPx != before.pulledPx -> available.y
                            else -> 0f
                        }
                        return Offset(0f, consumed)
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        val released = ForumIo.archivePullOnRelease(pullState)
                        if (released != pullState) pullState = released
                        return if (released.pulledPx == 0f && !released.revealed) {
                            available
                        } else {
                            Velocity.Zero
                        }
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        val released = ForumIo.archivePullOnRelease(pullState)
                        if (released != pullState) pullState = released
                        return Velocity.Zero
                    }
                }
            }
            LaunchedEffect(listState, hasMore, visibleTopics.size) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= visibleTopics.lastIndex - 3
                }.collect { nearEnd ->
                    if (nearEnd && hasMore) onLoadMore()
                }
            }
            val peekDp = with(density) { peekPx.value.toDp() }
            LazyColumn(
                modifier = modifier.fillMaxSize().nestedScroll(pull),
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (hiddenCount > 0) {
                    item(key = "archive-peek") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(peekDp)
                                .clipToBounds(),
                        ) {
                            Column {
                                hiddenTopics.forEach { topic ->
                                    TopicRow(
                                        topic = topic,
                                        canHideGeneral = canHideGeneral,
                                        onClick = { onOpen(topic) },
                                        onToggleHidden = { id, hidden ->
                                            if (hidden) pullState = ForumIo.ArchivePullState()
                                            onToggleHidden(id, hidden)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                items(visibleTopics, key = { it.id }) { topic ->
                    TopicRow(
                        modifier = Modifier.animateItem(),
                        topic = topic,
                        canHideGeneral = canHideGeneral,
                        onClick = { onOpen(topic) },
                        onToggleHidden = { id, hidden ->
                            if (hidden) pullState = ForumIo.ArchivePullState()
                            onToggleHidden(id, hidden)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopicRow(
    topic: ForumTopic,
    onClick: () -> Unit,
    onToggleHidden: (Int, Boolean) -> Unit = { _, _ -> },
    canHideGeneral: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
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
        if (topic.hidden) {
            append(" · ")
            append(stringResource(R.string.dialog_topic_hidden))
        }
        if (unreadLabel.isNotEmpty()) {
            append(" · ")
            append(unreadLabel)
        }
    }
    Box(modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (topic.isGeneral && canHideGeneral) menu = true },
            )
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
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (topic.hidden) R.string.dialog_topic_show_general
                            else R.string.dialog_topic_hide_general,
                        ),
                    )
                },
                onClick = {
                    menu = false
                    onToggleHidden(topic.id, !topic.hidden)
                },
            )
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
