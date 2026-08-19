package org.monogram.data.infra

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.SponsorDao
import org.monogram.data.db.model.SponsorEntity
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus

@OptIn(ExperimentalCoroutinesApi::class)
class SponsorSyncManagerTest {

    @Test
    fun `loads cached sponsor ids into state on start`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val environment = TestEnvironment(
            scope = TestScope(dispatcher),
            dispatcher = dispatcher,
            cachedIds = listOf(11L, 22L)
        )

        environment.createManager()
        environment.flush()

        val state = environment.manager.sponsorState.value
        assertEquals(setOf(11L, 22L), state.supporterIds)
        assertEquals(2, state.supportersCount)
        assertTrue(state.isLoaded)
        assertFalse(state.isSyncInProgress)
        environment.close()
    }

    @Test
    fun `successful empty sync marks state as loaded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val environment = TestEnvironment(
            scope = TestScope(dispatcher),
            dispatcher = dispatcher,
            cachedIds = emptyList()
        ).apply {
            historyResult = TdApi.Messages(0, emptyArray())
        }

        environment.createManager()
        environment.flush()

        environment.manager.forceSync()
        environment.flush()

        val state = environment.manager.sponsorState.value
        assertTrue(state.isLoaded)
        assertEquals(0, state.supportersCount)
        assertTrue(state.supporterIds.isEmpty())
        assertFalse(state.isSyncInProgress)
        environment.close()
    }

    @Test
    fun `manual sync publishes parsed supporter count`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val environment = TestEnvironment(
            scope = TestScope(dispatcher),
            dispatcher = dispatcher,
            cachedIds = emptyList()
        ).apply {
            historyResult = TdApi.Messages(
                0,
                arrayOf(
                    sponsorMessage(3L, "100, 200"),
                    sponsorMessage(2L, "300")
                )
            )
        }

        environment.createManager()
        environment.flush()

        environment.manager.forceSync()
        environment.flush()

        val state = environment.manager.sponsorState.value
        assertTrue(state.isLoaded)
        assertEquals(3, state.supportersCount)
        assertEquals(setOf(100L, 200L, 300L), state.supporterIds)
        assertTrue(state.lastSyncAt > 0L)
        environment.close()
    }

    private fun sponsorMessage(id: Long, text: String): TdApi.Message {
        return TdApi.Message().apply {
            this.id = id
            this.chatId = -1003640797855L
            this.content = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)
        }
    }

    private class TestEnvironment(
        val scope: TestScope,
        private val dispatcher: CoroutineDispatcher,
        cachedIds: List<Long>
    ) {
        val sponsorDao = FakeSponsorDao(cachedIds)
        val gateway = FakeTelegramGateway()
        val authRepository = FakeAuthRepository()
        lateinit var manager: SponsorSyncManager
        var historyResult: TdApi.Messages = TdApi.Messages(0, emptyArray())

        fun createManager() {
            gateway.historyProvider = { historyResult }
            manager = SponsorSyncManager(
                scope = scope,
                gateway = gateway,
                sponsorDao = sponsorDao,
                authRepository = authRepository,
                ioDispatcher = dispatcher
            )
        }

        fun flush() {
            scope.testScheduler.runCurrent()
        }

        fun close() {
            scope.cancel()
        }
    }

    private class FakeSponsorDao(
        cachedIds: List<Long>
    ) : SponsorDao {
        private val items = linkedMapOf<Long, SponsorEntity>().apply {
            cachedIds.forEach { id ->
                put(id, SponsorEntity(userId = id, sourceChannelId = 0L, updatedAt = 1L))
            }
        }

        override suspend fun getAllIds(): List<Long> = items.keys.toList()

        override suspend fun insertAll(items: List<SponsorEntity>) {
            items.forEach { this.items[it.userId] = it }
        }

        override suspend fun clearAll() {
            items.clear()
        }

        override suspend fun deleteNotIn(ids: List<Long>) {
            items.keys.retainAll(ids.toSet())
        }

        override suspend fun getLatestUpdatedAt(): Long? = items.values.maxOfOrNull { it.updatedAt }
    }

    private class FakeTelegramGateway : TelegramGateway {
        override fun lane(
            name: String,
            scope: kotlinx.coroutines.CoroutineScope,
            context: kotlin.coroutines.CoroutineContext,
            filter: (TdApi.Update) -> Boolean,
            handler: suspend (TdApi.Update) -> Unit,
        ) = org.monogram.data.testing.fakeUpdateLane(_updates, scope, context, filter, handler)

        private val _updates = MutableSharedFlow<TdApi.Update>()
        private val _isAuthenticated = MutableStateFlow(true)
        var historyProvider: suspend () -> TdApi.Messages = { TdApi.Messages(0, emptyArray()) }

        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            @Suppress("UNCHECKED_CAST")
            return when (function) {
                is TdApi.SearchPublicChat -> TdApi.Chat().apply { id = -1003640797855L } as T
                is TdApi.GetChatHistory -> historyProvider() as T
                else -> error("Unexpected function ${function::class.java.simpleName}")
            }
        }

        override val updates: SharedFlow<TdApi.Update> = _updates
        override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated
    }

    private class FakeAuthRepository : AuthRepository {
        override val authState = MutableStateFlow<AuthStep>(AuthStep.Ready)
        override val authUiStatus = MutableStateFlow<AuthUiStatus>(AuthUiStatus.Idle)
        override val errors = MutableSharedFlow<AuthError>()

        override fun sendPhone(phone: String) = Unit
        override fun resendCode() = Unit
        override fun sendCode(code: String) = Unit
        override fun sendPassword(password: String) = Unit
        override fun signUp(firstName: String, lastName: String) = Unit
        override fun retryLastAction() = Unit
        override fun reset() = Unit
    }
}
