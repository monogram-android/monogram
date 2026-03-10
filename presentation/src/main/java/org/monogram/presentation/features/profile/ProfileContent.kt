package org.monogram.presentation.features.profile

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.presentation.core.ui.CollapsingToolbarScaffold
import org.monogram.presentation.core.ui.rememberCollapsingToolbarScaffoldState
import org.monogram.presentation.core.util.ScrollStrategy
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.profile.components.*
import org.monogram.presentation.features.viewers.ImageViewer
import org.monogram.presentation.features.viewers.VideoViewer
import org.monogram.presentation.features.webapp.MiniAppViewer

@Composable
fun ProfileContent(component: ProfileComponent) {
    val state by component.state.subscribeAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val collapsingToolbarState = rememberCollapsingToolbarScaffoldState()


    val chat = state.chat
    val user = state.user

    val avatarPath = remember(state.profilePhotos, state.chat, state.user, state.personalAvatarPath) {
        state.personalAvatarPath
            ?: state.profilePhotos.firstOrNull()
            ?: state.chat?.personalAvatarPath
            ?: state.chat?.avatarPath
            ?: state.user?.avatarPath
    }


    val title = remember(user, chat) {
        chat?.title ?: listOfNotNull(user?.firstName, user?.lastName)
            .joinToString(" ")
            .ifBlank { "Unknown" }
    }

    val subtitle = remember(user, chat) {
        if (chat?.isGroup == true || chat?.isChannel == true) {
            val members = "${chat.memberCount} members"
            if (chat.onlineCount > 0) "$members, ${chat.onlineCount} online" else members
        } else {
            getUserStatusText(user)
        }
    }

    val isOnline = user?.type != UserTypeEnum.BOT && user?.userStatus == UserStatusType.ONLINE

    val collapsedColor = MaterialTheme.colorScheme.surface
    val expandedColor = MaterialTheme.colorScheme.background
    val dynamicContainerColor = lerp(
        start = collapsedColor,
        stop = expandedColor,
        fraction = collapsingToolbarState.toolbarState.progress
    )

    val isCustomBackHandlingEnabled = state.fullScreenImages != null || state.fullScreenVideoPath != null || state.miniAppUrl != null || state.isStatisticsVisible || state.isRevenueStatisticsVisible || state.selectedLocation != null

    BackHandler(enabled = isCustomBackHandlingEnabled) {
        if (state.fullScreenImages != null || state.fullScreenVideoPath != null) component.onDismissViewer()
        else if (state.miniAppUrl != null) component.onDismissMiniApp()
        else if (state.isStatisticsVisible || state.isRevenueStatisticsVisible) component.onDismissStatistics()
        else if (state.selectedLocation != null) component.onDismissLocation()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                ProfileTopBar(
                    onBack = component::onBack,
                    progress = collapsingToolbarState.toolbarState.progress,
                    title = title,
                    userModel = user,
                    chatModel = chat,
                    isVerified = user?.isVerified == true || chat?.isVerified == true,
                    onSearch = { Toast.makeText(context, "Search not implemented", Toast.LENGTH_SHORT).show() },
                    onShare = { Toast.makeText(context, "Share not implemented", Toast.LENGTH_SHORT).show() },
                    onEdit = component::onEdit,
                    onBlock = { Toast.makeText(context, "Block not implemented", Toast.LENGTH_SHORT).show() },
                    onDelete = { Toast.makeText(context, "Delete not implemented", Toast.LENGTH_SHORT).show() }
                )
            },
            containerColor = dynamicContainerColor
        ) { padding ->
            CollapsingToolbarScaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dynamicContainerColor),
                state = collapsingToolbarState,
                scrollStrategy = ScrollStrategy.ExitUntilCollapsed,
                toolbar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(padding.calculateTopPadding())
                            .pin()
                    )
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .road(Alignment.Center, Alignment.BottomCenter)
                    ) {

                        val progress = collapsingToolbarState.toolbarState.progress
                        val avatarSize = androidx.compose.ui.unit.lerp(140.dp, maxWidth, progress)
                        val cornerPercent = (100 * (1f - progress)).toInt()
                        val sidePadding = androidx.compose.ui.unit.lerp(24.dp, 0.dp, progress)
                        val topPadding = androidx.compose.ui.unit.lerp(
                            0.dp + padding.calculateTopPadding(),
                            0.dp,
                            progress
                        )
                        ProfileHeaderTransformed(
                            avatarPath = avatarPath,
                            title = title,
                            subtitle = subtitle,
                            avatarSize = avatarSize,
                            avatarCornerPercent = cornerPercent,
                            isOnline = isOnline,
                            isVerified = user?.isVerified == true || chat?.isVerified == true,
                            statusEmojiPath = user?.statusEmojiPath,
                            progress = progress,
                            contentPadding = PaddingValues(
                                top = topPadding,
                                start = sidePadding,
                                end = sidePadding
                            ),
                            onAvatarClick = component::onAvatarClick,
                            userModel = user,
                            chatModel = chat,
                            onActionClick = {},
                            videoPlayerPool = component.videoPlayerPool
                        )
                    }

                }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(span = { GridItemSpan(3) }) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            ProfileInfoSection(
                                state = state,
                                clipboardManager = clipboardManager,
                                onOpenMiniApp = { url, name, chatId -> component.onOpenMiniApp(url, name, chatId) },
                                onSendMessage = component::onSendMessage,
                                onToggleMute = component::onToggleMute,
                                onShowQRCode = component::onShowQRCode,
                                onEdit = component::onEdit,
                                onLeave = component::onLeave,
                                onJoin = component::onJoinChat,
                                onReport = component::onShowReport,
                                onShowLogs = component::onShowLogs,
                                onShowStatistics = component::onShowStatistics,
                                onShowRevenueStatistics = component::onShowRevenueStatistics,
                                onLinkedChatClick = component::onLinkedChatClick,
                                onShowPermissions = component::onShowPermissions,
                                onAcceptTOS = component::onAcceptTOS,
                                onLocationClick = component::onLocationClick,
                                videoPlayerPool = component.videoPlayerPool
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    profileMediaSection(
                        state = state,
                        onTabSelected = component::onTabSelected,
                        onMessageClick = component::onMessageClick,
                        onMessageLongClick = component::onMessageLongClick,
                        onLoadMore = component::onLoadMoreMedia,
                        onMemberClick = component::onMemberClick,
                        onMemberLongClick = component::onMemberLongClick,
                        onLoadMedia = { msg ->
                            component.onDownloadMedia(msg)
                        },
                        videoPlayerPool = component.videoPlayerPool
                    )

                    item(span = { GridItemSpan(3) }) {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.fullScreenImages != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            state.fullScreenImages?.let { images ->
                ImageViewer(
                    images = images,
                    startIndex = state.fullScreenStartIndex,
                    onDismiss = component::onDismissViewer,
                    autoDownload = true,
                    downloadUtils = component.downloadUtils,
                    onPageChanged = { index ->

                        if (!state.isViewingProfilePhotos && state.canLoadMoreMedia && !state.isLoadingMoreMedia &&
                            index >= images.size - 5) {
                            component.onLoadMoreMedia()
                        }

                        if (!state.isViewingProfilePhotos) {
                            val photoMessages = state.mediaMessages.filter { it.content is MessageContent.Photo }

                            val message = photoMessages.getOrNull(index)
                            if (message != null) {
                                component.onDownloadMedia(message)

                                val nextMessage = photoMessages.getOrNull(index + 1)
                                if (nextMessage != null) {
                                    component.onDownloadMedia(nextMessage)
                                }
                            }
                        }
                    },
                    onForward = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    onDelete = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    onCopyLink = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    onCopyText = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    captions = state.fullScreenCaptions.filterNotNull(),
                    showImageNumber = false
                )
            }
        }

        AnimatedVisibility(
            visible = state.fullScreenVideoPath != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            state.fullScreenVideoPath?.let { path ->
                val msg = state.mediaMessages.find {
                    when (val content = it.content) {
                        is MessageContent.Video -> content.path == path
                        is MessageContent.Gif -> content.path == path
                        is MessageContent.VideoNote -> content.path == path
                        else -> false
                    }
                }
                val videoContent = msg?.content as? MessageContent.Video
                val fileId = videoContent?.fileId ?: (msg?.content as? MessageContent.Gif)?.fileId
                ?: (msg?.content as? MessageContent.VideoNote)?.fileId ?: 0
                val supportsStreaming = videoContent?.supportsStreaming ?: false

                VideoViewer(
                    path = path,
                    onDismiss = component::onDismissViewer,
                    isGesturesEnabled = true,
                    isDoubleTapSeekEnabled = true,
                    seekDuration = 10,
                    isZoomEnabled = true,
                    downloadUtils = component.downloadUtils,
                    onForward = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    onDelete = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    onCopyLink = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    onCopyText = { Toast.makeText(context, "Not implemented", Toast.LENGTH_SHORT).show() },
                    caption = state.fullScreenVideoCaption,
                    fileId = fileId,
                    supportsStreaming = supportsStreaming
                )
            }
        }

        AnimatedVisibility(
            visible = state.miniAppUrl != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            if (state.miniAppUrl != null && state.miniAppName != null) {
                MiniAppViewer(
                    baseUrl = state.miniAppUrl.toString(),
                    botName = title,
                    onDismiss = { component.onDismissMiniApp() },
                    chatId = state.chatId,
                    botUserId = state.user!!.id,
                    messageRepository = component.messageRepository,
                )
            }
        }

        AnimatedVisibility(
            visible = state.isStatisticsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            StatisticsViewer(
                title = "Statistics",
                data = state.statistics,
                onDismiss = component::onDismissStatistics,
                onLoadGraph = component::onLoadStatisticsGraph
            )
        }

        AnimatedVisibility(
            visible = state.isRevenueStatisticsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            StatisticsViewer(
                title = "Revenue",
                data = state.revenueStatistics,
                onDismiss = component::onDismissStatistics,
                onLoadGraph = component::onLoadStatisticsGraph
            )
        }

        ProfileQRDialog(
            state = state,
            onDismiss = component::onDismissQRCode
        )

        ProfileReportDialog(
            state = state,
            onDismiss = component::onDismissReport,
            onReport = component::onReport
        )

        if (state.miniAppUrl == null) {
            ProfilePermissionsDialog(
                state = state,
                onDismiss = component::onDismissPermissions,
                onTogglePermission = component::onTogglePermission
            )

            ProfileTOSDialog(
                state = state,
                onDismiss = component::onDismissTOS,
                onAccept = component::onAcceptTOS
            )
        }

        state.selectedLocation?.let { location ->
            LocationViewer(
                location = location,
                onDismiss = component::onDismissLocation
            )
        }
    }
}