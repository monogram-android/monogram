package org.monogram.feature.dialog.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.Outcome
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.UploadItem
import org.monogram.core.models.displayedChatAction
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.components.AppModalSheet
import org.monogram.core.ui.components.AppStatusBanner
import org.monogram.core.ui.components.AppSyncStatus
import org.monogram.core.ui.components.ChatWallpaper
import org.monogram.core.ui.components.MessageListSkeleton
import org.monogram.core.ui.components.OnlineLeaseExpiry
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.SearchField
import org.monogram.core.ui.components.TypingDots
import org.monogram.core.ui.components.UnreadBadge
import org.monogram.core.ui.components.listItemMotion
import org.monogram.core.ui.components.liveActionTransition
import org.monogram.core.ui.components.peerStatusLabel
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.components.typingStatusText
import org.monogram.core.ui.components.uiLabel
import org.monogram.core.ui.loading.MonogramLoading
import org.monogram.core.ui.loading.MonogramLoadingListSize
import org.monogram.core.ui.menu.AppMenuPlacementState
import org.monogram.core.ui.menu.AppMenuScrimPopup
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogComponent
import org.monogram.feature.dialog.DialogDayKind
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DialogTime
import org.monogram.feature.dialog.R
import org.monogram.feature.dialog.albumSlice
import org.monogram.feature.dialog.dialogSyncStatus
import org.monogram.feature.dialog.followBottomFromScroll
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isAlbumHead
import org.monogram.feature.dialog.liftAnchorTarget
import org.monogram.feature.dialog.shouldFollowIncomingNewest
import org.monogram.feature.dialog.shouldPageNewer
import org.monogram.feature.dialog.shouldPageOlder
import org.monogram.feature.dialog.unreadDividerIndex
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.UserDownload
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import org.monogram.core.ui.components.SponsorBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DialogTopBar(
    component: DialogComponent,
    state: DialogStore.State,
    title: String,
    syncStatus: AppSyncStatus,
    presenceStatus: String?,
    presenceAt: Long?,
    presenceNow: Long,
    showSearch: Boolean,
    onToggleSearch: () -> Unit,
    onOpenEmojiStatus: (Long) -> Unit,
    selectedMessageCount: Int,
    canForwardSelected: Boolean,
    onClearSelectedMessages: () -> Unit,
    onForwardSelectedMessages: () -> Unit,
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
                    PeerAvatar(title = title, size = 38.dp, imageFile = chatAvatar)
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
                    val typing = displayedChatAction(
                        state.typingUsers.values,
                        named = state.isGroup || state.isChannel,
                    )
                    val syncLabel = syncStatus.uiLabel()
                    val fallback = syncLabel
                        ?: peerStatusLabel(presenceStatus, presenceAt, presenceNow)
                        ?: chatStatsSubtitle(
                            isChannel = state.isChannel,
                            isGroup = state.isGroup,
                            members = state.membersCount,
                            online = state.onlineCount,
                        )
                        ?: when {
                            state.isChannel -> stringResource(R.string.dialog_channel)
                            state.isGroup -> stringResource(R.string.dialog_group)
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
                        contentKey = { (live, subtitle) -> live.first ?: subtitle.orEmpty() },
                        label = "dialog-status",
                    ) { (live, subtitle) ->
                        val (action, names) = live
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
                            subtitle?.let { status ->
                                Text(
                                    text = status,
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
            }
        },
        navigationIcon = {
            IconButton(onClick = component::onBack) {
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
