package org.monogram.presentation.features.profile.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Topic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ConfirmationSheet
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTextField
import org.monogram.presentation.core.ui.SettingsTile
import org.monogram.presentation.core.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatEditContent(component: ChatEditComponent) {
    val state by component.state.subscribeAsState()
    val chat = state.chat
    val isChannel = chat?.isChannel == true
    val isGroup = chat?.isGroup == true
    val showJoinToSendMessages = isGroup && state.isPublic
    val showTopics = isGroup && state.linkedChatId == 0L
    val context = LocalContext.current
    var showDeleteChatSheet by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let {
                val path = FileUtils.getPath(context, it)
                component.onChangeAvatar(path.toString())
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = component::onSave, enabled = state.editor.canSave) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading || chat == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .clickable {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Avatar(path = state.avatarPath, name = state.title, size = 104.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.CameraAlt,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.description.ifEmpty {
                                state.chat?.username?.let { "@$it" } ?: ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            }

            item {
                SettingsTextField(
                    value = state.title,
                    onValueChange = component::onUpdateTitle,
                    placeholder = if (isChannel) stringResource(R.string.channel_name) else stringResource(R.string.group_name),
                    icon = Icons.Rounded.Title,
                    position = ItemPosition.TOP,
                    minLines = 1
                )
                SettingsTextField(
                    value = state.description,
                    onValueChange = component::onUpdateDescription,
                    placeholder = stringResource(R.string.description),
                    icon = Icons.Rounded.Description,
                    position = ItemPosition.BOTTOM,
                    singleLine = false,
                    minLines = 2
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings))
                SettingsSwitchTile(
                    title = if (isChannel) stringResource(R.string.public_channel) else stringResource(R.string.public_group),
                    icon = Icons.Rounded.Public,
                    checked = state.isPublic,
                    onCheckedChange = component::onTogglePublic,
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = ItemPosition.TOP
                )
                if (state.isPublic) {
                    SettingsTextField(
                        value = state.username,
                        onValueChange = component::onUpdateUsername,
                        placeholder = stringResource(R.string.username),
                        icon = Icons.Rounded.AlternateEmail,
                        position = ItemPosition.BOTTOM
                    )
                }
            }

            if (isChannel) {
                item {
                    SectionHeader(stringResource(R.string.channels_title))
                    SettingsSwitchTile(
                        title = stringResource(R.string.protected_content_title),
                        subtitle = stringResource(R.string.protected_content_subtitle),
                        icon = Icons.Rounded.Lock,
                        checked = state.hasProtectedContent,
                        onCheckedChange = component::onToggleProtectedContent,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.TOP
                    )
                    SettingsSwitchTile(
                        title = stringResource(R.string.permission_sign_messages),
                        subtitle = stringResource(R.string.permission_sign_messages_subtitle),
                        icon = Icons.Rounded.Draw,
                        checked = state.signMessages,
                        onCheckedChange = component::onToggleSignMessages,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.BOTTOM
                    )
                }
            }

            if (isGroup) {
                item {
                    SectionHeader(stringResource(R.string.groups_title))
                    SettingsSwitchTile(
                        title = stringResource(R.string.protected_content_title),
                        subtitle = stringResource(R.string.protected_content_subtitle),
                        icon = Icons.Rounded.Lock,
                        checked = state.hasProtectedContent,
                        onCheckedChange = component::onToggleProtectedContent,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = when {
                            showJoinToSendMessages || showTopics -> ItemPosition.TOP
                            else -> ItemPosition.STANDALONE
                        }
                    )
                    if (showJoinToSendMessages) {
                        SettingsSwitchTile(
                            title = stringResource(R.string.permission_join_to_send_messages),
                            subtitle = stringResource(R.string.permission_join_to_send_messages_subtitle),
                            icon = Icons.Rounded.Login,
                            checked = state.joinToSendMessages,
                            onCheckedChange = component::onToggleJoinToSendMessages,
                            iconColor = MaterialTheme.colorScheme.primary,
                            position = if (showTopics) ItemPosition.MIDDLE else ItemPosition.BOTTOM
                        )
                    }
                    if (showTopics) {
                        SettingsSwitchTile(
                            title = stringResource(R.string.topics),
                            subtitle = stringResource(R.string.topics_subtitle),
                            icon = Icons.Rounded.Topic,
                            checked = state.isForum,
                            onCheckedChange = component::onToggleTopics,
                            iconColor = MaterialTheme.colorScheme.primary,
                            position = ItemPosition.BOTTOM
                        )
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.management))
                SettingsTile(
                    title = stringResource(R.string.administrators),
                    icon = Icons.Rounded.AdminPanelSettings,
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = ItemPosition.TOP,
                    onClick = component::onManageAdmins
                )
                SettingsTile(
                    title = if (isChannel) stringResource(R.string.subscribers) else stringResource(R.string.members),
                    icon = Icons.Rounded.Groups,
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onManageMembers
                )
                SettingsTile(
                    title = stringResource(R.string.blacklist),
                    icon = Icons.Rounded.Block,
                    iconColor = MaterialTheme.colorScheme.error,
                    position = ItemPosition.BOTTOM,
                    onClick = component::onManageBlacklist
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showDeleteChatSheet = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isChannel) stringResource(R.string.delete_channel) else stringResource(R.string.delete_group),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showDeleteChatSheet) {
        ConfirmationSheet(
            icon = Icons.Rounded.Delete,
            title = if (isChannel) stringResource(R.string.delete_channel_title) else stringResource(R.string.delete_group_title),
            description = if (isChannel) stringResource(R.string.delete_channel_confirmation) else stringResource(R.string.delete_group_confirmation),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                component.onDeleteChat()
                showDeleteChatSheet = false
            },
            onDismiss = { showDeleteChatSheet = false }
        )
    }

    if (state.editor.showDiscardChangesDialog) {
        ConfirmationSheet(
            icon = Icons.Rounded.Delete,
            title = stringResource(R.string.photo_editor_discard_title),
            description = stringResource(R.string.photo_editor_discard_message),
            confirmText = stringResource(R.string.photo_editor_discard_button),
            onConfirm = component::onConfirmDiscardChanges,
            onDismiss = component::onDismissDiscardChanges
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
