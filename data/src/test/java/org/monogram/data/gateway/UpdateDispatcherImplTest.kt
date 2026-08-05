package org.monogram.data.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.testing.fakeUpdateLane
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDispatcherImplTest {

    @Test
    fun `routes send acknowledgement separately from terminal send updates`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val updates = MutableSharedFlow<TdApi.Update>()
        val dispatcher = UpdateDispatcherImpl(FakeTelegramGateway(updates))
        val received = mutableListOf<TdApi.UpdateMessageSendAcknowledged>()
        val collector = scope.launch {
            dispatcher.messageSendAcknowledged.collect(received::add)
        }
        runCurrent()

        updates.emit(TdApi.UpdateMessageSendAcknowledged(10L, -11L))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(10L, received.single().chatId)
        assertEquals(-11L, received.single().messageId)
        collector.cancel()
        scope.cancel()
    }

    @Test
    fun `message pipeline routes terminal burst without dropping a lifecycle branch`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val updates = MutableSharedFlow<TdApi.Update>()
        val dispatcher = UpdateDispatcherImpl(FakeTelegramGateway(updates))
        val newMessageIds = mutableListOf<Long>()
        val succeededIds = mutableListOf<Long>()
        val failedIds = mutableListOf<Long>()
        val deletedIds = mutableListOf<Long>()
        val collectors = listOf(
            scope.launch {
                dispatcher.newMessage.take(BURST_SIZE).collect { newMessageIds += it.message.id }
            },
            scope.launch {
                dispatcher.messageSendSucceeded.take(BURST_SIZE)
                    .collect { succeededIds += it.oldMessageId }
            },
            scope.launch {
                dispatcher.messageSendFailed.take(BURST_SIZE)
                    .collect { failedIds += it.oldMessageId }
            },
            scope.launch {
                dispatcher.messageDeleted.take(BURST_SIZE)
                    .collect { deletedIds += it.messageIds.single() }
            }
        )
        runCurrent()

        repeat(BURST_SIZE) { index ->
            val id = index + 1L
            updates.emit(TdApi.UpdateNewMessage(message(id)))
            updates.emit(TdApi.UpdateMessageSendSucceeded(message(id), -id))
            updates.emit(
                TdApi.UpdateMessageSendFailed(
                    message(id),
                    -id,
                    TdApi.Error(500, "temporary")
                )
            )
            updates.emit(TdApi.UpdateDeleteMessages(CHAT_ID, longArrayOf(id), true, false))
        }
        advanceUntilIdle()

        assertEquals((1L..BURST_SIZE.toLong()).toList(), newMessageIds)
        assertEquals((-1L downTo -BURST_SIZE.toLong()).toList(), succeededIds)
        assertEquals((-1L downTo -BURST_SIZE.toLong()).toList(), failedIds)
        assertEquals((1L..BURST_SIZE.toLong()).toList(), deletedIds)
        collectors.forEach { it.cancel() }
        scope.cancel()
    }

    private fun message(id: Long) = TdApi.Message().apply {
        this.id = id
        chatId = CHAT_ID
    }

    private class FakeTelegramGateway(
        override val updates: MutableSharedFlow<TdApi.Update>
    ) : TelegramGateway {
        override val isAuthenticated = MutableStateFlow(false)

        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            error("Not used")
        }

        override fun lane(
            name: String,
            scope: CoroutineScope,
            context: CoroutineContext,
            filter: (TdApi.Update) -> Boolean,
            handler: suspend (TdApi.Update) -> Unit,
        ) = fakeUpdateLane(updates, scope, context, filter, handler)
    }

    private companion object {
        const val CHAT_ID = 10L
        const val BURST_SIZE = 128
    }
}
