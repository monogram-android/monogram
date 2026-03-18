package org.monogram.presentation.features.profile.admin

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile

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
                        text = stringResource(R.string.permissions),
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
                    IconButton(onClick = component::onSave) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.save),
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
                SectionHeader(stringResource(R.string.what_can_members_do))
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_send_messages),
                    Icons.AutoMirrored.Rounded.Chat,
                    permissions.canSendBasicMessages,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_MESSAGES) },
                    ItemPosition.TOP
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_send_media),
                    Icons.Rounded.Image,
                    permissions.canSendPhotos,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_MEDIA) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_send_stickers_gifs),
                    Icons.Rounded.EmojiEmotions,
                    permissions.canSendOtherMessages,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_STICKERS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_send_polls),
                    Icons.Rounded.Poll,
                    permissions.canSendPolls,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.SEND_POLLS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_embed_links),
                    Icons.Rounded.Link,
                    permissions.canAddLinkPreviews,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.EMBED_LINKS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_add_members),
                    Icons.Rounded.PersonAdd,
                    permissions.canInviteUsers,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.ADD_MEMBERS) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_pin_messages),
                    Icons.Rounded.PushPin,
                    permissions.canPinMessages,
                    { component.onTogglePermission(ChatPermissionsComponent.Permission.PIN_MESSAGES) },
                    ItemPosition.MIDDLE
                )
            }
            item {
                PermissionItem(
                    stringResource(R.string.permission_change_chat_info),
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
