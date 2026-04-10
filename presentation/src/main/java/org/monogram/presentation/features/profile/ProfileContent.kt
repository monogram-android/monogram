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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.koin.compose.koinInject
import org.monogram.domain.models.UserStatusType
import org.monogram.domain.models.UserTypeEnum
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.CollapsingToolbarScaffold
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.rememberCollapsingToolbarScaffoldState
import org.monogram.presentation.core.ui.rememberShimmerBrush
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.core.util.ScrollStrategy
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.profile.components.ProfileHeaderTransformed
import org.monogram.presentation.features.profile.components.ProfileInfoSection
import org.monogram.presentation.features.profile.components.ProfileInfoSectionSkeleton
import org.monogram.presentation.features.profile.components.ProfilePermissionsDialog
import org.monogram.presentation.features.profile.components.ProfileQRDialog
import org.monogram.presentation.features.profile.components.ProfileReportDialog
import org.monogram.presentation.features.profile.components.ProfileTOSDialog
import org.monogram.presentation.features.profile.components.ProfileTopBar
import org.monogram.presentation.features.profile.components.profileMediaSection

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
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    val chat = state.chat
    val user = state.user
    val isCurrentUserProfile = user != null && state.currentUser?.id == user.id
    val isInitialLoading = state.isLoading && chat == null && user == null

    val avatarPath = remember(state.profilePhotos, state.chat, state.user, state.personalAvatarPath) {
        state.personalAvatarPath
            ?: state.profilePhotos.firstOrNull()
            ?: state.user?.avatarPath
            ?: state.chat?.personalAvatarPath
            ?: state.chat?.avatarPath
    }


    val unknownTitle = stringResource(R.string.unknown_title)
    val title = remember(user, chat, unknownTitle) {
        chat?.title ?: listOfNotNull(user?.firstName, user?.lastName)
            .joinToString(" ")
            .ifBlank { unknownTitle }
    }

    val membersOnlineCountFormat = stringResource(R.string.members_online_count_format)
    val ownProfileSubtitle = stringResource(R.string.menu_my_profile_subtitle)
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
                ?.let { "$ownProfileSubtitle • @$it" }
                ?: ownProfileSubtitle
        }

        else -> getUserStatusText(user, context, timeFormat)
    }

    val isOnline = user?.type != UserTypeEnum.BOT && user?.userStatus == UserStatusType.ONLINE
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
    val shareLink = remember(user, chat, state.publicLink) {
        user?.username?.takeIf { it.isNotBlank() }?.let { "https://t.me/$it" }
            ?: chat?.username?.takeIf { it.isNotBlank() }?.let { "https://t.me/$it" }
            ?: state.publicLink?.takeIf { it.isNotBlank() }
    }
    val fallbackShareText = remember(isCurrentUserProfile, user) {
        if (isCurrentUserProfile) user.id.toString() else null
    }
    val canShareTopBar = !shareLink.isNullOrEmpty() || !fallbackShareText.isNullOrEmpty()
    val canReportTopBar = isGroupOrChannel && !isCurrentUserProfile
    val canBlockTopBar = !isCurrentUserProfile && !isGroupOrChannel && user?.type != UserTypeEnum.BOT
    val canEditContactTopBar = !isCurrentUserProfile && !isGroupOrChannel && user?.isContact == true
    val canDeleteTopBar = !isCurrentUserProfile && (!isGroupOrChannel || chat.isMember)
    var showLeaveSheet by remember { mutableStateOf(false) }
    var showDeleteChatSheet by remember { mutableStateOf(false) }
    var showBlockSheet by remember { mutableStateOf(false) }
    var showEditContactDialog by remember { mutableStateOf(false) }
    var editContactFirstName by remember { mutableStateOf("") }
    var editContactLastName by remember { mutableStateOf("") }

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
                    canReport = canReportTopBar,
                    canBlock = canBlockTopBar,
                    isBlocked = state.isBlocked,
                    canDelete = canDeleteTopBar,
                    onSearch = { Toast.makeText(context, searchNotImplemented, Toast.LENGTH_SHORT).show() },
                    onShare = {
                        val valueToCopy = shareLink ?: fallbackShareText
                        if (valueToCopy != null) {
                            localClipboard.nativeClipboard.setPrimaryClip(
                                ClipData.newPlainText("", AnnotatedString(valueToCopy))
                            )

                            val copiedMessage = if (shareLink != null) linkCopied else userIdCopied

                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onEdit = component::onEdit,
                    onEditContact = {
                        val currentUser = state.user ?: return@ProfileTopBar
                        editContactFirstName = currentUser.firstName
                        editContactLastName = currentUser.lastName.orEmpty()
                        showEditContactDialog = true
                    },
                    onReport = component::onShowReport,
                    onBlock = { showBlockSheet = true },
                    onDelete = {
                        if (isGroupOrChannel) {
                            showLeaveSheet = true
                        } else {
                            showDeleteChatSheet = true
                        }
                    }
                )
            },
            containerColor = dynamicContainerColor
        ) { padding ->
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
                                    onLeave = { showLeaveSheet = true },
                                    onJoin = component::onJoinChat,
                                    onShowLogs = component::onShowLogs,
                                    onShowStatistics = component::onShowStatistics,
                                    onShowRevenueStatistics = component::onShowRevenueStatistics,
                                    onLinkedChatClick = component::onLinkedChatClick,
                                    onShowPermissions = component::onShowPermissions,
                                    onAcceptTOS = component::onAcceptTOS,
                                    onToggleContact = component::onToggleContact,
                                    onLocationClick = component::onLocationClick
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    profileMediaSection(
                        state = state,
                        isGroup = isGroup,
                        tabs = tabs,
                        onTabSelected = component::onTabSelected,
                        onMessageClick = component::onMessageClick,
                        onMessageLongClick = component::onMessageLongClick,
                        onLoadMore = component::onLoadMoreMedia,
                        onMemberClick = component::onMemberClick,
                        onMemberLongClick = component::onMemberLongClick,
                        onLoadMedia = { msg ->
                            component.onDownloadMedia(msg)
                        }
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

        ProfileReportDialog(
            state = state,
            onDismiss = component::onDismissReport,
            onReport = component::onReport
        )

        if (showLeaveSheet) {
            ConfirmationSheet(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                title = stringResource(R.string.leave_chat_title),
                description = stringResource(R.string.leave_chat_confirmation),
                confirmText = stringResource(R.string.action_leave),
                onConfirm = {
                    component.onLeave()
                    showLeaveSheet = false
                },
                onDismiss = { showLeaveSheet = false }
            )
        }

        if (showDeleteChatSheet && !isGroupOrChannel) {
            ConfirmationSheet(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.delete_chat_title),
                description = stringResource(R.string.delete_chat_confirmation),
                confirmText = stringResource(R.string.action_delete_chat),
                onConfirm = {
                    component.onDeleteChat()
                    showDeleteChatSheet = false
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
                    showBlockSheet = false
                },
                onDismiss = { showBlockSheet = false },
                isDestructive = !state.isBlocked
            )
        }

        if (showEditContactDialog && canEditContactTopBar && state.user != null) {
            ModalBottomSheet(
                onDismissRequest = { showEditContactDialog = false },
                dragHandle = { BottomSheetDefaults.DragHandle() },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = stringResource(R.string.edit_contact_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsTextField(
                        value = editContactFirstName,
                        onValueChange = { editContactFirstName = it },
                        placeholder = stringResource(R.string.first_name_label),
                        icon = Icons.Rounded.Person,
                        position = ItemPosition.TOP,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    SettingsTextField(
                        value = editContactLastName,
                        onValueChange = { editContactLastName = it },
                        placeholder = stringResource(R.string.last_name_label),
                        icon = Icons.Rounded.Edit,
                        position = ItemPosition.BOTTOM,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showEditContactDialog = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                stringResource(R.string.cancel_button),
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                component.onEditContact(editContactFirstName, editContactLastName)
                                showEditContactDialog = false
                            },
                            enabled = editContactFirstName.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                stringResource(R.string.action_save),
                                fontSize = 16.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }
            }
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
