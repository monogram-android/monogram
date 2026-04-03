package org.monogram.app.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.app.R
import org.monogram.presentation.features.chats.chatList.components.AvatarTopAppBar
import org.monogram.presentation.root.RootComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatConfirmJoinSheet(root: RootComponent) {
    val chatConfirmJoinState by root.chatToConfirmJoin.collectAsState()
    if (chatConfirmJoinState.chat != null || chatConfirmJoinState.inviteLink != null) {
        ModalBottomSheet(
            onDismissRequest = root::dismissChatConfirmJoin,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val title =
                    chatConfirmJoinState.chat?.title ?: chatConfirmJoinState.inviteTitle ?: ""
                val avatarPath =
                    chatConfirmJoinState.chat?.avatarPath ?: chatConfirmJoinState.inviteAvatarPath
                val isChannel =
                    chatConfirmJoinState.chat?.isChannel ?: chatConfirmJoinState.inviteIsChannel
                val memberCount =
                    chatConfirmJoinState.chat?.memberCount ?: chatConfirmJoinState.inviteMemberCount
                val description = chatConfirmJoinState.fullInfo?.description
                    ?: chatConfirmJoinState.inviteDescription

                AvatarTopAppBar(
                    path = avatarPath,
                    name = title,
                    size = 100.dp,
                    fontSize = 32
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                val channelStr = stringResource(R.string.chat_channel)
                val groupStr = stringResource(R.string.chat_group)
                val infoText = buildString {
                    if (isChannel) append(channelStr) else append(groupStr)
                    if (memberCount > 0) {
                        append(" • ")
                        append(pluralStringResource(R.plurals.members_count, memberCount, memberCount))
                    }
                }

                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                description?.let { bio ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = root::dismissChatConfirmJoin,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Button(
                        onClick = {
                            val chatId = chatConfirmJoinState.chat?.id
                            val inviteLink = chatConfirmJoinState.inviteLink
                            if (chatId != null) {
                                root.confirmJoinChat(chatId)
                            } else if (inviteLink != null) {
                                root.confirmJoinInviteLink(inviteLink)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_join),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
