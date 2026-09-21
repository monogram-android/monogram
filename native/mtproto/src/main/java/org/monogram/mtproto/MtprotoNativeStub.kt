package org.monogram.mtproto

import uniffi.monogram_mtproto.AuthCodeSent
import uniffi.monogram_mtproto.AuthSignedIn
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.ContactsSearchDto
import uniffi.monogram_mtproto.FolderDto
import uniffi.monogram_mtproto.GlobalMessageSearchDto
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.NotifyExceptionDto
import uniffi.monogram_mtproto.NotifySettingsDto
import uniffi.monogram_mtproto.ProfileDto
import uniffi.monogram_mtproto.UpdateEventDto
import uniffi.monogram_mtproto.UpdatesStateDto
import uniffi.monogram_mtproto.UploadItemDto

object MtprotoNativeStub : MtprotoNative {
    override fun libraryVersion(): String = "stub-0.0.1"

    override fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long = 1L

    override fun isAuthorized(handle: Long): Boolean = false

    override fun connect(handle: Long) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun destroyClient(handle: Long) = Unit

    override fun sendAuthCode(handle: Long, phone: String): AuthCodeSent {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun signIn(
        handle: Long,
        phone: String,
        phoneCodeHash: String,
        phoneCode: String,
    ): AuthSignedIn {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun checkPassword(handle: Long, password: String): AuthSignedIn {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun logout(handle: Long) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getChats(handle: Long): List<ChatDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getFolders(handle: Long): List<FolderDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getHistoryPage(
        handle: Long,
        chatId: Long,
        limit: Int,
        offsetId: Int,
        offsetDate: Int,
        addOffset: Int,
    ): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun loadMoreChats(
        handle: Long,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
        folderId: Int,
    ): List<ChatDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun searchMessages(
        handle: Long,
        chatId: Long,
        query: String,
        limit: Int,
    ): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun searchMessagesFiltered(
        handle: Long,
        chatId: Long,
        query: String,
        filter: String,
        offsetId: Int,
        addOffset: Int,
        limit: Int,
    ): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun contactsSearch(
        handle: Long,
        query: String,
        limit: Int,
    ): ContactsSearchDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun searchGlobal(
        handle: Long,
        query: String,
        offsetRate: Int,
        offsetPeerId: Long,
        offsetId: Int,
        limit: Int,
    ): GlobalMessageSearchDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getPinnedMessages(
        handle: Long,
        chatId: Long,
        limit: Int,
    ): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun sendTextMessage(
        handle: Long,
        chatId: Long,
        text: String,
        replyToMsgId: Int,
        entitiesJson: String?,
        topMsgId: Int,
    ): MessageDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun sendPhotoMessage(
        handle: Long,
        chatId: Long,
        path: String,
        caption: String,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): MessageDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun sendUploadedMedia(
        handle: Long,
        chatId: Long,
        item: UploadItemDto,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): MessageDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun sendUploadedAlbum(
        handle: Long,
        chatId: Long,
        items: List<UploadItemDto>,
        replyToMsgId: Int,
        topMsgId: Int,
    ): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun editTextMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        text: String,
        entitiesJson: String?,
    ): MessageDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun deleteMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        revoke: Boolean,
    ) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun forwardMessages(
        handle: Long,
        fromChatId: Long,
        messageIds: List<Int>,
        toChatId: Long,
        dropAuthor: Boolean,
    ): List<MessageDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun readHistory(handle: Long, chatId: Long, maxId: Int) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun markDialogUnread(handle: Long, chatId: Long, unread: Boolean) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun setTyping(handle: Long, chatId: Long, typing: Boolean) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun updateStatus(handle: Long, offline: Boolean) {
        throw MtprotoException.Message("native library failed to load")
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
        throw MtprotoException.Message("native library failed to load")
    }

    override fun unregisterDevice(
        handle: Long,
        tokenType: Int,
        token: String,
        otherUids: List<Long>,
    ) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getNotifySettings(handle: Long, peerKind: String, chatId: Long): NotifySettingsDto {
        throw MtprotoException.Message("native library failed to load")
    }

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
        throw MtprotoException.Message("native library failed to load")
    }

    override fun resetNotifySettings(handle: Long) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun setContactJoinedSilent(handle: Long, silent: Boolean) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getNotifyExceptions(handle: Long, compareSound: Boolean): List<NotifyExceptionDto> {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun decryptPushPayload(secret: ByteArray, payload: String): String {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getProfile(handle: Long, peerId: Long): ProfileDto {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getGroupAdminTags(handle: Long, chatId: Long): String {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getSearchCounters(handle: Long, chatId: Long, filters: List<String>): String {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getParticipants(
        handle: Long,
        chatId: Long,
        filter: String,
        query: String,
        offset: Int,
        limit: Int,
    ): String {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getCommonChats(handle: Long, userId: Long, maxId: Long, limit: Int): String {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun startUpdates(handle: Long) {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun drainUpdates(handle: Long): List<UpdateEventDto> = emptyList()

    override fun downloadMessageMedia(
        handle: Long,
        chatId: Long,
        messageId: Int,
        destPath: String,
    ): String {
        throw MtprotoException.Message("native library failed to load")
    }

    override fun getUpdatesState(handle: Long): UpdatesStateDto {
        throw MtprotoException.Message("native library failed to load")
    }
}
