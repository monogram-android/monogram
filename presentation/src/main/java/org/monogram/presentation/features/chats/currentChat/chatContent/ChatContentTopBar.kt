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
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.core.util.rememberUserStatusText
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.components.ChatTopBar
import org.monogram.presentation.features.chats.currentChat.components.pins.PinnedMessageBar
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatContentTopBar(
    state: ChatComponent.State,
    component: ChatComponent,
    contentAlpha: Float,
    onBack: () -> Unit,
    onOpenMenu: () -> Unit = {},
    onPinnedMessageClick: (MessageModel) -> Unit,
    showBack: Boolean = true
) {
    val localClipboard = LocalClipboard.current
    val isAdBlockEnabled by component.appPreferences.isAdBlockEnabled.collectAsState()
    val isSelectionMode = state.selectedMessageIds.isNotEmpty()
    val isMainChat = state.currentTopicId == null && state.rootMessage == null
    val canClearOrDeleteChat = (!state.isGroup && !state.isChannel) || state.isAdmin
    val otherUserId = state.otherUser?.id
    val canReportChat = state.isGroup || state.isChannel ||
            (otherUserId != null && state.currentUser?.id != otherUserId)

    var showDeleteSheet by remember { mutableStateOf(false) }
    var pendingUnpinMessage by remember { mutableStateOf<MessageModel?>(null) }
    val iconButtonShapes = ExpressiveDefaults.iconButtonShapes()

    if (showDeleteSheet) {
        val selectedMessages = remember(state.messages, state.selectedMessageIds) {
            state.messages.filter { it.id in state.selectedMessageIds }
        }
        val canRevoke = remember(selectedMessages) {
            selectedMessages.any { it.canBeDeletedForAllUsers }
        }

        DeleteMessagesSheet(
            count = state.selectedMessageIds.size,
            canRevoke = canRevoke,
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
                        Text(text = "${state.selectedMessageIds.size}")
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
                val formattedUserStatus = rememberUserStatusText(state.otherUser)
                val statusText = when {
                    state.typingAction != null -> state.typingAction
                    state.isChannel -> stringResource(
                        R.string.subscribers_count_format,
                        state.memberCount
                    )

                    state.isGroup -> {
                        if (state.onlineCount > 0) {
                            stringResource(
                                R.string.members_online_count_format,
                                stringResource(R.string.members_count_format, state.memberCount),
                                state.onlineCount
                            )
                        } else {
                            stringResource(R.string.members_count_format, state.memberCount)
                        }
                    }

                    else -> formattedUserStatus
                }
                val currentTopic = remember(state.currentTopicId, state.topics) {
                    if (state.currentTopicId != null) {
                        state.topics.find { it.id.toLong() == state.currentTopicId }
                    } else null
                }

                val threadTitle = stringResource(R.string.thread_title)
                val title = remember(currentTopic, state.rootMessage, state.chatTitle, threadTitle) {
                    when {
                        currentTopic != null -> currentTopic.name
                        state.rootMessage != null -> threadTitle
                        else -> state.chatTitle
                    }
                }
                val topicEmojiPath = currentTopic?.iconCustomEmojiPath

                ChatTopBar(
                    title = title,
                    avatarPath = state.chatAvatar,
                    emojiStatusPath = state.chatEmojiStatus,
                    statusText = statusText,
                    isOnline = state.isOnline,
                    isVerified = state.isVerified,
                    isSponsor = state.isSponsor,
                    onBack = onBack,
                    onMenu = onOpenMenu,
                    onClick = { component.onProfileClicked() },
                    topicEmojiPath = topicEmojiPath,
                    isChannel = state.isChannel,
                    isWhitelistedInAdBlock = state.isWhitelistedInAdBlock,
                    onToggleAdBlockWhitelist = if (isMainChat && state.isChannel && isAdBlockEnabled && !state.isInstalledFromGooglePlay) {
                        {
                            if (state.isWhitelistedInAdBlock) {
                                component.onRemoveFromAdBlockWhitelist()
                            } else {
                                component.onAddToAdBlockWhitelist()
                            }
                        }
                    } else null,
                    isMuted = state.isMuted,
                    onToggleMute = component::onToggleMute,
                    isSearchActive = state.isSearchActive,
                    searchQuery = state.searchQuery,
                    onSearchToggle = component::onSearchToggle,
                    onSearchQueryChange = component::onSearchQueryChange,
                    onClearHistory = if (isMainChat && canClearOrDeleteChat) component::onClearHistory else null,
                    onDeleteChat = if (isMainChat && canClearOrDeleteChat) component::onDeleteChat else null,
                    onReport = if (isMainChat && canReportChat) component::onReport else null,
                    onCopyLink = if (isMainChat && (state.isGroup || state.isChannel)) {
                        { component.onCopyLink(localClipboard) }
                    } else null,
                    onManageMembers = if (isMainChat && state.isGroup && (state.isAdmin || state.permissions.canInviteUsers)) {
                        { component.onProfileClicked() }
                    } else null,
                    showBack = showBack,
                    personalAvatarPath = state.chatPersonalAvatar
                )
            }
        }

        val showPinned = state.pinnedMessage != null && !isSelectionMode && state.rootMessage == null
        AnimatedVisibility(
            visible = showPinned,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            state.pinnedMessage?.let { pinned ->
                PinnedMessageBar(
                    message = pinned,
                    count = state.pinnedMessageCount,
                    onClose = { pendingUnpinMessage = pinned },
                    onClick = { onPinnedMessageClick(pinned) },
                    onShowAll = { component.onShowAllPinnedMessages() }
                )
            }
        }
    }
}