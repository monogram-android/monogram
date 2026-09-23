package org.monogram.core.database

import org.monogram.core.database.entity.ChatEntity
import org.monogram.core.database.entity.FolderEntity
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.PeerEntity
import org.monogram.core.database.entity.ProfileCommonEntity
import org.monogram.core.database.entity.ProfileMediaEntity
import org.monogram.core.database.entity.ProfileMemberEntity
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileExtras
import org.monogram.core.models.Checklists
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.TextEntities
import org.monogram.core.models.preferredPeerTitle
import org.monogram.core.models.isForcedVerifiedChat

fun ChatEntity.toModel(): Chat = Chat(
    id = PeerId(id),
    title = title,
    isChannel = isChannel,
    isGroup = isGroup,
    isForum = isForum,
    left = left,
    unreadCount = unreadCount,
    lastMessagePreview = lastMessagePreview,
    lastMessageOutgoing = lastMessageOutgoing,
    lastMessageSenderName = lastMessageSenderName,
    lastMessageMediaKind = lastMessageMediaKind,
    lastMessageDate = lastMessageDate,
    photoCacheKey = photoCacheKey,
    archived = archived,
    muted = muted,
    muteOverride = muteOverride,
    unreadMark = unreadMark,
    unreadMentionsCount = unreadMentionsCount,
    unreadReactionsCount = unreadReactionsCount,
    isContact = isContact,
    isBot = isBot,
    isVerified = isVerified || isForcedVerifiedChat(id),
    pinned = pinned,
    pinnedOrder = pinnedOrder,
    readInboxMaxId = readInboxMaxId,
    readOutboxMaxId = readOutboxMaxId,
    peerStatus = peerStatus,
    peerStatusAt = peerStatusAt,
    lastMediaThumbCacheKey = lastMediaThumbCacheKey,
    lastMessageId = lastMessageId,
    dialogScrollMessageId = dialogScrollMessageId,
    canView = canView,
    canSendPlain = canSendPlain,
    canSendPhotos = canSendPhotos,
    canForward = canForward,
    canDeleteOthers = canDeleteOthers,
    emojiStatusDocumentId = emojiStatusDocumentId,
)

fun Chat.toEntity(): ChatEntity = ChatEntity(
    id = id.value,
    title = title,
    isChannel = isChannel,
    isGroup = isGroup,
    isForum = isForum,
    left = left,
    unreadCount = unreadCount,
    lastMessagePreview = lastMessagePreview,
    lastMessageOutgoing = lastMessageOutgoing,
    lastMessageSenderName = lastMessageSenderName,
    lastMessageMediaKind = lastMessageMediaKind,
    lastMessageDate = lastMessageDate,
    photoCacheKey = photoCacheKey,
    archived = archived,
    muted = muted,
    muteOverride = muteOverride,
    unreadMark = unreadMark,
    unreadMentionsCount = unreadMentionsCount,
    unreadReactionsCount = unreadReactionsCount,
    isContact = isContact,
    isBot = isBot,
    isVerified = isVerified,
    pinned = pinned,
    pinnedOrder = pinnedOrder,
    readInboxMaxId = readInboxMaxId,
    readOutboxMaxId = readOutboxMaxId,
    peerStatus = peerStatus,
    peerStatusAt = peerStatusAt,
    lastMediaThumbCacheKey = lastMediaThumbCacheKey,
    lastMessageId = lastMessageId,
    dialogScrollMessageId = dialogScrollMessageId,
    canView = canView,
    canSendPlain = canSendPlain,
    canSendPhotos = canSendPhotos,
    canForward = canForward,
    canDeleteOthers = canDeleteOthers,
    emojiStatusDocumentId = emojiStatusDocumentId,
)

fun FolderEntity.toModel(): Folder = Folder(
    id = id,
    title = title,
    emoticon = emoticon,
    chatIds = parsePeerIds(chatIdsCsv),
    pinnedChatIds = parsePeerIds(pinnedChatIdsCsv),
    includeContacts = includeContacts,
    includeNonContacts = includeNonContacts,
    includeGroups = includeGroups,
    includeChannels = includeChannels,
    includeBots = includeBots,
    excludeChatIds = parsePeerIds(excludeChatIdsCsv),
    excludeMuted = excludeMuted,
    excludeRead = excludeRead,
    excludeArchived = excludeArchived,
)

fun Folder.toEntity(position: Int = 0): FolderEntity = FolderEntity(
    id = id,
    title = title,
    emoticon = emoticon,
    chatIdsCsv = peerIdsCsv(chatIds),
    pinnedChatIdsCsv = peerIdsCsv(pinnedChatIds),
    includeContacts = includeContacts,
    includeNonContacts = includeNonContacts,
    includeGroups = includeGroups,
    includeChannels = includeChannels,
    includeBots = includeBots,
    excludeChatIdsCsv = peerIdsCsv(excludeChatIds),
    excludeMuted = excludeMuted,
    excludeRead = excludeRead,
    excludeArchived = excludeArchived,
    position = position,
)

fun MessageEntity.toModel(): Message = Message(
    id = MessageId(chatId = PeerId(chatId), id = id),
    senderId = senderId?.let(::PeerId),
    text = text,
    date = date,
    editDate = editDate,
    outgoing = outgoing,
    mediaCacheKey = mediaCacheKey,
    mediaKind = mediaKind,
    thumbCacheKey = thumbCacheKey,
    mediaDuration = mediaDuration,
    mediaWidth = mediaWidth,
    mediaHeight = mediaHeight,
    groupedId = groupedId,
    fileName = fileName,
    fileSize = fileSize,
    supportsStreaming = supportsStreaming,
    reactionsJson = reactionsJson,
    repliesCount = repliesCount,
    discussionPeerId = discussionPeerId,
    replyQuote = replyQuote,
    entities = TextEntities.parse(entitiesJson),
    replyToMsgId = replyToMsgId,
    fwdFrom = fwdFrom,
    fwdFromId = fwdFromId,
    fwdDate = fwdDate,
    viaBot = viaBot,
    senderName = senderName,
    replyMarkup = ReplyMarkups.parse(replyMarkupJson),
    pending = pending,
    randomId = randomId,
    checklist = Checklists.parse(checklistJson),
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    chatId = id.chatId.value,
    id = id.id,
    senderId = senderId?.value,
    text = text,
    date = date,
    editDate = editDate,
    outgoing = outgoing,
    mediaCacheKey = mediaCacheKey,
    mediaKind = mediaKind,
    thumbCacheKey = thumbCacheKey,
    mediaDuration = mediaDuration,
    mediaWidth = mediaWidth,
    mediaHeight = mediaHeight,
    groupedId = groupedId,
    fileName = fileName,
    fileSize = fileSize,
    supportsStreaming = supportsStreaming,
    reactionsJson = reactionsJson,
    repliesCount = repliesCount,
    discussionPeerId = discussionPeerId,
    replyQuote = replyQuote,
    entitiesJson = TextEntities.serialize(entities),
    replyToMsgId = replyToMsgId,
    fwdFrom = fwdFrom,
    fwdFromId = fwdFromId,
    fwdDate = fwdDate,
    viaBot = viaBot,
    senderName = senderName,
    replyMarkupJson = ReplyMarkups.serialize(replyMarkup),
    pending = pending,
    randomId = randomId,
    checklistJson = Checklists.serialize(checklist),
)

fun PeerEntity.toProfile(): Profile {
    val extra = ProfileExtras.parse(extraJson)
    return ProfileExtras.applyTo(
        Profile(
            id = PeerId(id),
            kind = kind,
            title = title,
            username = username,
            about = about,
            avatarCacheKey = avatarCacheKey,
            isSelf = isSelf,
            status = status,
            statusAt = statusAt,
            emojiStatusDocumentId = emojiStatusDocumentId,
        ),
        extra,
    )
}

fun Profile.toPeerEntity(prior: PeerEntity?): PeerEntity {
    val extra = ProfileExtras.mergeFull(
        ProfileExtras.parse(prior?.extraJson),
        ProfileExtras.fromProfile(this),
    )
    val kind = when {
        this.kind.isNotBlank() &&
            (prior?.kind == "channel" || prior?.kind == "group" || prior?.kind == "chat") &&
            this.kind == "user" -> prior.kind
        this.kind.isNotBlank() -> this.kind
        else -> prior?.kind ?: "user"
    }
    return PeerEntity(
        id = id.value,
        kind = kind,
        title = preferredPeerTitle(title, prior?.title, id.value),
        username = username ?: prior?.username,
        about = about ?: prior?.about,
        avatarCacheKey = avatarCacheKey ?: prior?.avatarCacheKey,
        status = status ?: prior?.status,
        statusAt = statusAt ?: prior?.statusAt,
        emojiStatusDocumentId = emojiStatusDocumentId ?: prior?.emojiStatusDocumentId,
        extraJson = ProfileExtras.serialize(extra),
        isSelf = isSelf || prior?.isSelf == true,
    )
}

fun ProfileMember.toEntity(peerId: Long, position: Int, totalCount: Int = 0): ProfileMemberEntity =
    ProfileMemberEntity(
        peerId = peerId,
        userId = id.value,
        position = position,
        title = title,
        username = username,
        avatarCacheKey = avatarCacheKey,
        status = status,
        statusAt = statusAt,
        role = role,
        rank = rank,
        totalCount = totalCount,
    )

fun ProfileMemberEntity.toModel(): ProfileMember = ProfileMember(
    id = PeerId(userId),
    title = title,
    username = username,
    avatarCacheKey = avatarCacheKey,
    status = status,
    statusAt = statusAt,
    role = role,
    rank = rank,
)

fun Message.toProfileMediaEntity(peerId: Long, tab: String, position: Int): ProfileMediaEntity =
    ProfileMediaEntity(
        peerId = peerId,
        tab = tab,
        messageId = id.id,
        position = position,
        chatId = id.chatId.value,
        text = text,
        date = date,
        outgoing = outgoing,
        mediaCacheKey = mediaCacheKey,
        mediaKind = mediaKind,
        thumbCacheKey = thumbCacheKey,
        fileName = fileName,
        fileSize = fileSize,
    )

fun ProfileMediaEntity.toMessage(): Message = Message(
    id = MessageId(chatId = PeerId(chatId), id = messageId),
    senderId = null,
    text = text,
    date = date,
    outgoing = outgoing,
    mediaCacheKey = mediaCacheKey,
    mediaKind = mediaKind,
    thumbCacheKey = thumbCacheKey,
    fileName = fileName,
    fileSize = fileSize,
)

fun Chat.toProfileCommonEntity(peerId: Long, position: Int): ProfileCommonEntity =
    ProfileCommonEntity(
        peerId = peerId,
        chatId = id.value,
        position = position,
        title = title,
        isChannel = isChannel,
        isGroup = isGroup,
        photoCacheKey = photoCacheKey,
    )

fun ProfileCommonEntity.toChat(): Chat = Chat(
    id = PeerId(chatId),
    title = title,
    isChannel = isChannel,
    isGroup = isGroup,
    photoCacheKey = photoCacheKey,
)

private fun parsePeerIds(csv: String): List<PeerId> =
    csv.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull()?.let(::PeerId) }

private fun peerIdsCsv(ids: List<PeerId>): String =
    ids.joinToString(",") { it.value.toString() }
