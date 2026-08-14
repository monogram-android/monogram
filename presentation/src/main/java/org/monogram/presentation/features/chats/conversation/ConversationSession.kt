package org.monogram.presentation.features.chats.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ConversationUpdate
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryPage
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.ConversationPipelineMode
import org.monogram.domain.repository.BoundaryState
import org.monogram.domain.repository.HistorySource
import java.util.concurrent.atomic.AtomicBoolean

internal object ConversationPipelineFallbackGate {
    private val fallbackRequested = AtomicBoolean(false)

    fun modeFor(configured: ConversationPipelineMode): ConversationPipelineMode =
        if (fallbackRequested.get()) ConversationPipelineMode.Legacy else configured

    fun requestFallback() {
        fallbackRequested.set(true)
    }

    internal fun resetForTest() {
        fallbackRequested.set(false)
    }
}

internal data class ConversationSessionState(
    val generation: Long = 0L,
    val messages: List<MessageModel> = emptyList(),
    val outgoingMessageStates: Map<OutgoingMessageReducer.Key, OutgoingMessageReducer.State> = emptyMap(),
    val ascending: Boolean = false,
    val closed: Boolean = false,
    val rejectedLateResults: Long = 0L,
    val isLoadingInitial: Boolean = false,
    val isLoadingHistoryRequest: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isLoadingNewer: Boolean = false,
    val isOldestLoaded: Boolean = false,
    val isLatestLoaded: Boolean = true
)

internal sealed interface ConversationSessionCommand {
    data class ApplySnapshot(
        val generation: Long,
        val messages: List<MessageModel>,
        val ascending: Boolean,
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    ) : ConversationSessionCommand

    data class LoadHistory(
        val generation: Long,
        val request: HistoryRequest,
        val completion: CompletableDeferred<HistoryPage> = CompletableDeferred()
    ) : ConversationSessionCommand

    data class ApplyUpdate(
        val update: ConversationUpdate,
        val canInsertNewMessage: Boolean,
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    ) : ConversationSessionCommand

    data class TransformMessage(
        val chatId: Long,
        val messageId: Long,
        val transform: (MessageModel) -> MessageModel,
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    ) : ConversationSessionCommand

    data class HistoryLoaded(
        val request: HistoryRequest,
        val generation: Long,
        val token: Any,
        val result: Result<HistoryPage>
    ) : ConversationSessionCommand

    data class AdvanceGeneration(val generation: Long) : ConversationSessionCommand

    data class MarkBoundaryReached(
        val generation: Long,
        val direction: HistoryDirection
    ) : ConversationSessionCommand

    data class SetOperationLoading(
        val generation: Long,
        val loading: Boolean,
        val resetBoundaries: Boolean,
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    ) : ConversationSessionCommand

    data class SetBoundaries(
        val generation: Long,
        val oldestLoaded: Boolean,
        val latestLoaded: Boolean,
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    ) : ConversationSessionCommand

    data class Close(
        val generation: Long,
        val completion: CompletableDeferred<Unit> = CompletableDeferred()
    ) : ConversationSessionCommand
}

internal object ConversationWindowReducer {
    fun applySnapshot(
        state: ConversationSessionState,
        generation: Long,
        messages: List<MessageModel>,
        ascending: Boolean
    ): ConversationSessionState {
        if (state.closed || generation < state.generation) {
            return state.copy(rejectedLateResults = state.rejectedLateResults + 1L)
        }
        val normalized = messages
            .associateBy(MessageModel::id)
            .values
            .let { unique ->
                if (ascending) {
                    unique.sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
                } else {
                    unique.sortedWith(compareByDescending<MessageModel> { it.date }.thenByDescending { it.id })
                }
            }
        return state.copy(
            generation = generation,
            messages = normalized,
            outgoingMessageStates = state.outgoingMessageStates + OutgoingMessageReducer.recover(
                normalized
            ),
            ascending = ascending,
            closed = false
        )
    }

    fun applyUpdate(
        state: ConversationSessionState,
        update: ConversationUpdate,
        canInsertNewMessage: Boolean
    ): ConversationSessionState {
        if (state.closed) return state
        val messages = when (update) {
            is ConversationUpdate.Upsert -> {
                val hasMessage =
                    state.messages.any { it.chatId == update.chatId && it.id == update.message.id }
                when {
                    hasMessage -> state.messages.map { current ->
                        if (current.chatId == update.chatId && current.id == update.message.id) update.message else current
                    }

                    update.isNew && canInsertNewMessage -> state.messages + update.message
                    else -> state.messages
                }
            }

            is ConversationUpdate.ReplaceTemporaryId -> {
                val hadTemporary = state.messages.any {
                    it.chatId == update.chatId && it.id == update.temporaryMessageId
                }
                val hadFinal = state.messages.any {
                    it.chatId == update.chatId && it.id == update.message.id
                }
                val withoutReplacedIds = state.messages.filterNot {
                    it.chatId == update.chatId &&
                            (it.id == update.temporaryMessageId || it.id == update.message.id)
                }
                if (hadTemporary || hadFinal || canInsertNewMessage) withoutReplacedIds + update.message
                else withoutReplacedIds
            }

            is ConversationUpdate.SendFailed -> {
                val hadTemporary = state.messages.any {
                    it.chatId == update.chatId && it.id == update.temporaryMessageId
                }
                if (hadTemporary) {
                    state.messages.map { current ->
                        if (current.chatId == update.chatId && current.id == update.temporaryMessageId) {
                            update.message
                        } else {
                            current
                        }
                    }
                } else {
                    state.messages
                }
            }

            is ConversationUpdate.Delete -> state.messages.filterNot {
                it.chatId == update.chatId && it.id in update.messageIds
            }

            is ConversationUpdate.InboxRead -> state.messages.map { message ->
                if (message.chatId == update.chatId && !message.isOutgoing &&
                    !message.isRead && message.id <= update.lastReadMessageId
                ) message.copy(isRead = true) else message
            }

            is ConversationUpdate.OutboxRead -> state.messages.map { message ->
                if (message.chatId == update.chatId && message.isOutgoing &&
                    !message.isRead && message.id <= update.lastReadMessageId
                ) message.copy(isRead = true) else message
            }

            is ConversationUpdate.SendAcknowledged -> state.messages
        }

        val outgoingStates = when (update) {
            is ConversationUpdate.Upsert -> OutgoingMessageReducer.pending(
                state.outgoingMessageStates,
                update.message
            )

            is ConversationUpdate.ReplaceTemporaryId -> OutgoingMessageReducer.succeeded(
                current = state.outgoingMessageStates,
                key = OutgoingMessageReducer.Key(update.chatId, update.temporaryMessageId),
                finalMessageId = update.message.id
            )

            is ConversationUpdate.SendAcknowledged -> OutgoingMessageReducer.acknowledged(
                current = state.outgoingMessageStates,
                key = OutgoingMessageReducer.Key(update.chatId, update.temporaryMessageId)
            )

            is ConversationUpdate.SendFailed -> OutgoingMessageReducer.failed(
                current = state.outgoingMessageStates,
                key = OutgoingMessageReducer.Key(update.chatId, update.temporaryMessageId),
                errorCode = update.errorCode
            )

            is ConversationUpdate.Delete -> state.outgoingMessageStates.filterNot { (key, value) ->
                key.chatId == update.chatId &&
                        (key.temporaryMessageId in update.messageIds ||
                                (value as? OutgoingMessageReducer.State.Succeeded)?.finalMessageId in update.messageIds)
            }

            is ConversationUpdate.InboxRead,
            is ConversationUpdate.OutboxRead -> state.outgoingMessageStates
        }

        return state.copy(
            messages = normalize(messages, state.ascending),
            outgoingMessageStates = outgoingStates
        )
    }

    private fun normalize(messages: List<MessageModel>, ascending: Boolean): List<MessageModel> =
        messages
            .associateBy { it.chatId to it.id }
            .values
            .let { unique ->
                if (ascending) {
                    unique.sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
                } else {
                    unique.sortedWith(compareByDescending<MessageModel> { it.date }.thenByDescending { it.id })
                }
            }
}

internal class ConversationSession(
    private val scope: CoroutineScope,
    private val historyLoader: suspend (HistoryRequest) -> HistoryPage
) {
    private val commands = Channel<ConversationSessionCommand>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(ConversationSessionState())
    val state: StateFlow<ConversationSessionState> = _state.asStateFlow()

    private data class ActiveLoad(
        val generation: Long,
        val request: HistoryRequest,
        val token: Any,
        val job: Job,
        val waiters: MutableList<CompletableDeferred<HistoryPage>>
    )

    private val activeLoads = LinkedHashMap<HistoryRequest, ActiveLoad>()

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is ConversationSessionCommand.ApplySnapshot -> {
                        _state.value = ConversationWindowReducer.applySnapshot(
                            state = _state.value,
                            generation = command.generation,
                            messages = command.messages,
                            ascending = command.ascending
                        )
                        command.completion.complete(Unit)
                    }

                    is ConversationSessionCommand.LoadHistory -> {
                        handleLoadHistory(command)
                    }

                    is ConversationSessionCommand.ApplyUpdate -> {
                        _state.value = ConversationWindowReducer.applyUpdate(
                            state = _state.value,
                            update = command.update,
                            canInsertNewMessage = command.canInsertNewMessage
                        )
                        command.completion.complete(Unit)
                    }

                    is ConversationSessionCommand.TransformMessage -> {
                        val current = _state.value
                        val transformed = current.messages.map { message ->
                            if (message.chatId == command.chatId && message.id == command.messageId) {
                                command.transform(message)
                            } else {
                                message
                            }
                        }
                        _state.value = current.copy(messages = transformed)
                        command.completion.complete(Unit)
                    }

                    is ConversationSessionCommand.HistoryLoaded -> {
                        handleHistoryLoaded(command)
                    }

                    is ConversationSessionCommand.AdvanceGeneration -> {
                        applyGeneration(command.generation)
                    }

                    is ConversationSessionCommand.MarkBoundaryReached -> {
                        if (command.generation == _state.value.generation) {
                            _state.value = when (command.direction) {
                                HistoryDirection.Older -> _state.value.copy(isOldestLoaded = true)
                                HistoryDirection.Newer, HistoryDirection.Initial ->
                                    _state.value.copy(isLatestLoaded = true)

                                HistoryDirection.Around -> _state.value
                            }
                        }
                    }

                    is ConversationSessionCommand.SetOperationLoading -> {
                        if (command.generation >= _state.value.generation) {
                            applyGeneration(command.generation)
                            if (command.generation == _state.value.generation) {
                                _state.value = _state.value.copy(
                                    isLoadingInitial = command.loading,
                                    isOldestLoaded = if (command.resetBoundaries) false else _state.value.isOldestLoaded,
                                    isLatestLoaded = if (command.resetBoundaries) false else _state.value.isLatestLoaded
                                )
                            }
                        }
                        command.completion.complete(Unit)
                    }

                    is ConversationSessionCommand.SetBoundaries -> {
                        if (command.generation == _state.value.generation) {
                            _state.value = _state.value.copy(
                                isOldestLoaded = command.oldestLoaded,
                                isLatestLoaded = command.latestLoaded
                            )
                        }
                        command.completion.complete(Unit)
                    }

                    is ConversationSessionCommand.Close -> {
                        applyGeneration(command.generation)
                        cancelLoads { true }
                        _state.value = _state.value.copy(closed = true)
                        command.completion.complete(Unit)
                        commands.close()
                    }
                }
            }
        }
    }

    private fun handleLoadHistory(command: ConversationSessionCommand.LoadHistory) {
        if (_state.value.closed || command.generation < _state.value.generation) {
            command.completion.cancel(CancellationException("Stale conversation generation"))
            return
        }
        applyGeneration(command.generation)

        activeLoads[command.request]?.let { active ->
            active.waiters += command.completion
            return
        }

        val direction = command.request.direction
        if (direction == HistoryDirection.Initial || direction == HistoryDirection.Around) {
            cancelLoads { true }
        } else {
            cancelLoads { it.request.direction == direction }
        }

        val token = Any()
        val job = scope.launch {
            val result = runCatching { historyLoader(command.request) }
            commands.trySend(
                ConversationSessionCommand.HistoryLoaded(
                    request = command.request,
                    generation = command.generation,
                    token = token,
                    result = result
                )
            )
        }
        activeLoads[command.request] = ActiveLoad(
            generation = command.generation,
            request = command.request,
            token = token,
            job = job,
            waiters = mutableListOf(command.completion)
        )
        syncLoadingState()
    }

    private fun handleHistoryLoaded(command: ConversationSessionCommand.HistoryLoaded) {
        val active = activeLoads[command.request]
            ?.takeIf { it.token === command.token }
            ?: return
        activeLoads.remove(command.request)
        syncLoadingState()
        if (_state.value.closed || command.generation != _state.value.generation) {
            active.waiters.forEach { it.cancel(CancellationException("Stale conversation generation")) }
            return
        }
        command.result.fold(
            onSuccess = { page ->
                applyHistoryBoundaries(command.request, page)
                active.waiters.forEach { it.complete(page) }
            },
            onFailure = { error -> active.waiters.forEach { it.completeExceptionally(error) } }
        )
    }

    private fun applyGeneration(generation: Long) {
        if (generation <= _state.value.generation) return
        _state.value = _state.value.copy(generation = generation)
        cancelLoads { it.generation < generation }
    }

    private fun cancelLoads(predicate: (ActiveLoad) -> Boolean) {
        activeLoads.values.filter(predicate).forEach { active ->
            activeLoads.remove(active.request)
            active.job.cancel()
            active.waiters.forEach { it.cancel(CancellationException("Conversation load superseded")) }
        }
        syncLoadingState()
    }

    private fun syncLoadingState() {
        val directions = activeLoads.values.mapTo(mutableSetOf()) { it.request.direction }
        _state.value = _state.value.copy(
            isLoadingHistoryRequest = HistoryDirection.Initial in directions || HistoryDirection.Around in directions,
            isLoadingOlder = HistoryDirection.Older in directions,
            isLoadingNewer = HistoryDirection.Newer in directions
        )
    }

    private fun applyHistoryBoundaries(request: HistoryRequest, page: HistoryPage) {
        val current = _state.value
        _state.value = when (request.direction) {
            HistoryDirection.Initial -> current.copy(
                isOldestLoaded = page.olderBoundary is BoundaryState.Reached
            )

            HistoryDirection.Around -> current.copy(
                isOldestLoaded = page.olderBoundary is BoundaryState.Reached,
                isLatestLoaded = page.newerBoundary is BoundaryState.Reached
            )

            HistoryDirection.Older -> current.copy(
                isOldestLoaded = current.isOldestLoaded || page.olderBoundary is BoundaryState.Reached
            )

            HistoryDirection.Newer -> current.copy(
                isLatestLoaded = current.isLatestLoaded ||
                        page.newerBoundary is BoundaryState.Reached ||
                        (page.source == HistorySource.TdlibNetwork && page.messages.size < request.limit)
            )
        }
    }

    suspend fun applySnapshot(
        generation: Long,
        messages: List<MessageModel>,
        ascending: Boolean
    ): ConversationSessionState {
        val command = ConversationSessionCommand.ApplySnapshot(generation, messages, ascending)
        check(commands.trySend(command).isSuccess) { "Conversation session is closed" }
        command.completion.await()
        return state.value
    }

    suspend fun loadHistory(generation: Long, request: HistoryRequest): HistoryPage {
        val command = ConversationSessionCommand.LoadHistory(generation, request)
        check(commands.trySend(command).isSuccess) { "Conversation session is closed" }
        return command.completion.await()
    }

    suspend fun applyUpdate(
        update: ConversationUpdate,
        canInsertNewMessage: Boolean
    ): ConversationSessionState {
        val command = ConversationSessionCommand.ApplyUpdate(update, canInsertNewMessage)
        check(commands.trySend(command).isSuccess) { "Conversation session is closed" }
        command.completion.await()
        return state.value
    }

    suspend fun transformMessage(
        chatId: Long,
        messageId: Long,
        transform: (MessageModel) -> MessageModel
    ): ConversationSessionState {
        val command = ConversationSessionCommand.TransformMessage(chatId, messageId, transform)
        check(commands.trySend(command).isSuccess) { "Conversation session is closed" }
        command.completion.await()
        return state.value
    }

    fun advanceGeneration(generation: Long) {
        commands.trySend(ConversationSessionCommand.AdvanceGeneration(generation))
    }

    fun markBoundaryReached(generation: Long, direction: HistoryDirection) {
        commands.trySend(ConversationSessionCommand.MarkBoundaryReached(generation, direction))
    }

    suspend fun setOperationLoading(
        generation: Long,
        loading: Boolean,
        resetBoundaries: Boolean = false
    ) {
        val command = ConversationSessionCommand.SetOperationLoading(
            generation = generation,
            loading = loading,
            resetBoundaries = resetBoundaries
        )
        check(commands.trySend(command).isSuccess) { "Conversation session is closed" }
        command.completion.await()
    }

    suspend fun setBoundaries(
        generation: Long,
        oldestLoaded: Boolean,
        latestLoaded: Boolean
    ) {
        val command = ConversationSessionCommand.SetBoundaries(
            generation = generation,
            oldestLoaded = oldestLoaded,
            latestLoaded = latestLoaded
        )
        check(commands.trySend(command).isSuccess) { "Conversation session is closed" }
        command.completion.await()
    }

    fun close(generation: Long) {
        val command = ConversationSessionCommand.Close(generation)
        commands.trySend(command)
    }
}
