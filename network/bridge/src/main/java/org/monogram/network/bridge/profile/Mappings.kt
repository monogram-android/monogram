package org.monogram.network.bridge.profile

import org.monogram.core.models.BotCallbackAnswer
import org.monogram.core.models.Chat
import org.monogram.core.models.ContactsSearch
import org.monogram.core.models.GlobalMessageSearch
import org.monogram.core.models.InlineBotResult
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileExtras
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileMemberPage
import org.monogram.core.models.ResolvedPeer
import org.monogram.core.models.SearchPeer
import org.monogram.core.models.StickerCatalog
import org.monogram.core.models.StickerList
import org.monogram.core.models.StickerPack
import uniffi.monogram_mtproto.ProfileDto
import org.monogram.network.bridge.message.toModel as toMessageModel
import org.monogram.core.models.isForcedVerifiedUser

internal fun ProfileDto.toModel(): Profile {
    val extra = ProfileExtras.parse(extraJson)
    return Profile(
        id = PeerId(id),
        kind = kind,
        title = title,
        username = username,
        about = about,
        avatarCacheKey = avatarCacheKey,
        isSelf = isSelf,
        status = status,
        statusAt = statusAt,
        phone = extra.phone,
        membersCount = extra.membersCount,
        onlineCount = extra.onlineCount,
        commonChatsCount = extra.commonChatsCount,
        isBot = extra.isBot,
        isVerified = extra.isVerified || isForcedVerifiedUser(id),
        isScam = extra.isScam,
        isPremium = extra.isPremium,
        emojiStatusDocumentId = extra.emojiStatusDocumentId,
        canViewParticipants = extra.canViewParticipants,
    )
}

internal fun parseProfileMembers(json: String): ProfileMemberPage {
    val root = org.json.JSONObject(json)
    val members = root.optJSONArray("members")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", 0L)
            if (id == 0L) return@mapNotNull null
            ProfileMember(
                id = PeerId(item.optLong("peer", id)),
                title = item.optString("title").ifBlank { id.toString() },
                username = item.optString("username").takeIf { it.isNotBlank() },
                avatarCacheKey = item.optString("avatar").takeIf { it.isNotBlank() },
                status = item.optString("status").takeIf { it.isNotBlank() },
                statusAt = item.optLong("status_at", 0L).takeIf { item.has("status_at") },
                role = item.optString("role").ifBlank { ProfileMember.ROLE_MEMBER },
                rank = item.optString("rank").takeIf { it.isNotBlank() },
            )
        }
    } ?: emptyList()
    return ProfileMemberPage(
        count = root.optInt("count", members.size),
        members = members,
    )
}

internal fun parseCommonChats(json: String): List<Chat> {
    val root = org.json.JSONObject(json)
    val chats = root.optJSONArray("chats") ?: return emptyList()
    return (0 until chats.length()).mapNotNull { index ->
        val item = chats.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optLong("id", 0L)
        if (id == 0L) return@mapNotNull null
        val kind = item.optString("kind")
        Chat(
            id = PeerId(id),
            title = item.optString("title").ifBlank { id.toString() },
            isChannel = kind == "channel",
            isGroup = kind == "group" || kind == "chat",
            photoCacheKey = item.optString("avatar").takeIf { it.isNotBlank() },
        )
    }
}

internal fun uniffi.monogram_mtproto.StickerPackDto.toModel(): StickerPack = StickerPack(
    id = id,
    title = title,
    shortName = shortName,
    count = count,
    isEmoji = isEmoji,
    previewDocumentIds = previewDocumentIds,
    accessHash = accessHash,
)

internal fun uniffi.monogram_mtproto.StickerCatalogDto.toModel() = StickerCatalog(
    hash = hash,
    notModified = notModified,
    sets = sets.map { it.toModel() },
)

internal fun uniffi.monogram_mtproto.StickerListDto.toModel() = StickerList(
    hash = hash,
    notModified = notModified,
    documentIds = documentIds,
)

internal fun uniffi.monogram_mtproto.ResolvedPeerDto.toModel() = ResolvedPeer(
    peerId = PeerId(peerId),
    username = username,
    title = title,
    isBot = isBot,
)

internal fun uniffi.monogram_mtproto.InlineBotResultDto.toModel() = InlineBotResult(
    id = id,
    kind = kind,
    title = title,
    description = description,
    url = url,
    documentId = documentId,
    thumbCacheKey = thumbCacheKey,
)

internal fun uniffi.monogram_mtproto.BotCallbackAnswerDto.toModel() = BotCallbackAnswer(
    alert = alert,
    message = message,
    url = url,
    cacheTime = cacheTime,
)

internal fun uniffi.monogram_mtproto.InlineBotResultsDto.toModel() = InlineBotResults(
    queryId = queryId,
    gallery = gallery,
    nextOffset = nextOffset,
    cacheTime = cacheTime,
    results = results.map { it.toModel() },
)

internal fun uniffi.monogram_mtproto.SearchPeerDto.toModel() = SearchPeer(
    id = PeerId(peerId),
    title = title,
    username = username,
    kind = kind,
    isBot = isBot,
    isGroup = isGroup,
    isChannel = isChannel,
)

internal fun uniffi.monogram_mtproto.ContactsSearchDto.toModel() = ContactsSearch(
    people = people.map { it.toModel() },
    chats = chats.map { it.toModel() },
)

internal fun uniffi.monogram_mtproto.GlobalMessageSearchDto.toModel() = GlobalMessageSearch(
    messages = messages.map { it.toMessageModel() },
    nextRate = nextRate,
    nextPeerId = PeerId(nextPeerId),
    nextOffsetId = nextOffsetId,
)
