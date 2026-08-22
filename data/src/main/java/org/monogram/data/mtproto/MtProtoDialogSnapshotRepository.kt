package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.User_1990f29d1e
import org.monogram.mtproto.tl.generated.cloud.layer223.Channel
import org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogsSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_ba027bdead
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Dialogs_d319adbade
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetDialogs
import org.monogram.mtproto.transport.MtProtoRpcException
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository

internal data class MtProtoDialogFolderUpdate(
    val folderId: Int?,
    val dialogs: List<DialogSnapshotModel>,
)

internal class MtProtoDialogSnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val dialogStore: MtProtoDialogStore,
    private val sessionFactory: TelegramMtProtoSessionFactory? = null,
    private val resultStager: MtProtoDialogResultStager? = null,
    private val scope: CoroutineScope? = null,
    private val accountId: String = "default",
) : DialogSnapshotRepository {
    private val _dialogUpdates = MutableSharedFlow<MtProtoDialogFolderUpdate>(replay = 1, extraBufferCapacity = 1)
    internal val dialogUpdates = _dialogUpdates.asSharedFlow()
    private val continuationCursors = mutableMapOf<DialogPageKey, DialogCursor>()
    private val exhaustedPages = mutableSetOf<DialogPageKey>()

    init {
        // Live republish: Room projection writes (messages, pins, read state) re-emit the All-chats
        // snapshot so MtProtoDialogChatListRepository stays fresh without a network refresh.
        scope?.launch {
            val config = configSource.createForAccount(accountId)
            val authScope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
            dialogStore.observeChanges(authScope)
                .debounce(PROJECTION_REPUBLISH_DEBOUNCE_MILLIS)
                .collect {
                    _dialogUpdates.emit(MtProtoDialogFolderUpdate(null, dialogsForFolder(authScope, null)))
                }
        }
    }

    override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> =
        getDialogsForFolder(accountId, null)

    override suspend fun getDialogsForFolder(accountId: String, folderId: Int?): List<DialogSnapshotModel> {
        val pageKey = DialogPageKey(accountId, folderId)
        exhaustedPages.remove(pageKey)
        continuationCursors.remove(pageKey)
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        if (sessionFactory != null && resultStager != null) {
            sessionFactory.open(accountId).use { transport ->
                var offsetDate = 0
                var offsetId = 0
                var offsetPeer: InputPeer = InputPeerEmpty
                var previousCursor: DialogCursor? = null
                var loadedDialogs = 0
                // Publish a bounded first snapshot; further pages are streamed through loadMore.
                for (pageIndex in 0 until INITIAL_DIALOG_PAGES) {
                    val result = executeDialogsPage(
                        transport = transport,
                        request = GetDialogs(
                            excludePinned = pageIndex > 0,
                            folderId = folderId,
                            offsetDate = offsetDate,
                            offsetId = offsetId,
                            offsetPeer = offsetPeer,
                            limit = INITIAL_PAGE_SIZE,
                            hash = 0L,
                        ),
                    )
                    check(resultStager.stage(scope, result)) { "Unsupported messages.getDialogs result" }
                    _dialogUpdates.emit(MtProtoDialogFolderUpdate(folderId, dialogsForFolder(scope, folderId)))
                    val page = result.dialogPage()
                    loadedDialogs += page.dialogs.size
                    if (!page.hasMore(loadedDialogs)) {
                        continuationCursors.remove(pageKey)
                        exhaustedPages += pageKey
                        break
                    }
                    val cursor = page.cursor { peer, users, chats -> peer.toInputPeer(users, chats) }
                    check(cursor != previousCursor) { "messages.getDialogs returned a duplicate cursor" }
                    previousCursor = cursor
                    offsetDate = cursor.date
                    offsetId = cursor.messageId
                    offsetPeer = cursor.peer
                }
                if (previousCursor != null && pageKey !in exhaustedPages) {
                    continuationCursors[pageKey] = previousCursor
                } else if (previousCursor == null) {
                    exhaustedPages += pageKey
                }
            }
        }
        return dialogsForFolder(scope, folderId)
    }

    override suspend fun loadMore(accountId: String, limit: Int): List<DialogSnapshotModel> =
        loadMoreForFolder(accountId, limit, null)

    override suspend fun loadMoreForFolder(accountId: String, limit: Int, folderId: Int?): List<DialogSnapshotModel> {
        require(limit > 0) { "Dialog chunk limit must be positive" }
        val pageKey = DialogPageKey(accountId, folderId)
        if (pageKey in exhaustedPages) return emptyList()
        val startCursor = continuationCursors[pageKey] ?: return emptyList()
        val sessionFactory = sessionFactory ?: return emptyList()
        val resultStager = resultStager ?: return emptyList()
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        sessionFactory.open(accountId).use { transport ->
            var offsetDate = startCursor.date
            var offsetId = startCursor.messageId
            var offsetPeer: InputPeer = startCursor.peer
            var previousCursor: DialogCursor? = startCursor
            var loaded = 0
            for (pageIndex in 0 until MAX_DIALOG_PAGES) {
                if (loaded >= limit) break
                val result = executeDialogsPage(
                    transport = transport,
                    request = GetDialogs(
                        excludePinned = true,
                        folderId = folderId,
                        offsetDate = offsetDate,
                        offsetId = offsetId,
                        offsetPeer = offsetPeer,
                        limit = INITIAL_PAGE_SIZE,
                        hash = 0L,
                    ),
                )
                check(resultStager.stage(scope, result)) { "Unsupported messages.getDialogs result" }
                _dialogUpdates.emit(MtProtoDialogFolderUpdate(folderId, dialogsForFolder(scope, folderId)))
                val page = result.dialogPage()
                loaded += page.dialogs.size
                if (!page.hasMore(loaded + 1) || page.dialogs.isEmpty()) {
                    continuationCursors.remove(pageKey)
                    exhaustedPages += pageKey
                    break
                }
                val cursor = page.cursor { peer, users, chats -> peer.toInputPeer(users, chats) }
                check(cursor != previousCursor) { "messages.getDialogs returned a duplicate cursor" }
                previousCursor = cursor
                offsetDate = cursor.date
                offsetId = cursor.messageId
                offsetPeer = cursor.peer
            }
            if (previousCursor != null && pageKey !in exhaustedPages) {
                continuationCursors[pageKey] = previousCursor
            }
        }
        return dialogsForFolder(scope, folderId)
    }

    private suspend fun dialogsForFolder(scope: MtProtoAuthKeyScope, folderId: Int?): List<DialogSnapshotModel> =
        (folderId?.let { dialogStore.getByFolder(scope, it) } ?: dialogStore.getAll(scope))
            .map { it.toDomain() }

    private data class DialogPageKey(val accountId: String, val folderId: Int?)

    private suspend fun executeDialogsPage(
        transport: org.monogram.mtproto.transport.MtProtoRpcTransport,
        request: GetDialogs,
    ): Dialogs_ba027bdead {
        repeat(MAX_FLOOD_WAIT_RETRIES) {
            try {
                return transport.execute(request)
            } catch (rpc: MtProtoRpcException) {
                val waitSeconds = rpc.floodWaitSeconds() ?: throw rpc
                delay(waitSeconds * 1_000L)
            }
        }
        return transport.execute(request)
    }

    private fun MtProtoDialogReadModel.toDomain() = DialogSnapshotModel(
        peerId = peerId,
        peerType = when (peerKind) {
            MtProtoDialogPeerKind.PRIVATE -> DialogPeerType.PRIVATE
            MtProtoDialogPeerKind.BASIC_GROUP -> DialogPeerType.BASIC_GROUP
            MtProtoDialogPeerKind.SUPERGROUP -> DialogPeerType.SUPERGROUP
            MtProtoDialogPeerKind.CHANNEL -> DialogPeerType.CHANNEL
            MtProtoDialogPeerKind.UNKNOWN -> DialogPeerType.UNKNOWN
        },
        title = title,
        username = username,
        isPeerResolved = isPeerResolved,
        isPeerDeleted = isPeerDeleted,
        isPeerForbidden = isPeerForbidden,
        unreadCount = unreadCount,
        unreadMentionsCount = unreadMentionsCount,
        unreadReactionsCount = unreadReactionsCount,
        isPinned = isPinned,
        isMuted = isMuted,
        latestMessage = DialogMessagePreviewModel(
            messageId = latestMessage.messageId.toLong(),
            senderId = latestMessage.senderId,
            date = latestMessage.date,
            text = latestMessage.text,
            isService = latestMessage.isService,
            isDeleted = latestMessage.isDeleted,
            isOutgoing = latestMessage.isOutgoing,
            hasMedia = latestMessage.hasMedia,
        ),
    )

    private data class DialogPage(
        val dialogs: List<Dialog_cf9860a8bd>,
        val messages: List<org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4>,
        val users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        val chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>,
        val totalCount: Int?,
    ) {
        fun hasMore(loadedDialogs: Int): Boolean = totalCount?.let { loadedDialogs < it } ?: false

        fun cursor(toInputPeer: (org.monogram.mtproto.tl.generated.cloud.layer223.Peer, List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>, List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>) -> InputPeer): DialogCursor {
            val dialog = dialogs.lastUnpinnedDialog()
                ?: error("messages.getDialogs returned no unpinned dialog cursor")
            val message = messages.firstOrNull { it.messageId() == dialog.topMessage }
            val peer = dialog.peer
            return DialogCursor(
                date = messageDate(message ?: error("Missing top message ${dialog.topMessage} for dialog cursor")),
                messageId = dialog.topMessage,
                peer = toInputPeer(peer, users, chats),
            )
        }

        private fun org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4.messageId(): Int = when (this) {
            is org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty -> id
            is org.monogram.mtproto.tl.generated.cloud.layer223.MessageService -> id
            is org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3 -> id
        }

        private fun messageDate(message: org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4?): Int = when (message) {
            is org.monogram.mtproto.tl.generated.cloud.layer223.Message_7b7ecf54a3 -> message.date
            is org.monogram.mtproto.tl.generated.cloud.layer223.MessageService -> message.date
            else -> 0
        }
    }

    private data class DialogCursor(val date: Int, val messageId: Int, val peer: InputPeer)

    private fun Dialogs_ba027bdead.dialogPage() = when (this) {
        is Dialogs_d319adbade -> DialogPage(
            dialogs.filterIsInstance<Dialog_cf9860a8bd>(), messages, users, chats, totalCount = null,
        )
        is DialogsSlice -> DialogPage(
            dialogs.filterIsInstance<Dialog_cf9860a8bd>(), messages, users, chats, totalCount = count,
        )
        else -> error("Unsupported messages.getDialogs result")
    }

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.Peer.toInputPeer(
        users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>,
    ): InputPeer = when (this) {
        is PeerUser -> users.filterIsInstance<User_1990f29d1e>().firstOrNull { it.id == userId }?.let {
            if (it.self) InputPeerSelf else InputPeerUser(userId, it.accessHash ?: error("Missing access hash for dialog user $userId"))
        } ?: error("Missing access hash for dialog user $userId")
        is PeerChat -> InputPeerChat(chatId)
        is PeerChannel -> chats.filterIsInstance<Channel>().firstOrNull { it.id == channelId }?.let {
            InputPeerChannel(channelId, it.accessHash ?: error("Missing access hash for dialog channel $channelId"))
        } ?: error("Missing access hash for dialog channel $channelId")
    }

    private companion object {
        const val INITIAL_PAGE_SIZE = 100
        const val INITIAL_DIALOG_PAGES = 3
        const val MAX_DIALOG_PAGES = 100
        const val MAX_FLOOD_WAIT_RETRIES = 3
        const val PROJECTION_REPUBLISH_DEBOUNCE_MILLIS = 250L
    }
}

internal fun List<Dialog_cf9860a8bd>.lastUnpinnedDialog(): Dialog_cf9860a8bd? =
    lastOrNull { !it.pinned }

internal fun MtProtoRpcException.floodWaitSeconds(): Long? =
    DIALOG_FLOOD_WAIT_PATTERN.matchEntire(rpcMessage)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { errorCode == DIALOG_FLOOD_WAIT_ERROR_CODE && it in 1..DIALOG_MAX_FLOOD_WAIT_SECONDS }

private const val DIALOG_FLOOD_WAIT_ERROR_CODE = 420
private const val DIALOG_MAX_FLOOD_WAIT_SECONDS = 60L
private val DIALOG_FLOOD_WAIT_PATTERN = Regex("FLOOD_WAIT_(\\d+)")
