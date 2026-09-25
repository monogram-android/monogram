package org.monogram.network.bridge.profile

import org.monogram.core.common.AppLog
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
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.bridge.message.toModel as toMessageModel

internal class ProfileApi(private val core: SessionCore) : ProfileOps {
    override suspend fun contactsSearch(
        query: String,
        limit: Int,
    ): Outcome<ContactsSearch> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("contactsSearch failed") { activeHandle ->
            core.native.contactsSearch(activeHandle, query, limit).toModel()
        }
    }

    override suspend fun searchGlobal(
        query: String,
        offsetRate: Int,
        offsetPeerId: PeerId,
        offsetId: Int,
        limit: Int,
        folderId: Int,
    ): Outcome<GlobalMessageSearch> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("searchGlobal failed") { activeHandle ->
            core.native.searchGlobal(
                activeHandle,
                query,
                offsetRate,
                offsetPeerId.value,
                offsetId,
                limit,
                folderId,
            ).toModel()
        }
    }

    override suspend fun getProfile(peerId: PeerId): Outcome<Profile> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getProfile failed") { activeHandle ->
            core.native.getProfile(
                activeHandle,
                peerId.value
            ).toModel()
        }
    }

    override suspend fun animatedEmojiMax(): Outcome<Int> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("animatedEmojiMax failed") { activeHandle ->
            core.native.animatedEmojiMax(
                activeHandle
            )
        }
    }

    override suspend fun customEmojiIsFree(documentId: Long): Outcome<Boolean> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("customEmojiIsFree failed") { activeHandle ->
            core.native.customEmojiIsFree(activeHandle, documentId)
        }
    }

    override suspend fun getProfileTabCounts(
        peerId: PeerId,
        tabs: List<ProfileTab>,
    ): Outcome<ProfileTabCounts> {
        if (tabs.isEmpty()) return Outcome.Ok(ProfileTabCounts())
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getProfileTabCounts failed") { activeHandle ->
            val raw =
                core.native.getSearchCounters(activeHandle, peerId.value, tabs.map { it.wire })
            val json = org.json.JSONObject(raw)
            val counts = LinkedHashMap<ProfileTab, Int>()
            val inexact = LinkedHashSet<ProfileTab>()
            json.keys().forEach { key ->
                val tab = ProfileTab.fromWire(key) ?: return@forEach
                val entry = json.optJSONObject(key) ?: return@forEach
                counts[tab] = entry.optInt("count", 0)
                if (entry.optBoolean("inexact", false)) inexact += tab
            }
            AppLog.api("getProfileTabCounts", "ok filters=${counts.size} bytes=${raw.length}")
            ProfileTabCounts(counts = counts, inexact = inexact)
        }
    }

    override suspend fun getProfileMembers(
        peerId: PeerId,
        filter: String,
        query: String,
        offset: Int,
        limit: Int,
    ): Outcome<ProfileMemberPage> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getProfileMembers failed") { activeHandle ->
            val raw = core.native.getParticipants(
                activeHandle,
                peerId.value,
                filter,
                query,
                offset,
                limit
            )
            parseProfileMembers(raw).also { page ->
                AppLog.api("getProfileMembers", "ok count=${page.members.size} total=${page.count}")
            }
        }
    }

    override suspend fun getCommonChats(
        userId: PeerId,
        maxId: Long,
        limit: Int,
    ): Outcome<List<Chat>> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getCommonChats failed") { activeHandle ->
            val raw = core.native.getCommonChats(activeHandle, userId.value, maxId, limit)
            parseCommonChats(raw).also { chats ->
                AppLog.api("getCommonChats", "ok count=${chats.size} bytes=${raw.length}")
            }
        }
    }

    override suspend fun getStickerPack(documentId: Long): Outcome<StickerPack> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getStickerPack failed") { activeHandle ->
            core.native.getStickerPack(activeHandle, documentId).toModel()
        }
    }

    override suspend fun getStickerSet(setId: Long, accessHash: Long): Outcome<StickerPack> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getStickerSet failed") { activeHandle ->
            core.native.getStickerSet(activeHandle, setId, accessHash).toModel()
        }
    }

    override suspend fun getAllStickers(hash: Long): Outcome<StickerCatalog> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getAllStickers failed") { activeHandle ->
            core.native.getAllStickers(activeHandle, hash).toModel()
        }
    }

    override suspend fun getEmojiStickers(hash: Long): Outcome<StickerCatalog> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpcBackground("getEmojiStickers failed") { activeHandle ->
            core.native.getEmojiStickers(activeHandle, hash).toModel()
        }
    }

    override suspend fun getStickers(
        emoticon: String,
        hash: Long,
    ): Outcome<StickerList> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getStickers failed") { activeHandle ->
            core.native.getStickers(activeHandle, emoticon, hash).toModel()
        }
    }

    override suspend fun resolveUsername(
        username: String,
    ): Outcome<ResolvedPeer> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("resolveUsername failed") { activeHandle ->
            core.native.resolveUsername(activeHandle, username).toModel()
        }
    }

    override suspend fun getInlineBotResults(
        chatId: PeerId,
        botId: PeerId,
        query: String,
        offset: String,
    ): Outcome<InlineBotResults> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getInlineBotResults failed") { activeHandle ->
            core.native.getInlineBotResults(
                activeHandle,
                chatId.value,
                botId.value,
                query,
                offset,
            ).toModel()
        }
    }

    override suspend fun sendInlineBotResult(
        chatId: PeerId,
        queryId: Long,
        resultId: String,
        replyToMsgId: Int,
        topMsgId: Int,
    ): Outcome<Message> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("sendInlineBotResult failed") { activeHandle ->
            core.native.sendInlineBotResult(
                activeHandle,
                chatId.value,
                queryId,
                resultId,
                replyToMsgId,
                topMsgId,
            ).toMessageModel()
        }
    }

    override suspend fun getBotCallbackAnswer(
        chatId: PeerId,
        messageId: Int,
        dataHex: String,
    ): Outcome<BotCallbackAnswer> {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return core.rpc("getBotCallbackAnswer failed") { activeHandle ->
            core.native.getBotCallbackAnswer(activeHandle, chatId.value, messageId, dataHex)
                .toModel()
        }
    }
}
