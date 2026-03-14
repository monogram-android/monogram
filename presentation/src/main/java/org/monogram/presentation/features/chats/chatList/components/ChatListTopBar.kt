package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShieldMoon
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.stickers.ui.view.StickerImage


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListTopBar(
    user: UserModel?,
    videoPlayerPool: VideoPlayerPool,
    connectionStatus: ConnectionStatus?,
    isProxyEnabled: Boolean,
    onRetryConnection: () -> Unit,
    onProxySettingsClick: () -> Unit,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onMenuClick: () -> Unit
) {
    AnimatedContent(
        targetState = isSearchActive,
        transitionSpec = {
            if (targetState) {
                (fadeIn() + slideInVertically { -it / 4 }).togetherWith(fadeOut())
            } else {
                fadeIn().togetherWith(fadeOut() + slideOutVertically { -it / 4 })
            }
        },
        label = "TopBarSearchTransition"
    ) { active ->
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text(stringResource(R.string.search_conversations_placeholder)) },
                            leadingIcon = {
                                IconButton(onClick = onSearchToggle) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                                }
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                                    }
                                }
                            }
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    shape = RoundedCornerShape(24.dp),
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        dividerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {}
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.app_name_monogram),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (user?.statusEmojiPath != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            StickerImage(
                                path = user.statusEmojiPath,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    if (connectionStatus != null && connectionStatus !is ConnectionStatus.Connected) {
                        val (text, color) = when (connectionStatus) {
                            ConnectionStatus.WaitingForNetwork -> stringResource(R.string.waiting_for_network) to MaterialTheme.colorScheme.error
                            ConnectionStatus.Connecting -> stringResource(R.string.connecting) to MaterialTheme.colorScheme.onSurfaceVariant
                            ConnectionStatus.Updating -> stringResource(R.string.updating) to MaterialTheme.colorScheme.primary
                            ConnectionStatus.ConnectingToProxy -> stringResource(R.string.connecting_to_proxy) to MaterialTheme.colorScheme.primary
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier.clickable {
                                if (connectionStatus == ConnectionStatus.Connecting || connectionStatus == ConnectionStatus.ConnectingToProxy) {
                                    onProxySettingsClick()
                                } else {
                                    onRetryConnection()
                                }
                            }
                        )
                    } else if (isProxyEnabled) {
                        Text(
                            text = stringResource(R.string.proxy_enabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onProxySettingsClick() }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isProxyEnabled) {
                        val isConnected =
                            connectionStatus is ConnectionStatus.Connected || connectionStatus is ConnectionStatus.Updating
                        IconButton(onClick = onProxySettingsClick) {
                            Icon(
                                imageVector = if (isConnected) Icons.Rounded.Shield else Icons.Rounded.ShieldMoon,
                                contentDescription = stringResource(R.string.cd_proxy),
                                modifier = Modifier.size(24.dp),
                                tint = if (isConnected) Color(0xFF34A853) else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    IconButton(onClick = onSearchToggle) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.action_search),
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.size(40.dp).semantics { contentDescription = "Settings" }
                    ) {
                        AvatarTopAppBar(
                            path = user?.personalAvatarPath ?: user?.avatarPath,
                            name = user?.firstName ?: "",
                            size = 36.dp,
                            videoPlayerPool = videoPlayerPool
                        )
                    }
                }
            }
        }
    }
}