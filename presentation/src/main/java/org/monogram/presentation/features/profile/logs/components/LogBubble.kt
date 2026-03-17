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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.ChatEventActionModel
import org.monogram.domain.models.ChatEventModel
import org.monogram.domain.models.MessageSenderModel
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
        is ChatEventActionModel.MessageEdited -> "edited a message"
        is ChatEventActionModel.MessageDeleted -> "deleted a message"
        is ChatEventActionModel.MessagePinned -> "pinned a message"
        is ChatEventActionModel.MessageUnpinned -> "unpinned a message"
        is ChatEventActionModel.MemberJoined -> "joined the chat"
        is ChatEventActionModel.MemberLeft -> "left the chat"
        is ChatEventActionModel.MemberInvited -> {
            val targetName = allSenderInfo[action.userId]?.name ?: "User ${action.userId}"
            "invited $targetName"
        }

        is ChatEventActionModel.MemberPromoted -> {
            val targetName = allSenderInfo[action.userId]?.name ?: "User ${action.userId}"
            "changed permissions for $targetName"
        }

        is ChatEventActionModel.MemberRestricted -> {
            val targetName = allSenderInfo[action.userId]?.name ?: "User ${action.userId}"
            "changed restrictions for $targetName"
        }

        is ChatEventActionModel.TitleChanged -> "changed chat title to \"${action.newTitle}\""
        is ChatEventActionModel.DescriptionChanged -> "changed chat description"
        is ChatEventActionModel.UsernameChanged -> "changed username to @${action.newUsername}"
        is ChatEventActionModel.PhotoChanged -> "changed chat photo"
        is ChatEventActionModel.InviteLinkEdited -> "edited invite link"
        is ChatEventActionModel.InviteLinkRevoked -> "revoked invite link"
        is ChatEventActionModel.InviteLinkDeleted -> "deleted invite link"
        is ChatEventActionModel.VideoChatCreated -> "started a video chat"
        is ChatEventActionModel.VideoChatEnded -> "ended video chat"
        is ChatEventActionModel.Unknown -> "performed an action: ${action.type}"
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
            videoPlayerPool = component.videoPlayerPool,
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
