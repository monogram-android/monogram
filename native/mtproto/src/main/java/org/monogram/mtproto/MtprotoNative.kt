package org.monogram.mtproto

import uniffi.monogram_mtproto.AuthCodeSent
import uniffi.monogram_mtproto.AuthSignedIn
import uniffi.monogram_mtproto.BotCallbackAnswerDto
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.ContactsSearchDto
import uniffi.monogram_mtproto.DiscussionDto
import uniffi.monogram_mtproto.FolderDto
import uniffi.monogram_mtproto.ForumTopicsPageDto
import uniffi.monogram_mtproto.GlobalMessageSearchDto
import uniffi.monogram_mtproto.InlineBotResultsDto
import uniffi.monogram_mtproto.InstantViewDto
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.NotifyExceptionDto
import uniffi.monogram_mtproto.NotifySettingsDto
import uniffi.monogram_mtproto.OutboxReadDto
import uniffi.monogram_mtproto.ProfileDto
import uniffi.monogram_mtproto.ReadParticipantsDto
import uniffi.monogram_mtproto.ReadReceiptConfigDto
import uniffi.monogram_mtproto.ReactionChoiceDto
import uniffi.monogram_mtproto.ResolvedPeerDto
import uniffi.monogram_mtproto.SavedGifDto
import uniffi.monogram_mtproto.StickerCatalogDto
import uniffi.monogram_mtproto.StickerListDto
import uniffi.monogram_mtproto.StickerPackDto
import uniffi.monogram_mtproto.UpdateEventDto
import uniffi.monogram_mtproto.UpdatesStateDto
import uniffi.monogram_mtproto.UploadItemDto
import uniffi.monogram_mtproto.ReactionPeersDto
import uniffi.monogram_mtproto.PollVotersDto

/**
 * Low-level UniFFI surface backed by the Tellers MTProto runtime.
 */
interface MtprotoNative {
    fun createRequestControl(): Long = 0L
    fun bindRequestControl(id: Long): Long = 0L
    fun cancelRequestControl(id: Long) = Unit
    fun releaseRequestControl(id: Long) = Unit

    /** Dispatch class for the request on this thread. */
    fun setDispatchClass(classId: Int) = Unit

    /** Applies the "Faster downloads" setting: media lanes and parts in flight. */
    fun setDownloadConcurrency(lanes: Int, parts: Int) = Unit

    /** Effective `[lanes, parts]` after native clamping. */
    fun downloadConcurrency(): List<Int> = listOf(0, 0)

    /** Upload/download part size in KiB. 32 is default; 512 speeds up large sends. */
    fun setFilePartKib(kib: Int) = Unit
    fun libraryVersion(): String

    /** Switches Rust netcode timing spans on or off (off by default). */
    fun perfSetEnabled(enabled: Boolean) = Unit

    /** JSON timing snapshot of the Rust side; `reset` clears the window. */
    fun perfSnapshot(reset: Boolean): String = "{}"

    fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long

    @Throws(MtprotoException::class)
    fun setTestDc(handle: Long, enabled: Boolean) = Unit

    @Throws(MtprotoException::class)
    fun isAuthorized(handle: Long): Boolean

    @Throws(MtprotoException::class)
    fun connect(handle: Long)

    fun destroyClient(handle: Long)

    fun clientExists(handle: Long): Boolean = false

    fun clientApiId(handle: Long): Int = 0

    @Throws(MtprotoException::class)
    fun sendAuthCode(handle: Long, phone: String): AuthCodeSent

    @Throws(MtprotoException::class)
    fun resendAuthCode(handle: Long, phone: String, phoneCodeHash: String): AuthCodeSent =
        sendAuthCode(handle, phone)

    @Throws(MtprotoException::class)
    fun signIn(
        handle: Long,
        phone: String,
        phoneCodeHash: String,
        phoneCode: String,
    ): AuthSignedIn

    @Throws(MtprotoException::class)
    fun checkPassword(handle: Long, password: String): AuthSignedIn

    @Throws(MtprotoException::class)
    fun logout(handle: Long)

    @Throws(MtprotoException::class)
    fun getChats(handle: Long): List<ChatDto>

    fun getWallpapers(handle: Long, hash: Long): uniffi.monogram_mtproto.WallpaperCatalogDto =
        error("Wallpaper catalog unavailable")

    fun downloadWallpaper(handle: Long, id: Long, accessHash: Long, destPath: String): String =
        error("Wallpaper download unavailable")

    @Throws(MtprotoException::class)
    fun getWebPage(handle: Long, url: String, hash: Int): InstantViewDto =
        throw MtprotoException.Message("instant view requires native rebuild")

    @Throws(MtprotoException::class)
    fun getFolders(handle: Long): List<FolderDto>

    @Throws(MtprotoException::class)
    fun updateFolder(handle: Long, folder: FolderDto): Unit =
        throw MtprotoException.Message("update folder requires native rebuild")

    @Throws(MtprotoException::class)
    fun deleteFolder(handle: Long, id: Int): Unit =
        throw MtprotoException.Message("delete folder requires native rebuild")

    @Throws(MtprotoException::class)
    fun updateFolderOrder(handle: Long, order: List<Int>): Unit =
        throw MtprotoException.Message("folder order requires native rebuild")

    @Throws(MtprotoException::class)
    fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto>

    @Throws(MtprotoException::class)
    fun getHistoryPage(
        handle: Long,
        chatId: Long,
        limit: Int,
        offsetId: Int,
        offsetDate: Int,
        addOffset: Int,
    ): List<MessageDto>

    @Throws(MtprotoException::class)
    fun getReplies(
        handle: Long,
        chatId: Long,
        msgId: Int,
        limit: Int,
        offsetId: Int,
        addOffset: Int,
    ): List<MessageDto> =
        throw MtprotoException.Message("replies require native rebuild")

    @Throws(MtprotoException::class)
    fun getForumTopics(
        handle: Long,
        chatId: Long,
        offsetDate: Int,
        offsetId: Int,
        offsetTopic: Int,
        limit: Int,
    ): ForumTopicsPageDto =
        throw MtprotoException.Message("forum topics require native rebuild")

    @Throws(MtprotoException::class)
    fun getForumTopicsById(
        handle: Long,
        chatId: Long,
        topicIds: List<Int>,
    ): ForumTopicsPageDto =
        throw MtprotoException.Message("forum topics require native rebuild")

    @Throws(MtprotoException::class)
    fun loadMoreChats(
        handle: Long,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
        folderId: Int,
    ): List<ChatDto>

    @Throws(MtprotoException::class)
    fun searchMessages(handle: Long, chatId: Long, query: String, limit: Int): List<MessageDto>

    @Throws(MtprotoException::class)
    fun searchMessagesFiltered(
        handle: Long,
        chatId: Long,
        query: String,
        filter: String,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
    ): List<MessageDto> = throw MtprotoException.Message("filtered search requires native rebuild")

    @Throws(MtprotoException::class)
    fun contactsSearch(handle: Long, query: String, limit: Int): ContactsSearchDto =
        throw MtprotoException.Message("contacts search requires native rebuild")

    @Throws(MtprotoException::class)
    fun searchGlobal(
        handle: Long,
        query: String,
        offsetRate: Int,
        offsetPeerId: Long,
        offsetId: Int,
        limit: Int,
    ): GlobalMessageSearchDto =
        throw MtprotoException.Message("global search requires native rebuild")

    @Throws(MtprotoException::class)
    fun getPinnedMessages(handle: Long, chatId: Long, limit: Int): List<MessageDto>

    @Throws(MtprotoException::class)
    fun getMessageReadParticipants(
        handle: Long,
        chatId: Long,
        msgId: Int,
    ): ReadParticipantsDto = throw MtprotoException.Message("read participants require native rebuild")

    @Throws(MtprotoException::class)
    fun getMessageReactionsList(
        handle: Long,
        chatId: Long,
        messageId: Int,
    ): ReactionPeersDto = throw MtprotoException.Message("reaction list requires native rebuild")

    @Throws(MtprotoException::class)
    fun getPollVotes(
        handle: Long,
        chatId: Long,
        messageId: Int,
    ): PollVotersDto = throw MtprotoException.Message("poll votes require native rebuild")

    @Throws(MtprotoException::class)
    fun getOutboxReadDate(
        handle: Long,
        chatId: Long,
        msgId: Int,
    ): OutboxReadDto = throw MtprotoException.Message("outbox read date requires native rebuild")

    @Throws(MtprotoException::class)
    fun getReadReceiptConfig(handle: Long): ReadReceiptConfigDto =
        ReadReceiptConfigDto(
            chatReadMarkSizeThreshold = 100,
            chatReadMarkExpirePeriod = 604800,
            pmReadDateExpirePeriod = 604800,
            fromServer = false,
        )

    @Throws(MtprotoException::class)
    fun sendTextMessage(
        handle: Long,
        chatId: Long,
        text: String,
        replyToMsgId: Int = 0,
        entitiesJson: String? = null,
        topMsgId: Int = 0,
    ): MessageDto

    @Throws(MtprotoException::class)
    fun sendPhotoMessage(
        handle: Long,
        chatId: Long,
        path: String,
        caption: String,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
        entitiesJson: String? = null,
    ): MessageDto

    @Throws(MtprotoException::class)
    fun sendUploadedMedia(
        handle: Long,
        chatId: Long,
        item: UploadItemDto,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
        entitiesJson: String? = null,
    ): MessageDto = throw MtprotoException.Message("upload requires native rebuild")

    @Throws(MtprotoException::class)
    fun sendUploadedAlbum(
        handle: Long,
        chatId: Long,
        items: List<UploadItemDto>,
        replyToMsgId: Int = 0,
        topMsgId: Int = 0,
    ): List<MessageDto> = throw MtprotoException.Message("albums require native rebuild")

    @Throws(MtprotoException::class)
    fun editTextMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        text: String,
        entitiesJson: String? = null,
    ): MessageDto

    @Throws(MtprotoException::class)
    fun deleteMessage(handle: Long, chatId: Long, messageId: Int, revoke: Boolean)

    @Throws(MtprotoException::class)
    fun forwardMessages(
        handle: Long,
        fromChatId: Long,
        messageId: Int,
        toChatId: Long,
    ): List<MessageDto>

    @Throws(MtprotoException::class)
    fun readHistory(handle: Long, chatId: Long, maxId: Int)

    /** `messages.markDialogUnread`: toggles the manual unread mark on a dialog. */
    @Throws(MtprotoException::class)
    fun markDialogUnread(handle: Long, chatId: Long, unread: Boolean)

    /** `messages.getUnreadMentions` */
    @Throws(MtprotoException::class)
    fun getUnreadMentions(
        handle: Long,
        chatId: Long,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
        topMsgId: Int,
    ): List<MessageDto> =
        throw MtprotoException.Message("unread mentions require native rebuild")

    /** `messages.readMentions` */
    @Throws(MtprotoException::class)
    fun readMentions(handle: Long, chatId: Long, topMsgId: Int): Unit =
        throw MtprotoException.Message("unread mentions require native rebuild")

    /** `messages.getUnreadReactions` */
    @Throws(MtprotoException::class)
    fun getUnreadReactions(
        handle: Long,
        chatId: Long,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
        topMsgId: Int,
    ): List<MessageDto> =
        throw MtprotoException.Message("unread reactions require native rebuild")

    /** `messages.readReactions` */
    @Throws(MtprotoException::class)
    fun readReactions(handle: Long, chatId: Long, topMsgId: Int): Unit =
        throw MtprotoException.Message("unread reactions require native rebuild")

    @Throws(MtprotoException::class)
    fun readDiscussion(handle: Long, chatId: Long, msgId: Int, readMaxId: Int): Unit =
        throw MtprotoException.Message("forum topics require native rebuild")

    @Throws(MtprotoException::class)
    fun setTyping(handle: Long, chatId: Long, typing: Boolean)

    @Throws(MtprotoException::class)
    fun updateStatus(handle: Long, offline: Boolean)

    @Throws(MtprotoException::class)
    fun registerDevice(
        handle: Long,
        tokenType: Int,
        token: String,
        secret: ByteArray,
        noMuted: Boolean,
        appSandbox: Boolean,
        otherUids: List<Long> = emptyList(),
    )

    @Throws(MtprotoException::class)
    fun unregisterDevice(
        handle: Long,
        tokenType: Int,
        token: String,
        otherUids: List<Long> = emptyList(),
    )

    @Throws(MtprotoException::class)
    fun getNotifySettings(handle: Long, peerKind: String, chatId: Long): NotifySettingsDto

    @Throws(MtprotoException::class)
    fun updateNotifySettings(
        handle: Long,
        peerKind: String,
        chatId: Long,
        showPreviews: Boolean,
        silent: Boolean,
        muteUntil: Int,
        storiesMuted: Boolean,
        sound: String,
    )

    @Throws(MtprotoException::class)
    fun resetNotifySettings(handle: Long)

    @Throws(MtprotoException::class)
    fun setContactJoinedSilent(handle: Long, silent: Boolean)

    @Throws(MtprotoException::class)
    fun getNotifyExceptions(handle: Long, compareSound: Boolean): List<NotifyExceptionDto>

    @Throws(MtprotoException::class)
    fun decryptPushPayload(secret: ByteArray, payload: String): String

    @Throws(MtprotoException::class)
    fun getProfile(handle: Long, peerId: Long): ProfileDto

    @Throws(MtprotoException::class)
    fun getGroupAdminTags(handle: Long, chatId: Long): String

    /** `messages.getSearchCounters` counts per filter, as compact JSON. */
    @Throws(MtprotoException::class)
    fun getSearchCounters(handle: Long, chatId: Long, filters: List<String>): String

    /** Participant page with roles, as compact JSON. */
    @Throws(MtprotoException::class)
    fun getParticipants(
        handle: Long,
        chatId: Long,
        filter: String,
        query: String,
        offset: Int,
        limit: Int,
    ): String

    /** Groups and channels shared with a user, as compact JSON. */
    @Throws(MtprotoException::class)
    fun getCommonChats(handle: Long, userId: Long, maxId: Long, limit: Int): String

    @Throws(MtprotoException::class)
    fun startUpdates(handle: Long)

    @Throws(MtprotoException::class)
    fun clearActiveDialog(handle: Long) = Unit

    @Throws(MtprotoException::class)
    fun drainUpdates(handle: Long): List<UpdateEventDto>

    @Throws(MtprotoException::class)
    fun downloadMessageMedia(handle: Long, chatId: Long, messageId: Int, destPath: String): String
    fun downloadMessageMediaChunk(handle: Long, chatId: Long, messageId: Int, destPath: String, offset: Long): String =
        throw MtprotoException.Message("media streaming unavailable")

    @Throws(MtprotoException::class)
    fun downloadMessageThumb(handle: Long, chatId: Long, messageId: Int, destPath: String): String =
        downloadMessageMedia(handle, chatId, messageId, destPath)

    @Throws(MtprotoException::class)
    fun downloadMessageDisplay(handle: Long, chatId: Long, messageId: Int, destPath: String): String =
        downloadMessageMedia(handle, chatId, messageId, destPath)

    @Throws(MtprotoException::class)
    fun downloadCustomEmoji(handle: Long, documentId: Long, destPath: String): String {
        throw MtprotoException.Message("custom emoji requires native rebuild")
    }

    @Throws(MtprotoException::class)
    fun getStickerPack(handle: Long, documentId: Long): StickerPackDto {
        throw MtprotoException.Message("sticker pack requires native rebuild")
    }

    @Throws(MtprotoException::class)
    fun getStickerSet(handle: Long, setId: Long, accessHash: Long): StickerPackDto =
        throw MtprotoException.Message("sticker set requires native rebuild")

    @Throws(MtprotoException::class)
    fun getAllStickers(handle: Long, hash: Long): StickerCatalogDto =
        throw MtprotoException.Message("sticker catalog requires native rebuild")

    @Throws(MtprotoException::class)
    fun getEmojiStickers(handle: Long, hash: Long): StickerCatalogDto =
        throw MtprotoException.Message("emoji stickers require native rebuild")

    @Throws(MtprotoException::class)
    fun getStickers(handle: Long, emoticon: String, hash: Long): StickerListDto =
        throw MtprotoException.Message("sticker suggestions require native rebuild")

    @Throws(MtprotoException::class)
    fun resolveUsername(handle: Long, username: String): ResolvedPeerDto =
        throw MtprotoException.Message("resolve username requires native rebuild")

    @Throws(MtprotoException::class)
    fun getInlineBotResults(
        handle: Long,
        chatId: Long,
        botId: Long,
        query: String,
        offset: String,
    ): InlineBotResultsDto =
        throw MtprotoException.Message("inline bots require native rebuild")

    @Throws(MtprotoException::class)
    fun sendInlineBotResult(
        handle: Long,
        chatId: Long,
        queryId: Long,
        resultId: String,
        replyToMsgId: Int,
        topMsgId: Int,
    ): MessageDto =
        throw MtprotoException.Message("inline send requires native rebuild")

    @Throws(MtprotoException::class)
    fun getSavedGifs(handle: Long): List<SavedGifDto> =
        throw MtprotoException.Message("saved gifs requires native rebuild")

    @Throws(MtprotoException::class)
    fun getBotCallbackAnswer(
        handle: Long,
        chatId: Long,
        messageId: Int,
        dataHex: String,
    ): BotCallbackAnswerDto =
        throw MtprotoException.Message("bot callback requires native rebuild")

    @Throws(MtprotoException::class)
    fun toggleTodoCompleted(
        handle: Long,
        chatId: Long,
        messageId: Int,
        completed: List<Int>,
        incompleted: List<Int>,
    ): Unit =
        throw MtprotoException.Message("todo toggle requires native rebuild")

    @Throws(MtprotoException::class)
    fun sendLocation(
        handle: Long,
        chatId: Long,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int,
        heading: Int,
        replyToMsgId: Int,
    ): Unit =
        throw MtprotoException.Message("location send requires native rebuild")

    @Throws(MtprotoException::class)
    fun sendPollVote(
        handle: Long,
        chatId: Long,
        messageId: Int,
        options: List<ByteArray>,
    ): Unit =
        throw MtprotoException.Message("poll vote requires native rebuild")

    @Throws(MtprotoException::class)
    fun appendTodoItems(
        handle: Long,
        chatId: Long,
        messageId: Int,
        firstId: Int,
        titles: List<String>,
    ): Unit =
        throw MtprotoException.Message("checklist append requires native rebuild")

    @Throws(MtprotoException::class)
    fun sendSavedGif(
        handle: Long,
        chatId: Long,
        documentId: Long,
        replyToMsgId: Int,
        topMsgId: Int = 0,
    ): MessageDto =
        throw MtprotoException.Message("saved gifs requires native rebuild")

    @Throws(MtprotoException::class)
    fun sendReaction(
        handle: Long,
        chatId: Long,
        messageId: Int,
        emoticon: String,
        documentId: Long,
    ) {
        throw MtprotoException.Message("reactions require native rebuild")
    }

    @Throws(MtprotoException::class)
    fun getDiscussionMessage(handle: Long, chatId: Long, messageId: Int): DiscussionDto =
        throw MtprotoException.Message("discussion requires native rebuild")

    @Throws(MtprotoException::class)
    fun getRecentReactions(handle: Long): List<ReactionChoiceDto> =
        throw MtprotoException.Message("reactions require native rebuild")

    @Throws(MtprotoException::class)
    fun animatedEmojiMax(handle: Long): Int = 0

    @Throws(MtprotoException::class)
    fun customEmojiIsFree(handle: Long, documentId: Long): Boolean = false

    @Throws(MtprotoException::class)
    fun getUpdatesState(handle: Long): UpdatesStateDto

    object Stub : MtprotoNative by MtprotoNativeStub
    object UniFfi : MtprotoNative by MtprotoNativeUniFfi
}
