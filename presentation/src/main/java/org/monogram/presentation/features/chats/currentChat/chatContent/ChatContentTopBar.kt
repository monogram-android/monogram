package org.monogram.presentation.features.chats.currentChat.chatContent

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.util.rememberUserStatusText
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.components.ChatTopBar
import org.monogram.presentation.features.chats.currentChat.components.pins.PinnedMessageBar
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.viewers.components.ViewerSettingsDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContentTopBar(
    state: ChatComponent.State,
    component: ChatComponent,
    contentAlpha: Float,
    onBack: () -> Unit,
    onPinnedMessageClick: (MessageModel) -> Unit,
    showBack: Boolean = true
) {
    val clipboardManager = LocalClipboardManager.current
    val isAdBlockEnabled by component.appPreferences.isAdBlockEnabled.collectAsState()
    val isSelectionMode = state.selectedMessageIds.isNotEmpty()
    val isMainChat = state.currentTopicId == null && state.rootMessage == null
    val otherUserId = state.otherUser?.id
    val canReportChat = state.isGroup || state.isChannel ||
            (otherUserId != null && state.currentUser?.id != otherUserId)

    var showDeleteSheet by remember { mutableStateOf(false) }

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
                        IconButton(onClick = { component.onClearSelection() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.cd_clear_selection)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { component.onForwardSelectedMessages() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Forward,
                                contentDescription = stringResource(R.string.menu_forward)
                            )
                        }
                        IconButton(onClick = { component.onCopySelectedMessages(clipboardManager) }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.menu_copy)
                            )
                        }
                        IconButton(onClick = { showDeleteSheet = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.menu_delete)
                            )
                        }
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
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
                                                        component.onCopySelectedMessages(clipboardManager)
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
                    onMenu = { },
                    onClick = { component.onProfileClicked() },
                    topicEmojiPath = topicEmojiPath,
                    isChannel = state.isChannel,
                    isWhitelistedInAdBlock = state.isWhitelistedInAdBlock,
                    onToggleAdBlockWhitelist = if (isMainChat && state.isChannel && isAdBlockEnabled) {
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
                    onClearHistory = if (isMainChat) component::onClearHistory else null,
                    onDeleteChat = if (isMainChat) component::onDeleteChat else null,
                    onReport = if (isMainChat && canReportChat) component::onReport else null,
                    onCopyLink = if (isMainChat && (state.isGroup || state.isChannel)) {
                        { component.onCopyLink(clipboardManager) }
                    } else null,
                    showBack = showBack,
                    personalAvatarPath = state.chatPersonalAvatar,
                    videoPlayerPool = component.videoPlayerPool
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
                    onClose = { component.onUnpinMessage(pinned) },
                    onClick = { onPinnedMessageClick(pinned) },
                    onShowAll = { component.onShowAllPinnedMessages() }
                )
            }
        }
    }
}