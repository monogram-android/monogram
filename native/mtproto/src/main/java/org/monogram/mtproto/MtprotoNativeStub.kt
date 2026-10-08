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
    private fun nativeUnavailable(): Nothing =
        throw MtprotoException.Message("native library failed to load")

    override fun libraryVersion(): String = "stub-0.0.1"

    override fun setTransportMode(mode: String) = Unit
    override fun setProxy(kind: String, host: String, port: Int, username: String?, password: String?, secret: ByteArray) = Unit
    override fun clearProxy() = Unit

    override fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long = 1L

    override fun isAuthorized(handle: Long): Boolean = false

    override fun connect(handle: Long) {
        nativeUnavailable()
    }

    override fun destroyClient(handle: Long) = Unit

    override fun sendAuthCode(handle: Long, phone: String): AuthCodeSent {
        nativeUnavailable()
    }

    override fun signIn(
        handle: Long,
        phone: String,
        phoneCodeHash: String,
        phoneCode: String,
    ): AuthSignedIn {
        nativeUnavailable()
    }

    override fun checkPassword(handle: Long, password: String): AuthSignedIn {
        nativeUnavailable()
    }

    override fun logout(handle: Long) {
        nativeUnavailable()
    }

    override fun getChats(handle: Long): List<ChatDto> {
        nativeUnavailable()
    }

    override fun getFolders(handle: Long): List<FolderDto> {
        nativeUnavailable()
    }

    override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> {
        nativeUnavailable()
    }

    override fun getHistoryPage(
        handle: Long,
        chatId: Long,
        limit: Int,
        offsetId: Int,
        offsetDate: Int,
        addOffset: Int,
    ): List<MessageDto> {
        nativeUnavailable()
    }

    override fun loadMoreChats(
        handle: Long,
        offsetDate: Int,
        offsetId: Int,
        offsetPeerId: Long,
        folderId: Int,
    ): List<ChatDto> {
        nativeUnavailable()
    }

    override fun searchMessages(
        handle: Long,
        chatId: Long,
        query: String,
        limit: Int,
    ): List<MessageDto> {
        nativeUnavailable()
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
        nativeUnavailable()
    }

    override fun contactsSearch(
        handle: Long,
        query: String,
        limit: Int,
    ): ContactsSearchDto {
        nativeUnavailable()
    }

    override fun searchGlobal(
        handle: Long,
        query: String,
        offsetRate: Int,
        offsetPeerId: Long,
        offsetId: Int,
        limit: Int,
        folderId: Int,
    ): GlobalMessageSearchDto {
        nativeUnavailable()
    }

    override fun getPinnedMessages(
        handle: Long,
        chatId: Long,
        limit: Int,
    ): List<MessageDto> {
        nativeUnavailable()
    }

    override fun sendTextMessage(
        handle: Long,
        chatId: Long,
        text: String,
        replyToMsgId: Int,
        entitiesJson: String?,
        topMsgId: Int,
        webpageUrl: String?,
        randomId: Long,
    ): MessageDto {
        nativeUnavailable()
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
        nativeUnavailable()
    }

    override fun sendUploadedMedia(
        handle: Long,
        chatId: Long,
        item: UploadItemDto,
        replyToMsgId: Int,
        topMsgId: Int,
        entitiesJson: String?,
    ): MessageDto {
        nativeUnavailable()
    }

    override fun sendUploadedAlbum(
        handle: Long,
        chatId: Long,
        items: List<UploadItemDto>,
        replyToMsgId: Int,
        topMsgId: Int,
    ): List<MessageDto> {
        nativeUnavailable()
    }

    override fun editTextMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        text: String,
        entitiesJson: String?,
    ): MessageDto {
        nativeUnavailable()
    }

    override fun deleteMessage(
        handle: Long,
        chatId: Long,
        messageId: Int,
        revoke: Boolean,
    ) {
        nativeUnavailable()
    }

    override fun forwardMessages(
        handle: Long,
        fromChatId: Long,
        messageIds: List<Int>,
        toChatId: Long,
        dropAuthor: Boolean,
    ): List<MessageDto> {
        nativeUnavailable()
    }

    override fun readHistory(handle: Long, chatId: Long, maxId: Int) {
        nativeUnavailable()
    }

    override fun markDialogUnread(handle: Long, chatId: Long, unread: Boolean) {
        nativeUnavailable()
    }

    override fun setTyping(handle: Long, chatId: Long, typing: Boolean) {
        nativeUnavailable()
    }

    override fun updateStatus(handle: Long, offline: Boolean) {
        nativeUnavailable()
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
        nativeUnavailable()
    }

    override fun unregisterDevice(
        handle: Long,
        tokenType: Int,
        token: String,
        otherUids: List<Long>,
    ) {
        nativeUnavailable()
    }

    override fun getNotifySettings(handle: Long, peerKind: String, chatId: Long): NotifySettingsDto {
        nativeUnavailable()
    }

    override fun updateNotifySettings(
        handle: Long,
        peerKind: String,
        chatId: Long,
        showPreviews: Boolean?,
        silent: Boolean,
        muteUntil: Int,
        storiesMuted: Boolean,
        sound: String,
    ) {
        nativeUnavailable()
    }

    override fun resetNotifySettings(handle: Long) {
        nativeUnavailable()
    }

    override fun joinChat(handle: Long, chatId: Long): NativeJoinResult {
        nativeUnavailable()
    }

    override fun unblockUser(handle: Long, chatId: Long) {
        nativeUnavailable()
    }

    override fun setContactJoinedSilent(handle: Long, silent: Boolean) {
        nativeUnavailable()
    }

    override fun getNotifyExceptions(handle: Long, compareSound: Boolean): List<NotifyExceptionDto> {
        nativeUnavailable()
    }

    override fun decryptPushPayload(secret: ByteArray, payload: String): String {
        nativeUnavailable()
    }

    override fun getProfile(handle: Long, peerId: Long): ProfileDto {
        nativeUnavailable()
    }

    override fun getGroupAdminTags(handle: Long, chatId: Long): String {
        nativeUnavailable()
    }

    override fun getSearchCounters(handle: Long, chatId: Long, filters: List<String>): String {
        nativeUnavailable()
    }

    override fun getParticipants(
        handle: Long,
        chatId: Long,
        filter: String,
        query: String,
        offset: Int,
        limit: Int,
    ): String {
        nativeUnavailable()
    }

    override fun getCommonChats(handle: Long, userId: Long, maxId: Long, limit: Int): String {
        nativeUnavailable()
    }

    override fun startUpdates(handle: Long) {
        nativeUnavailable()
    }

    override fun drainUpdates(handle: Long): List<UpdateEventDto> = emptyList()

    override fun downloadMessageMedia(
        handle: Long,
        chatId: Long,
        messageId: Int,
        destPath: String,
    ): String {
        nativeUnavailable()
    }

    override fun getUpdatesState(handle: Long): UpdatesStateDto {
        nativeUnavailable()
    }
}
