package org.monogram.feature.dialog.scheduled

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.message.MessageOps
import org.monogram.network.http.MediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledQueueComponent(
    componentContext: ComponentContext,
    factory: StoreFactory,
    client: MessageOps,
    warmup: OfflineWarmup?,
    val chatId: PeerId,
    val onBack: () -> Unit,
    val mediaRepository: MediaRepository? = null,
) : ComponentContext by componentContext {
    private val store = instanceKeeper.getStore {
        ScheduledQueueStoreFactory(
            factory, client, warmup, chatId, loadPreview = mediaRepository?.let { repository ->
                { message ->
                    withContext(Dispatchers.IO) {
                        repository.ensureLocalScheduledMessageMedia(message)
                    }
                }
            }).create()
    }
    val state = store.stateFlow

    init {
        lifecycle.doOnStart { store.accept(ScheduledQueueStore.Intent.Resume) }
        lifecycle.doOnStop { store.accept(ScheduledQueueStore.Intent.Pause) }
    }

    fun onPreview(id: Int) = store.accept(ScheduledQueueStore.Intent.Preview(id))
    fun onClosePreview() = store.accept(ScheduledQueueStore.Intent.ClosePreview)
    fun onRetryPreview() = store.accept(ScheduledQueueStore.Intent.RetryPreview)
    fun onEdit(
        original: org.monogram.core.models.ScheduledMessage,
        text: String,
        entities: List<org.monogram.core.models.TextEntity>
    ) = store.accept(ScheduledQueueStore.Intent.Edit(original, text, entities))

    fun onReschedule(id: Int, date: Int) =
        store.accept(ScheduledQueueStore.Intent.Reschedule(id, date))

    fun onSendNow(id: Int) = store.accept(ScheduledQueueStore.Intent.SendNow(id))
    fun onCancel(id: Int) = store.accept(ScheduledQueueStore.Intent.Cancel(id))
    fun onRefresh() = store.accept(ScheduledQueueStore.Intent.Refresh)
}