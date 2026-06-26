package org.monogram.presentation.features.chats.conversation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.AvatarForChat
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.TypingDots
import org.monogram.presentation.core.util.LocalTabletInterfaceEnabled
import org.monogram.presentation.features.stickers.ui.menu.ActionMenuPopup
import org.monogram.presentation.features.stickers.ui.menu.MenuOptionRow
import org.monogram.presentation.features.stickers.ui.view.StickerImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatTopBar(
    title: String,
    avatarPath: String?,
    emojiStatusPath: String?,
    statusText: String?,
    isOnline: Boolean = false,
    isVerified: Boolean = false,
    isSponsor: Boolean = false,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    onClick: () -> Unit = {},
    topicEmojiPath: String? = null,
    isChannel: Boolean = false,
    isWhitelistedInAdBlock: Boolean = false,
    onToggleAdBlockWhitelist: (() -> Unit)? = null,
    isMuted: Boolean = false,
    onToggleMute: (() -> Unit)? = null,
    isSearchActive: Boolean = false,
    searchQuery: String = "",
    onSearchToggle: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onClearHistory: (() -> Unit)? = null,
    onLeaveChat: (() -> Unit)? = null,
    onDeleteChat: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    onManageMembers: (() -> Unit)? = null,
    onShowPinnedMessage: (() -> Unit)? = null,
    showBack: Boolean = true,
    personalAvatarPath: String? = null,
    isTablet: Boolean = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    ) && LocalTabletInterfaceEnabled.current
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showClearHistorySheet by rememberSaveable { mutableStateOf(false) }
    var showLeaveChatSheet by rememberSaveable { mutableStateOf(false) }
    var showDeleteChatSheet by rememberSaveable { mutableStateOf(false) }

    val windowInsets = WindowInsets.statusBars
    val topInsetModifier = Modifier.fillMaxWidth()
    val topBarShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

    Surface(
        modifier = topInsetModifier,
        shape = topBarShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Box {
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "TopBarSearchTransition"
            ) { searching ->
                if (searching) {
                    TopAppBar(
                        windowInsets = windowInsets,
                        title = {
                            TextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.search_messages_hint)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                singleLine = true
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onSearchToggle) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back)
                                )
                            }
                        },
                        actions = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.action_clear)
                                    )
                                }
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        windowInsets = windowInsets,
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onClick)
                                    .semantics { contentDescription = "ChatHeaderButton" }
                                    .padding(6.dp)
                            ) {
                                if (topicEmojiPath != null) {
                                    StickerImage(
                                        path = topicEmojiPath,
                                        modifier = Modifier.size(40.dp),
                                        animate = true
                                    )
                                } else {
                                    AvatarForChat(
                                        path = avatarPath,
                                        fallbackPath = personalAvatarPath,
                                        name = title,
                                        size = 40.dp,
                                        isOnline = isOnline
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleMediumEmphasized,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isMuted) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                                                contentDescription = stringResource(R.string.cd_muted),
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (isVerified) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Rounded.Verified,
                                                contentDescription = stringResource(R.string.cd_verified),
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (isSponsor) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Rounded.Favorite,
                                                contentDescription = stringResource(R.string.cd_sponsor),
                                                modifier = Modifier.size(18.dp),
                                                tint = Color(0xFFE53935)
                                            )
                                        }
                                        if (emojiStatusPath != null) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            StickerImage(
                                                path = emojiStatusPath,
                                                modifier = Modifier.size(18.dp),
                                                animate = false
                                            )
                                        }
                                    }

                                    AnimatedContent(
                                        targetState = statusText,
                                        transitionSpec = {
                                            fadeIn() togetherWith fadeOut()
                                        },
                                        label = "StatusAnimation"
                                    ) { targetStatus ->
                                        if (!targetStatus.isNullOrEmpty()) {
                                            val normalizedStatus = targetStatus.lowercase()
                                            val typingTokens = listOf(
                                                stringResource(R.string.typing_typing),
                                                stringResource(R.string.typing_recording_video),
                                                stringResource(R.string.typing_recording_voice),
                                                stringResource(R.string.typing_uploading_photo),
                                                stringResource(R.string.typing_uploading_video),
                                                stringResource(R.string.typing_uploading_document),
                                                stringResource(R.string.typing_choosing_sticker),
                                                stringResource(R.string.typing_playing_game),
                                                stringResource(R.string.typing_multi_typing)
                                            ).map { it.lowercase() }
                                            val isTyping = typingTokens.any { token ->
                                                token.isNotBlank() && normalizedStatus.contains(
                                                    token
                                                )
                                            }

                                            Row(verticalAlignment = Alignment.Bottom) {
                                                Text(
                                                    text = targetStatus,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isOnline || isTyping) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isTyping) {
                                                    Spacer(Modifier.width(2.dp))
                                                    TypingDots(
                                                        dotSize = 3.dp,
                                                        dotColor = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(bottom = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            if (showBack) {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back)
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = onSearchToggle) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.action_search)
                                )
                            }
                            IconButton(onClick = {
                                onMenu()
                                showMenu = true
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            }

            ActionMenuPopup(
                visible = showMenu,
                onDismiss = { showMenu = false },
                modifier = Modifier
                    .windowInsetsPadding(windowInsets)
                    .padding(top = 8.dp, end = 16.dp)
            ) {
                if (onToggleMute != null) {
                    MenuOptionRow(
                        icon = if (isMuted) {
                            Icons.AutoMirrored.Rounded.VolumeUp
                        } else {
                            Icons.AutoMirrored.Rounded.VolumeOff
                        },
                        title = if (isMuted) {
                            stringResource(R.string.menu_unmute)
                        } else {
                            stringResource(R.string.menu_mute)
                        },
                        onClick = {
                            showMenu = false
                            onToggleMute()
                        }
                    )
                }
                if (isChannel && onToggleAdBlockWhitelist != null) {
                    MenuOptionRow(
                        icon = if (isWhitelistedInAdBlock) {
                            Icons.Rounded.Block
                        } else {
                            Icons.AutoMirrored.Rounded.PlaylistAddCheck
                        },
                        title = if (isWhitelistedInAdBlock) {
                            stringResource(R.string.menu_filter_ads)
                        } else {
                            stringResource(R.string.menu_whitelist_channel)
                        },
                        onClick = {
                            showMenu = false
                            onToggleAdBlockWhitelist()
                        }
                    )
                }
                if (onCopyLink != null) {
                    MenuOptionRow(
                        icon = Icons.Rounded.Link,
                        title = stringResource(R.string.menu_copy_link),
                        onClick = {
                            showMenu = false
                            onCopyLink()
                        }
                    )
                }
                if (onManageMembers != null) {
                    MenuOptionRow(
                        icon = Icons.Rounded.Groups,
                        title = stringResource(R.string.members),
                        onClick = {
                            showMenu = false
                            onManageMembers()
                        }
                    )
                }
                if (onShowPinnedMessage != null) {
                    MenuOptionRow(
                        icon = Icons.Rounded.PushPin,
                        title = stringResource(R.string.menu_show_pin),
                        onClick = {
                            showMenu = false
                            onShowPinnedMessage()
                        }
                    )
                }
                if (onClearHistory != null) {
                    MenuOptionRow(
                        icon = Icons.Rounded.CleaningServices,
                        title = stringResource(R.string.menu_clear_history),
                        onClick = {
                            showMenu = false
                            showClearHistorySheet = true
                        }
                    )
                }
                if (onLeaveChat != null) {
                    MenuOptionRow(
                        icon = Icons.AutoMirrored.Rounded.ExitToApp,
                        title = stringResource(R.string.menu_leave),
                        destructive = true,
                        onClick = {
                            showMenu = false
                            showLeaveChatSheet = true
                        }
                    )
                }
                if (onDeleteChat != null) {
                    MenuOptionRow(
                        icon = Icons.Rounded.Delete,
                        title = stringResource(R.string.menu_delete_chat),
                        destructive = true,
                        onClick = {
                            showMenu = false
                            showDeleteChatSheet = true
                        }
                    )
                }
                if (onReport != null) {
                    MenuOptionRow(
                        icon = Icons.Rounded.Report,
                        title = stringResource(R.string.menu_report),
                        onClick = {
                            showMenu = false
                            onReport()
                        }
                    )
                }
            }

            if (showClearHistorySheet && onClearHistory != null) {
                ConfirmationSheet(
                    icon = Icons.Rounded.CleaningServices,
                    title = stringResource(R.string.clear_history_title),
                    description = stringResource(R.string.clear_history_confirmation),
                    confirmText = stringResource(R.string.action_clear_history),
                    onConfirm = {
                        onClearHistory()
                        showClearHistorySheet = false
                    },
                    onDismiss = { showClearHistorySheet = false }
                )
            }

            if (showLeaveChatSheet && onLeaveChat != null) {
                ConfirmationSheet(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = stringResource(R.string.leave_chat_title),
                    description = stringResource(R.string.leave_chat_confirmation),
                    confirmText = stringResource(R.string.action_leave),
                    onConfirm = {
                        onLeaveChat()
                        showLeaveChatSheet = false
                    },
                    onDismiss = { showLeaveChatSheet = false }
                )
            }

            if (showDeleteChatSheet && onDeleteChat != null) {
                ConfirmationSheet(
                    icon = Icons.Rounded.Delete,
                    title = stringResource(R.string.delete_chat_title),
                    description = stringResource(R.string.delete_chat_confirmation),
                    confirmText = stringResource(R.string.action_delete_chat),
                    onConfirm = {
                        onDeleteChat()
                        showDeleteChatSheet = false
                    },
                    onDismiss = { showDeleteChatSheet = false }
                )
            }
        }
    }
}
