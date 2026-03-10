package org.monogram.presentation.profile.admin

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.settingsScreens.settings.components.ItemPosition
import org.monogram.presentation.settingsScreens.settings.components.SettingsSwitchTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPermissionsContent(component: ChatPermissionsComponent) {
    val state by component.state.subscribeAsState()
    val permissions = state.permissions
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Permissions",
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
                    IconButton(onClick = component::onSave) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Save",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            item {
                SectionHeader("What can members of this group do?")
            }
            item {
                PermissionItem(
                    "Send Messages",
                    Icons.AutoMirrored.Rounded.Chat,
                    permissions.canSendBasicMessages,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_MESSAGES) },
                    ItemPosition.TOP
                )
            }
            item {
                PermissionItem(
                    "Send Media",
                    Icons.Rounded.Image,
                    permissions.canSendPhotos,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_MEDIA) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    "Send Stickers & GIFs",
                    Icons.Rounded.EmojiEmotions,
                    permissions.canSendOtherMessages,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_STICKERS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    "Send Polls",
                    Icons.Rounded.Poll,
                    permissions.canSendPolls,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_POLLS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    "Embed Links",
                    Icons.Rounded.Link,
                    permissions.canAddLinkPreviews,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.EMBED_LINKS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    "Add Members",
                    Icons.Rounded.PersonAdd,
                    permissions.canInviteUsers,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.ADD_MEMBERS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    "Pin Messages",
                    Icons.Rounded.PushPin,
                    permissions.canPinMessages,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.PIN_MESSAGES) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    "Change Chat Info",
                    Icons.Rounded.Info,
                    permissions.canChangeInfo,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.CHANGE_INFO) },
                    ItemPosition.MIDDLE
                )
            }
//            item {
//                PermissionItem(
//                    "Manage Topics",
//                    Icons.Rounded.Topic,
//                    permissions.canManageTopics,
//                    { component.onTogglePermission(ChatPermissionsComponent.Permission.MANAGE_TOPICS) },
//                    ItemPosition.BOTTOM
//                )
//            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PermissionItem(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    position: ItemPosition
) {
    SettingsSwitchTile(
        title = title,
        icon = icon,
        checked = checked,
        onCheckedChange = onCheckedChange,
        iconColor = MaterialTheme.colorScheme.primary,
        position = position
    )
}
