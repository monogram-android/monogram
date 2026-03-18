package org.monogram.presentation.features.profile.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.monogram.presentation.core.util.FileUtils
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsSwitchTile
import org.monogram.presentation.core.ui.SettingsTile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatEditContent(component: ChatEditComponent) {
    val state by component.state.subscribeAsState()
    val isChannel = state.chat?.isChannel == true
    val context = LocalContext.current

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
                        text = if (isChannel) stringResource(R.string.edit_channel) else stringResource(R.string.edit_group),
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
                    IconButton(onClick = component::onSave, enabled = !state.isLoading) {
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
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .clickable {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Avatar(path = state.avatarPath, name = state.title, size = 100.dp, videoPlayerPool = component.videoPlayerPool)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.CameraAlt,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }

            item {
                SettingsTextField(
                    value = state.title,
                    onValueChange = component::onUpdateTitle,
                    placeholder = if (isChannel) stringResource(R.string.channel_name) else stringResource(R.string.group_name),
                    icon = Icons.Rounded.Title,
                    position = ItemPosition.TOP
                )
                SettingsTextField(
                    value = state.description,
                    onValueChange = component::onUpdateDescription,
                    placeholder = stringResource(R.string.description),
                    icon = Icons.Rounded.Description,
                    position = ItemPosition.BOTTOM,
                    singleLine = false
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
                        position = ItemPosition.MIDDLE
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "https://t.me/${state.username.ifEmpty { "username" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }

                if (isChannel) {
                    SettingsSwitchTile(
                        title = stringResource(R.string.auto_translate),
                        icon = Icons.Rounded.Translate,
                        checked = state.isTranslatable,
                        onCheckedChange = component::onToggleAutoTranslate,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.BOTTOM
                    )
                } else {
                    SettingsSwitchTile(
                        title = stringResource(R.string.topics),
                        icon = Icons.Rounded.Topic,
                        checked = state.isForum,
                        onCheckedChange = component::onToggleTopics,
                        iconColor = MaterialTheme.colorScheme.primary,
                        position = ItemPosition.BOTTOM
                    )
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
                    title = stringResource(R.string.permissions),
                    icon = Icons.Rounded.Lock,
                    iconColor = MaterialTheme.colorScheme.primary,
                    position = ItemPosition.MIDDLE,
                    onClick = component::onManagePermissions
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
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = component::onDeleteChat,
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
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}
