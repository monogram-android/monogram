package org.monogram.presentation.features.profile.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminManageContent(component: AdminManageComponent) {
    val state by component.state.subscribeAsState()
    val status = state.currentStatus as? ChatMemberStatus.Administrator

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Admin Rights",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = component::onSave,
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Save",
                            tint = if (state.isLoading) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
            ) {
                item {
                    state.member?.user?.let { user ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Avatar(path = user.avatarPath, name = user.firstName, size = 64.dp, videoPlayerPool = component.videoPlayerPool)
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = listOfNotNull(user.firstName, user.lastName).joinToString(" "),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    state.member?.rank?.let { rank ->
                                        if (rank.isNotEmpty()) {
                                            Text(
                                                text = rank,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                                IconButton(onClick = { /* TODO: Edit rank or user */ }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SectionHeader("Custom Title")
                    SettingsTextField(
                        value = status?.customTitle ?: "",
                        onValueChange = component::onUpdateCustomTitle,
                        placeholder = "Custom Title",
                        icon = Icons.Rounded.Badge,
                        position = ItemPosition.STANDALONE,
                        singleLine = true
                    )

                    Text(
                        text = "This title will be visible to all members in the chat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp)
                    )
                }

                item {
                    SectionHeader("What can this admin do?")
                    PermissionSwitch(
                        title = "Manage Chat",
                        icon = Icons.Rounded.Settings,
                        checked = status?.canManageChat == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.MANAGE_CHAT) },
                        position = ItemPosition.TOP
                    )
                    PermissionSwitch(
                        title = "Change Chat Info",
                        icon = Icons.Rounded.Info,
                        checked = status?.canChangeInfo == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.CHANGE_INFO) },
                        position = ItemPosition.MIDDLE
                    )
                    if (state.isChannel) {
                        PermissionSwitch(
                            title = "Post Messages",
                            icon = Icons.Rounded.Edit,
                            checked = status?.canPostMessages == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.POST_MESSAGES) },
                            position = ItemPosition.MIDDLE
                        )
                        PermissionSwitch(
                            title = "Edit Messages",
                            icon = Icons.Rounded.EditNote,
                            checked = status?.canEditMessages == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.EDIT_MESSAGES) },
                            position = ItemPosition.MIDDLE
                        )
                    }
                    PermissionSwitch(
                        title = "Delete Messages",
                        icon = Icons.Rounded.Delete,
                        checked = status?.canDeleteMessages == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.DELETE_MESSAGES) },
                        position = ItemPosition.MIDDLE
                    )
                    if (!state.isChannel) {
                        PermissionSwitch(
                            title = "Restrict Members",
                            icon = Icons.Rounded.Block,
                            checked = status?.canRestrictMembers == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.RESTRICT_MEMBERS) },
                            position = ItemPosition.MIDDLE
                        )
                    }
                    PermissionSwitch(
                        title = "Invite Users",
                        icon = Icons.Rounded.PersonAdd,
                        checked = status?.canInviteUsers == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.INVITE_USERS) },
                        position = ItemPosition.MIDDLE
                    )
                    if (!state.isChannel) {
                        PermissionSwitch(
                            title = "Pin Messages",
                            icon = Icons.Rounded.PushPin,
                            checked = status?.canPinMessages == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.PIN_MESSAGES) },
                            position = ItemPosition.MIDDLE
                        )
                        PermissionSwitch(
                            title = "Manage Topics",
                            icon = Icons.Rounded.Topic,
                            checked = status?.canManageTopics == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.MANAGE_TOPICS) },
                            position = ItemPosition.MIDDLE
                        )
                    }
                    PermissionSwitch(
                        title = "Manage Video Chats",
                        icon = Icons.Rounded.Videocam,
                        checked = status?.canManageVideoChats == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.MANAGE_VIDEO_CHATS) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = "Post Stories",
                        icon = Icons.Rounded.AddPhotoAlternate,
                        checked = status?.canPostStories == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.POST_STORIES) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = "Edit Stories",
                        icon = Icons.Rounded.AutoFixHigh,
                        checked = status?.canEditStories == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.EDIT_STORIES) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = "Delete Stories",
                        icon = Icons.Rounded.DeleteForever,
                        checked = status?.canDeleteStories == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.DELETE_STORIES) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = "Add New Admins",
                        icon = Icons.Rounded.AddModerator,
                        checked = status?.canPromoteMembers == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.PROMOTE_MEMBERS) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = "Remain Anonymous",
                        icon = Icons.Rounded.VisibilityOff,
                        checked = status?.isAnonymous == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.ANONYMOUS) },
                        position = ItemPosition.BOTTOM
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp, top = 16.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PermissionSwitch(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: ItemPosition
) {
    SettingsSwitchTile(
        title = title,
        icon = icon,
        iconColor = MaterialTheme.colorScheme.primary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        position = position
    )
}
