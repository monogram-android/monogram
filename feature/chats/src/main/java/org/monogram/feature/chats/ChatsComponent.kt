package org.monogram.feature.chats

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import org.monogram.core.common.push.NotificationLocalStore
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    client: MtprotoClient,
    warmup: OfflineWarmup?,
    sessionStore: SessionMetadataStore?,
    notifications: NotificationLocalStore? = null,
    val mediaRepository: MediaRepository? = null,
    private val onOpenChat: (PeerId, Boolean) -> Unit,
    private val onOpenProfile: (PeerId) -> Unit = {},
    private val onOpenSelfProfile: () -> Unit = {},
    private val onOpenSettings: () -> Unit = {},
    private val onOpenFolders: () -> Unit = {},
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        ChatsStoreFactory(storeFactory, client, warmup, sessionStore, notifications).create()
    }

    val state: StateFlow<ChatsStore.State> = store.stateFlow


    fun onRefresh() = store.accept(ChatsStore.Intent.Refresh)
    fun onLoadMore() = store.accept(ChatsStore.Intent.LoadMore)
    fun onQueryChanged(value: String) = store.accept(ChatsStore.Intent.QueryChanged(value))
    fun onMarkRead(chatIds: List<PeerId>) = store.accept(ChatsStore.Intent.MarkRead(chatIds))
    fun onMarkUnread(chatId: PeerId, unread: Boolean) =
        store.accept(ChatsStore.Intent.MarkUnread(chatId, unread))
    fun onFolderSelected(folderId: Int?) = store.accept(ChatsStore.Intent.FolderSelected(folderId))
    fun onChatClick(id: PeerId) {
        val forum = state.value.chats.firstOrNull { it.id == id }?.isForum == true
        onOpenChat(id, forum)
    }
    fun onPeerProfile(id: PeerId) = onOpenProfile(id)
    fun onOpenSelfProfile() = onOpenSelfProfile.invoke()
    fun onOpenSettings() = onOpenSettings.invoke()
    fun onOpenFolders() = onOpenFolders.invoke()
}
