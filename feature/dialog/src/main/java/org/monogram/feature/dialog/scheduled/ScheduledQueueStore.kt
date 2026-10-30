package org.monogram.feature.dialog.scheduled

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.PeerId
import org.monogram.core.models.ScheduledMessage
import org.monogram.core.models.TextEntity
import org.monogram.network.bridge.message.MessageOps
import kotlin.coroutines.CoroutineContext

private sealed interface QueueMutation {
    data object SendNow : QueueMutation
    data object Cancel : QueueMutation
    data class Reschedule(val date: Int) : QueueMutation
    data class Edit(
        val original: ScheduledMessage, val text: String, val entities: List<TextEntity>
    ) : QueueMutation
}

interface ScheduledQueueStore :
    Store<ScheduledQueueStore.Intent, ScheduledQueueStore.State, Nothing> {
    sealed interface Intent {
        data object Resume : Intent
        data object Pause : Intent
        data class Preview(val messageId: Int) : Intent
        data object ClosePreview : Intent
        data object RetryPreview : Intent
        data object Refresh : Intent
        data class SendNow(val messageId: Int) : Intent
        data class Cancel(val messageId: Int) : Intent
        data class Reschedule(val messageId: Int, val date: Int) : Intent
        data class Edit(
            val original: ScheduledMessage, val text: String, val entities: List<TextEntity>
        ) : Intent
    }

    data class PreviewState(
        val message: ScheduledMessage,
        val file: java.io.File? = null,
        val loading: Boolean = false,
        val error: TelegramError? = null
    )

    data class State(
        val preview: PreviewState? = null,
        val messages: List<ScheduledMessage> = emptyList(),
        val loading: Boolean = false,
        val fromCache: Boolean = true,
        val needsRefresh: Boolean = false,
        val error: TelegramError? = null,
        val mutatingId: Int? = null,
        val uncertainIds: Set<Int> = emptySet(),
    )
}

internal class ScheduledQueueStoreFactory(
    private val factory: StoreFactory,
    private val client: MessageOps,
    private val warmup: OfflineWarmup?,
    private val chatId: PeerId,
    private val mainContext: CoroutineContext = Dispatchers.Main.immediate,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val loadPreview: (suspend (ScheduledMessage) -> Outcome<java.io.File>)? = null,
) {
    fun create(): ScheduledQueueStore = object : ScheduledQueueStore,
        Store<ScheduledQueueStore.Intent, ScheduledQueueStore.State, Nothing> by factory.create(
            name = "ScheduledQueueStore", initialState = ScheduledQueueStore.State(),
            bootstrapper = SimpleBootstrapper(Unit), executorFactory = { Executor() },
            reducer = Reducer<ScheduledQueueStore.State, ScheduledQueueStore.State> { it },
        ) {}

    private inner class Executor :
        CoroutineExecutor<ScheduledQueueStore.Intent, Unit, ScheduledQueueStore.State, ScheduledQueueStore.State, Nothing>(
            mainContext
        ) {
        private val accountEpoch = warmup?.outgoingAccountEpoch()
        private var refresh: Job? = null
        private var resumed = false
        private var generation = 0L
        private var mutationGeneration = 0L
        private var mutation: Job? = null
        private var previewJob: Job? = null
        private var previewGeneration = 0L
        private var blockedWall = 0L
        private var blockedElapsed = 0L

        private suspend fun floodError(): TelegramError? {
            val persisted = warmup?.outgoingSendBlockedUntil() ?: 0
            if (persisted > blockedWall) {
                blockedWall = persisted
                blockedElapsed =
                    maxOf(blockedElapsed, elapsedMs() + (persisted - nowMs()).coerceAtLeast(0))
            }
            val remaining = maxOf(blockedWall - nowMs(), blockedElapsed - elapsedMs())
            return if (remaining > 0) TelegramError.parse("FLOOD_WAIT_${(remaining + 999) / 1000}") else null
        }

        private suspend fun recordFlood(error: TelegramError) {
            if (error.kind != TelegramError.Kind.Flood) return
            val delay = (error.retryAfterSeconds?.toLong() ?: 0).coerceAtLeast(0) * 1000
            blockedWall = maxOf(blockedWall, nowMs() + delay)
            blockedElapsed = maxOf(blockedElapsed, elapsedMs() + delay)
            accountEpoch?.let { warmup?.blockScheduledQueueUntil(blockedWall, it) }
        }

        override fun executeAction(action: Unit) {
            warmup?.observeScheduledMessages(chatId)?.onEach { rows ->
                invalidatePreview(rows)
                dispatch(state().copy(messages = ordered(rows)))
            }?.launchIn(scope)
        }

        override fun executeIntent(intent: ScheduledQueueStore.Intent) {
            when (intent) {
                ScheduledQueueStore.Intent.Resume -> {
                    resumed = true; fetch()
                    state().preview?.takeIf { it.file?.let { file -> file.exists() && file.length() > 0 } != true }
                        ?.let { openPreview(it.message.message.id.id) }
                }

                is ScheduledQueueStore.Intent.Preview -> openPreview(intent.messageId)
                ScheduledQueueStore.Intent.ClosePreview -> closePreview()
                ScheduledQueueStore.Intent.RetryPreview -> state().preview?.let { openPreview(it.message.message.id.id) }
                ScheduledQueueStore.Intent.Refresh -> if (resumed) fetch()
                is ScheduledQueueStore.Intent.SendNow -> mutate(
                    intent.messageId, QueueMutation.SendNow
                )

                is ScheduledQueueStore.Intent.Cancel -> mutate(
                    intent.messageId, QueueMutation.Cancel
                )

                is ScheduledQueueStore.Intent.Reschedule -> if (org.monogram.feature.dialog.store.validSendSchedule(
                        intent.date, nowMs() / 1000
                    )
                ) mutate(intent.messageId, QueueMutation.Reschedule(intent.date))

                is ScheduledQueueStore.Intent.Edit -> {
                    val original = intent.original
                    if (original.canEditText && original.message.date in 1..Int.MAX_VALUE.toLong() && state().messages.contains(
                            original
                        ) && (original.message.mediaKind != null || intent.text.isNotBlank()) && intent.entities.all { it.offset >= 0 && it.length > 0 && it.offset <= intent.text.length - it.length }
                    ) {
                        mutate(
                            original.message.id.id,
                            QueueMutation.Edit(original, intent.text, intent.entities.toList())
                        )
                    }
                }

                ScheduledQueueStore.Intent.Pause -> {
                    resumed = false
                    previewGeneration++
                    previewJob?.cancel()
                    state().preview?.let { dispatch(state().copy(preview = it.copy(loading = false))) }
                    generation++
                    refresh?.cancel()
                    val pending = state().mutatingId
                    mutationGeneration++
                    mutation?.cancel()
                    dispatch(
                        state().copy(
                            loading = false,
                            mutatingId = null,
                            uncertainIds = state().uncertainIds + listOfNotNull(pending)
                        )
                    )
                }
            }
        }

        private fun closePreview() {
            previewGeneration++
            previewJob?.cancel()
            dispatch(state().copy(preview = null))
        }

        private fun invalidatePreview(rows: List<ScheduledMessage>) {
            state().preview?.let { selected -> if (rows.none { it == selected.message }) closePreview() }
        }

        private fun openPreview(id: Int) {
            val load = loadPreview ?: return
            if (!resumed) return
            val selected =
                state().messages.singleOrNull { it.message.id.id == id && it.canPreviewMedia }
                    ?: return
            if (state().preview?.let { it.message == selected && it.loading } == true) return
            previewJob?.cancel()
            val token = ++previewGeneration
            dispatch(
                state().copy(
                    preview = ScheduledQueueStore.PreviewState(
                        selected, loading = true
                    )
                )
            )
            previewJob = scope.launch {
                try {
                    accountEpoch?.let { warmup?.scheduledQueueRevision(chatId, it) }
                    val wait = floodError()
                    if (wait != null) {
                        dispatch(
                            state().copy(
                                preview = ScheduledQueueStore.PreviewState(
                                    selected, error = wait
                                )
                            )
                        )
                        return@launch
                    }
                    val result = load(selected)
                    currentCoroutineContext().ensureActive()
                    accountEpoch?.let { warmup?.scheduledQueueRevision(chatId, it) }
                    if (token != previewGeneration || !resumed) return@launch
                    if (result is Outcome.Err) recordFlood(result.telegramError)
                    currentCoroutineContext().ensureActive()
                    if (token != previewGeneration || !resumed) return@launch
                    dispatch(
                        state().copy(
                            preview = when (result) {
                                is Outcome.Ok -> ScheduledQueueStore.PreviewState(
                                    selected, file = result.value
                                )

                                is Outcome.Err -> ScheduledQueueStore.PreviewState(
                                    selected, error = result.telegramError
                                )
                            }
                        )
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (token == previewGeneration && resumed) dispatch(
                        state().copy(
                            preview = ScheduledQueueStore.PreviewState(
                                selected, error = TelegramError.parse("RPC 500: INTERNAL")
                            )
                        )
                    )
                }
            }
        }

        private fun ordered(rows: List<ScheduledMessage>): List<ScheduledMessage> {
            require(rows.all { it.message.id.chatId == chatId && it.message.id.id > 0 })
            require(rows.map { it.message.id.id }.distinct().size == rows.size)
            return rows.sortedWith(compareBy<ScheduledMessage> { it.message.date }.thenBy { it.message.id.id })
        }

        private fun mutate(id: Int, action: QueueMutation) {
            if (!resumed || state().fromCache || state().needsRefresh || state().mutatingId != null || id in state().uncertainIds || state().messages.none { it.message.id.id == id }) return
            generation++
            refresh?.cancel()
            val token = ++mutationGeneration
            dispatch(state().copy(loading = false, error = null, mutatingId = id))
            mutation = scope.launch {
                var confirmed = false
                try {
                    accountEpoch?.let { warmup?.scheduledQueueRevision(chatId, it) }
                    currentCoroutineContext().ensureActive()
                    val wait = floodError()
                    if (wait != null) {
                        dispatch(state().copy(error = wait, needsRefresh = true))
                        return@launch
                    }
                    val result = when (action) {
                        QueueMutation.SendNow -> client.sendScheduledMessages(chatId, listOf(id))
                        QueueMutation.Cancel -> client.deleteScheduledMessages(chatId, listOf(id))
                        is QueueMutation.Reschedule -> client.editScheduledMessage(
                            chatId, id, action.date
                        )

                        is QueueMutation.Edit -> {
                            if (!state().messages.contains(action.original)) return@launch
                            client.editScheduledMessage(
                                chatId,
                                id,
                                action.original.message.date.toInt(),
                                action.text,
                                org.monogram.core.models.TextEntities.serialize(action.entities),
                                noWebpage = true
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (token != mutationGeneration) return@launch
                    when (result) {
                        is Outcome.Ok -> confirmed = true
                        is Outcome.Err -> {
                            recordFlood(result.telegramError)
                            dispatch(
                                state().copy(
                                    error = result.telegramError,
                                    needsRefresh = true,
                                    uncertainIds = state().uncertainIds + id
                                )
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (token == mutationGeneration) dispatch(
                        state().copy(
                            needsRefresh = true, uncertainIds = state().uncertainIds + id
                        )
                    )
                    throw cancelled
                } catch (_: Exception) {
                    if (token == mutationGeneration) dispatch(
                        state().copy(
                            error = TelegramError.parse("RPC 500: INTERNAL"),
                            needsRefresh = true,
                            uncertainIds = state().uncertainIds + id
                        )
                    )
                } finally {
                    if (token == mutationGeneration) {
                        dispatch(state().copy(mutatingId = null))
                        if (confirmed && resumed) fetch()
                    }
                }
            }
        }

        private fun fetch() {
            if (state().mutatingId != null || refresh?.isActive == true) return
            val token = ++generation
            dispatch(state().copy(loading = true, error = null))
            refresh = scope.launch {
                try {
                    val revision = accountEpoch?.let { warmup?.scheduledQueueRevision(chatId, it) }
                    val wait = floodError()
                    if (wait != null) {
                        dispatch(state().copy(error = wait, needsRefresh = true))
                        return@launch
                    }
                    when (val result = client.getScheduledHistory(chatId)) {
                        is Outcome.Ok -> {
                            currentCoroutineContext().ensureActive()
                            val rows = ordered(result.value)
                            val accepted =
                                if (warmup != null && accountEpoch != null && revision != null) warmup.replaceScheduledMessagesIfCurrent(
                                    chatId, revision, rows, accountEpoch
                                )
                                else true
                            if (warmup == null) invalidatePreview(rows)
                            dispatch(
                                state().copy(
                                    messages = if (warmup == null) rows else state().messages,
                                    fromCache = !accepted, needsRefresh = !accepted,
                                    uncertainIds = if (accepted) emptySet() else state().uncertainIds,
                                )
                            )
                        }

                        is Outcome.Err -> if (token == generation) {
                            recordFlood(result.telegramError)
                            dispatch(
                                state().copy(
                                    error = result.telegramError, needsRefresh = true
                                )
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (token == generation) dispatch(
                        state().copy(
                            error = TelegramError.parse("RPC 500: INTERNAL"), needsRefresh = true
                        )
                    )
                } finally {
                    if (token == generation) dispatch(state().copy(loading = false))
                }
            }
        }
    }
}
