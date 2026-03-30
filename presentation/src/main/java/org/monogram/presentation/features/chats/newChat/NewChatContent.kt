package org.monogram.presentation.features.chats.newChat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Announcement
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserStatusType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.shimmerBackground
import org.monogram.presentation.core.util.FileUtils
import org.monogram.presentation.core.util.getUserStatusText
import org.monogram.presentation.features.chats.chatList.components.NewChannelContent
import org.monogram.presentation.features.chats.chatList.components.NewGroupContent
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatContent(component: NewChatComponent) {
    val state by component.state.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<UserModel?>(null) }
    var removingContact by remember { mutableStateOf<UserModel?>(null) }
    var editFirstName by remember { mutableStateOf("") }
    var editLastName by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val groupNameRequired = stringResource(R.string.group_name_required)
    val channelNameRequired = stringResource(R.string.channel_name_required)

    LaunchedEffect(state.validationError) {
        when (state.validationError) {
            NewChatComponent.ValidationError.GROUP_TITLE_REQUIRED -> {
                snackbarHostState.showSnackbar(groupNameRequired)
                component.onConsumeValidationError()
            }

            NewChatComponent.ValidationError.CHANNEL_TITLE_REQUIRED -> {
                snackbarHostState.showSnackbar(channelNameRequired)
                component.onConsumeValidationError()
            }

            null -> Unit
        }
    }

    LaunchedEffect(state.step) {
        if (state.step != NewChatComponent.Step.CONTACTS && state.step != NewChatComponent.Step.GROUP_MEMBERS) {
            isSearchActive = false
        }
    }

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
        modifier = Modifier.semantics { contentDescription = "NewChatContent" },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                        stringResource(R.string.selected_members_count, state.selectedUserIds.size),
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
                            if (state.step == NewChatComponent.Step.CONTACTS || state.step == NewChatComponent.Step.GROUP_MEMBERS) {
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
                    onClick = {
                        if (!state.isCreating) {
                            component.onConfirmCreate()
                        }
                    },
                    containerColor = if (state.title.isNotBlank() && !state.isCreating) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (state.title.isNotBlank() && !state.isCreating) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    if (state.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Icon(Icons.Rounded.Check, stringResource(R.string.confirm_button))
                    }
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
                        },
                        onOpenProfile = { user ->
                            component.onOpenProfile(user.id)
                        },
                        onEditContact = { user ->
                            editingContact = user
                            editFirstName = user.firstName
                            editLastName = user.lastName.orEmpty()
                        },
                        onRemoveContact = { user ->
                            removingContact = user
                        }
                    )
                }

                NewChatComponent.Step.GROUP_INFO -> {
                    NewGroupContent(
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

    editingContact?.let { contact ->
        ModalBottomSheet(
            onDismissRequest = { editingContact = null },
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.edit_contact_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsTextField(
                    value = editFirstName,
                    onValueChange = { editFirstName = it },
                    placeholder = stringResource(R.string.first_name_label),
                    icon = Icons.Rounded.Person,
                    position = ItemPosition.TOP,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                SettingsTextField(
                    value = editLastName,
                    onValueChange = { editLastName = it },
                    placeholder = stringResource(R.string.last_name_label),
                    icon = Icons.Rounded.Edit,
                    position = ItemPosition.BOTTOM,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { editingContact = null },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.cancel_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            component.onEditContact(contact.id, editFirstName, editLastName)
                            editingContact = null
                        },
                        enabled = editFirstName.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(stringResource(R.string.action_save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    removingContact?.let { contact ->
        val displayName = "${contact.firstName} ${contact.lastName.orEmpty()}".trim()
        ConfirmationSheet(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.remove_contact_title),
            description = stringResource(R.string.remove_contact_confirmation, displayName),
            confirmText = stringResource(R.string.action_remove_contact),
            onConfirm = {
                component.onRemoveContact(contact.id)
                removingContact = null
            },
            onDismiss = { removingContact = null }
        )
    }

}

@Composable
private fun ContactsList(
    state: NewChatComponent.State,
    isSearchActive: Boolean,
    videoPlayerPool: VideoPlayerPool,
    onActionClick: (String) -> Unit,
    onUserClick: (UserModel) -> Unit,
    onOpenProfile: (UserModel) -> Unit,
    onEditContact: (UserModel) -> Unit,
    onRemoveContact: (UserModel) -> Unit
) {
    val selectedUsers = remember(state.contacts, state.selectedUserIds) {
        state.contacts.filter { state.selectedUserIds.contains(it.id) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (state.step == NewChatComponent.Step.GROUP_MEMBERS && selectedUsers.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.selected_members_count, selectedUsers.size))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(selectedUsers, key = { it.id }) { user ->
                        FilterChip(
                            selected = true,
                            onClick = { onUserClick(user) },
                            label = {
                                Text(
                                    text = user.firstName,
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_clear),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }
        }

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

        if (displayList.isEmpty() && !state.isLoading) {
            item {
                val message = if (state.searchQuery.isNotEmpty()) {
                    stringResource(R.string.no_search_results_format, state.searchQuery)
                } else {
                    stringResource(R.string.no_contacts_found)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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
                enableLongPress = state.step == NewChatComponent.Step.CONTACTS,
                position = position,
                onClick = { onUserClick(user) },
                onOpenProfile = { onOpenProfile(user) },
                onEditContact = { onEditContact(user) },
                onRemoveContact = { onRemoveContact(user) },
                videoPlayerPool = videoPlayerPool
            )
        }

        if (state.isLoading) {
            items(6) { index ->
                val position = when {
                    index == 0 -> ItemPosition.TOP
                    index == 5 -> ItemPosition.BOTTOM
                    else -> ItemPosition.MIDDLE
                }
                ContactItemShimmer(
                    position = position,
                    showCheckbox = state.step == NewChatComponent.Step.GROUP_MEMBERS
                )
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
    enableLongPress: Boolean,
    position: ItemPosition,
    videoPlayerPool: VideoPlayerPool,
    onClick: () -> Unit,
    onOpenProfile: () -> Unit,
    onEditContact: () -> Unit,
    onRemoveContact: () -> Unit
) {
    val context = LocalContext.current
    val isSupport = user.isSupport
    var showMenu by remember { mutableStateOf(false) }
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

    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (enableLongPress) {
                            showMenu = true
                        }
                    }
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    path = user.avatarPath,
                    fallbackPath = user.personalAvatarPath,
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

        ContactActionsPopup(
            expanded = showMenu && enableLongPress,
            onDismiss = { showMenu = false },
            onOpenProfile = {
                showMenu = false
                onOpenProfile()
            },
            onEditContact = {
                showMenu = false
                onEditContact()
            },
            onRemoveContact = {
                showMenu = false
                onRemoveContact()
            }
        )
    }
    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ContactActionsPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenProfile: () -> Unit,
    onEditContact: () -> Unit,
    onRemoveContact: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(x = 0.dp, y = 8.dp),
        shape = RoundedCornerShape(22.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Surface(
            modifier = Modifier.widthIn(min = 220.dp, max = 260.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_menu_open_profile)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = onOpenProfile
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_menu_edit_name)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = onEditContact
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.contact_menu_remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = onRemoveContact
                )
            }
        }
    }
}

@Composable
private fun ContactItemShimmer(
    position: ItemPosition,
    showCheckbox: Boolean
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
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shimmerBackground(CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(16.dp)
                        .shimmerBackground(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(12.dp)
                        .shimmerBackground(RoundedCornerShape(6.dp))
                )
            }

            if (showCheckbox) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .shimmerBackground(CircleShape)
                )
            }
        }
    }

    if (position != ItemPosition.BOTTOM && position != ItemPosition.STANDALONE) {
        Spacer(Modifier.height(2.dp))
    }
}
