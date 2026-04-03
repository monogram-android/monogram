package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShieldMoon
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ConnectionStatus
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ExpressiveDefaults
import org.monogram.presentation.features.stickers.ui.view.StickerImage


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatListTopBar(
    user: UserModel?,
    connectionStatus: ConnectionStatus?,
    isProxyEnabled: Boolean,
    onRetryConnection: () -> Unit,
    onProxySettingsClick: () -> Unit,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onStatusClick: (Rect?) -> Unit,
    onMenuClick: () -> Unit
) {
    var statusAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val iconButtonShapes = ExpressiveDefaults.iconButtonShapes()
    val motionScheme = MaterialTheme.motionScheme

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
                                IconButton(onClick = onSearchToggle, shapes = iconButtonShapes) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                                }
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }, shapes = iconButtonShapes) {
                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                                    }
                                }
                            }
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    shape = ShapeDefaults.LargeIncreased,
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        dividerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {}
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .animateContentSize(
                                animationSpec = motionScheme.defaultSpatialSpec()
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name_monogram),
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (!user?.statusEmojiPath.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .onGloballyPositioned { statusAnchorBounds = it.boundsInRoot() }
                                        .clickable { onStatusClick(statusAnchorBounds) }
                                ) {
                                    StickerImage(
                                        path = user.statusEmojiPath,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            } else if (user?.isPremium == true) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = stringResource(R.string.telegram_premium_title),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .onGloballyPositioned { statusAnchorBounds = it.boundsInRoot() }
                                        .clickable { onStatusClick(statusAnchorBounds) },
                                    tint = Color(0xFF31A6FD)
                                )
                            }
                        }

                        val statusInfo = when {
                            connectionStatus != null && connectionStatus !is ConnectionStatus.Connected -> {
                                val (text, color, action) = when (connectionStatus) {
                                    ConnectionStatus.WaitingForNetwork -> Triple(
                                        stringResource(R.string.waiting_for_network),
                                        MaterialTheme.colorScheme.error,
                                        TopBarStatusAction.Retry
                                    )

                                    ConnectionStatus.Connecting -> Triple(
                                        stringResource(R.string.connecting),
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                        TopBarStatusAction.ProxySettings
                                    )

                                    ConnectionStatus.Updating -> Triple(
                                        stringResource(R.string.updating),
                                        MaterialTheme.colorScheme.primary,
                                        TopBarStatusAction.Retry
                                    )

                                    ConnectionStatus.ConnectingToProxy -> Triple(
                                        stringResource(R.string.connecting_to_proxy),
                                        MaterialTheme.colorScheme.primary,
                                        TopBarStatusAction.ProxySettings
                                    )
                                }

                                TopBarStatusInfo(text = text, color = color, action = action)
                            }

                            isProxyEnabled -> TopBarStatusInfo(
                                text = stringResource(R.string.proxy_enabled),
                                color = MaterialTheme.colorScheme.primary,
                                action = TopBarStatusAction.ProxySettings
                            )

                            else -> null
                        }

                        AnimatedContent(
                            targetState = statusInfo,
                            transitionSpec = {
                                val enter = fadeIn(
                                    animationSpec = motionScheme.defaultEffectsSpec()
                                ) +
                                        slideInVertically(
                                            animationSpec = motionScheme.defaultSpatialSpec(),
                                            initialOffsetY = { it / 2 }
                                        ) +
                                        expandVertically(
                                            animationSpec = motionScheme.defaultSpatialSpec(),
                                            expandFrom = Alignment.Top
                                        )

                                val exit = fadeOut(
                                    animationSpec = motionScheme.fastEffectsSpec()
                                ) +
                                        slideOutVertically(
                                            animationSpec = motionScheme.fastSpatialSpec(),
                                            targetOffsetY = { it / 3 }
                                        ) +
                                        shrinkVertically(
                                            animationSpec = motionScheme.fastSpatialSpec(),
                                            shrinkTowards = Alignment.Top
                                        )

                                enter.togetherWith(exit).using(SizeTransform(clip = false))
                            },
                            label = "TopBarStatusTextTransition"
                        ) { info ->
                            if (info != null) {
                                Text(
                                    text = info.text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = info.color,
                                    modifier = Modifier.clickable {
                                        when (info.action) {
                                            TopBarStatusAction.Retry -> onRetryConnection()
                                            TopBarStatusAction.ProxySettings -> onProxySettingsClick()
                                        }
                                    }
                                )
                            } else {
                                Spacer(modifier = Modifier.height(0.dp))
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isProxyEnabled) {
                            val isConnected =
                                connectionStatus is ConnectionStatus.Connected || connectionStatus is ConnectionStatus.Updating
                            IconButton(onClick = onProxySettingsClick, shapes = iconButtonShapes) {
                                Icon(
                                    imageVector = if (isConnected) Icons.Rounded.Shield else Icons.Rounded.ShieldMoon,
                                    contentDescription = stringResource(R.string.cd_proxy),
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isConnected) Color(0xFF34A853) else MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        IconButton(onClick = onSearchToggle, shapes = iconButtonShapes) {
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
                            shapes = iconButtonShapes,
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = "Settings" }
                        ) {
                            AvatarTopAppBar(
                                path = user?.avatarPath,
                                fallbackPath = user?.personalAvatarPath,
                                name = user?.firstName ?: "",
                                size = 36.dp
                            )
                        }
                    }
                }

            }
        }
    }
}

private data class TopBarStatusInfo(
    val text: String,
    val color: Color,
    val action: TopBarStatusAction
)

private enum class TopBarStatusAction {
    Retry,
    ProxySettings
}
