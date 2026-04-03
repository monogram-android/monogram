@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.presentation.R
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
                        text = stringResource(R.string.admin_rights),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = component::onSave,
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.save),
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
                ContainedLoadingIndicator()
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
                                Avatar(
                                    path = user.avatarPath,
                                    fallbackPath = user.personalAvatarPath,
                                    name = user.firstName,
                                    size = 64.dp
                                )
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
                                        contentDescription = stringResource(R.string.edit),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.custom_title))
                    SettingsTextField(
                        value = status?.customTitle ?: "",
                        onValueChange = component::onUpdateCustomTitle,
                        placeholder = stringResource(R.string.custom_title),
                        icon = Icons.Rounded.Badge,
                        position = ItemPosition.STANDALONE,
                        singleLine = true
                    )

                    Text(
                        text = stringResource(R.string.custom_title_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp)
                    )
                }

                item {
                    SectionHeader(stringResource(R.string.what_can_this_admin_do))
                    PermissionSwitch(
                        title = stringResource(R.string.permission_manage_chat),
                        icon = Icons.Rounded.Settings,
                        checked = status?.canManageChat == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.MANAGE_CHAT) },
                        position = ItemPosition.TOP
                    )
                    PermissionSwitch(
                        title = stringResource(R.string.permission_change_chat_info),
                        icon = Icons.Rounded.Info,
                        checked = status?.canChangeInfo == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.CHANGE_INFO) },
                        position = ItemPosition.MIDDLE
                    )
                    if (state.isChannel) {
                        PermissionSwitch(
                            title = stringResource(R.string.permission_post_messages),
                            icon = Icons.Rounded.Edit,
                            checked = status?.canPostMessages == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.POST_MESSAGES) },
                            position = ItemPosition.MIDDLE
                        )
                        PermissionSwitch(
                            title = stringResource(R.string.permission_edit_messages),
                            icon = Icons.Rounded.EditNote,
                            checked = status?.canEditMessages == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.EDIT_MESSAGES) },
                            position = ItemPosition.MIDDLE
                        )
                    }
                    PermissionSwitch(
                        title = stringResource(R.string.permission_delete_messages),
                        icon = Icons.Rounded.Delete,
                        checked = status?.canDeleteMessages == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.DELETE_MESSAGES) },
                        position = ItemPosition.MIDDLE
                    )
                    if (!state.isChannel) {
                        PermissionSwitch(
                            title = stringResource(R.string.permission_restrict_members),
                            icon = Icons.Rounded.Block,
                            checked = status?.canRestrictMembers == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.RESTRICT_MEMBERS) },
                            position = ItemPosition.MIDDLE
                        )
                    }
                    PermissionSwitch(
                        title = stringResource(R.string.permission_invite_users),
                        icon = Icons.Rounded.PersonAdd,
                        checked = status?.canInviteUsers == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.INVITE_USERS) },
                        position = ItemPosition.MIDDLE
                    )
                    if (!state.isChannel) {
                        PermissionSwitch(
                            title = stringResource(R.string.permission_pin_messages),
                            icon = Icons.Rounded.PushPin,
                            checked = status?.canPinMessages == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.PIN_MESSAGES) },
                            position = ItemPosition.MIDDLE
                        )
                        PermissionSwitch(
                            title = stringResource(R.string.permission_manage_topics),
                            icon = Icons.Rounded.Topic,
                            checked = status?.canManageTopics == true,
                            onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.MANAGE_TOPICS) },
                            position = ItemPosition.MIDDLE
                        )
                    }
                    PermissionSwitch(
                        title = stringResource(R.string.permission_manage_video_chats),
                        icon = Icons.Rounded.Videocam,
                        checked = status?.canManageVideoChats == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.MANAGE_VIDEO_CHATS) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = stringResource(R.string.permission_post_stories),
                        icon = Icons.Rounded.AddPhotoAlternate,
                        checked = status?.canPostStories == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.POST_STORIES) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = stringResource(R.string.permission_edit_stories),
                        icon = Icons.Rounded.AutoFixHigh,
                        checked = status?.canEditStories == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.EDIT_STORIES) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = stringResource(R.string.permission_delete_stories),
                        icon = Icons.Rounded.DeleteForever,
                        checked = status?.canDeleteStories == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.DELETE_STORIES) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = stringResource(R.string.permission_add_new_admins),
                        icon = Icons.Rounded.AddModerator,
                        checked = status?.canPromoteMembers == true,
                        onCheckedChange = { component.onTogglePermission(AdminManageComponent.Permission.PROMOTE_MEMBERS) },
                        position = ItemPosition.MIDDLE
                    )
                    PermissionSwitch(
                        title = stringResource(R.string.permission_remain_anonymous),
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
