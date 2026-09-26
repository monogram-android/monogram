package org.monogram.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    client: MtprotoClient,
    sessionStore: SessionMetadataStore?,
    val mediaRepository: MediaRepository?,
    private val peerId: PeerId,
    private val onBack: () -> Unit,
    private val onOpenChat: (PeerId) -> Unit = {},
    private val onOpenProfile: (PeerId) -> Unit = onOpenChat,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore {
        ProfileStoreFactory(storeFactory, client, sessionStore, peerId).create()
    }

    val state: StateFlow<ProfileStore.State> = store.stateFlow

    fun onRefresh() = store.accept(ProfileStore.Intent.Refresh)
    fun onBack() = onBack.invoke()
    fun onMessage() = onOpenChat(peerId)

    fun onOpenPeer(peer: PeerId) = onOpenProfile(peer)

    fun onOpenCommonChat(peer: PeerId) = onOpenChat(peer)

    fun onSelectPanel(panel: ProfilePanel) = store.accept(ProfileStore.Intent.SelectPanel(panel))

    fun onLoadMore(panel: ProfilePanel) = store.accept(ProfileStore.Intent.LoadMore(panel))
}
