package org.monogram.presentation.features.chats.chatList

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Announcement
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserStatusType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.FileUtils
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.chatList.components.NewChannelContent
import org.monogram.presentation.features.chats.chatList.components.NewGroupContent
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.newChat.NewChatComponent
import org.monogram.presentation.core.ui.ItemPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatContent(component: NewChatComponent) {
    val state by component.state.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val path = FileUtils.getPath(context, uri)
            component.onPhotoSelected(path)
        }
    }

    BackHandler(enabled = state.step != NewChatComponent.Step.CONTACTS) {
        component.onStepBack()
    }

    Scaffold(
        topBar = {
            AnimatedContent(
                targetState = isSearchActive,
                label = "TopBarSearchTransition"
            ) { searching ->
                if (searching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SearchBar(
                            inputField = {
                                SearchBarDefaults.InputField(
                                    query = state.searchQuery,
                                    onQueryChange = component::onSearchQueryChange,
                                    onSearch = {},
                                    expanded = false,
                                    onExpandedChange = {},
                                    placeholder = { Text(stringResource(R.string.search_contacts_hint)) },
                                    leadingIcon = {
                                        IconButton(onClick = {
                                            isSearchActive = false
                                            component.onSearchQueryChange("")
                                        }) {
                                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                                        }
                                    },
                                    trailingIcon = {
                                        if (state.searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { component.onSearchQueryChange("") }) {
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
                    TopAppBar(
                        title = {
                            Column {
                                val title = when (state.step) {
                                    NewChatComponent.Step.CONTACTS -> stringResource(R.string.new_message_title)
                                    NewChatComponent.Step.GROUP_MEMBERS -> stringResource(R.string.add_members_title)
                                    NewChatComponent.Step.GROUP_INFO -> stringResource(R.string.new_group_title)
                                    NewChatComponent.Step.CHANNEL_INFO -> stringResource(R.string.new_channel_title)
                                }
                                Text(
                                    text = title,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (state.step == NewChatComponent.Step.CONTACTS && state.contacts.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.contacts_count_format, state.contacts.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (state.step == NewChatComponent.Step.GROUP_MEMBERS) {
                                    Text(
                                        stringResource(R.string.members_limit_format, state.selectedUserIds.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                if (state.step == NewChatComponent.Step.CONTACTS) component.onBack()
                                else component.onStepBack()
                            }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                            }
                        },
                        actions = {
                            if (state.step == NewChatComponent.Step.CONTACTS) {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Rounded.Search, stringResource(R.string.action_search))
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.step == NewChatComponent.Step.GROUP_MEMBERS && state.selectedUserIds.isNotEmpty()) {
                FloatingActionButton(onClick = { component.onConfirmCreate() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.continue_button))
                }
            } else if (state.step == NewChatComponent.Step.GROUP_INFO || state.step == NewChatComponent.Step.CHANNEL_INFO) {
                FloatingActionButton(
                    onClick = { component.onConfirmCreate() },
                    containerColor = if (state.title.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(Icons.Rounded.Check, stringResource(R.string.confirm_button))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (state.step) {
                NewChatComponent.Step.CONTACTS, NewChatComponent.Step.GROUP_MEMBERS -> {
                    ContactsList(
                        state = state,
                        isSearchActive = isSearchActive,
                        onActionClick = { action ->
                            when (action) {
                                "group" -> component.onCreateGroup()
                                "channel" -> component.onCreateChannel()
                            }
                        },
                        videoPlayerPool = component.videoPlayerPool,
                        onUserClick = { user ->
                            if (state.step == NewChatComponent.Step.GROUP_MEMBERS) {
                                component.onToggleUserSelection(user.id)
                            } else {
                                component.onUserClicked(user.id)
                            }
                        }
                    )
                }

                NewChatComponent.Step.GROUP_INFO -> {
                    NewGroupContent(
                        title = state.title,
                        photoPath = state.photoPath,
                        autoDeleteTime = state.autoDeleteTime,
                        onTitleChange = component::onTitleChange,
                        onPhotoClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onAutoDeleteTimeChange = component::onAutoDeleteTimeChange,
                        videoPlayerPool = component.videoPlayerPool
                    )
                }

                NewChatComponent.Step.CHANNEL_INFO -> {
                    NewChannelContent(
                        title = state.title,
                        description = state.description,
                        photoPath = state.photoPath,
                        autoDeleteTime = state.autoDeleteTime,
                        onTitleChange = component::onTitleChange,
                        onDescriptionChange = component::onDescriptionChange,
                        onPhotoClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onAutoDeleteTimeChange = component::onAutoDeleteTimeChange,
                        videoPlayerPool = component.videoPlayerPool
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactsList(
    state: NewChatComponent.State,
    isSearchActive: Boolean,
    videoPlayerPool: VideoPlayerPool,
    onActionClick: (String) -> Unit,
    onUserClick: (UserModel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (!isSearchActive && state.step == NewChatComponent.Step.CONTACTS) {
            item {
                NewChatActionItem(
                    icon = Icons.Rounded.Group,
                    title = stringResource(R.string.new_group_action),
                    position = ItemPosition.TOP,
                    onClick = { onActionClick("group") }
                )
                NewChatActionItem(
                    icon = Icons.AutoMirrored.Rounded.Announcement,
                    title = stringResource(R.string.new_channel_action),
                    position = ItemPosition.BOTTOM,
                    onClick = { onActionClick("channel") }
                )
            }

            item {
                SectionHeader(stringResource(R.string.sorted_by_last_seen))
            }
        }

        val displayList = if (state.searchQuery.isNotEmpty()) state.searchResults else state.contacts

        itemsIndexed(displayList, key = { _, user -> user.id }) { index, user ->
            val position = when {
                displayList.size == 1 -> ItemPosition.STANDALONE
                index == 0 -> ItemPosition.TOP
                index == displayList.size - 1 -> ItemPosition.BOTTOM
                else -> ItemPosition.MIDDLE
            }
            ContactItem(
                user = user,
                isSelected = state.selectedUserIds.contains(user.id),
                showCheckbox = state.step == NewChatComponent.Step.GROUP_MEMBERS,
                position = position,
                onClick = { onUserClick(user) },
                videoPlayerPool = videoPlayerPool
            )
        }

        if (state.isLoading) {
            item {
                Box(Modifier
                    .fillMaxWidth()
                    .padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun NewChatActionItem(
    icon: ImageVector,
    title: String,
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
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ContactItem(
    user: UserModel,
    isSelected: Boolean,
    showCheckbox: Boolean,
    position: ItemPosition,
    videoPlayerPool: VideoPlayerPool,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isSupport = user.isSupport
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                path = user.personalAvatarPath ?: user.avatarPath,
                name = user.firstName,
                size = 40.dp,
                isOnline = user.userStatus == UserStatusType.ONLINE && !isSupport,
                videoPlayerPool = videoPlayerPool
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${user.firstName} ${user.lastName ?: ""}".trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (isSupport) {
                    Text(
                        text = stringResource(R.string.support_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    val statusText = getUserStatusText(user, context)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (user.userStatus == UserStatusType.ONLINE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showCheckbox) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            }
        }
    }
    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}
