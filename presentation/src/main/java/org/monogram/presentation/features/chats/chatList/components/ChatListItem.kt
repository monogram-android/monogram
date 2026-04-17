package org.monogram.presentation.features.chats.chatList.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import org.koin.compose.koinInject
import org.monogram.core.date.toDate
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageEntityType
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.AvatarForChat
import org.monogram.presentation.core.ui.TypingDots
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.toShortRelativeDate
import org.monogram.presentation.features.chats.currentChat.components.chats.addEmojiStyle
import org.monogram.presentation.features.chats.currentChat.components.chats.buildAnnotatedMessageTextWithEmoji
import org.monogram.presentation.features.chats.currentChat.components.chats.rememberMessageInlineContent
import org.monogram.presentation.features.stickers.ui.view.StickerImage

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
            isTabletSelected -> MaterialTheme.colorScheme.primaryContainer
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            chat.isPinned -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        label = "ItemBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
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
    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()
    val chatTime = chat.lastMessageDate.toDate().toShortRelativeDate(timeFormat)
    val savedMessagesTitle = stringResource(R.string.menu_saved_messages)

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
                text = if (isSavedMessages) savedMessagesTitle else chat.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = if (isSavedMessages) savedMessagesTitle else chat.title
                }
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
                ChatListPreviewText(
                    text = chat.draftMessage.orEmpty(),
                    entities = chat.draftMessageEntities,
                    emojiFontFamily = emojiFontFamily,
                    maxLines = messageLines,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    prefix = stringResource(R.string.message_draft_prefix),
                    prefixColor = MaterialTheme.colorScheme.error
                )
            } else {
                ChatListMessagePreview(
                    chat = chat,
                    emojiFontFamily = emojiFontFamily,
                    maxLines = messageLines,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        ChatListItemStatus(chat = chat)
    }
}

@Composable
private fun ChatListMessagePreview(
    chat: ChatModel,
    emojiFontFamily: FontFamily,
    maxLines: Int,
    color: Color
) {
    val previewPaths = remember(chat.lastMessagePreviewPaths, chat.lastMessagePreviewPath) {
        chat.lastMessagePreviewPaths.ifEmpty {
            listOfNotNull(chat.lastMessagePreviewPath?.takeIf { it.isNotBlank() })
        }
    }
    val showThumbnail = shouldShowMediaThumbnail(chat.lastMessageContentType, previewPaths)
    val mediaLabel = rememberMediaLabel(chat.lastMessageContentType)
    val visibleMediaLabel =
        if (showThumbnail && shouldHideMediaLabelWhenThumbnailShown(chat.lastMessageContentType)) {
            null
        } else {
            mediaLabel
        }
    val hasLastMessage = remember(
        chat.lastMessageId,
        chat.lastMessageDate,
        chat.lastMessageText,
        chat.lastMessageEntities,
        chat.lastMessageContentType
    ) {
        hasLastMessagePreview(chat)
    }
    val fallbackText = if (chat.isChannel) {
        stringResource(R.string.no_posts_yet)
    } else {
        stringResource(R.string.no_messages_yet)
    }
    val payload = remember(chat.lastMessageText, chat.lastMessageEntities) {
        sanitizePreviewPayload(chat.lastMessageText, chat.lastMessageEntities)
    }
    val renderText = if (!hasLastMessage) {
        fallbackText
    } else {
        payload.text
    }
    val renderEntities = if (renderText == payload.text) payload.entities else emptyList()
    val visibleBodyPayload = remember(
        renderText,
        renderEntities,
        chat.lastMessageContentType,
        mediaLabel,
        hasLastMessage
    ) {
        if (!hasLastMessage) {
            PreviewPayload(renderText, renderEntities)
        } else {
            sanitizeMeaningfulPreviewBody(
                text = renderText,
                entities = renderEntities,
                contentType = chat.lastMessageContentType,
                mediaLabel = mediaLabel
            )
        }
    }
    val bodyText = rememberPreviewBodyText(
        text = visibleBodyPayload.text,
        entities = visibleBodyPayload.entities,
        emojiFontFamily = emojiFontFamily
    )
    val inlineContent = rememberPreviewInlineContent(
        text = visibleBodyPayload.text,
        entities = visibleBodyPayload.entities
    )
    val hasVisibleTail = visibleMediaLabel != null || bodyText.text.isNotBlank()
    val finalText = buildAnnotatedString {
        if (!chat.isChannel && chat.lastMessageSenderName.isNotBlank()) {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(chat.lastMessageSenderName)
                if (hasVisibleTail) {
                    append(": ")
                }
            }
        }

        if (visibleMediaLabel != null) {
            withStyle(
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(visibleMediaLabel)
            }
            if (bodyText.text.isNotBlank()) {
                append(" ")
            }
        }

        if (bodyText.text.isNotBlank()) {
            append(bodyText)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showThumbnail) {
            ChatListPreviewThumbnails(
                contentType = chat.lastMessageContentType,
                paths = previewPaths
            )
            Spacer(Modifier.width(8.dp))
        }

        Text(
            modifier = Modifier.weight(1f),
            text = finalText,
            inlineContent = inlineContent,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}

@Composable
private fun rememberMediaLabel(contentType: String): String? {
    return when (contentType) {
        "photo" -> stringResource(R.string.chat_mapper_photo)
        "video" -> stringResource(R.string.chat_mapper_video)
        "voice" -> stringResource(R.string.chat_mapper_voice)
        "video_note" -> stringResource(R.string.chat_mapper_video_note)
        "sticker" -> stringResource(R.string.chat_mapper_sticker)
        "document" -> stringResource(R.string.chat_mapper_document)
        "audio" -> stringResource(R.string.chat_mapper_audio)
        "gif" -> stringResource(R.string.chat_mapper_gif)
        "contact" -> stringResource(R.string.chat_mapper_contact)
        "poll" -> stringResource(R.string.chat_mapper_poll)
        "location" -> stringResource(R.string.chat_mapper_location)
        "call" -> stringResource(R.string.chat_mapper_call)
        "game" -> stringResource(R.string.chat_mapper_game)
        "invoice" -> stringResource(R.string.chat_mapper_invoice)
        "story" -> stringResource(R.string.chat_mapper_story)
        "pinned" -> stringResource(R.string.chat_mapper_pinned)
        "message" -> stringResource(R.string.chat_mapper_message)
        else -> null
    }
}

@Composable
private fun ChatListPreviewThumbnails(
    contentType: String,
    paths: List<String>
) {
    val visiblePaths = paths.filter { it.isNotBlank() }.take(3)
    if (visiblePaths.isEmpty()) return

    if (visiblePaths.size == 1) {
        ChatListPreviewThumbnail(
            contentType = contentType,
            path = visiblePaths.first(),
            modifier = Modifier
        )
        return
    }

    Box(
        modifier = Modifier
            .width((38 + (visiblePaths.lastIndex * 10)).dp)
            .height(38.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        visiblePaths.forEachIndexed { index, path ->
            ChatListPreviewThumbnail(
                contentType = contentType,
                path = path,
                modifier = Modifier
                    .padding(start = (index * 10).dp)
            )
        }
    }
}

@Composable
private fun ChatListPreviewThumbnail(
    contentType: String,
    path: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (contentType == "sticker") {
            StickerImage(
                path = path,
                modifier = Modifier.size(30.dp)
            )
        } else {
            AsyncImage(
                model = path,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun ChatListPreviewText(
    text: String,
    entities: List<MessageEntity>,
    emojiFontFamily: FontFamily,
    maxLines: Int,
    color: Color,
    prefix: String? = null,
    prefixColor: Color = Color.Unspecified,
    fallbackText: String? = null
) {
    val payload = remember(text, entities) { sanitizePreviewPayload(text, entities) }

    val renderText = if (payload.text.isBlank() && !fallbackText.isNullOrBlank()) {
        fallbackText
    } else {
        payload.text
    }
    val renderEntities = if (renderText == payload.text) payload.entities else emptyList()
    val inlineContent = rememberPreviewInlineContent(
        text = renderText,
        entities = renderEntities
    )
    val bodyText = rememberPreviewBodyText(
        text = renderText,
        entities = renderEntities,
        emojiFontFamily = emojiFontFamily
    )

    val finalText = if (prefix == null) {
        bodyText
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(color = prefixColor)) {
                append(prefix)
            }
            append(bodyText)
        }
    }

    Text(
        text = finalText,
        inlineContent = inlineContent,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        color = color
    )
}

@Composable
private fun rememberPreviewInlineContent(
    text: String,
    entities: List<MessageEntity>
) =
    if (text.isNotBlank() && entities.isNotEmpty() && entities.none { it.type is MessageEntityType.Spoiler }) {
        rememberMessageInlineContent(
            entities = entities,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize.value
        )
    } else {
        emptyMap()
    }

@Composable
private fun rememberPreviewBodyText(
    text: String,
    entities: List<MessageEntity>,
    emojiFontFamily: FontFamily
) = when {
    entities.any { it.type is MessageEntityType.Spoiler } -> {
        val spoilerLabel = stringResource(R.string.message_spoiler)
        buildAnnotatedString {
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
    }

    entities.isNotEmpty() -> buildAnnotatedMessageTextWithEmoji(
        text = text,
        entities = entities
    )

    else -> buildAnnotatedString {
        append(text)
        addEmojiStyle(text, emojiFontFamily)
    }
}

private data class PreviewPayload(
    val text: String,
    val entities: List<MessageEntity>
)

private fun shouldShowMediaThumbnail(contentType: String, paths: List<String>): Boolean {
    if (paths.isEmpty()) return false
    return contentType == "photo" ||
            contentType == "video" ||
            contentType == "gif" ||
            contentType == "sticker" ||
            contentType == "video_note"
}

private fun shouldHideMediaLabelWhenThumbnailShown(contentType: String): Boolean {
    return contentType == "photo" ||
            contentType == "video" ||
            contentType == "gif" ||
            contentType == "sticker" ||
            contentType == "video_note"
}

private fun hasLastMessagePreview(chat: ChatModel): Boolean {
    return chat.lastMessageId != 0L ||
            chat.lastMessageDate != 0 ||
            chat.lastMessageText.isNotBlank() ||
            chat.lastMessageEntities.isNotEmpty() ||
            chat.lastMessageContentType != "text"
}

private fun sanitizePreviewPayload(
    text: String,
    entities: List<MessageEntity>
): PreviewPayload {
    if (text.isEmpty() || entities.isEmpty()) {
        return PreviewPayload(
            text = text,
            entities = entities.filterNot(::isMissingCustomEmoji)
        )
    }

    val removedChars = BooleanArray(text.length)
    entities.forEach { entity ->
        if (!isMissingCustomEmoji(entity)) return@forEach

        val safeStart = entity.offset.coerceIn(0, text.length)
        val safeEnd = (entity.offset.toLong() + entity.length.toLong())
            .coerceIn(safeStart.toLong(), text.length.toLong())
            .toInt()

        for (index in safeStart until safeEnd) {
            removedChars[index] = true
        }
    }

    if (!removedChars.any { it }) {
        return PreviewPayload(
            text = text,
            entities = entities.filterNot(::isMissingCustomEmoji)
        )
    }

    val mapping = IntArray(text.length + 1)
    val rebuiltText = StringBuilder(text.length)
    var newIndex = 0

    for (oldIndex in text.indices) {
        mapping[oldIndex] = newIndex
        if (!removedChars[oldIndex]) {
            rebuiltText.append(text[oldIndex])
            newIndex++
        }
    }
    mapping[text.length] = newIndex

    val rebuiltEntities = entities.mapNotNull { entity ->
        if (isMissingCustomEmoji(entity)) return@mapNotNull null

        val safeStart = entity.offset.coerceIn(0, text.length)
        val safeEnd = (entity.offset.toLong() + entity.length.toLong())
            .coerceIn(safeStart.toLong(), text.length.toLong())
            .toInt()

        val mappedStart = mapping[safeStart]
        val mappedEnd = mapping[safeEnd]
        if (mappedStart >= mappedEnd) return@mapNotNull null

        entity.copy(offset = mappedStart, length = mappedEnd - mappedStart)
    }

    return PreviewPayload(
        text = rebuiltText.toString(),
        entities = rebuiltEntities
    )
}

private fun sanitizeMeaningfulPreviewBody(
    text: String,
    entities: List<MessageEntity>,
    contentType: String,
    mediaLabel: String?
): PreviewPayload {
    if (text.isBlank()) return PreviewPayload("", emptyList())

    if (
        contentType != "photo" &&
        contentType != "video" &&
        contentType != "gif" &&
        contentType != "sticker" &&
        contentType != "video_note"
    ) {
        return PreviewPayload(text, entities)
    }

    val normalized = normalizePreviewMeaning(text, entities)
    if (normalized.isBlank()) {
        return PreviewPayload("", emptyList())
    }

    val genericTokens = buildList {
        mediaLabel?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let(::add)
        mediaContentEnglishLabel(contentType)?.let(::add)
    }.distinct()

    val reduced = genericTokens
        .fold(normalized) { acc, token -> acc.replace(token, " ") }
        .replace(Regex("\\s+"), " ")
        .trim()

    return if (reduced.isBlank()) {
        PreviewPayload("", emptyList())
    } else {
        PreviewPayload(text, entities)
    }
}

private fun normalizePreviewMeaning(
    text: String,
    entities: List<MessageEntity>
): String {
    val customEmojiRanges = entities
        .filter { it.type is MessageEntityType.CustomEmoji }
        .map { entity ->
            val start = entity.offset.coerceIn(0, text.length)
            val end = (entity.offset + entity.length).coerceIn(start, text.length)
            start until end
        }

    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val insideCustomEmoji = customEmojiRanges.any { index in it }
        if (insideCustomEmoji) {
            index++
            continue
        }

        val codePoint = text.codePointAt(index)
        if (Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)) {
            builder.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }

    return builder.toString()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun mediaContentEnglishLabel(contentType: String): String? = when (contentType) {
    "photo" -> "photo"
    "video" -> "video"
    "gif" -> "gif"
    "sticker" -> "sticker"
    "video_note" -> "video message"
    else -> null
}

private fun isMissingCustomEmoji(entity: MessageEntity): Boolean {
    val customEmoji = entity.type as? MessageEntityType.CustomEmoji ?: return false
    return customEmoji.path.isNullOrBlank()
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
