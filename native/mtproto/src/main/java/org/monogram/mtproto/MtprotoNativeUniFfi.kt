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
import uniffi.monogram_mtproto.uniffiEnsureInitialized
import java.io.File
import uniffi.monogram_mtproto.animatedEmojiMax as nativeAnimatedEmojiMax
import uniffi.monogram_mtproto.checkPassword as nativeCheckPassword
import uniffi.monogram_mtproto.clearActiveDialog as nativeClearActiveDialog
import uniffi.monogram_mtproto.clientApiId as nativeClientApiId
import uniffi.monogram_mtproto.clientExists as nativeClientExists
import uniffi.monogram_mtproto.connect as nativeConnect
import uniffi.monogram_mtproto.contactsSearch as nativeContactsSearch
import uniffi.monogram_mtproto.createEncryptedClient as nativeCreateEncryptedClient
import uniffi.monogram_mtproto.customEmojiIsFree as nativeCustomEmojiIsFree
import uniffi.monogram_mtproto.decryptPushPayload as nativeDecryptPushPayload
import uniffi.monogram_mtproto.deleteMessage as nativeDeleteMessage
import uniffi.monogram_mtproto.destroyClient as nativeDestroyClient
import uniffi.monogram_mtproto.downloadCustomEmoji as nativeDownloadCustomEmoji
import uniffi.monogram_mtproto.downloadMessageMedia as nativeDownloadMessageMedia
import uniffi.monogram_mtproto.downloadMessageThumb as nativeDownloadMessageThumb
import uniffi.monogram_mtproto.drainUpdates as nativeDrainUpdates
import uniffi.monogram_mtproto.editTextMessage as nativeEditTextMessage
import uniffi.monogram_mtproto.forwardMessages as nativeForwardMessages
import uniffi.monogram_mtproto.getAllStickers as nativeGetAllStickers
import uniffi.monogram_mtproto.getBotCallbackAnswer as nativeGetBotCallbackAnswer
import uniffi.monogram_mtproto.getChats as nativeGetChats
import uniffi.monogram_mtproto.getCommonChats as nativeGetCommonChats
import uniffi.monogram_mtproto.getDiscussionMessage as nativeGetDiscussionMessage
import uniffi.monogram_mtproto.getEmojiStickers as nativeGetEmojiStickers
import uniffi.monogram_mtproto.getFolders as nativeGetFolders
import uniffi.monogram_mtproto.updateFolder as nativeUpdateFolder
import uniffi.monogram_mtproto.deleteFolder as nativeDeleteFolder
import uniffi.monogram_mtproto.updateFolderOrder as nativeUpdateFolderOrder
import uniffi.monogram_mtproto.getForumTopics as nativeGetForumTopics
import uniffi.monogram_mtproto.getForumTopicsById as nativeGetForumTopicsById
import uniffi.monogram_mtproto.getGroupAdminTags as nativeGetGroupAdminTags
import uniffi.monogram_mtproto.getHistory as nativeGetHistory
import uniffi.monogram_mtproto.getHistoryPage as nativeGetHistoryPage
import uniffi.monogram_mtproto.getUnreadMentions as nativeGetUnreadMentions
import uniffi.monogram_mtproto.getUnreadReactions as nativeGetUnreadReactions
import uniffi.monogram_mtproto.getInlineBotResults as nativeGetInlineBotResults
import uniffi.monogram_mtproto.getNotifyExceptions as nativeGetNotifyExceptions
import uniffi.monogram_mtproto.getNotifySettings as nativeGetNotifySettings
import uniffi.monogram_mtproto.getParticipants as nativeGetParticipants
import uniffi.monogram_mtproto.getMessageReadParticipants as nativeGetMessageReadParticipants
import uniffi.monogram_mtproto.getMessageReactionsList as nativeGetMessageReactionsList
import uniffi.monogram_mtproto.getPollVotes as nativeGetPollVotes
import uniffi.monogram_mtproto.ReactionPeersDto
import uniffi.monogram_mtproto.PollVotersDto
import uniffi.monogram_mtproto.getOutboxReadDate as nativeGetOutboxReadDate
import uniffi.monogram_mtproto.getPinnedMessages as nativeGetPinnedMessages
import uniffi.monogram_mtproto.getReadReceiptConfig as nativeGetReadReceiptConfig
import uniffi.monogram_mtproto.getProfile as nativeGetProfile
import uniffi.monogram_mtproto.getRecentReactions as nativeGetRecentReactions
import uniffi.monogram_mtproto.getReplies as nativeGetReplies
import uniffi.monogram_mtproto.getSavedGifs as nativeGetSavedGifs
import uniffi.monogram_mtproto.getSearchCounters as nativeGetSearchCounters
import uniffi.monogram_mtproto.getStickerPack as nativeGetStickerPack
import uniffi.monogram_mtproto.getStickerSet as nativeGetStickerSet
import uniffi.monogram_mtproto.getStickers as nativeGetStickers
import uniffi.monogram_mtproto.getUpdatesState as nativeGetUpdatesState
import uniffi.monogram_mtproto.getWebPage as nativeGetWebPage
import uniffi.monogram_mtproto.isAuthorized as nativeIsAuthorized
import uniffi.monogram_mtproto.libraryVersion as nativeLibraryVersion
import uniffi.monogram_mtproto.loadMoreChats as nativeLoadMoreChats
import uniffi.monogram_mtproto.logout as nativeLogout
import uniffi.monogram_mtproto.readDiscussion as nativeReadDiscussion
import uniffi.monogram_mtproto.readHistory as nativeReadHistory
import uniffi.monogram_mtproto.readMessageContents as nativeReadMessageContents
import uniffi.monogram_mtproto.readMentions as nativeReadMentions
import uniffi.monogram_mtproto.readReactions as nativeReadReactions
import uniffi.monogram_mtproto.markDialogUnread as nativeMarkDialogUnread
import uniffi.monogram_mtproto.registerDevice as nativeRegisterDevice
import uniffi.monogram_mtproto.resendAuthCode as nativeResendAuthCode
import uniffi.monogram_mtproto.resetNotifySettings as nativeResetNotifySettings
import uniffi.monogram_mtproto.resolveUsername as nativeResolveUsername
import uniffi.monogram_mtproto.searchGlobal as nativeSearchGlobal
import uniffi.monogram_mtproto.searchMessages as nativeSearchMessages
import uniffi.monogram_mtproto.searchMessagesFiltered as nativeSearchMessagesFiltered
import uniffi.monogram_mtproto.sendAuthCode as nativeSendAuthCode
import uniffi.monogram_mtproto.sendInlineBotResult as nativeSendInlineBotResult
import uniffi.monogram_mtproto.sendPhotoMessage as nativeSendPhotoMessage
import uniffi.monogram_mtproto.sendReaction as nativeSendReaction
import uniffi.monogram_mtproto.sendSavedGif as nativeSendSavedGif
import uniffi.monogram_mtproto.sendTextMessage as nativeSendTextMessage
import uniffi.monogram_mtproto.sendUploadedAlbum as nativeSendUploadedAlbum
import uniffi.monogram_mtproto.sendUploadedMedia as nativeSendUploadedMedia
import uniffi.monogram_mtproto.setClientTestDc as nativeSetClientTestDc
import uniffi.monogram_mtproto.setContactJoinedSilent as nativeSetContactJoinedSilent
import uniffi.monogram_mtproto.setTyping as nativeSetTyping
import uniffi.monogram_mtproto.signIn as nativeSignIn
import uniffi.monogram_mtproto.startUpdates as nativeStartUpdates
import uniffi.monogram_mtproto.toggleTodoCompleted as nativeToggleTodoCompleted
import uniffi.monogram_mtproto.sendLocation as nativeSendLocation
import uniffi.monogram_mtproto.sendPollVote as nativeSendPollVote
import uniffi.monogram_mtproto.appendTodoItems as nativeAppendTodoItems
import uniffi.monogram_mtproto.unregisterDevice as nativeUnregisterDevice
import uniffi.monogram_mtproto.updateNotifySettings as nativeUpdateNotifySettings
import uniffi.monogram_mtproto.updateStatus as nativeUpdateStatus

object MtprotoNativeUniFfi : MtprotoNative {
    override fun downloadMessageMediaChunk(handle: Long, chatId: Long, messageId: Int, destPath: String, offset: Long): String =
        uniffi.monogram_mtproto.downloadMessageMediaChunk(handle.toULong(), chatId, messageId, destPath, offset)
    override fun createRequestControl(): Long = uniffi.monogram_mtproto.createRequestControl().toLong()
    override fun bindRequestControl(id: Long): Long = uniffi.monogram_mtproto.bindRequestControl(id.toULong()).toLong()
    override fun cancelRequestControl(id: Long) = uniffi.monogram_mtproto.cancelRequestControl(id.toULong())
    override fun releaseRequestControl(id: Long) = uniffi.monogram_mtproto.releaseRequestControl(id.toULong())
    override fun setDispatchClass(classId: Int) =
        uniffi.monogram_mtproto.setDispatchClass(classId)

    override fun setDownloadConcurrency(lanes: Int, parts: Int) =
        uniffi.monogram_mtproto.setDownloadConcurrency(lanes, parts)

    override fun downloadConcurrency(): List<Int> =
        uniffi.monogram_mtproto.downloadConcurrency()
    init {
        uniffiEnsureInitialized()
    }

    override fun libraryVersion(): String = nativeLibraryVersion()

    override fun perfSetEnabled(enabled: Boolean) =
        uniffi.monogram_mtproto.perfSetEnabled(enabled)

    override fun perfSnapshot(reset: Boolean): String =
        uniffi.monogram_mtproto.perfSnapshot(reset)

    override fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long {
        val key = try {
            SessionKeyStore.loadOrCreate(sessionPath)
        } catch (_: Exception) {
            throw MtprotoException.Message("session key unavailable")
        }
        return try {
            nativeCreateEncryptedClient(apiId, apiHash, File(sessionPath).canonicalPath, key).toLong()
        } finally {
            key.fill(0)
        }
    }

    override fun setTestDc(handle: Long, enabled: Boolean) {
        nativeSetClientTestDc(handle.toULong(), enabled)
    }

    override fun isAuthorized(handle: Long): Boolean =
        nativeIsAuthorized(handle.toULong())

    override fun connect(handle: Long) {
        nativeConnect(handle.toULong())
    }

    override fun destroyClient(handle: Long) {
        nativeDestroyClient(handle.toULong())
    }

    override fun clientExists(handle: Long): Boolean = nativeClientExists(handle.toULong())

    override fun clientApiId(handle: Long): Int = nativeClientApiId(handle.toULong())

    override fun sendAuthCode(handle: Long, phone: String): AuthCodeSent =
        nativeSendAuthCode(handle.toULong(), phone)

    override fun resendAuthCode(
        handle: Long,
        phone: String,
        phoneCodeHash: String,
    ): AuthCodeSent = nativeResendAuthCode(handle.toULong(), phone, phoneCodeHash)

    override fun signIn(
        handle: Long,
        phone: String,
        phoneCodeHash: String,
        phoneCode: String,
    ): AuthSignedIn = nativeSignIn(handle.toULong(), phone, phoneCodeHash, phoneCode)

    override fun checkPassword(handle: Long, password: String): AuthSignedIn =
        nativeCheckPassword(handle.toULong(), password)

    override fun logout(handle: Long) {
        nativeLogout(handle.toULong())
    }

    override fun getChats(handle: Long): List<ChatDto> = nativeGetChats(handle.toULong())

    override fun getWallpapers(handle: Long, hash: Long) =
        uniffi.monogram_mtproto.getWallpapers(handle.toULong(), hash)

    override fun downloadWallpaper(handle: Long, id: Long, accessHash: Long, destPath: String) =
        uniffi.monogram_mtproto.downloadWallpaper(handle.toULong(), id, accessHash, destPath)

    override fun getWebPage(handle: Long, url: String, hash: Int): InstantViewDto =
        nativeGetWebPage(handle.toULong(), url, hash)

    override fun getFolders(handle: Long): List<FolderDto> = nativeGetFolders(handle.toULong())

    override fun updateFolder(handle: Long, folder: FolderDto) =
        nativeUpdateFolder(handle.toULong(), folder)

    override fun deleteFolder(handle: Long, id: Int) =
        nativeDeleteFolder(handle.toULong(), id)

    override fun updateFolderOrder(handle: Long, order: List<Int>) =
        nativeUpdateFolderOrder(handle.toULong(), order)

    override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> =
        nativeGetHistory(handle.toULong(), chatId, limit)

    override fun getHistoryPage(
        handle: Long,
        chatId: Long,
        limit: Int,
        offsetId: Int,
        offsetDate: Int,
        addOffset: Int,
    ): List<MessageDto> =
        nativeGetHistoryPage(handle.toULong(), chatId, limit, offsetId, offsetDate, addOffset)

    override fun getReplies(
        handle: Long,
        chatId: Long,
        msgId: Int,
        limit: Int,
        offsetId: Int,
        addOffset: Int,
    ): List<MessageDto> =
        nativeGetReplies(handle.toULong(), chatId, msgId, limit, offsetId, addOffset)

    override fun getForumTopics(
        handle: Long,
        chatId: Long,
        offsetDate: Int,
        offsetId: Int,
        offsetTopic: Int,
        limit: Int,
    ): ForumTopicsPageDto =
        nativeGetForumTopics(handle.toULong(), chatId, offsetDate, offsetId, offsetTopic, limit)

    override fun getForumTopicsById(
        handle: Long,
        chatId: Long,
        topicIds: List<Int>,
    ): ForumTopicsPageDto =
        nativeGetForumTopicsById(handle.toULong(), chatId, topicIds)

    override fun loadMoreChats(
        handle: Long,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
        folderId: Int,
    ): List<ChatDto> =
        nativeLoadMoreChats(handle.toULong(), offsetDate, offsetId, offsetPeerId, folderId)

    override fun searchMessages(
        handle: Long,
        chatId: Long,
        query: String,
        limit: Int,
    ): List<MessageDto> =
        nativeSearchMessages(handle.toULong(), chatId, query, limit)

    override fun searchMessagesFiltered(
        handle: Long,
        chatId: Long,
        query: String,
        filter: String,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
    ): List<MessageDto> =
        nativeSearchMessagesFiltered(
            handle.toULong(),
            chatId,
            query,
            filter,
            offsetId,
            addOffset,
            limit,
        )

    override fun contactsSearch(
        handle: Long,
        query: String,
        limit: Int,
    ): ContactsSearchDto = nativeContactsSearch(handle.toULong(), query, limit)

    override fun searchGlobal(
        handle: Long,
        query: String,
        offsetRate: Int,
        offsetPeerId: Long,
        offsetId: Int,
        limit: Int,
    ): GlobalMessageSearchDto =
        nativeSearchGlobal(
            handle.toULong(),
            query,
            offsetRate,
            offsetPeerId,
            offsetId,
            limit,
        )

    override fun getPinnedMessages(
        handle: Long,
        chatId: Long,
        limit: Int,
    ): List<MessageDto> =
        nativeGetPinnedMessages(handle.toULong(), chatId, limit)

    override fun getMessageReadParticipants(
        handle: Long,
        chatId: Long,
        msgId: Int,
    ): ReadParticipantsDto =
        nativeGetMessageReadParticipants(handle.toULong(), chatId, msgId)

    override fun getMessageReactionsList(
        handle: Long,
        chatId: Long,
        messageId: Int,
    ): ReactionPeersDto =
        nativeGetMessageReactionsList(handle.toULong(), chatId, messageId)

    override fun getPollVotes(
        handle: Long,
        chatId: Long,
        messageId: Int,
    ): PollVotersDto =
        nativeGetPollVotes(handle.toULong(), chatId, messageId)

    override fun getOutboxReadDate(
        handle: Long,
        chatId: Long,
        msgId: Int,
    ): OutboxReadDto = nativeGetOutboxReadDate(handle.toULong(), chatId, msgId)

    override fun getReadReceiptConfig(handle: Long): ReadReceiptConfigDto =
        nativeGetReadReceiptConfig(handle.toULong())

    override fun sendTextMessage(
        handle: Long,
        chatId: Long,
        text: String,
        replyToMsgId: Int,
        entitiesJson: String?,
        topMsgId: Int,
    ): MessageDto =
        nativeSendTextMessage(handle.toULong(), chatId, text, replyToMsgId, entitiesJson, topMsgId)

    override fun sendPhotoMessage(
        handle: Long,
        chatId: Long,
        path: String,
        caption: String,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): MessageDto =
        nativeSendPhotoMessage(handle.toULong(), chatId, path, caption, replyToMsgId, topMsgId, entitiesJson)

    override fun sendUploadedMedia(
        handle: Long,
        chatId: Long,
        item: UploadItemDto,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): MessageDto =
        nativeSendUploadedMedia(
            handle.toULong(),
            chatId,
            item,
            replyToMsgId,
            topMsgId,
            entitiesJson,
        )

    override fun sendUploadedAlbum(
        handle: Long,
        chatId: Long,
        items: List<UploadItemDto>,
        replyToMsgId: Int,
        topMsgId: Int,
    ): List<MessageDto> =
        nativeSendUploadedAlbum(
            handle.toULong(),
            chatId,
            items,
            replyToMsgId,
            topMsgId,
        )

    override fun editTextMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        text: String,
        entitiesJson: String?,
    ): MessageDto =
        nativeEditTextMessage(handle.toULong(), chatId, messageId, text, entitiesJson)

    override fun forwardMessages(
        handle: Long,
        fromChatId: Long,
        messageIds: List<Int>,
        toChatId: Long,
        dropAuthor: Boolean,
    ): List<MessageDto> =
        nativeForwardMessages(handle.toULong(), fromChatId, messageIds, toChatId, dropAuthor)

    override fun deleteMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        revoke: Boolean,
    ) {
        nativeDeleteMessage(handle.toULong(), chatId, messageId, revoke)
    }

    override fun readHistory(handle: Long, chatId: Long, maxId: Int) {
        nativeReadHistory(handle.toULong(), chatId, maxId)
    }

    override fun readMessageContents(handle: Long, chatId: Long, messageIds: List<Int>) {
        nativeReadMessageContents(handle.toULong(), chatId, messageIds)
    }

    override fun markDialogUnread(handle: Long, chatId: Long, unread: Boolean) {
        nativeMarkDialogUnread(handle.toULong(), chatId, unread)
    }

    override fun getUnreadMentions(
        handle: Long,
        chatId: Long,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
        topMsgId: Int,
    ): List<MessageDto> =
        nativeGetUnreadMentions(handle.toULong(), chatId, offsetId, addOffset, limit, topMsgId)

    override fun readMentions(handle: Long, chatId: Long, topMsgId: Int) {
        nativeReadMentions(handle.toULong(), chatId, topMsgId)
    }

    override fun getUnreadReactions(
        handle: Long,
        chatId: Long,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
        topMsgId: Int,
    ): List<MessageDto> =
        nativeGetUnreadReactions(handle.toULong(), chatId, offsetId, addOffset, limit, topMsgId)

    override fun readReactions(handle: Long, chatId: Long, topMsgId: Int) {
        nativeReadReactions(handle.toULong(), chatId, topMsgId)
    }

    override fun readDiscussion(handle: Long, chatId: Long, msgId: Int, readMaxId: Int) {
        nativeReadDiscussion(handle.toULong(), chatId, msgId, readMaxId)
    }

    override fun setTyping(handle: Long, chatId: Long, typing: Boolean) {
        nativeSetTyping(handle.toULong(), chatId, typing)
    }

    override fun updateStatus(handle: Long, offline: Boolean) {
        nativeUpdateStatus(handle.toULong(), offline)
    }

    override fun registerDevice(
        handle: Long,
        tokenType: Int,
        token: String,
        secret: ByteArray,
        noMuted: Boolean,
        appSandbox: Boolean,
        otherUids: List<Long>,
    ) {
        nativeRegisterDevice(handle.toULong(), tokenType, token, secret, noMuted, appSandbox, otherUids)
    }

    override fun unregisterDevice(
        handle: Long,
        tokenType: Int,
        token: String,
        otherUids: List<Long>,
    ) {
        nativeUnregisterDevice(handle.toULong(), tokenType, token, otherUids)
    }

    override fun getNotifySettings(handle: Long, peerKind: String, chatId: Long): NotifySettingsDto =
        nativeGetNotifySettings(handle.toULong(), peerKind, chatId)

    override fun updateNotifySettings(
        handle: Long,
        peerKind: String,
        chatId: Long,
        showPreviews: Boolean,
        silent: Boolean,
        muteUntil: Int,
        storiesMuted: Boolean,
        sound: String,
    ) {
        nativeUpdateNotifySettings(
            handle.toULong(),
            peerKind,
            chatId,
            showPreviews,
            silent,
            muteUntil,
            storiesMuted,
            sound,
        )
    }

    override fun resetNotifySettings(handle: Long) {
        nativeResetNotifySettings(handle.toULong())
    }

    override fun setContactJoinedSilent(handle: Long, silent: Boolean) {
        nativeSetContactJoinedSilent(handle.toULong(), silent)
    }

    override fun getNotifyExceptions(handle: Long, compareSound: Boolean): List<NotifyExceptionDto> =
        nativeGetNotifyExceptions(handle.toULong(), compareSound)

    override fun decryptPushPayload(secret: ByteArray, payload: String): String =
        nativeDecryptPushPayload(secret, payload)

    override fun getProfile(handle: Long, peerId: Long): ProfileDto =
        nativeGetProfile(handle.toULong(), peerId)

    override fun getGroupAdminTags(handle: Long, chatId: Long): String =
        nativeGetGroupAdminTags(handle.toULong(), chatId)

    override fun getSearchCounters(handle: Long, chatId: Long, filters: List<String>): String =
        nativeGetSearchCounters(handle.toULong(), chatId, filters)

    override fun getParticipants(
        handle: Long,
        chatId: Long,
        filter: String,
        query: String,
        offset: Int,
        limit: Int,
    ): String =
        nativeGetParticipants(handle.toULong(), chatId, filter, query, offset, limit)

    override fun getCommonChats(handle: Long, userId: Long, maxId: Long, limit: Int): String =
        nativeGetCommonChats(handle.toULong(), userId, maxId, limit)

    override fun startUpdates(handle: Long) {
        nativeStartUpdates(handle.toULong())
    }

    override fun clearActiveDialog(handle: Long) {
        nativeClearActiveDialog(handle.toULong())
    }

    override fun drainUpdates(handle: Long): List<UpdateEventDto> =
        nativeDrainUpdates(handle.toULong())

    override fun downloadMessageMedia(
        handle: Long,
        chatId: Long,
        messageId: Int,
        destPath: String,
    ): String = nativeDownloadMessageMedia(handle.toULong(), chatId, messageId, destPath)

    override fun downloadMessageThumb(
        handle: Long,
        chatId: Long,
        messageId: Int,
        destPath: String,
    ): String = nativeDownloadMessageThumb(handle.toULong(), chatId, messageId, destPath)

    override fun downloadMessageDisplay(
        handle: Long,
        chatId: Long,
        messageId: Int,
        destPath: String,
    ): String = nativeDownloadMessageMedia(handle.toULong(), chatId, messageId, destPath)

    override fun downloadCustomEmoji(
        handle: Long,
        documentId: Long,
        destPath: String,
    ): String = nativeDownloadCustomEmoji(handle.toULong(), documentId, destPath)

    override fun getStickerPack(
        handle: Long,
        documentId: Long,
    ): StickerPackDto = nativeGetStickerPack(handle.toULong(), documentId)

    override fun getStickerSet(
        handle: Long,
        setId: Long,
        accessHash: Long,
    ): StickerPackDto = nativeGetStickerSet(handle.toULong(), setId, accessHash)

    override fun getAllStickers(handle: Long, hash: Long): StickerCatalogDto =
        nativeGetAllStickers(handle.toULong(), hash)

    override fun getEmojiStickers(handle: Long, hash: Long): StickerCatalogDto =
        nativeGetEmojiStickers(handle.toULong(), hash)

    override fun getStickers(
        handle: Long,
        emoticon: String,
        hash: Long,
    ): StickerListDto = nativeGetStickers(handle.toULong(), emoticon, hash)

    override fun resolveUsername(handle: Long, username: String): ResolvedPeerDto =
        nativeResolveUsername(handle.toULong(), username)

    override fun getInlineBotResults(
        handle: Long,
        chatId: Long,
        botId: Long,
        query: String,
        offset: String,
    ): InlineBotResultsDto =
        nativeGetInlineBotResults(handle.toULong(), chatId, botId, query, offset)

    override fun sendInlineBotResult(
        handle: Long,
        chatId: Long,
        queryId: Long,
        resultId: String,
        replyToMsgId: Int,
        topMsgId: Int,
    ): MessageDto = nativeSendInlineBotResult(
        handle.toULong(),
        chatId,
        queryId,
        resultId,
        replyToMsgId,
        topMsgId,
    )

    override fun getSavedGifs(handle: Long): List<SavedGifDto> =
        nativeGetSavedGifs(handle.toULong())

    override fun getBotCallbackAnswer(
        handle: Long,
        chatId: Long,
        messageId: Int,
        dataHex: String,
    ): BotCallbackAnswerDto =
        nativeGetBotCallbackAnswer(handle.toULong(), chatId, messageId, dataHex)

    override fun toggleTodoCompleted(
        handle: Long,
        chatId: Long,
        messageId: Int,
        completed: List<Int>,
        incompleted: List<Int>,
    ) {
        nativeToggleTodoCompleted(handle.toULong(), chatId, messageId, completed, incompleted)
    }

    override fun sendLocation(
        handle: Long,
        chatId: Long,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int,
        heading: Int,
        replyToMsgId: Int,
    ) {
        nativeSendLocation(
            handle.toULong(),
            chatId,
            latitude,
            longitude,
            livePeriodSeconds,
            heading,
            replyToMsgId,
        )
    }

    override fun sendPollVote(
        handle: Long,
        chatId: Long,
        messageId: Int,
        options: List<ByteArray>,
    ) {
        nativeSendPollVote(handle.toULong(), chatId, messageId, options)
    }

    override fun appendTodoItems(
        handle: Long,
        chatId: Long,
        messageId: Int,
        firstId: Int,
        titles: List<String>,
    ) {
        nativeAppendTodoItems(handle.toULong(), chatId, messageId, firstId, titles)
    }

    override fun sendSavedGif(
        handle: Long,
        chatId: Long,
        documentId: Long,
        replyToMsgId: Int,
        topMsgId: Int,
    ): MessageDto = nativeSendSavedGif(handle.toULong(), chatId, documentId, replyToMsgId, topMsgId)

    override fun sendReaction(
        handle: Long,
        chatId: Long,
        messageId: Int,
        emoticon: String,
        documentId: Long,
    ) {
        nativeSendReaction(handle.toULong(), chatId, messageId, emoticon, documentId)
    }

    override fun getDiscussionMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
    ): DiscussionDto = nativeGetDiscussionMessage(handle.toULong(), chatId, messageId)

    override fun animatedEmojiMax(handle: Long): Int =
        nativeAnimatedEmojiMax(handle.toULong())

    override fun customEmojiIsFree(handle: Long, documentId: Long): Boolean =
        nativeCustomEmojiIsFree(handle.toULong(), documentId)

    override fun getRecentReactions(handle: Long): List<ReactionChoiceDto> =
        nativeGetRecentReactions(handle.toULong())

    override fun getUpdatesState(handle: Long): UpdatesStateDto =
        nativeGetUpdatesState(handle.toULong())
}
