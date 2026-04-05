@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.notifications

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsExceptionsContent(
    component: NotificationsComponent,
    scope: TdNotificationScope
) {
    val state by component.state.subscribeAsState()
    val exceptions = remember(state, scope) {
        when (scope) {
            TdNotificationScope.PRIVATE_CHATS -> state.privateExceptions
            TdNotificationScope.GROUPS -> state.groupExceptions
            TdNotificationScope.CHANNELS -> state.channelExceptions
        }
    }

    val title = remember(scope) {
        when (scope) {
            TdNotificationScope.PRIVATE_CHATS -> "Private Chats"
            TdNotificationScope.GROUPS -> "Groups"
            TdNotificationScope.CHANNELS -> "Channels"
        }
    }

    var selectedChat by remember { mutableStateOf<ChatModel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val filteredExceptions = remember(exceptions, searchQuery) {
        if (searchQuery.isEmpty()) {
            exceptions
        } else {
            exceptions?.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
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
                                    onQueryChange = { searchQuery = it },
                                    onSearch = {},
                                    expanded = false,
                                    onExpandedChange = {},
                                    placeholder = { Text("Search exceptions...") },
                                    leadingIcon = {
                                        IconButton(onClick = {
                                            isSearchActive = false
                                            searchQuery = ""
                                        }) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.ArrowBack,
                                                contentDescription = "Back"
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Clear")
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
                    TopAppBar(
                        title = {
                            Column {
                                Text(title,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    text = "Exceptions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = component::onBackClicked) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            if (!exceptions.isNullOrEmpty()) {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        when {
            exceptions == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    ContainedLoadingIndicator()
                }
            }

            exceptions.isEmpty() -> {
                EmptyExceptionsPlaceholder(Modifier.padding(padding))
            }

            filteredExceptions.isNullOrEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results found for \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        Text(
                            text = "CHATS",
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    itemsIndexed(filteredExceptions) { index, chat ->
                        val position = when {
                            filteredExceptions.size == 1 -> ItemPosition.STANDALONE
                            index == 0 -> ItemPosition.TOP
                            index == filteredExceptions.size - 1 -> ItemPosition.BOTTOM
                            else -> ItemPosition.MIDDLE
                        }

                        ExceptionItem(
                            chat = chat,
                            position = position,
                            onClick = { selectedChat = chat }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Tap on a chat to change its notification settings or remove the exception.",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    selectedChat?.let { chat ->
        ExceptionActionsSheet(
            chat = chat,
            onDismiss = { selectedChat = null },
            onToggleMute = {
                component.onChatExceptionToggled(chat.id, chat.isMuted)
                selectedChat = null
            },
            onRemoveException = {
                component.onChatExceptionReset(chat.id)
                selectedChat = null
            }
        )
    }
}

@Composable
private fun EmptyExceptionsPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "No Exceptions",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "You can add specific notification settings for individual chats. These settings will override the global defaults.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExceptionActionsSheet(
    chat: ChatModel,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onRemoveException: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Exception Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    path = chat.avatarPath,
                    fallbackPath = chat.personalAvatarPath,
                    name = chat.title,
                    size = 48.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (chat.isMuted) "Notifications are muted" else "Notifications are enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                ListItem(
                    headlineContent = {
                        Text(
                            if (chat.isMuted) "Unmute Chat" else "Mute Chat",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    leadingContent = {
                        Icon(
                            if (chat.isMuted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff,
                            contentDescription = null,
                            tint = if (chat.isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onToggleMute)
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                ListItem(
                    headlineContent = {
                        Text(
                            "Remove Exception",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = onRemoveException)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExceptionItem(
    chat: ChatModel,
    position: ItemPosition,
    onClick: () -> Unit
) {
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
                path = chat.avatarPath,
                fallbackPath = chat.personalAvatarPath,
                name = chat.title,
                size = 44.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (chat.isMuted) "Muted" else "Always On",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chat.isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                imageVector = if (chat.isMuted) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                contentDescription = null,
                tint = if (chat.isMuted) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary.copy(
                    alpha = 0.6f
                ),
                modifier = Modifier.size(20.dp)
            )
        }
    }
    Spacer(Modifier.size(2.dp))
}
