package org.monogram.presentation.settings.privacy.userSelection

import androidx.compose.animation.*
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.UserModel
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.core.ui.SettingsGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSelectionContent(component: UserSelectionComponent) {
    val state by component.state.subscribeAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.searchQuery,
                        onValueChange = component::onSearchQueryChanged,
                        placeholder = { Text("Search users (ID for now)") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackClicked) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AnimatedContent(
            targetState = state.isLoading to (state.users.isEmpty() && state.searchQuery.isNotEmpty()),
            transitionSpec = {
                fadeIn(animationSpec = tween(300, easing = EaseIn)) +
                        scaleIn(initialScale = 0.95f, animationSpec = tween(300, easing = EaseIn)) togetherWith
                        fadeOut(animationSpec = tween(200, easing = EaseOut))
            },
            label = "UserSelectionContent",
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
        ) { (isLoading, isEmptySearch) ->
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                isEmptySearch -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No users found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 16.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                    ) {
                        if (state.users.isNotEmpty()) {
                            item {
                                SettingsGroup {
                                    state.users.forEach { user ->
                                        UserSelectionItem(
                                            user = user,
                                            onClick = { component.onUserClicked(user.id) },
                                            modifier = Modifier.animateItem(),
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
    }
}

@Composable
fun UserSelectionItem(
    user: UserModel,
    onClick: () -> Unit,
    videoPlayerPool: VideoPlayerPool,
    modifier: Modifier = Modifier
) {
    val displayName = remember(user) {
        buildString {
            if (user.firstName.isBlank()) {
                append("${user.id} (deleted)")
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

    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayName, modifier = Modifier.weight(1f, fill = false))
                if (user.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = "Verified",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        leadingContent = {
            Avatar(
                path = user.avatarPath,
                name = user.firstName.ifBlank { "D" },
                size = 40.dp,
                videoPlayerPool = videoPlayerPool
            )
        },
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
