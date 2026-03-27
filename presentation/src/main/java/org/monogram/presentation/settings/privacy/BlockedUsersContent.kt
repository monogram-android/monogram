package org.monogram.presentation.settings.privacy

import androidx.compose.animation.*
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.UserModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedUsersContent(component: BlockedUsersComponent) {
    val state by component.state.subscribeAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.blocked_users_title),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (state.blockedUsers.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.blocked_users_count_format, state.blockedUsers.size),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = component::onAddBlockedUserClicked,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.privacy_block_user_cd))
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AnimatedContent(
            targetState = state.isLoading to state.blockedUsers.isEmpty(),
            transitionSpec = {
                fadeIn(animationSpec = tween(300, easing = EaseIn)) +
                        scaleIn(initialScale = 0.95f, animationSpec = tween(300, easing = EaseIn)) togetherWith
                        fadeOut(animationSpec = tween(200, easing = EaseOut))
            },
            label = "BlockedUsersContent",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { (isLoading, isEmpty) ->
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                isEmpty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.privacy_no_blocked_users),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.privacy_blocked_users_help),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp)
                            )
                        }

                        item {
                            state.blockedUsers.forEachIndexed { index, user ->
                                val position = when {
                                    state.blockedUsers.size == 1 -> ItemPosition.STANDALONE
                                    index == 0 -> ItemPosition.TOP
                                    index == state.blockedUsers.size - 1 -> ItemPosition.BOTTOM
                                    else -> ItemPosition.MIDDLE
                                }
                                BlockedUserItem(
                                    user = user,
                                    position = position,
                                    onUnblock = { component.onUnblockUserClicked(user.id) },
                                    onClick = { component.onUserClicked(user.id) },
                                    videoPlayerPool = component.videoPlayerPool
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedUserItem(
    user: UserModel,
    position: ItemPosition,
    videoPlayerPool: VideoPlayerPool,
    onUnblock: () -> Unit,
    onClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val deletedText = stringResource(R.string.privacy_user_deleted)
    val displayName = remember(user, deletedText) {
        buildString {
            if (user.firstName.isBlank()) {
                append("${user.id} $deletedText")
            } else {
                append(user.firstName)
                if (!user.lastName.isNullOrBlank()) {
                    append(" ")
                    append(user.lastName)
                }
            }
            if (!user.username.isNullOrBlank()) {
                append(" (@${user.username})")
            }
        }
    }

    val cornerRadius = 24.dp
    val shape = when (position) {
        ItemPosition.TOP -> RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )

        ItemPosition.MIDDLE -> RoundedCornerShape(4.dp)
        ItemPosition.BOTTOM -> RoundedCornerShape(
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius,
            topStart = 4.dp,
            topEnd = 4.dp
        )

        ItemPosition.STANDALONE -> RoundedCornerShape(cornerRadius)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                path = user.avatarPath,
                fallbackPath = user.personalAvatarPath,
                name = user.firstName.ifBlank { "D" },
                size = 40.dp,
                videoPlayerPool = videoPlayerPool
            )
            Spacer(modifier = Modifier.width(16.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (user.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = stringResource(R.string.cd_verified),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (user.isSponsor) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(R.string.cd_sponsor),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFE53935)
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.privacy_unblock_action)) },
                        onClick = {
                            onUnblock()
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}
