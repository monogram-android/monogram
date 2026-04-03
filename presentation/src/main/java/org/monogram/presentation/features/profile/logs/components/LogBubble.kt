package org.monogram.presentation.features.profile.logs.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.ChatEventActionModel
import org.monogram.domain.models.ChatEventModel
import org.monogram.domain.models.MessageSenderModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.features.profile.logs.ProfileLogsComponent
import java.text.SimpleDateFormat
import java.util.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LogBubble(
    event: ChatEventModel,
    senderInfo: ProfileLogsComponent.SenderInfo?,
    allSenderInfo: Map<Long, ProfileLogsComponent.SenderInfo>,
    component: ProfileLogsComponent,
    modifier: Modifier = Modifier
) {
    val isRestriction = event.action is ChatEventActionModel.MemberRestricted
    LocalClipboardManager.current
    LocalContext.current

    val actionText = when (val action = event.action) {
        is ChatEventActionModel.MessageEdited -> stringResource(R.string.logs_action_edited)
        is ChatEventActionModel.MessageDeleted -> stringResource(R.string.logs_action_deleted)
        is ChatEventActionModel.MessagePinned -> stringResource(R.string.logs_action_pinned)
        is ChatEventActionModel.MessageUnpinned -> stringResource(R.string.logs_action_unpinned)
        is ChatEventActionModel.MemberJoined -> stringResource(R.string.logs_action_joined)
        is ChatEventActionModel.MemberLeft -> stringResource(R.string.logs_action_left)
        is ChatEventActionModel.MemberInvited -> {
            val targetName = allSenderInfo[action.userId]?.name ?: "User ${action.userId}"
            stringResource(R.string.logs_action_invited, targetName)
        }

        is ChatEventActionModel.MemberPromoted -> {
            val targetName = allSenderInfo[action.userId]?.name ?: "User ${action.userId}"
            stringResource(R.string.logs_action_permissions_changed, targetName)
        }

        is ChatEventActionModel.MemberRestricted -> {
            val targetName = allSenderInfo[action.userId]?.name ?: "User ${action.userId}"
            stringResource(R.string.logs_action_restrictions_changed, targetName)
        }

        is ChatEventActionModel.TitleChanged -> stringResource(R.string.logs_action_title_changed, action.newTitle)
        is ChatEventActionModel.DescriptionChanged -> stringResource(R.string.logs_action_description_changed)
        is ChatEventActionModel.UsernameChanged -> stringResource(R.string.logs_action_username_changed, action.newUsername)
        is ChatEventActionModel.PhotoChanged -> stringResource(R.string.logs_action_photo_changed)
        is ChatEventActionModel.InviteLinkEdited -> stringResource(R.string.logs_action_link_edited)
        is ChatEventActionModel.InviteLinkRevoked -> stringResource(R.string.logs_action_link_revoked)
        is ChatEventActionModel.InviteLinkDeleted -> stringResource(R.string.logs_action_link_deleted)
        is ChatEventActionModel.VideoChatCreated -> stringResource(R.string.logs_action_video_started)
        is ChatEventActionModel.VideoChatEnded -> stringResource(R.string.logs_action_video_ended)
        is ChatEventActionModel.Unknown -> stringResource(R.string.logs_action_unknown, action.type)
    }

    val senderName = senderInfo?.name ?: when (val sender = event.memberId) {
        is MessageSenderModel.User -> "User ${sender.userId}"
        is MessageSenderModel.Chat -> "Chat ${sender.chatId}"
    }

    when (val s = event.memberId) {
        is MessageSenderModel.User -> s.userId
        is MessageSenderModel.Chat -> s.chatId
    }

    var showFullDate by remember { mutableStateOf(false) }
    val date = Date(event.date.toLong() * 1000)
    val dateText = if (showFullDate) {
        SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Avatar(
            path = senderInfo?.avatarPath,
            name = senderName,
            size = 40.dp,
            fontSize = 16,
            onClick = {
                if (event.memberId is MessageSenderModel.User) {
                    component.onUserClick((event.memberId as MessageSenderModel.User).userId)
                }
            }
        )

        Spacer(modifier = Modifier.width(8.dp))

        val bubbleShape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .background(
                    color = if (isRestriction) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = bubbleShape
                )
                .then(
                    if (isRestriction) Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                        shape = bubbleShape
                    ) else Modifier
                )
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isRestriction) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        if (event.memberId is MessageSenderModel.User) {
                            component.onUserClick((event.memberId as MessageSenderModel.User).userId)
                        }
                    }
                )
                if (isRestriction) {
                    Icon(
                        imageVector = Icons.Rounded.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            SelectionContainer {
                Column {
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isRestriction) FontWeight.Medium else FontWeight.Normal
                    )

                    ActionDetails(event.action, allSenderInfo, component)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable { showFullDate = !showFullDate }
            )
        }
    }
}
