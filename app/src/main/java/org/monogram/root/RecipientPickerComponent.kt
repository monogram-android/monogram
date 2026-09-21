package org.monogram.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.http.MediaRepository

@Serializable
data class RecipientRequest(
    val share: IncomingShare? = null,
    val fromChatId: Long? = null,
    val messageIds: List<Int> = emptyList(),
) {
    val forwarding: Boolean get() = fromChatId != null && messageIds.isNotEmpty()
}

data class RecipientPickerState(
    val selected: Set<Long> = emptySet(),
    val completed: Set<Long> = emptySet(),
    val comment: String = "",
    val dropAuthor: Boolean = false,
    val sending: Boolean = false,
    val error: Boolean = false,
    val done: Boolean = false,
    val started: Boolean = false,
    val interrupted: Boolean = false,
)

@Serializable
internal data class RecipientProgress(
    val selected: Set<Long> = emptySet(),
    val completed: Set<Long> = emptySet(),
    val comment: String = "",
    val dropAuthor: Boolean = false,
    val nextStep: Map<Long, Int> = emptyMap(),
    val started: Boolean = false,
    val inFlight: Boolean = false,
    val done: Boolean = false,
)

class RecipientPickerComponent(
    context: ComponentContext,
    val request: RecipientRequest,
    client: MtprotoClient,
    warmup: OfflineWarmup?,
    val mediaRepository: MediaRepository?,
    private val close: () -> Unit,
) : ComponentContext by context {
    private val restored = stateKeeper.consume("recipient-progress", RecipientProgress.serializer())
    private val sender = instanceKeeper.getOrCreate { RecipientSender(request, client, warmup, restored) }
    val state: StateFlow<RecipientPickerState> = sender.state
    fun onComment(value: String) = sender.update { if (started) this else copy(comment = value) }
    fun onDropAuthor(value: Boolean) = sender.update { if (started) this else copy(dropAuthor = value) }
    fun onToggle(id: Long) = sender.update {
        if (started) this else copy(selected = if (id in selected) selected - id else selected + id)
    }
    fun onSend() = sender.send()
    fun onClose() { if (!state.value.sending) close() }

    init {
        stateKeeper.register("recipient-progress", RecipientProgress.serializer()) { sender.progress() }
        backHandler.register(object : com.arkivanov.essenty.backhandler.BackCallback(isEnabled = true) {
            override fun onBack() = onClose()
        })
    }
}

private class RecipientSender(
    private val request: RecipientRequest,
    private val client: MtprotoClient,
    private val warmup: OfflineWarmup?,
    restored: RecipientProgress?,
) : InstanceKeeper.Instance {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow(RecipientPickerState(
        comment = restored?.comment ?: request.share?.text.orEmpty(),
        selected = restored?.selected.orEmpty(), completed = restored?.completed.orEmpty(),
        dropAuthor = restored?.dropAuthor ?: false, started = restored?.started ?: false,
        interrupted = restored?.inFlight ?: false, done = restored?.done ?: false,
    ))
    private val nextStep = restored?.nextStep.orEmpty().toMutableMap()
    fun progress() = state.value.let {
        RecipientProgress(it.selected, it.completed, it.comment, it.dropAuthor, nextStep.toMap(), it.started,
            it.sending || it.interrupted, it.done)
    }

    override fun onDestroy() = scope.cancel()
    fun update(block: RecipientPickerState.() -> RecipientPickerState) { state.value = state.value.block() }

    fun send() {
        val snapshot = state.value
        if (snapshot.sending || snapshot.done || snapshot.interrupted || snapshot.selected.isEmpty()) return
        update { copy(sending = true, error = false, started = true) }
        scope.launch {
            try {
                for (id in snapshot.selected - snapshot.completed) {
                    val operations = operations(PeerId(id), snapshot)
                    var step = nextStep[id] ?: 0
                    while (step < operations.size) {
                        when (val result = operations[step]()) {
                            is Outcome.Err -> {
                                update { copy(sending = false, error = true) }
                                return@launch
                            }
                            is Outcome.Ok -> {
                                step++
                                nextStep[id] = step
                                warmup?.upsertMessages(result.value)
                            }
                        }
                    }
                    update { copy(completed = completed + id) }
                }
                update { copy(sending = false, done = true) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { update { copy(sending = false, error = true) } }
        }
    }

    private fun operations(peer: PeerId, snapshot: RecipientPickerState): List<suspend () -> Outcome<List<Message>>> = buildList {
        val comment = snapshot.comment.trim()
        if (request.forwarding) {
            if (comment.isNotEmpty()) add { client.sendText(peer, comment).asMessages() }
            request.messageIds.distinct().sorted().chunked(100).forEach { ids ->
                add { client.forwardMessages(PeerId(requireNotNull(request.fromChatId)), ids, peer, snapshot.dropAuthor) }
            }
        } else {
            val attachments = request.share?.attachments.orEmpty()
            if (attachments.isEmpty()) {
                if (comment.isNotEmpty()) add { client.sendText(peer, comment).asMessages() }
            } else {
                attachments.forEachIndexed { index, item ->
                    add { client.sendUploadedMedia(peer, item.toUploadItem().copy(caption = if (index == 0) comment else "")).asMessages() }
                }
            }
        }
    }
}

private fun Outcome<Message>.asMessages(): Outcome<List<Message>> = when (this) {
    is Outcome.Ok -> Outcome.Ok(listOf(value))
    is Outcome.Err -> this
}
