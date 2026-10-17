package org.monogram.feature.dialog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.monogram.core.common.Outcome
import org.monogram.core.models.DisplayedChatAction
import org.monogram.core.models.displayedChatAction
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.OnlineLeaseExpiry
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.PeerPresence
import org.monogram.core.ui.components.SponsorBadge
import org.monogram.core.ui.components.TypingDots
import org.monogram.core.ui.components.liveActionTransition
import org.monogram.core.ui.components.peerStatusLabel
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.components.typingStatusText
import org.monogram.core.ui.components.uiLabel
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.ui.rememberStrippedPreview
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.UserDownload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialogTopBar(
    component: DialogComponent,
    state: DialogStore.State,
    title: String,
    syncStatus: AppSyncStatus,
    showSearch: Boolean,
    onToggleSearch: () -> Unit,
    onOpenEmojiStatus: (Long) -> Unit,
    selectedMessageCount: Int,
    canForwardSelected: Boolean,
    canDeleteSelected: Boolean,
    onClearSelectedMessages: () -> Unit,
    onForwardSelectedMessages: () -> Unit,
    onDeleteSelectedMessages: () -> Unit,
) {
    if (selectedMessageCount > 0) {
        TopAppBar(
            windowInsets = WindowInsets.statusBars,
            title = {
                Text(
                    text = pluralStringResource(
                        R.plurals.dialog_messages_selected,
                        selectedMessageCount,
                        selectedMessageCount,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onClearSelectedMessages) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                    )
                }
            },
            actions = {
                if (canDeleteSelected) {
                    IconButton(onClick = onDeleteSelectedMessages) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.dialog_delete),
                        )
                    }
                }
                IconButton(
                    enabled = canForwardSelected,
                    onClick = onForwardSelectedMessages,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Forward,
                        contentDescription = stringResource(R.string.dialog_forward),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
        return
    }
    CompositionLocalProvider(
        LocalDialogMedia provides component.mediaRepository,
    ) {
    val mediaRepository = component.mediaRepository
    val userDownloads by remember(mediaRepository) {
        mediaRepository?.userDownloads ?: MutableStateFlow(emptyMap<String, UserDownload>())
    }.collectAsState()
    val downloadProgress by remember(mediaRepository) {
        mediaRepository?.downloadProgress ?: MutableStateFlow(emptyMap())
    }.collectAsState()
    var showDownloads by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
    TopAppBar(
        windowInsets = WindowInsets.statusBars,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.clickable(onClick = component::onOpenProfile),
            ) {
                val cacheGeneration =
                    component.mediaRepository?.cacheGeneration?.collectAsState()?.value ?: 0L
                val chatAvatar = rememberEnsuredFile(
                    generation = cacheGeneration,
                    identity = state.chatId.value to state.photoCacheKey,
                    resolve = {
                        state.photoCacheKey?.let { component.mediaRepository?.cachedFile(it) }
                            ?: component.mediaRepository?.cachedAvatar(state.chatId)
                    },
                    ensure = {
                        val repo = component.mediaRepository ?: return@rememberEnsuredFile null
                        val key = peerAvatarCacheKey(
                            state.chatId,
                            state.photoCacheKey,
                        )
                        when (
                            val result = repo.ensureLocalAvatar(
                                state.chatId,
                                key,
                                priority = MediaPriority.VISIBLE,
                            )
                        ) {
                            is Outcome.Ok -> result.value
                            is Outcome.Err -> null
                        }
                    },
                )
                if (state.inForumTopic) {
                    TopicIcon(
                        org.monogram.core.models.ForumTopic(
                            id = state.threadTopId,
                            title = title,
                            iconColor = state.topicIconColor,
                            iconEmojiId = state.topicIconEmojiId,
                        ),
                    )
                } else {
                    PeerAvatar(
                        title = title,
                        size = 38.dp,
                        imageFile = chatAvatar,
                        previewBytes = rememberStrippedPreview(state.chatId) {
                            component.mediaRepository?.peerInlineThumbJpeg(state.chatId)
                        },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        SponsorBadge(peerId = state.chatId.value, size = 18.dp)
                        state.emojiStatusDocumentId?.let { documentId ->
                            CustomEmojiGlyph(
                                documentId = documentId,
                                size = 18.dp,
                                onClick = { onOpenEmojiStatus(documentId) },
                            )
                        }
                    }
                    DialogStatusLine(
                        chatId = state.chatId.value,
                        enabled = !state.isSelf,
                        status = state.peerStatus,
                        statusAt = state.peerStatusAt,
                        onExpired = component::onRefreshPresence,
                        typing = displayedChatAction(
                            state.typingUsers.values,
                            named = state.isGroup || state.isChannel,
                        ),
                        syncLabel = syncStatus.uiLabel(),
                        stats = chatStatsSubtitle(
                            isChannel = state.isChannel,
                            isGroup = state.isGroup,
                            members = state.membersCount,
                            online = state.onlineCount,
                        ),
                        channelLabel = stringResource(R.string.dialog_channel),
                        groupLabel = stringResource(R.string.dialog_group),
                        isChannel = state.isChannel,
                        isGroup = state.isGroup,
                    )
                }
            }
        },
        navigationIcon = {
            if (component.showBackButton) IconButton(onClick = component::onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.dialog_back),
                )
            }
        },
        actions = {
            if (userDownloads.isNotEmpty()) {
                IconButton(onClick = { showDownloads = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.dialog_downloads),
                    )
                }
            }
            IconButton(
                onClick = onToggleSearch,
            ) {
                Icon(
                    imageVector = if (showSearch) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = stringResource(
                        if (showSearch) R.string.dialog_search_close else R.string.dialog_search,
                    ),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
        DialogDownloadProgressBar(
            downloads = userDownloads,
            progress = downloadProgress,
        )
    }
        if (showDownloads) {
            DialogDownloadsSheet(
                downloads = userDownloads,
                progress = downloadProgress,
                onCancel = { key -> mediaRepository?.cancel(key) },
                onDismiss = { showDownloads = false },
            )
        }
    }
}

@Composable
private fun DialogStatusLine(
    chatId: Long,
    enabled: Boolean,
    status: String?,
    statusAt: Long?,
    onExpired: () -> Unit,
    typing: DisplayedChatAction?,
    syncLabel: String?,
    stats: String?,
    channelLabel: String,
    groupLabel: String,
    isChannel: Boolean,
    isGroup: Boolean,
) {
    val live by PeerPresence.flow(chatId).collectAsState()
    val presenceStatus = if (enabled) live?.status ?: status else null
    val presenceAt = if (enabled) live?.statusAt ?: statusAt else null
    val presenceNow = rememberPeerStatusNow(presenceStatus, presenceAt)
    OnlineLeaseExpiry(presenceStatus, presenceAt, onExpired)
    val fallback = syncLabel
        ?: peerStatusLabel(presenceStatus, presenceAt, presenceNow)
        ?: stats
        ?: when {
            isChannel -> channelLabel
            isGroup -> groupLabel
            else -> null
        }
    AnimatedContent(
        targetState = typing?.action to (typing?.names ?: emptyList()) to fallback,
        transitionSpec = {
            val typingNow = targetState.first.first != null ||
                    initialState.first.first != null
            if (typingNow) {
                liveActionTransition()
            } else {
                fadeIn(tween(220)) togetherWith fadeOut(tween(160))
            }
        },
        contentKey = { (liveAction, subtitle) -> liveAction.first ?: subtitle.orEmpty() },
        label = "dialog-status",
    ) { (liveAction, subtitle) ->
        val (action, names) = liveAction
        if (action != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TypingDots(
                    dotSize = 4.dp,
                    dotColor = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = typingStatusText(names, action),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        } else {
            subtitle?.let { statusText ->
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (syncLabel != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
