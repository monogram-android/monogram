package org.monogram.presentation.profile.logs.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.monogram.domain.models.ChatEventActionModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.presentation.profile.logs.ProfileLogsComponent
import org.monogram.presentation.uikit.Avatar
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ActionDetails(
    action: ChatEventActionModel,
    allSenderInfo: Map<Long, ProfileLogsComponent.SenderInfo>,
    component: ProfileLogsComponent
) {
    when (action) {
        is ChatEventActionModel.MessageEdited -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Original message:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.oldMessage, component = component)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "New message:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(
                    message = action.newMessage,
                    oldMessage = action.oldMessage,
                    component = component
                )
            }
        }

        is ChatEventActionModel.MessageDeleted -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Deleted message:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.message, component = component)
            }
        }

        is ChatEventActionModel.MessagePinned -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Pinned message:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.message, component = component)
            }
        }

        is ChatEventActionModel.MessageUnpinned -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Unpinned message:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.message, component = component)
            }
        }

        is ChatEventActionModel.MemberPromoted -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                TargetUserRow(action.userId, allSenderInfo, component)
                Spacer(modifier = Modifier.height(8.dp))
                StatusTransition(action.oldStatus, action.newStatus)
            }
        }

        is ChatEventActionModel.MemberRestricted -> {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            ) {
                TargetUserRow(action.userId, allSenderInfo, component)

                Spacer(modifier = Modifier.height(8.dp))

                StatusTransition(action.oldStatus, action.newStatus)

                if (action.untilDate > 0) {
                    val date = Date(action.untilDate.toLong() * 1000)
                    val dateText = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(date)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Until: ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (action.untilDate == 0 && action.newStatus.contains("Restricted", ignoreCase = true)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AllInclusive,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Restricted permanently",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (action.newPermissions != null) {
                    PermissionsDiff(action.oldPermissions, action.newPermissions!!)
                }
            }
        }

        is ChatEventActionModel.PhotoChanged -> {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (action.oldPhotoPath != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Old", style = MaterialTheme.typography.labelSmall)
                        AsyncImage(
                            model = File(action.oldPhotoPath.toString()),
                            contentDescription = null,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .clickable {
                                    component.onPhotoClick(action.oldPhotoPath.toString(), "Old chat photo")
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                if (action.newPhotoPath != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("New", style = MaterialTheme.typography.labelSmall)
                        AsyncImage(
                            model = File(action.newPhotoPath.toString()),
                            contentDescription = null,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .clickable {
                                    component.onPhotoClick(action.newPhotoPath.toString(), "New chat photo")
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        else -> {}
    }
}

@Composable
private fun TargetUserRow(
    userId: Long,
    allSenderInfo: Map<Long, ProfileLogsComponent.SenderInfo>,
    component: ProfileLogsComponent
) {
    val info = allSenderInfo[userId]
    val name = info?.name ?: "User $userId"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { component.onUserClick(userId) }
            .padding(vertical = 4.dp)
    ) {
        Avatar(
            path = info?.avatarPath,
            name = name,
            size = 24.dp,
            fontSize = 10,
            videoPlayerPool = component.videoPlayerPool
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusTransition(oldStatus: String, newStatus: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusChangeRow("From", oldStatus)
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatusChangeRow("To", newStatus)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsDiff(old: ChatPermissionsModel?, new: ChatPermissionsModel) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = if (old != null) "Permission changes:" else "Current permissions:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
            fontWeight = FontWeight.Bold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PermissionChip("Messages", old?.canSendBasicMessages, new.canSendBasicMessages, Icons.Rounded.ChatBubble)
            PermissionChip(
                "Media",
                (old?.canSendPhotos ?: true) || (old?.canSendVideos ?: true),
                new.canSendPhotos || new.canSendVideos,
                Icons.Rounded.PermMedia
            )
            PermissionChip("Stickers", old?.canSendOtherMessages, new.canSendOtherMessages,
                Icons.AutoMirrored.Rounded.StickyNote2
            )
            PermissionChip("Links", old?.canAddLinkPreviews, new.canAddLinkPreviews, Icons.Rounded.Link)
            PermissionChip("Polls", old?.canSendPolls, new.canSendPolls, Icons.Rounded.Poll)
            PermissionChip("Invite", old?.canInviteUsers, new.canInviteUsers, Icons.Rounded.PersonAdd)
            PermissionChip("Pin", old?.canPinMessages, new.canPinMessages, Icons.Rounded.PushPin)
            PermissionChip("Info", old?.canChangeInfo, new.canChangeInfo, Icons.Rounded.Info)
        }
    }
}

@Composable
private fun PermissionChip(label: String, oldVal: Boolean?, newVal: Boolean, icon: ImageVector) {
    val shouldShow = oldVal == null || oldVal != newVal

    if (shouldShow) {
        val isRestricted = !newVal
        val color = if (isRestricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val containerColor = color.copy(alpha = 0.1f)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isRestricted) Icons.Rounded.Block else icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
