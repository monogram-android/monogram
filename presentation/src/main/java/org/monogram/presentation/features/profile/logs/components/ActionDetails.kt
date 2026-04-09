package org.monogram.presentation.features.profile.logs.components

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.koin.compose.koinInject
import org.monogram.domain.models.ChatEventActionModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.features.profile.logs.ProfileLogsComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    text = stringResource(R.string.logs_original_message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.oldMessage, component = component)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.logs_new_message),
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
                    text = stringResource(R.string.logs_deleted_message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.message, component = component)
            }
        }

        is ChatEventActionModel.MessagePinned -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.logs_pinned_message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MessagePreview(action.message, component = component)
            }
        }

        is ChatEventActionModel.MessageUnpinned -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = stringResource(R.string.logs_unpinned_message),
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
                    val dateFormatManager: DateFormatManager = koinInject();
                    val timeFormat = dateFormatManager.getHourMinuteFormat()
                    val dateText =
                        SimpleDateFormat("MMM dd, yyyy $timeFormat", Locale.getDefault()).format(date)
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
                            text = stringResource(R.string.logs_restricted_until, ""),
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
                } else if (action.untilDate == 0 && action.newStatus.contains(
                        "Restricted",
                        ignoreCase = true
                    )
                ) {
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
                            text = stringResource(R.string.logs_restricted_permanently),
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
                        Text(stringResource(R.string.logs_photo_old), style = MaterialTheme.typography.labelSmall)
                        AsyncImage(
                            model = File(action.oldPhotoPath.toString()),
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .clickable {
                                    component.onPhotoClick(
                                        action.oldPhotoPath.toString(),
                                        "Old chat photo"
                                    )
                                },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                if (action.newPhotoPath != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.logs_photo_new), style = MaterialTheme.typography.labelSmall)
                        AsyncImage(
                            model = File(action.newPhotoPath.toString()),
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .clickable {
                                    component.onPhotoClick(
                                        action.newPhotoPath.toString(),
                                        "New chat photo"
                                    )
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
    val localClipboard = LocalClipboard.current
    val context = LocalContext.current
    val logsUserIdCopiedResId = stringResource(R.string.logs_user_id_copied)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(
                onClick = { component.onUserClick(userId) },
                onLongClick = {
                    localClipboard.nativeClipboard.setPrimaryClip(
                        ClipData.newPlainText("", AnnotatedString(userId.toString()))
                    )
                    Toast.makeText(context, logsUserIdCopiedResId, Toast.LENGTH_SHORT).show()
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Avatar(
            path = info?.avatarPath,
            name = name,
            size = 28.dp,
            fontSize = 12
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
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
        StatusChangeRow(stringResource(R.string.logs_status_from), oldStatus)
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatusChangeRow(stringResource(R.string.logs_status_to), newStatus)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsDiff(old: ChatPermissionsModel?, new: ChatPermissionsModel) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = if (old != null) stringResource(R.string.logs_permissions_changes) else stringResource(R.string.logs_permissions_current),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
            fontWeight = FontWeight.Bold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PermissionChip(
                stringResource(R.string.logs_perm_messages),
                old?.canSendBasicMessages,
                new.canSendBasicMessages,
                Icons.Rounded.ChatBubble
            )
            PermissionChip(
                stringResource(R.string.logs_perm_media),
                (old?.canSendPhotos ?: true) || (old?.canSendVideos ?: true),
                new.canSendPhotos || new.canSendVideos,
                Icons.Rounded.PermMedia
            )
            PermissionChip(
                stringResource(R.string.logs_perm_stickers),
                old?.canSendOtherMessages,
                new.canSendOtherMessages,
                Icons.AutoMirrored.Rounded.StickyNote2
            )
            PermissionChip(
                stringResource(R.string.logs_perm_links),
                old?.canAddLinkPreviews,
                new.canAddLinkPreviews,
                Icons.Rounded.Link
            )
            PermissionChip(stringResource(R.string.logs_perm_polls), old?.canSendPolls, new.canSendPolls, Icons.Rounded.Poll)
            PermissionChip(
                stringResource(R.string.logs_perm_invite),
                old?.canInviteUsers,
                new.canInviteUsers,
                Icons.Rounded.PersonAdd
            )
            PermissionChip(stringResource(R.string.logs_perm_pin), old?.canPinMessages, new.canPinMessages, Icons.Rounded.PushPin)
            PermissionChip(stringResource(R.string.logs_perm_info), old?.canChangeInfo, new.canChangeInfo, Icons.Rounded.Info)
        }
    }
}

@Composable
private fun PermissionChip(label: String, oldVal: Boolean?, newVal: Boolean, icon: ImageVector) {
    val shouldShow = oldVal == null || oldVal != newVal

    if (shouldShow) {
        val isRestricted = !newVal
        val color =
            if (isRestricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val containerColor = color.copy(alpha = 0.12f)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(containerColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isRestricted) Icons.Rounded.Block else icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
