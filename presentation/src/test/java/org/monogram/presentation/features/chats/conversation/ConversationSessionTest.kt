package org.monogram.presentation.features.chats.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ConversationUpdate
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.BoundaryState
import org.monogram.domain.repository.ConversationKey
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryPage
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.HistorySource
import org.monogram.presentation.features.chats.conversation.logic.withConversationSessionUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionTest {
    @Test
    fun `equal requests share one loader call`() = runTest {
        val result = CompletableDeferred<HistoryPage>()
        var calls = 0
        val session = ConversationSession(this) {
            calls++
            result.await()
        }
        val request = request(HistoryDirection.Older, anchorId = 20L)

        val first = async { session.loadHistory(1L, request) }
        val second = async { session.loadHistory(1L, request) }
        runCurrent()

        assertEquals(1, calls)
        result.complete(page(10L))
        assertEquals(listOf(10L), first.await().messages.map(MessageModel::id))
        assertEquals(listOf(10L), second.await().messages.map(MessageModel::id))

        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `jump cancels active edge load`() = runTest {
        val olderStarted = CompletableDeferred<Unit>()
        val olderResult = CompletableDeferred<HistoryPage>()
        val session = ConversationSession(this) { request ->
            when (request.direction) {
                HistoryDirection.Older -> {
                    olderStarted.complete(Unit)
                    olderResult.await()
                }

                HistoryDirection.Around -> page(50L)
                else -> error("Unexpected direction ${request.direction}")
            }
        }

        val older = async { session.loadHistory(1L, request(HistoryDirection.Older, 20L)) }
        olderStarted.await()
        val jump = async { session.loadHistory(1L, request(HistoryDirection.Around, 50L)) }
        runCurrent()

        assertEquals(listOf(50L), jump.await().messages.map(MessageModel::id))
        assertTrue(older.isCancelled)

        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `jump cancels active initial load`() = runTest {
        val initialStarted = CompletableDeferred<Unit>()
        val initialResult = CompletableDeferred<HistoryPage>()
        val session = ConversationSession(this) { request ->
            when (request.direction) {
                HistoryDirection.Initial -> {
                    initialStarted.complete(Unit)
                    initialResult.await()
                }

                HistoryDirection.Around -> page(50L)
                else -> error("Unexpected direction ${request.direction}")
            }
        }

        val initial = async { session.loadHistory(1L, request(HistoryDirection.Initial)) }
        initialStarted.await()
        val jump = async { session.loadHistory(1L, request(HistoryDirection.Around, 50L)) }
        runCurrent()

        assertEquals(listOf(50L), jump.await().messages.map(MessageModel::id))
        assertTrue(initial.isCancelled)

        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `generation advance cancels old result before delivery`() = runTest {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<HistoryPage>()
        val session = ConversationSession(this) {
            started.complete(Unit)
            result.await()
        }

        val load = async { session.loadHistory(1L, request(HistoryDirection.Initial)) }
        started.await()
        session.advanceGeneration(2L)
        runCurrent()

        assertTrue(load.isCancelled)
        assertEquals(2L, session.state.value.generation)

        session.close(3L)
        advanceUntilIdle()
    }

    @Test
    fun `session owns edge loading and boundary state`() = runTest {
        val result = CompletableDeferred<HistoryPage>()
        val session = ConversationSession(this) { result.await() }
        val load = async { session.loadHistory(1L, request(HistoryDirection.Older, 20L)) }
        runCurrent()

        assertTrue(session.state.value.isLoadingOlder)
        result.complete(
            HistoryPage(
                messages = listOf(message(10L)),
                olderBoundary = BoundaryState.Reached,
                newerBoundary = BoundaryState.Gap(20L),
                source = HistorySource.NetworkSnapshot
            )
        )
        load.await()

        assertTrue(!session.state.value.isLoadingOlder)
        assertTrue(session.state.value.isOldestLoaded)
        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `history request completion does not clear operation loading`() = runTest {
        val session = ConversationSession(this) { page(1L) }
        session.setOperationLoading(1L, loading = true, resetBoundaries = true)

        session.loadHistory(1L, request(HistoryDirection.Initial))

        assertTrue(session.state.value.isLoadingInitial)
        assertTrue(!session.state.value.isLoadingHistoryRequest)
        session.setOperationLoading(1L, loading = false)
        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `final reconciliation owns exact boundary state`() = runTest {
        val session = ConversationSession(this) { page(1L) }
        session.setOperationLoading(1L, loading = true, resetBoundaries = true)
        session.loadHistory(1L, request(HistoryDirection.Initial))

        session.setBoundaries(1L, oldestLoaded = false, latestLoaded = true)

        assertTrue(!session.state.value.isOldestLoaded)
        assertTrue(session.state.value.isLatestLoaded)
        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `stale operation completion cannot clear current loading`() = runTest {
        val session = ConversationSession(this) { page(1L) }

        session.setOperationLoading(1L, loading = true, resetBoundaries = true)
        session.setOperationLoading(2L, loading = true, resetBoundaries = true)
        session.setOperationLoading(1L, loading = false)

        assertTrue(session.state.value.isLoadingInitial)
        assertEquals(2L, session.state.value.generation)
        session.setOperationLoading(2L, loading = false)
        assertTrue(!session.state.value.isLoadingInitial)
        session.close(3L)
        advanceUntilIdle()
    }

    @Test
    fun `media runtime transform is serialized by session`() = runTest {
        val original = message(10L)
        val session = ConversationSession(this) { page(1L) }
        session.applySnapshot(1L, listOf(original), ascending = false)

        val transformed = session.transformMessage(1L, 10L) { current ->
            current.copy(content = MessageContent.Text("updated"))
        }

        assertEquals("updated", (transformed.messages.single().content as MessageContent.Text).text)
        session.close(2L)
        advanceUntilIdle()
    }

    @Test
    fun `close cancels active load and rejects its result`() = runTest {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<HistoryPage>()
        val session = ConversationSession(this) {
            started.complete(Unit)
            result.await()
        }

        val load = async { session.loadHistory(1L, request(HistoryDirection.Initial)) }
        started.await()
        session.close(2L)
        runCurrent()

        assertTrue(load.isCancelled)
        assertTrue(session.state.value.closed)
        assertEquals(2L, session.state.value.generation)

        advanceUntilIdle()
    }

    @Test
    fun `new projection publishes actor terminal lifecycle`() {
        val message = message(-10L, isOutgoing = true)
        val key = OutgoingMessageReducer.Key(chatId = 1L, temporaryMessageId = -10L)
        val sessionState = ConversationSessionState(
            messages = listOf(message),
            outgoingMessageStates = mapOf(
                key to OutgoingMessageReducer.State.Failed(errorCode = 500, retryable = true)
            )
        )

        val projected =
            ChatComponent.State(messages = listOf(message)).withConversationSessionUpdate(
                sessionState = sessionState,
                update = ConversationUpdate.SendFailed(
                    chatId = 1L,
                    temporaryMessageId = -10L,
                    message = message,
                    errorCode = 500
                ),
                rootChatId = 1L
            )

        assertEquals(sessionState.outgoingMessageStates, projected.outgoingMessageStates)
    }

    private fun request(
        direction: HistoryDirection,
        anchorId: Long? = null
    ) = HistoryRequest(
        key = ConversationKey(chatId = 1L),
        anchor = anchorId?.let(HistoryAnchor::Message) ?: HistoryAnchor.Latest,
        direction = direction,
        limit = 50
    )

    private fun page(messageId: Long) = HistoryPage(
        messages = listOf(message(messageId)),
        olderBoundary = BoundaryState.Open,
        newerBoundary = BoundaryState.Open,
        source = HistorySource.NetworkSnapshot
    )

    private fun message(id: Long, isOutgoing: Boolean = false) = MessageModel(
        id = id,
        date = id.toInt(),
        isOutgoing = isOutgoing,
        senderName = "sender",
        chatId = 1L,
        content = MessageContent.Text("message")
    )
}
