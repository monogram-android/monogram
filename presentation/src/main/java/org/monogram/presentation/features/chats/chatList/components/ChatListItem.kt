package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.MessageEntityType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.AvatarForChat
import org.monogram.presentation.core.ui.TypingDots
import org.monogram.presentation.core.util.toShortRelativeDate
import org.monogram.presentation.features.chats.currentChat.components.chats.addEmojiStyle
import org.monogram.presentation.features.chats.currentChat.components.chats.buildAnnotatedMessageTextWithEmoji
import org.monogram.presentation.features.chats.currentChat.components.chats.rememberMessageInlineContent
import org.monogram.presentation.features.stickers.ui.view.StickerImage
import org.monogram.core.date.toDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListItem(
    chat: ChatModel,
    currentUserId: Long?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    showPhotos: Boolean,
    modifier: Modifier = Modifier,
    isTabletSelected: Boolean = false
) {
    val isSavedMessages = chat.id == currentUserId

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isTabletSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            chat.isPinned -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        label = "ItemBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(24))
            .background(backgroundColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = chat.title },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = showPhotos,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Row {
                ChatListItemAvatar(
                    chat = chat,
                    isSavedMessages = isSavedMessages,
                    isSelected = isSelected
                )

                Spacer(Modifier.width(14.dp))
            }
        }

        ChatListItemInfo(
            chat = chat,
            isSavedMessages = isSavedMessages,
            emojiFontFamily = emojiFontFamily,
            messageLines = messageLines,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ChatListItemAvatar(
    chat: ChatModel,
    isSavedMessages: Boolean,
    isSelected: Boolean
) {
    Box(contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = isSelected,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Check,
                    null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (!isSelected) {
            Box(modifier = Modifier.size(56.dp)) {
                if (isSavedMessages) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Bookmark,
                            null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    AvatarForChat(
                        path = chat.avatarPath,
                        fallbackPath = chat.personalAvatarPath,
                        name = chat.title,
                        size = 56.dp,
                        isOnline = chat.isOnline
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatListItemInfo(
    chat: ChatModel,
    isSavedMessages: Boolean,
    emojiFontFamily: FontFamily,
    messageLines: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ChatListItemHeader(
            chat = chat,
            isSavedMessages = isSavedMessages
        )

        Spacer(Modifier.height(4.dp))

        ChatListItemContent(
            chat = chat,
            emojiFontFamily = emojiFontFamily,
            messageLines = messageLines
        )
    }
}

@Composable
private fun ChatListItemHeader(
    chat: ChatModel,
    isSavedMessages: Boolean
) {
    val chatTime = chat.lastMessageDate.toDate().toShortRelativeDate()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            if (chat.isForum) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = stringResource(R.string.cd_forum),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = if (isSavedMessages) stringResource(R.string.menu_saved_messages) else chat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "ChatTitle" }
            )

            if (!isSavedMessages && chat.isMuted) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.NotificationsOff,
                    contentDescription = stringResource(R.string.cd_muted),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            if (!isSavedMessages && chat.isVerified) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = stringResource(R.string.cd_verified),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (!isSavedMessages && chat.isSponsor) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = stringResource(R.string.cd_sponsor),
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFE53935)
                )
            }

            if (!isSavedMessages && chat.emojiStatusPath != null) {
                Spacer(Modifier.width(4.dp))
                StickerImage(
                    path = chat.emojiStatusPath,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        val timeColor = if (chat.unreadCount > 0 && !chat.isMuted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Text(
            text = chatTime,
            style = MaterialTheme.typography.labelMedium,
            color = timeColor
        )
    }
}

@Composable
private fun ChatListItemContent(
    chat: ChatModel,
    emojiFontFamily: FontFamily,
    messageLines: Int
) {
    val fontSize = MaterialTheme.typography.bodyMedium.fontSize.value

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            if (chat.typingAction != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = chat.typingAction ?: "",
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(2.dp))
                    TypingDots(
                        dotSize = 3.dp,
                        dotColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            } else if (chat.draftMessage != null) {
                val draftHasSpoiler = chat.draftMessageEntities.any { it.type is MessageEntityType.Spoiler }
                val inlineContent = if (!draftHasSpoiler) {
                    rememberMessageInlineContent(
                        entities = chat.draftMessageEntities,
                        fontSize = fontSize
                    )
                } else {
                    emptyMap()
                }
                val annotatedDraft = if (draftHasSpoiler) {
                    buildAnnotatedString {
                        val spoilerLabel = stringResource(R.string.message_spoiler)
                        append(spoilerLabel)
                        addStyle(
                            SpanStyle(
                                background = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold
                            ),
                            0,
                            spoilerLabel.length
                        )
                    }
                } else {
                    buildAnnotatedMessageTextWithEmoji(
                        text = chat.draftMessage ?: "",
                        entities = chat.draftMessageEntities
                    )
                }
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append(stringResource(R.string.message_draft_prefix)) }
                        append(annotatedDraft)
                    },
                    inlineContent = inlineContent,
                    maxLines = messageLines,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val lastText = chat.lastMessageText.ifEmpty {
                    if (chat.isChannel) stringResource(R.string.no_posts_yet) else stringResource(R.string.no_messages_yet)
                }
                val inlineContent = rememberMessageInlineContent(
                    entities = chat.lastMessageEntities,
                    fontSize = fontSize
                )
                val annotatedText = if (chat.lastMessageText.isNotEmpty()) {
                    val hasSpoiler = chat.lastMessageEntities.any { it.type is MessageEntityType.Spoiler }
                    if (hasSpoiler) {
                        buildAnnotatedString {
                            val spoilerLabel = stringResource(R.string.message_spoiler)
                            append(spoilerLabel)
                            addStyle(
                                SpanStyle(
                                    background = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontWeight = FontWeight.Bold
                                ),
                                0,
                                spoilerLabel.length
                            )
                        }
                    } else {
                        buildAnnotatedMessageTextWithEmoji(
                            text = chat.lastMessageText,
                            entities = chat.lastMessageEntities
                        )
                    }
                } else {
                    buildAnnotatedString {
                        append(lastText)
                        addEmojiStyle(lastText, emojiFontFamily)
                    }
                }
                Text(
                    text = annotatedText,
                    inlineContent = inlineContent,
                    maxLines = messageLines,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        ChatListItemStatus(chat = chat)
    }
}

@Composable
private fun ChatListItemStatus(chat: ChatModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val hasUnread = chat.unreadCount > 0 || chat.isMarkedAsUnread || chat.unreadMentionCount > 0

        if (chat.isPinned && !hasUnread) {
            Icon(
                Icons.Rounded.PushPin,
                contentDescription = stringResource(R.string.cd_pinned),
                modifier = Modifier
                    .size(14.dp)
                    .rotate(45f),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(4.dp))
        }

        if (chat.unreadMentionCount > 0) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AlternateEmail,
                    contentDescription = stringResource(R.string.cd_mentions),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        if (chat.unreadCount > 0 || chat.isMarkedAsUnread) {
            val badgeColor = if (chat.isMuted) {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primary
            }
            val contentColor = if (chat.isMuted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onPrimary
            }

            Box(
                modifier = Modifier
                    .background(badgeColor, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (chat.unreadCount > 0) {
                    Text(
                        text = chat.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(contentColor, CircleShape)
                    )
                }
            }
        } else if (chat.isLastMessageOutgoing) {
            val isRead = chat.lastReadOutboxMessageId >= chat.lastMessageId
            Icon(
                imageVector = if (isRead) Icons.Rounded.DoneAll else Icons.Rounded.Done,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}