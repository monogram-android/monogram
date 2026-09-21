package org.monogram.feature.chats.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.monogram.core.common.Outcome
import org.monogram.core.models.Chat
import org.monogram.core.models.Presence
import org.monogram.core.models.chatListMediaLabel
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.unpackTypingNames
import org.monogram.core.ui.components.ChatRowMetrics
import org.monogram.core.ui.components.EmojiStatusMark
import org.monogram.core.ui.components.OutgoingStatusMark
import org.monogram.core.ui.components.PeerAvatar
import org.monogram.core.ui.components.ServiceAvatar
import org.monogram.core.ui.components.TypingDots
import org.monogram.core.ui.components.UnreadBadge
import org.monogram.core.ui.components.UnreadCountRow
import org.monogram.core.ui.components.formatChatTime
import org.monogram.core.ui.components.isPeerOnline
import org.monogram.core.ui.components.liveActionTransition
import org.monogram.core.ui.components.rememberPeerStatusNow
import org.monogram.core.ui.components.typingStatusText
import org.monogram.core.ui.perf.RecompositionProbe
import org.monogram.core.ui.rememberCacheGeneration
import org.monogram.core.ui.rememberEnsuredFile
import org.monogram.core.ui.serviceMessageCatalog
import org.monogram.feature.chats.ChatPreviewMedia
import org.monogram.feature.chats.R
import org.monogram.feature.chats.chatPreviewMedia
import org.monogram.feature.chats.splitPreviewSender
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import org.monogram.feature.chats.chatListPreviewText as formatPreviewText
import org.monogram.core.ui.components.SponsorBadge

internal val ChatRowHeight = 72.dp
internal val ChatRowAvatarSize = ChatRowMetrics.AvatarSize

internal fun avatarTapHandler(
    openProfileOnAvatarTap: Boolean,
    onOpenProfile: () -> Unit,
): (() -> Unit)? = if (openProfileOnAvatarTap) onOpenProfile else null

/** Title and preview line boxes, shared so the archive header lines up with chat names. */
private val ChatRowTitleLineHeight = ChatRowMetrics.TitleLineHeight
private val ChatRowPreviewLineHeight = ChatRowMetrics.PreviewLineHeight

/**
 * The title line box is [ChatRowTitleLineHeight] tall while Android font padding keeps the glyphs at
 * its top, so the cap band the title actually occupies sits [ChatTitleMarkLift] above the box
 * centre. Marks that share the title line (emoji status, muted bell) are lifted onto that band
 * instead of being centred on the box, which otherwise leaves them hanging below the baseline.
 */
private val ChatTitleMarkLift = 3.dp
private val ChatTitleMarkGap = 4.dp
private val ChatMutedMarkSize = 16.dp

private val ChatRowThumbSize = ChatRowMetrics.ThumbSize
private val ChatRowSideInset = ChatRowMetrics.SideInset
private val ChatRowCorner = ChatRowMetrics.Corner

/** Localized labels for the chat-list rows, resolved once per screen. */
@Immutable
internal data class ChatListTexts(
    val you: String,
    val someone: String,
    val untitled: String,
    val savedMessages: String,
    val muted: String,
    val pinned: String,
    val yesterday: String,
    val photo: String,
    val video: String,
    val gif: String,
    val sticker: String,
    val document: String,
    val audio: String,
    val voice: String,
    val link: String,
    val checklist: String,
) {
    fun mediaLabel(kind: String): String? = when (kind) {
        "photo" -> photo
        "video" -> video
        "gif" -> gif
        "sticker", "sticker_animated", "sticker_video" -> sticker
        "document" -> document
        "audio" -> audio
        "voice" -> voice
        "webpage" -> link
        "todo" -> checklist
        else -> chatListMediaLabel(kind)
    }
}

@Composable
internal fun chatListTexts(): ChatListTexts = ChatListTexts(
    you = stringResource(R.string.chats_preview_you),
    someone = stringResource(org.monogram.core.ui.R.string.service_someone),
    untitled = stringResource(R.string.chats_untitled),
    savedMessages = stringResource(R.string.chats_saved_messages),
    muted = stringResource(R.string.chats_muted),
    pinned = stringResource(R.string.chats_pinned),
    yesterday = stringResource(R.string.chats_yesterday),
    photo = stringResource(R.string.chats_preview_photo),
    video = stringResource(R.string.chats_preview_video),
    gif = stringResource(R.string.chats_preview_gif),
    sticker = stringResource(R.string.chats_preview_sticker),
    document = stringResource(R.string.chats_preview_document),
    audio = stringResource(R.string.chats_preview_audio),
    voice = stringResource(R.string.chats_preview_voice),
    link = stringResource(R.string.chats_preview_link),
    checklist = stringResource(R.string.chats_preview_checklist),
)

/**
 * Archive is a service header above the chats, not a conversation: the leading icon shares the
 * avatar column and centre so the title lands on the same name line, but the row keeps service
 * weight (titleSmall, surfaceContainerLow) and previews what is inside (latest archived chats).
 */
@Composable
internal fun ArchiveRow(
    preview: String?,
    unmuted: Int,
    mutedUnread: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unreadTotal = unmuted + mutedUnread
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatRowHeight)
            .padding(
                horizontal = ChatRowSideInset,
                vertical = ChatRowMetrics.ContainerPaddingV,
            )
            .clip(RoundedCornerShape(ChatRowCorner))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(
                horizontal = ChatRowMetrics.ContentPaddingH,
                vertical = ChatRowMetrics.ContentPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ChatRowAvatarSize)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.chats_folder_archive),
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = ChatRowTitleLineHeight),
            )
            // Preview-line box: keeps the title on the name line shared with chat rows.
            Box(
                modifier = Modifier.heightIn(min = ChatRowPreviewLineHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (!preview.isNullOrBlank()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (unreadTotal > 0) {
            Spacer(Modifier.width(8.dp))
            UnreadCountRow(
                unmuted = unmuted,
                muted = mutedUnread,
                unmutedColor = MaterialTheme.colorScheme.error,
                mutedColor = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
internal fun ChatRow(
    chat: Chat,
    selected: Boolean,
    selectingRecipient: Boolean = false,
    recipientSelectable: Boolean = true,
    savedMessages: Boolean,
    mediaRepository: MediaRepository?,
    showAvatar: Boolean,
    showReadStatus: Boolean,
    texts: ChatListTexts,
    onClick: () -> Unit,
    onAvatarClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    RecompositionProbe("ChatRow", chat.id.value)
    val time = remember(chat.lastMessageDate, texts.yesterday) {
        formatChatTime(chat.lastMessageDate, yesterdayLabel = texts.yesterday)
    }
    val outgoingMark = remember(
        showReadStatus,
        savedMessages,
        chat.lastMessageOutgoing,
        chat.isChannel,
        chat.lastMessageId,
        chat.readInboxMaxId,
        chat.readOutboxMaxId,
        chat.unreadCount,
    ) {
        if (!showReadStatus || savedMessages) {
            null
        } else {
            Presence.chatListOutgoingMark(
                outgoing = chat.lastMessageOutgoing,
                isChannel = chat.isChannel,
                lastMessageId = chat.lastMessageId,
                readInboxMaxId = chat.readInboxMaxId,
                readOutboxMaxId = chat.readOutboxMaxId,
                unreadCount = chat.unreadCount,
            )
        }
    }
    val title = when {
        savedMessages -> texts.savedMessages
        chat.title.isNotBlank() -> chat.title
        else -> texts.untitled
    }
    val unread = chat.unreadCount
    val loud = unread > 0 && !chat.muted
    ChatRowContainer(
        selected = selected,
        selectingRecipient = selectingRecipient,
        enabled = !selectingRecipient || recipientSelectable,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    ) {
        if (showAvatar) {
            val avatarClick = if (selectingRecipient) {
                null
            } else {
                onAvatarClick ?: onClick
            }
            Box(
                modifier = Modifier
                    .size(ChatRowAvatarSize)
                    .then(if (avatarClick != null) Modifier.clickable(onClick = avatarClick) else Modifier),
            ) {
                if (savedMessages) {
                    ServiceAvatar(
                        icon = Icons.Outlined.Bookmark,
                        contentDescription = texts.savedMessages,
                        size = ChatRowAvatarSize,
                    )
                } else {
                    PeerAvatar(
                        title = title,
                        size = ChatRowAvatarSize,
                        imageFile = rememberChatAvatar(
                            chat = chat,
                            mediaRepository = mediaRepository,
                        ),
                    )
                }
                if (!savedMessages && isOnline(chat)) {
                    OnlineDot(modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
            Spacer(Modifier.width(ChatRowMetrics.AvatarGap))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ChatRowMetrics.LineSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = if (loud) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = ChatRowTitleLineHeight),
                )
                if (chat.isVerified) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.chats_verified),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .offset(y = -ChatTitleMarkLift)
                            .padding(start = ChatTitleMarkGap)
                            .size(ChatMutedMarkSize),
                    )
                }
                SponsorBadge(
                    peerId = chat.id.value,
                    size = ChatMutedMarkSize,
                    gap = ChatTitleMarkGap,
                    modifier = Modifier.offset(y = -ChatTitleMarkLift),
                )
                ChatEmojiStatus(
                    documentId = chat.emojiStatusDocumentId,
                    mediaRepository = mediaRepository,
                )
                if (chat.muted) {
                    Spacer(Modifier.width(ChatTitleMarkGap))
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = texts.muted,
                        modifier = Modifier
                            .size(ChatMutedMarkSize)
                            .offset(y = -ChatTitleMarkLift),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ChatPreviewLine(
                chat = chat,
                texts = texts,
                mediaRepository = mediaRepository,
                loud = loud,
            )
        }
        Spacer(Modifier.width(ChatRowMetrics.RightGap))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(ChatRowMetrics.RightSpacing),
        ) {
            Row(
                modifier = Modifier.heightIn(min = ChatRowTitleLineHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (outgoingMark != null) {
                    OutgoingStatusMark(
                        pending = outgoingMark.pending,
                        read = outgoingMark.read,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ChatTimeLabel(
                    time = time,
                    pinned = chat.pinned,
                    pinnedLabel = texts.pinned,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showsMentionBadge(chat)) {
                    MentionBadge(
                        muted = chat.muted,
                        description = stringResource(R.string.chats_unread_mentions),
                    )
                }
                if (showsReactionBadge(chat)) {
                    ReactionBadge(
                        muted = chat.muted,
                        description = stringResource(R.string.chats_unread_reactions),
                    )
                }
                UnreadBadge(
                    count = unread,
                    muted = chat.muted,
                    unmutedColor = MaterialTheme.colorScheme.error,
                    mutedColor = MaterialTheme.colorScheme.secondary,
                    description = if (unread > 0) {
                        pluralStringResource(R.plurals.chats_unread_count, unread, unread)
                    } else {
                        null
                    },
                )
            }
            if (chat.unreadMark && unread == 0 &&
                !showsMentionBadge(chat) && !showsReactionBadge(chat)
            ) {
                UnreadMarkDot(
                    muted = chat.muted,
                    description = stringResource(R.string.chats_marked_unread),
                )
            }
        }
        if (selectingRecipient) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = recipientSelectable,
                modifier = Modifier.size(48.dp).clearAndSetSemantics { },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

internal fun showsMentionBadge(chat: Chat): Boolean = chat.unreadMentionsCount > 0

internal fun showsReactionBadge(chat: Chat): Boolean = chat.unreadReactionsCount > 0

@Composable
private fun MentionBadge(muted: Boolean, description: String) {
    val scheme = MaterialTheme.colorScheme
    val container = if (muted) scheme.secondary else scheme.error
    val content = contentColorFor(container).takeIf { it != Color.Unspecified }
        ?: if (muted) scheme.surface else scheme.onError
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "@",
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

@Composable
private fun ReactionBadge(muted: Boolean, description: String) {
    val scheme = MaterialTheme.colorScheme
    val container = if (muted) scheme.secondary else scheme.error
    val content = contentColorFor(container).takeIf { it != Color.Unspecified }
        ?: if (muted) scheme.surface else scheme.onError
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.EmojiEmotions,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * `dialog.unread_mark` marker: a chat can be unread with no unread messages, and Telegram shows a
 * dot instead of a counter for it.
 */
@Composable
private fun UnreadMarkDot(muted: Boolean, description: String) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
                if (muted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
            .semantics { contentDescription = description },
    )
}

/** Selected rows are a contained 16.dp card, never a full-bleed wash. */
@Composable
private fun ChatRowContainer(
    selected: Boolean,
    selectingRecipient: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val clickModifier = if (selectingRecipient) {
        Modifier.toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatRowHeight)
            .padding(
                horizontal = ChatRowSideInset,
                vertical = ChatRowMetrics.ContainerPaddingV,
            )
            .clip(RoundedCornerShape(ChatRowCorner))
            .background(
                if (selected) {
                    if (selectingRecipient) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .then(clickModifier)
            .padding(
                horizontal = ChatRowMetrics.ContentPaddingH,
                vertical = ChatRowMetrics.ContentPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun ChatPreviewLine(
    chat: Chat,
    texts: ChatListTexts,
    mediaRepository: MediaRepository?,
    loud: Boolean,
) {
    val bodyColor = if (loud) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    AnimatedContent(
        targetState = chat.typingAction to chat.typingName,
        transitionSpec = { liveActionTransition() },
        contentKey = { it.first ?: "preview" },
        label = "chat-preview",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatRowPreviewLineHeight)
            .clipToBounds(),
    ) { (action, packed) ->
        if (action != null) {
            Row(
                modifier = Modifier.heightIn(min = ChatRowPreviewLineHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TypingDots(dotColor = MaterialTheme.colorScheme.primary)
                Text(
                    text = typingStatusText(unpackTypingNames(packed), action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val media = chatPreviewMedia(chat.lastMessageMediaKind)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (thumbAvailable(chat, media)) {
                    LastMessageThumb(
                        chat = chat,
                        mediaRepository = mediaRepository,
                    )
                } else if (media != null) {
                    Icon(
                        imageVector = media.icon,
                        contentDescription = null,
                        tint = bodyColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                val preview = formatPreviewText(
                    chat = chat,
                    youLabel = texts.you,
                    someoneLabel = texts.someone,
                    mediaLabel = texts::mediaLabel,
                    serviceTemplate = serviceMessageCatalog(LocalResources.current),
                )
                val (sender, body) = splitPreviewSender(preview, texts.you)
                if (sender != null) {
                    Text(
                        text = sender,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bodyColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Pin and time share one chip; plain rows show the time alone. */
@Composable
private fun ChatTimeLabel(
    time: String,
    pinned: Boolean,
    pinnedLabel: String,
) {
    if (time.isEmpty() && !pinned) return
    if (!pinned) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = pinnedLabel,
                modifier = Modifier.size(12.dp),
            )
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun OnlineDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun rememberChatAvatar(
    chat: Chat,
    mediaRepository: MediaRepository?,
) = rememberEnsuredFile(
    generation = rememberCacheGeneration(
        remember(mediaRepository, chat.id, chat.photoCacheKey) {
            mediaRepository?.cacheGeneration(peerAvatarCacheKey(chat.id, chat.photoCacheKey))
        },
    ),
    identity = chat.id.value to peerAvatarCacheKey(chat.id, chat.photoCacheKey),
    resolve = {
        val key = peerAvatarCacheKey(chat.id, chat.photoCacheKey)
        mediaRepository?.cachedFile(key) ?: mediaRepository?.cachedAvatar(chat.id)
    },
    ensure = {
        val repo = mediaRepository ?: return@rememberEnsuredFile null
        val key = peerAvatarCacheKey(chat.id, chat.photoCacheKey)
        when (val res = repo.ensureLocalAvatar(chat.id, key, MediaPriority.THUMB)) {
            is Outcome.Ok -> res.value
            is Outcome.Err -> null
        }
    },
)

@Composable
private fun isOnline(chat: Chat): Boolean {
    if (chat.isChannel || chat.isGroup) return false
    val now = rememberPeerStatusNow(chat.peerStatus, chat.peerStatusAt)
    return isPeerOnline(chat.peerStatus, chat.peerStatusAt, now)
}

@Composable
private fun LastMessageThumb(
    chat: Chat,
    mediaRepository: MediaRepository?,
) {
    val key = chat.lastMediaThumbCacheKey ?: return
    val file = rememberEnsuredFile(
        generation = rememberCacheGeneration(
            remember(mediaRepository, key) { mediaRepository?.cacheGeneration(key) },
        ),
        identity = chat.id.value to key,
        resolve = { mediaRepository?.cachedFile(key) },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            if (chat.lastMessageId == 0) return@rememberEnsuredFile null
            val probe = org.monogram.core.models.Message(
                id = org.monogram.core.models.MessageId(chat.id, chat.lastMessageId),
                senderId = null,
                text = null,
                date = 0L,
                outgoing = false,
                mediaCacheKey = key,
                thumbCacheKey = key,
            )
            when (val result = repo.ensureLocalMessageThumb(probe, MediaPriority.THUMB)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    val local = file ?: return
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { ChatRowThumbSize.roundToPx() }
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(local)
            .size(sizePx)
            .memoryCacheKey("${local.absolutePath}:${local.length()}:$sizePx")
            .diskCacheKey("${local.absolutePath}:${local.length()}")
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(ChatRowThumbSize)
            .clip(RoundedCornerShape(12.dp)),
    )
}

/** Thumbnails only exist for visual media with a cached small preview. */
private fun thumbAvailable(chat: Chat, media: ChatPreviewMedia?): Boolean =
    (media == ChatPreviewMedia.Photo ||
        media == ChatPreviewMedia.Video ||
        media == ChatPreviewMedia.Gif) &&
        chat.lastMessageId != 0 &&
        !chat.lastMediaThumbCacheKey.isNullOrBlank()

private val ChatPreviewMedia.icon: ImageVector
    get() = when (this) {
        ChatPreviewMedia.Photo -> Icons.Outlined.Image
        ChatPreviewMedia.Video -> Icons.Outlined.Videocam
        ChatPreviewMedia.Gif -> Icons.Outlined.Gif
        ChatPreviewMedia.Sticker -> Icons.Outlined.EmojiEmotions
        ChatPreviewMedia.Voice -> Icons.Outlined.Mic
        ChatPreviewMedia.Audio -> Icons.Outlined.MusicNote
        ChatPreviewMedia.Document -> Icons.AutoMirrored.Outlined.InsertDriveFile
        ChatPreviewMedia.Link -> Icons.Outlined.Link
        ChatPreviewMedia.Checklist -> Icons.Outlined.Checklist
    }

@Composable
internal fun ChatEmojiStatus(
    documentId: Long?,
    mediaRepository: MediaRepository?,
) {
    val id = documentId ?: return
    val key = "emoji:$id"
    val file = rememberEnsuredFile(
        generation = rememberCacheGeneration(
            remember(mediaRepository, key) { mediaRepository?.cacheGeneration(key) },
        ),
        identity = id,
        resolve = { mediaRepository?.cachedFile(key) },
        ensure = {
            val repo = mediaRepository ?: return@rememberEnsuredFile null
            when (val result = repo.ensureCustomEmoji(id, MediaPriority.IDLE)) {
                is Outcome.Ok -> result.value
                is Outcome.Err -> null
            }
        },
    )
    EmojiStatusMark(
        file = file,
        size = 18.dp,
        modifier = Modifier.offset(y = -ChatTitleMarkLift),
    )
}
