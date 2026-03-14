package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.ui.SettingsItem

@Composable
fun NewChannelContent(
    title: String,
    description: String,
    photoPath: String?,
    autoDeleteTime: Int,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPhotoClick: () -> Unit,
    onAutoDeleteTimeChange: (Int) -> Unit,
    videoPlayerPool: VideoPlayerPool,
    modifier: Modifier = Modifier
) {
    var showAutoDeleteSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ChannelPhotoSelector(
                photoPath = photoPath,
                title = title,
                onClick = onPhotoClick,
                videoPlayerPool = videoPlayerPool
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.channel_description_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }

        SectionHeader(stringResource(R.string.channel_details_header))

        SettingsTextField(
            value = title,
            onValueChange = onTitleChange,
            placeholder = stringResource(R.string.channel_name_placeholder),
            icon = Icons.Rounded.Campaign,
            position = ItemPosition.TOP,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            ),
            trailingIcon = {
                if (title.isNotEmpty()) {
                    IconButton(onClick = { onTitleChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            }
        )

        SettingsTextField(
            value = description,
            onValueChange = onDescriptionChange,
            placeholder = stringResource(R.string.description_optional_placeholder),
            icon = Icons.Rounded.Description,
            position = ItemPosition.BOTTOM,
            minLines = 3,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            )
        )

        SectionHeader(stringResource(R.string.settings_section_header))

        SettingsItem(
            icon = Icons.Rounded.Timer,
            iconBackgroundColor = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.auto_delete_messages_title),
            subtitle = autoDeleteTime.toAutoDeleteString(),
            position = ItemPosition.STANDALONE,
            onClick = { showAutoDeleteSheet = true }
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.channel_join_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    if (showAutoDeleteSheet) {
        AutoDeleteSelectorSheet(
            selectedTime = autoDeleteTime,
            onTimeSelected = {
                onAutoDeleteTimeChange(it)
                showAutoDeleteSheet = false
            },
            onDismissRequest = { showAutoDeleteSheet = false }
        )
    }
}

@Composable
private fun ChannelPhotoSelector(
    photoPath: String?,
    videoPlayerPool: VideoPlayerPool,
    title: String,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (photoPath != null) {
                Avatar(
                    path = photoPath,
                    name = title,
                    size = 100.dp,
                    videoPlayerPool = videoPlayerPool
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.AddAPhoto,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (photoPath == null) Icons.Rounded.Add else Icons.Rounded.CameraAlt,
                contentDescription = if (photoPath == null) stringResource(R.string.add_photo_cd) else stringResource(R.string.change_photo_cd),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
