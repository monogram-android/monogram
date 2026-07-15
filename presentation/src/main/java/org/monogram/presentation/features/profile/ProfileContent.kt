@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.profile

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.koin.compose.koinInject
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.TelegramLinkRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.CollapsingToolbarScaffold
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.rememberCollapsingToolbarScaffoldState
import org.monogram.presentation.core.ui.rememberShimmerBrush
import org.monogram.presentation.core.util.CountryManager
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.core.util.ScrollStrategy
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.common.ChatActionScreenContext
import org.monogram.presentation.features.chats.common.ChatActionState
import org.monogram.presentation.features.chats.common.ChatExitAction
import org.monogram.presentation.features.chats.common.resolveChatActionPolicy
import org.monogram.presentation.features.chats.conversation.ui.content.ReportChatDialog
import org.monogram.presentation.features.profile.components.LocationViewer
import org.monogram.presentation.features.profile.components.ProfileHeaderTransformed
import org.monogram.presentation.features.profile.components.ProfileInfoSection
import org.monogram.presentation.features.profile.components.ProfileInfoSectionSkeleton
import org.monogram.presentation.features.profile.components.ProfilePermissionsDialog
import org.monogram.presentation.features.profile.components.ProfileQRDialog
import org.monogram.presentation.features.profile.components.ProfileTOSDialog
import org.monogram.presentation.features.profile.components.ProfileTopBar
import org.monogram.presentation.features.profile.components.StatisticsViewer
import org.monogram.presentation.features.profile.components.profileMediaSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(component: ProfileComponent) {
    val state by component.state.subscribeAsState()
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val isTabletInterfaceEnabled = LocalTabletInterfaceEnabled.current
    val isTablet =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) && isTabletInterfaceEnabled
    val localClipboard = LocalClipboard.current
    val context = LocalContext.current
    val collapsingToolbarState = rememberCollapsingToolbarScaffoldState()

    val dateFormatManager: DateFormatManager = koinInject()
    val telegramLinkRepository: TelegramLinkRepository = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()
    val scope = rememberCoroutineScope()

    val chat = state.chat
    val user = state.user
    val isCurrentUserProfile = user != null && state.currentUser?.id == user.id
    val isInitialLoading = state.isLoading && chat == null && user == null

    val avatarPath = remember(state.chat, state.user, state.personalAvatarPath) {
        state.user?.avatarPath?.takeIf { it.isNotBlank() }
            ?: state.chat?.avatarPath?.takeIf { it.isNotBlank() }
            ?: state.personalAvatarPath?.takeIf { it.isNotBlank() }
            ?: state.chat?.personalAvatarPath?.takeIf { it.isNotBlank() }
    }
    val avatarFallbackPath = remember(state.chat, state.user, state.personalAvatarPath) {
        state.personalAvatarPath?.takeIf { it.isNotBlank() }
            ?: state.user?.personalAvatarPath?.takeIf { it.isNotBlank() }
            ?: state.chat?.personalAvatarPath?.takeIf { it.isNotBlank() }
    }


    val unknownTitle = stringResource(R.string.unknown_title)
    val deletedAccountTitle = stringResource(R.string.deleted_account)
    val title = remember(user, chat, unknownTitle, deletedAccountTitle) {
        if (user?.type == UserTypeEnum.DELETED) {
            deletedAccountTitle
        } else {
            chat?.title?.takeIf { it.isNotBlank() } ?: listOfNotNull(user?.firstName, user?.lastName)
                .joinToString(" ")
                .ifBlank { unknownTitle }
        }
    }

    val membersOnlineCountFormat = stringResource(R.string.members_online_count_format)
    val subtitle = when {
        chat?.isGroup == true || chat?.isChannel == true -> {
            val members = pluralStringResource(
                R.plurals.members_count_format,
                chat.memberCount,
                chat.memberCount
            )
            if (chat.onlineCount > 0) String.format(
                membersOnlineCountFormat,
                members,
                chat.onlineCount
            ) else members
        }

        isCurrentUserProfile -> {
            user.username
                ?.takeIf { it.isNotBlank() }
                ?.let { "@$it" }
                ?: user.phoneNumber
                    ?.takeIf { it.isNotBlank() }
                    ?.let { CountryManager.formatPhoneNumber(it) }
                ?: state.about?.takeIf { it.isNotBlank() }
                ?: ""
        }

        else -> getUserStatusText(user, context, timeFormat)
    }

    val isOnline =
        !isCurrentUserProfile && user?.type != UserTypeEnum.BOT && user?.userStatus == UserStatusType.ONLINE
    val isBot = user?.type == UserTypeEnum.BOT || chat?.isBot == true
    val isScam = user?.isScam == true || chat?.isScam == true
    val isFake = user?.isFake == true || chat?.isFake == true

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

    val searchNotImplemented = stringResource(R.string.search_not_implemented)
    val linkCopied = stringResource(R.string.link_copied)
    val userIdCopied = stringResource(R.string.logs_user_id_copied)

    val isGroupOrChannel = chat?.isGroup == true || chat?.isChannel == true
    val canEditTopBar = when {
        isCurrentUserProfile -> true
        isGroupOrChannel -> chat.isAdmin || chat.permissions.canChangeInfo
        else -> false
    }
    val shareUsername = remember(user, chat) {
        user?.username?.takeIf { it.isNotBlank() } ?: chat?.username?.takeIf { it.isNotBlank() }
    }
    val shareLink = remember(state.publicLink) {
        state.publicLink?.takeIf { it.isNotBlank() }
    }
    val fallbackShareText = remember(isCurrentUserProfile, user) {
        if (isCurrentUserProfile) user.id.toString() else null
    }
    val canShareTopBar = !shareLink.isNullOrEmpty() || !fallbackShareText.isNullOrEmpty()
    val canReportTopBar = isGroupOrChannel && !isCurrentUserProfile
    val canBlockTopBar = !isCurrentUserProfile && !isGroupOrChannel && user?.type != UserTypeEnum.BOT
    val canEditContactTopBar = !isCurrentUserProfile && !isGroupOrChannel && user?.isContact == true
    val canToggleContactTopBar =
        !isCurrentUserProfile && !isGroupOrChannel && user != null && user.type != UserTypeEnum.BOT
    val actionPolicy = remember(
        chat?.isGroup,
        chat?.isChannel,
        chat?.isMember,
        chat?.canBeDeletedOnlyForSelf,
        chat?.canBeDeletedForAllUsers,
        chat?.canBeReported,
        user?.id,
        isCurrentUserProfile
    ) {
        resolveChatActionPolicy(
            isMainChat = true,
            isGroup = chat?.isGroup == true,
            isChannel = chat?.isChannel == true,
            isMember = chat?.isMember == true,
            canDeleteChat = chat?.let { it.canBeDeletedOnlyForSelf || it.canBeDeletedForAllUsers } == true,
            canReport = chat?.canBeReported == true,
            canJoin = (chat?.isGroup == true || chat?.isChannel == true) && chat?.isMember == false,
            canBlockOrUnblock = !isCurrentUserProfile && !isGroupOrChannel && user?.type != UserTypeEnum.BOT,
            canPin = false,
            context = ChatActionScreenContext.Profile
        )
    }
    val exitAction = actionPolicy.exitAction
    val canLeaveTopBar = !isCurrentUserProfile && exitAction == ChatExitAction.Leave
    val canDeleteTopBar = !isCurrentUserProfile && exitAction == ChatExitAction.Delete
    val isActionPending = state.actionState is ChatActionState.Pending
    var showLeaveSheet by remember { mutableStateOf(false) }
    var showDeleteChatSheet by remember { mutableStateOf(false) }
    var showBlockSheet by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.semantics { contentDescription = "ProfileContent" },
            topBar = {
                ProfileTopBar(
                    onBack = component::onBack,
                    progress = collapsingToolbarState.toolbarState.progress,
                    title = title,
                    userModel = user,
                    chatModel = chat,
                    isVerified = user?.isVerified == true || chat?.isVerified == true,
                    isSponsor = user?.isSponsor == true,
                    isBot = isBot,
                    isScam = isScam,
                    isFake = isFake,
                    canSearch = false,
                    canShare = canShareTopBar,
                    canEdit = canEditTopBar,
                    canEditContact = canEditContactTopBar,
                    canToggleContact = canToggleContactTopBar,
                    canReport = canReportTopBar,
                    canBlock = canBlockTopBar,
                    isBlocked = state.isBlocked,
                    canLeave = canLeaveTopBar,
                    canDeleteChat = canDeleteTopBar,
                    onSearch = { Toast.makeText(context, searchNotImplemented, Toast.LENGTH_SHORT).show() },
                    onShare = {
                        when {
                            shareUsername != null -> {
                                scope.launch {
                                    val link = telegramLinkRepository.buildUrl(shareUsername)
                                    localClipboard.nativeClipboard.setPrimaryClip(
                                        ClipData.newPlainText("", AnnotatedString(link))
                                    )
                                    Toast.makeText(context, linkCopied, Toast.LENGTH_SHORT).show()
                                }
                            }

                            shareLink != null -> {
                                localClipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText("", AnnotatedString(shareLink))
                                )
                                Toast.makeText(context, linkCopied, Toast.LENGTH_SHORT).show()
                            }

                            fallbackShareText != null -> {
                                localClipboard.nativeClipboard.setPrimaryClip(
                                    ClipData.newPlainText("", AnnotatedString(fallbackShareText))
                                )
                                Toast.makeText(context, userIdCopied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onEdit = component::onEdit,
                    onEditContact = component::onEditContact,
                    onToggleContact = component::onToggleContact,
                    onReport = if (!isActionPending) ({ component.onShowReport() }) else ({}),
                    onBlock = { showBlockSheet = true },
                    onLeave = { showLeaveSheet = true },
                    onDeleteChat = { showDeleteChatSheet = true }
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
                        if (isInitialLoading) {
                            ProfileHeaderSkeleton(
                                progress = progress,
                                contentPadding = PaddingValues(
                                    top = topPadding,
                                    start = sidePadding,
                                    end = sidePadding
                                )
                            )
                        } else {
                            ProfileHeaderTransformed(
                                avatarPath = avatarPath,
                                avatarFallbackPath = avatarFallbackPath,
                                title = title,
                                subtitle = subtitle,
                                avatarSize = avatarSize,
                                avatarCornerPercent = cornerPercent,
                                isOnline = isOnline,
                                isVerified = user?.isVerified == true || chat?.isVerified == true,
                                isSponsor = user?.isSponsor == true,
                                isBot = isBot,
                                isScam = isScam,
                                isFake = isFake,
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
                                slowModeDelay = state.fullInfo?.slowModeDelay ?: 0,
                                onActionClick = {}
                            )
                        }
                    }

                }
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (isTablet) 12.dp else 0.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.background),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(span = { GridItemSpan(3) }) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            if (isInitialLoading) {
                                ProfileInfoSectionSkeleton(
                                    showLinkedChat = state.chatId < 0
                                )
                            } else {
                                ProfileInfoSection(
                                    state = state,
                                    localClipboard = localClipboard,
                                    onOpenMiniApp = { url, name, chatId -> component.onOpenMiniApp(url, name, chatId) },
                                    onSendMessage = component::onSendMessage,
                                    onToggleMute = component::onToggleMute,
                                    onShowQRCode = component::onShowQRCode,
                                    onEdit = component::onEdit,
                                    exitAction = exitAction,
                                    onLeave = { showLeaveSheet = true },
                                    onJoin = if (!isActionPending) ({ component.onJoinChat() }) else ({}),
                                    onShowLogs = component::onShowLogs,
                                    onShowStatistics = component::onShowStatistics,
                                    onShowRevenueStatistics = component::onShowRevenueStatistics,
                                    onLinkedChatClick = component::onLinkedChatClick,
                                    onShowPermissions = component::onShowPermissions,
                                    onAcceptTOS = component::onAcceptTOS,
                                    onLocationClick = component::onLocationClick
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    profileMediaSection(
                        state = state,
                        tabs = state.visibleTabs,
                        selectedTabKey = state.selectedTabKey,
                        onTabSelected = component::onTabSelected,
                        onMessageClick = component::onMessageClick,
                        onMessageLongClick = component::onMessageLongClick,
                        onLoadMore = component::onLoadMoreMedia,
                        onMemberClick = component::onMemberClick,
                        onMemberLongClick = component::onMemberLongClick,
                        onLoadMedia = { msg ->
                            component.onDownloadMedia(msg)
                        },
                        onOpenActiveStory = component::onOpenActiveStory,
                        onOpenPostedStory = component::onOpenPostedStory,
                        onOpenArchive = component::onOpenStoryArchive
                    )

                    item(span = { GridItemSpan(3) }) {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }

        ProfileQRDialog(
            state = state,
            onDismiss = component::onDismissQRCode
        )

        if (state.isReportVisible) {
            ReportChatDialog(
                onDismiss = component::onDismissReport,
                onReasonSelected = component::onReport
            )
        }

        if (showLeaveSheet && canLeaveTopBar) {
            ConfirmationSheet(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                title = stringResource(R.string.leave_chat_title),
                description = stringResource(R.string.leave_chat_confirmation),
                confirmText = stringResource(R.string.action_leave),
                onConfirm = {
                    component.onLeave()
                },
                onDismiss = { showLeaveSheet = false }
            )
        }

        if (showDeleteChatSheet && canDeleteTopBar) {
            ConfirmationSheet(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.delete_chat_title),
                description = stringResource(R.string.delete_chat_confirmation),
                confirmText = stringResource(R.string.action_delete_chat),
                onConfirm = {
                    component.onDeleteChat()
                },
                onDismiss = { showDeleteChatSheet = false }
            )
        }

        if (showBlockSheet && canBlockTopBar) {
            ConfirmationSheet(
                icon = Icons.Rounded.Block,
                title = if (state.isBlocked) stringResource(R.string.unblock_user_title) else stringResource(R.string.block_user_title),
                description = if (state.isBlocked) stringResource(R.string.unblock_user_confirmation) else stringResource(
                    R.string.block_user_confirmation
                ),
                confirmText = if (state.isBlocked) stringResource(R.string.privacy_unblock_action) else stringResource(R.string.action_block),
                onConfirm = {
                    component.onToggleBlockUser()
                },
                onDismiss = { showBlockSheet = false },
                isDestructive = !state.isBlocked
            )
        }

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

        if (state.isStatisticsVisible || state.isRevenueStatisticsVisible) {
            StatisticsViewer(
                title = if (state.isRevenueStatisticsVisible) {
                    stringResource(R.string.revenue_title)
                } else {
                    stringResource(R.string.statistics_title)
                },
                data = if (state.isRevenueStatisticsVisible) {
                    state.revenueStatistics
                } else {
                    state.statistics
                },
                onDismiss = component::onDismissStatistics,
                onLoadGraph = component::onLoadStatisticsGraph
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

@Composable
private fun ProfileHeaderSkeleton(
    progress: Float,
    contentPadding: PaddingValues
) {
    val shimmer = rememberShimmerBrush()
    val containerSize = LocalWindowInfo.current.containerSize
    val screenHeight = with(LocalDensity.current) { containerSize.height.toDp() }
    val titleWidth = androidx.compose.ui.unit.lerp(220.dp, 124.dp, progress)
    val subtitleWidth = androidx.compose.ui.unit.lerp(132.dp, 88.dp, progress)
    val avatarCornerPercent = (100 * (1f - progress)).toInt()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    ) {
        val headerHeight = maxWidth.coerceAtMost(screenHeight * 0.6f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .clip(RoundedCornerShape(avatarCornerPercent))
                .background(shimmer)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp, vertical = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .width(titleWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(shimmer)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .height(16.dp)
                        .width(subtitleWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(shimmer)
                )
            }
        }
    }
}
