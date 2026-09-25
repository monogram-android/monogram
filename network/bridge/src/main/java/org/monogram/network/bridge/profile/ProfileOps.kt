package org.monogram.network.bridge.profile

import org.monogram.core.common.Outcome
import org.monogram.core.models.BotCallbackAnswer
import org.monogram.core.models.Chat
import org.monogram.core.models.ContactsSearch
import org.monogram.core.models.GlobalMessageSearch
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMemberPage
import org.monogram.core.models.ProfileTab
import org.monogram.core.models.ProfileTabCounts
import org.monogram.core.models.ResolvedPeer
import org.monogram.core.models.StickerCatalog
import org.monogram.core.models.StickerList
import org.monogram.core.models.StickerPack

interface ProfileOps {
    suspend fun getProfile(peerId: PeerId): Outcome<Profile>
    suspend fun animatedEmojiMax(): Outcome<Int> = Outcome.Ok(0)
    suspend fun customEmojiIsFree(documentId: Long): Outcome<Boolean> = Outcome.Ok(false)

    /** Per-tab shared-media result counts from `messages.getSearchCounters`. */
    suspend fun getProfileTabCounts(
        peerId: PeerId,
        tabs: List<ProfileTab> = ProfileTab.entries,
    ): Outcome<ProfileTabCounts> = Outcome.Err("unsupported")

    /** Paged group members or channel participants with owner/admin/rank roles. */
    suspend fun getProfileMembers(
        peerId: PeerId,
        filter: String = "recent",
        query: String = "",
        offset: Int = 0,
        limit: Int = 50,
    ): Outcome<ProfileMemberPage> = Outcome.Err("unsupported")

    /** Groups and channels shared with a user (`messages.getCommonChats`). */
    suspend fun getCommonChats(
        userId: PeerId,
        maxId: Long = 0,
        limit: Int = 50
    ): Outcome<List<Chat>> =
        Outcome.Err("unsupported")

    suspend fun contactsSearch(query: String, limit: Int = 20): Outcome<ContactsSearch> =
        Outcome.Err("unsupported")

    suspend fun searchGlobal(
        query: String,
        offsetRate: Int = 0,
        offsetPeerId: PeerId = PeerId(0),
        offsetId: Int = 0,
        limit: Int = 40,
        folderId: Int = 0,
    ): Outcome<GlobalMessageSearch> = Outcome.Err("unsupported")

    suspend fun resolveUsername(username: String): Outcome<ResolvedPeer> =
        Outcome.Err("unsupported")

    suspend fun getInlineBotResults(
        chatId: PeerId,
        botId: PeerId,
        query: String,
        offset: String = "",
    ): Outcome<InlineBotResults> = Outcome.Err("unsupported")

    suspend fun sendInlineBotResult(
        chatId: PeerId,
        queryId: Long,
        resultId: String,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): Outcome<Message> = Outcome.Err("unsupported")

    suspend fun getBotCallbackAnswer(
        chatId: PeerId,
        messageId: Int,
        dataHex: String,
    ): Outcome<BotCallbackAnswer> = Outcome.Err("unsupported")

    suspend fun getStickerPack(documentId: Long): Outcome<StickerPack> =
        Outcome.Err("unsupported")

    suspend fun getStickerSet(setId: Long, accessHash: Long): Outcome<StickerPack> =
        Outcome.Err("unsupported")

    suspend fun getAllStickers(hash: Long = 0L): Outcome<StickerCatalog> =
        Outcome.Err("unsupported")

    suspend fun getEmojiStickers(hash: Long = 0L): Outcome<StickerCatalog> =
        Outcome.Err("unsupported")

    suspend fun getStickers(emoticon: String, hash: Long = 0L): Outcome<StickerList> =
        Outcome.Err("unsupported")
}
