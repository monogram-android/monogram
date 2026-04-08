package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.TopicModel
import org.monogram.domain.models.UserModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.util.rememberUserStatusText
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.components.ChatTopBar
import org.monogram.presentation.features.chats.currentChat.components.pins.PinnedMessageBar
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

@Immutable
data class ChatContentTopBarUiState(
    val currentTopicId: Long?,
    val rootMessage: MessageModel?,
    val isGroup: Boolean,
    val isChannel: Boolean,
    val isAdmin: Boolean,
    val permissions: ChatPermissionsModel,
    val otherUser: UserModel?,
    val currentUser: UserModel?,
    val typingAction: String?,
    val memberCount: Int,
    val onlineCount: Int,
    val topics: List<TopicModel>,
    val chatTitle: String,
    val chatAvatar: String?,
    val chatPersonalAvatar: String?,
    val chatEmojiStatus: String?,
    val isOnline: Boolean,
    val isVerified: Boolean,
    val isSponsor: Boolean,
    val isWhitelistedInAdBlock: Boolean,
    val isInstalledFromGooglePlay: Boolean,
    val isMuted: Boolean,
    val isSearchActive: Boolean,
    val searchQuery: String,
    val pinnedMessage: MessageModel?,
    val pinnedMessageCount: Int
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatContentTopBar(
    topBarState: ChatContentTopBarUiState,
    selectedCount: Int,
    canRevokeSelected: Boolean,
    component: ChatComponent,
    contentAlpha: Float,
    onBack: () -> Unit,
    onOpenMenu: () -> Unit = {},
    onPinnedMessageClick: (MessageModel) -> Unit,
    showBack: Boolean = true
) {
    val localClipboard = LocalClipboard.current
    val isAdBlockEnabled by component.appPreferences.isAdBlockEnabled.collectAsState()
    val isSelectionMode = selectedCount > 0
    val isMainChat = topBarState.currentTopicId == null && topBarState.rootMessage == null
    val canClearOrDeleteChat = (!topBarState.isGroup && !topBarState.isChannel) || topBarState.isAdmin
    val otherUserId = topBarState.otherUser?.id
    val canReportChat = topBarState.isGroup || topBarState.isChannel ||
            (otherUserId != null && topBarState.currentUser?.id != otherUserId)

    var showDeleteSheet by remember { mutableStateOf(false) }
    var pendingUnpinMessage by remember { mutableStateOf<MessageModel?>(null) }
    val iconButtonShapes = ExpressiveDefaults.iconButtonShapes()

    if (showDeleteSheet) {
        DeleteMessagesSheet(
            count = selectedCount,
            canRevoke = canRevokeSelected,
            onDismiss = { showDeleteSheet = false },
            onDelete = { revoke ->
                component.onDeleteSelectedMessages(revoke = revoke)
                showDeleteSheet = false
            }
        )
    }

    pendingUnpinMessage?.let { pinnedToUnpin ->
        ConfirmationSheet(
            icon = Icons.Rounded.PushPin,
            title = stringResource(R.string.unpin_message_title),
            description = stringResource(R.string.unpin_message_confirmation),
            confirmText = stringResource(R.string.action_unpin),
            onConfirm = {
                component.onUnpinMessage(pinnedToUnpin)
                pendingUnpinMessage = null
            },
            onDismiss = { pendingUnpinMessage = null }
        )
    }

    Column(
        modifier = Modifier.graphicsLayer {
            alpha = contentAlpha
        }
    ) {
        AnimatedContent(
            targetState = isSelectionMode,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "TopBarSelection"
        ) { selectionMode ->
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(text = "$selectedCount")
                    },
                    navigationIcon = {
                        IconButton(onClick = { component.onClearSelection() }, shapes = iconButtonShapes) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_clear_selection)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { component.onForwardSelectedMessages() }, shapes = iconButtonShapes) {
                            Icon(
                                Icons.AutoMirrored.Filled.Forward,
                                contentDescription = stringResource(R.string.menu_forward)
                            )
                        }
                        IconButton(onClick = { component.onCopySelectedMessages(localClipboard) }, shapes = iconButtonShapes) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.menu_copy)
                            )
                        }
                        IconButton(onClick = { showDeleteSheet = true }, shapes = iconButtonShapes) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.menu_delete)
                            )
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = {
                                onOpenMenu()
                                showMenu = true
                            }, shapes = iconButtonShapes) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.menu_more)
                                )
                            }
                            if (showMenu) {
                                Popup(
                                    onDismissRequest = { showMenu = false },
                                    properties = PopupProperties(focusable = true)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { showMenu = false }
                                    ) {
                                        var isVisible by remember { mutableStateOf(false) }
                                        LaunchedEffect(Unit) { isVisible = true }

                                        @Suppress("RemoveRedundantQualifierName")
                                        androidx.compose.animation.AnimatedVisibility(
                                            visible = isVisible,
                                            enter = fadeIn(tween(150)) + scaleIn(
                                                animationSpec = spring(
                                                    dampingRatio = 0.8f,
                                                    stiffness = Spring.StiffnessMedium
                                                ),
                                                initialScale = 0.8f,
                                                transformOrigin = TransformOrigin(1f, 0f)
                                            ),
                                            exit = fadeOut(tween(150)) + scaleOut(
                                                animationSpec = tween(150),
                                                targetScale = 0.9f,
                                                transformOrigin = TransformOrigin(1f, 0f)
                                            ),
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .windowInsetsPadding(WindowInsets.statusBars)
                                                .padding(top = 56.dp, end = 16.dp)
                                        ) {
                                            ViewerSettingsDropdown {
                                                MenuOptionRow(
                                                    icon = Icons.AutoMirrored.Filled.Forward,
                                                    title = stringResource(R.string.menu_forward),
                                                    onClick = {
                                                        showMenu = false
                                                        component.onForwardSelectedMessages()
                                                    }
                                                )
                                                MenuOptionRow(
                                                    icon = Icons.Default.ContentCopy,
                                                    title = stringResource(R.string.menu_copy),
                                                    onClick = {
                                                        showMenu = false
                                                        component.onCopySelectedMessages(localClipboard)
                                                    }
                                                )
                                                MenuOptionRow(
                                                    icon = Icons.Rounded.Report,
                                                    title = stringResource(R.string.menu_report),
                                                    onClick = {
                                                        showMenu = false
                                                        component.onReport()
                                                    }
                                                )
                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                MenuOptionRow(
                                                    icon = Icons.Default.Delete,
                                                    title = stringResource(R.string.menu_delete),
                                                    textColor = MaterialTheme.colorScheme.error,
                                                    iconTint = MaterialTheme.colorScheme.error,
                                                    onClick = {
                                                        showMenu = false
                                                        showDeleteSheet = true
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            } else {
                val formattedUserStatus = rememberUserStatusText(topBarState.otherUser)
                val statusText = when {
                    topBarState.typingAction != null -> topBarState.typingAction
                    topBarState.isChannel -> stringResource(
                        R.string.subscribers_count_format,
                        topBarState.memberCount
                    )

                    topBarState.isGroup -> {
                        if (topBarState.onlineCount > 0) {
                            stringResource(
                                R.string.members_online_count_format,
                                stringResource(R.string.members_count_format, topBarState.memberCount),
                                topBarState.onlineCount
                            )
                        } else {
                            stringResource(R.string.members_count_format, topBarState.memberCount)
                        }
                    }

                    else -> formattedUserStatus
                }
                val currentTopic = remember(topBarState.currentTopicId, topBarState.topics) {
                    if (topBarState.currentTopicId != null) {
                        topBarState.topics.find { it.id.toLong() == topBarState.currentTopicId }
                    } else null
                }

                val threadTitle = stringResource(R.string.thread_title)
                val title = remember(currentTopic, topBarState.rootMessage, topBarState.chatTitle, threadTitle) {
                    when {
                        currentTopic != null -> currentTopic.name
                        topBarState.rootMessage != null -> threadTitle
                        else -> topBarState.chatTitle
                    }
                }
                val topicEmojiPath = currentTopic?.iconCustomEmojiPath

                ChatTopBar(
                    title = title,
                    avatarPath = topBarState.chatAvatar,
                    emojiStatusPath = topBarState.chatEmojiStatus,
                    statusText = statusText,
                    isOnline = topBarState.isOnline,
                    isVerified = topBarState.isVerified,
                    isSponsor = topBarState.isSponsor,
                    onBack = onBack,
                    onMenu = onOpenMenu,
                    onClick = { component.onProfileClicked() },
                    topicEmojiPath = topicEmojiPath,
                    isChannel = topBarState.isChannel,
                    isWhitelistedInAdBlock = topBarState.isWhitelistedInAdBlock,
                    onToggleAdBlockWhitelist = if (isMainChat && topBarState.isChannel && isAdBlockEnabled && !topBarState.isInstalledFromGooglePlay) {
                        {
                            if (topBarState.isWhitelistedInAdBlock) {
                                component.onRemoveFromAdBlockWhitelist()
                            } else {
                                component.onAddToAdBlockWhitelist()
                            }
                        }
                    } else null,
                    isMuted = topBarState.isMuted,
                    onToggleMute = component::onToggleMute,
                    isSearchActive = topBarState.isSearchActive,
                    searchQuery = topBarState.searchQuery,
                    onSearchToggle = component::onSearchToggle,
                    onSearchQueryChange = component::onSearchQueryChange,
                    onClearHistory = if (isMainChat && canClearOrDeleteChat) component::onClearHistory else null,
                    onDeleteChat = if (isMainChat && canClearOrDeleteChat) component::onDeleteChat else null,
                    onReport = if (isMainChat && canReportChat) component::onReport else null,
                    onCopyLink = if (isMainChat && (topBarState.isGroup || topBarState.isChannel)) {
                        { component.onCopyLink(localClipboard) }
                    } else null,
                    onManageMembers = if (isMainChat && topBarState.isGroup && (topBarState.isAdmin || topBarState.permissions.canInviteUsers)) {
                        { component.onProfileClicked() }
                    } else null,
                    showBack = showBack,
                    personalAvatarPath = topBarState.chatPersonalAvatar
                )
            }
        }

        val showPinned = topBarState.pinnedMessage != null && !isSelectionMode && topBarState.rootMessage == null
        AnimatedVisibility(
            visible = showPinned,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            topBarState.pinnedMessage?.let { pinned ->
                PinnedMessageBar(
                    message = pinned,
                    count = topBarState.pinnedMessageCount,
                    onClose = { pendingUnpinMessage = pinned },
                    onClick = { onPinnedMessageClick(pinned) },
                    onShowAll = { component.onShowAllPinnedMessages() }
                )
            }
        }
    }
}