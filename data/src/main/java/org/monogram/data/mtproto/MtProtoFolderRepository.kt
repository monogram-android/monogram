package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.FolderModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.ChatFolderRepository
import org.monogram.domain.repository.FolderChatsUpdate
import org.monogram.domain.repository.FolderLoadingUpdate
import org.monogram.mtproto.tl.generated.cloud.layer223.DialogFilter_659582f1fd
import org.monogram.mtproto.tl.generated.cloud.layer223.DialogFilter_dee8a5d534
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d094604bd3
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DialogFilters_8a29d3221b
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetDialogFilters
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.UpdateDialogFilter
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.UpdateDialogFiltersOrder

internal class MtProtoFolderRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val userStore: MtProtoUserProjectionStore,
    private val chatStore: MtProtoChatProjectionStore,
    private val keyValueDao: KeyValueDao,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatFolderRepository {
    private val _foldersFlow = MutableStateFlow<List<FolderModel>>(emptyList())
    override val foldersFlow: StateFlow<List<FolderModel>> = _foldersFlow.asStateFlow()
    private val _folderChatsFlow = MutableSharedFlow<FolderChatsUpdate>(extraBufferCapacity = 1)
    override val folderChatsFlow: Flow<FolderChatsUpdate> = _folderChatsFlow.asSharedFlow()
    private val _folderLoadingFlow = MutableSharedFlow<FolderLoadingUpdate>(extraBufferCapacity = 1)
    override val folderLoadingFlow: Flow<FolderLoadingUpdate> = _folderLoadingFlow.asSharedFlow()

    init {
        scope.launch {
            keyValueDao.observeValue(storageKey()).collect { value ->
                _foldersFlow.value = value?.value?.let(::decode).orEmpty()
            }
        }
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        _folderLoadingFlow.emit(FolderLoadingUpdate(ALL_CHATS_FOLDER_ID, true))
        try {
            transportFactory.open(accountId).use { transport ->
                val result = transport.execute(GetDialogFilters)
                if (result is DialogFilters_8a29d3221b) persist(result.filters.mapNotNull { it.toModel() })
            }
        } finally {
            _folderLoadingFlow.emit(FolderLoadingUpdate(ALL_CHATS_FOLDER_ID, false))
        }
    }

    override suspend fun createFolder(title: String, iconName: String?, includedChatIds: List<Long>) {
        val id = (_foldersFlow.value.maxOfOrNull { it.id } ?: 0) + 1
        updateFolder(id, title, iconName, includedChatIds)
    }

    override suspend fun deleteFolder(folderId: Int) {
        updateRemote(folderId, null)
        persist(_foldersFlow.value.filterNot { it.id == folderId })
    }

    override suspend fun updateFolder(folderId: Int, title: String, iconName: String?, includedChatIds: List<Long>) {
        val model = FolderModel(folderId, title, iconName, includedChatIds = includedChatIds.distinct())
        updateRemote(folderId, model)
        persist((_foldersFlow.value.filterNot { it.id == folderId } + model).sortedBy { it.id })
    }

    override suspend fun reorderFolders(folderIds: List<Int>) {
        val config = configSource.createForAccount(accountId)
        transportFactory.open(accountId).use { transport -> transport.execute(UpdateDialogFiltersOrder(folderIds)) }
        val byId = _foldersFlow.value.associateBy { it.id }
        persist(folderIds.mapNotNull(byId::get) + _foldersFlow.value.filter { it.id !in folderIds })
    }

    private suspend fun updateRemote(id: Int, model: FolderModel?) {
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val filter: DialogFilter_dee8a5d534? = model?.let {
            DialogFilter_659582f1fd(
                contacts = false, nonContacts = false, groups = false, broadcasts = false, bots = false,
                excludeMuted = false, excludeRead = false, excludeArchived = false, titleNoanimate = false,
                id = id, title = TextWithEntities_d094604bd3(it.title, emptyList()), emoticon = it.iconName,
                color = null, pinnedPeers = it.pinnedChatIds.map { chatId -> toInputPeer(scope, chatId) },
                includePeers = it.includedChatIds.map { chatId -> toInputPeer(scope, chatId) }, excludePeers = emptyList(),
            )
        }
        transportFactory.open(accountId).use { transport -> transport.execute(UpdateDialogFilter(id, filter)) }
    }

    private suspend fun toInputPeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = if (chatId <= -1_000_000_000_001L) {
            val id = -(chatId + 1_000_000_000_000L)
            val chat = requireNotNull(chatStore.get(scope, id)) { "Missing MTProto chat projection: $id" }
            TelegramPeerChatId.decode(chatId, chat.type == MtProtoChatType.CHANNEL)
        } else TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(userStore.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash))
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chatStore.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash))
            }
            DialogPeerType.UNKNOWN -> error("Cannot add unknown peer to folder")
        }
    }

    private fun DialogFilter_dee8a5d534.toModel(): FolderModel? = (this as? DialogFilter_659582f1fd)?.takeIf { filter ->
        !filter.contacts && !filter.nonContacts && !filter.groups && !filter.broadcasts && !filter.bots &&
            !filter.excludeMuted && !filter.excludeRead && !filter.excludeArchived
    }?.let { filter ->
        FolderModel(
            id = filter.id,
            title = (filter.title as? TextWithEntities_d094604bd3)?.text ?: "Folder ${filter.id}",
            iconName = filter.emoticon,
            includedChatIds = filter.includePeers.mapNotNull { it.toChatId() },
            pinnedChatIds = filter.pinnedPeers.mapNotNull { it.toChatId() },
        )
    }

    private fun InputPeer.toChatId(): Long? = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser -> TelegramPeerChatId.encode(DialogPeerType.PRIVATE, userId)
        is org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat -> TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, chatId)
        is org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel -> TelegramPeerChatId.encode(DialogPeerType.CHANNEL, channelId)
        else -> null
    }

    private suspend fun persist(folders: List<FolderModel>) {
        keyValueDao.insertValue(KeyValueEntity(storageKey(), Json.encodeToString(folders)))
        _foldersFlow.value = folders
    }

    private fun decode(value: String) = runCatching { Json.decodeFromString<List<FolderModel>>(value) }.getOrNull()
    private fun storageKey() = "mtproto_folders_v1_$accountId"
    private companion object { const val DEFAULT_ACCOUNT_ID = "default"; const val ALL_CHATS_FOLDER_ID = -1 }
}
