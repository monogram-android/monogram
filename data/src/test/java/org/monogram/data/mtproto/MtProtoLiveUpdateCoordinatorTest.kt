package org.monogram.data.mtproto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.AuthError
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.AuthUiStatus
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.State_ddba9d7af9
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateState

@OptIn(ExperimentalCoroutinesApi::class)
class MtProtoLiveUpdateCoordinatorTest {
    @Test
    fun `selected ready session initializes recovers then closes transport`() = runTest {
        val transport = RecordingTransport(
            responses = ArrayDeque<TlObject>().apply {
                add(State_ddba9d7af9(pts = 10, qts = 20, date = 30, seq = 40, unreadCount = 0))
                add(DifferenceEmpty(date = 31, seq = 41))
            },
        )
        var opens = 0
        var dialogRequests = 0
        val stateStore = FakeStateStore()
        coordinator(
            auth = FakeAuthRepository(AuthStep.Ready),
            transportFactory = MtProtoSessionTransportFactory {
                opens++
                transport
            },
            stateStore = stateStore,
            dialogs = object : DialogSnapshotRepository {
                override suspend fun getDialogs(accountId: String): List<org.monogram.domain.models.DialogSnapshotModel> {
                    dialogRequests++
                    return emptyList()
                }
            },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()

        assertEquals(1, opens)
        assertTrue(transport.closed)
        assertEquals(1, dialogRequests)
        assertEquals(MtProtoUpdateCursor(10, 20, 31, 41), stateStore.state?.cursor)
    }

    @Test
    fun `initial difference resync requirement stops selected session without reconnecting`() = runTest {
        val transport = RecordingTransport(
            responses = ArrayDeque<TlObject>().apply {
                add(State_ddba9d7af9(pts = 10, qts = 20, date = 30, seq = 40, unreadCount = 0))
                add(DifferenceTooLong(99))
            },
        )
        var opens = 0
        coordinator(
            auth = FakeAuthRepository(AuthStep.Ready),
            transportFactory = MtProtoSessionTransportFactory {
                opens++
                transport
            },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()

        assertEquals(1, opens)
        assertTrue(transport.closed)
    }

    @Test
    fun `closed MTProto transport is reopened after backoff`() = runTest {
        val first = RecordingTransport(
            responses = ArrayDeque<TlObject>().apply {
                add(State_ddba9d7af9(pts = 10, qts = 20, date = 30, seq = 40, unreadCount = 0))
                add(DifferenceEmpty(date = 31, seq = 41))
            },
        )
        val secondEntered = CompletableDeferred<Unit>()
        val second = RecordingTransport(onExecute = {
            secondEntered.complete(Unit)
            CompletableDeferred<TlObject>().await()
        })
        var opens = 0
        coordinator(
            auth = FakeAuthRepository(AuthStep.Ready),
            transportFactory = MtProtoSessionTransportFactory {
                opens++
                if (opens == 1) first else second
            },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()
        assertEquals(1, opens)
        assertTrue(first.closed)

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        secondEntered.await()
        assertEquals(2, opens)
    }

    @Test
    fun `stops reconnecting after bounded consecutive open failures`() = runTest {
        var opens = 0
        coordinator(
            auth = FakeAuthRepository(AuthStep.Ready),
            transportFactory = MtProtoSessionTransportFactory {
                opens++
                error("offline")
            },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()
        repeat(5) {
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
        }

        assertEquals(6, opens)
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(6, opens)
    }

    @Test
    fun `auth reset cancels in-flight startup and closes transport`() = runTest {
        val auth = FakeAuthRepository(AuthStep.Ready)
        val entered = CompletableDeferred<Unit>()
        val transport = RecordingTransport(onExecute = {
            entered.complete(Unit)
            CompletableDeferred<TlObject>().await()
        })
        coordinator(
            auth = auth,
            transportFactory = MtProtoSessionTransportFactory { transport },
            scope = backgroundScope,
        )

        testScheduler.runCurrent()
        entered.await()
        auth.step.value = AuthStep.InputPhone
        testScheduler.runCurrent()

        assertTrue(transport.closed)
    }

    private fun coordinator(
        auth: FakeAuthRepository,
        transportFactory: MtProtoSessionTransportFactory,
        stateStore: FakeStateStore = FakeStateStore(),
        dialogs: DialogSnapshotRepository = object : DialogSnapshotRepository {
            override suspend fun getDialogs(accountId: String) = emptyList<org.monogram.domain.models.DialogSnapshotModel>()
        },
        scope: CoroutineScope,
    ) = MtProtoLiveUpdateCoordinator(
        authRepository = auth,
        transportFactory = transportFactory,
        configSource = configSource(),
        recovery = MtProtoRoomUpdateRecovery(
            stateStore = stateStore,
            liveUpdateApplier = MtProtoRoomLiveUpdateApplier(stateStore, FakePendingStore()),
        ),
        liveUpdateApplier = MtProtoRoomLiveUpdateApplier(stateStore, FakePendingStore()),
        dialogs = dialogs,
        scope = scope,
    )

    private fun configSource() = TelegramMtProtoBootstrapConfigSource {
        TelegramMtProtoBootstrapConfig(
            endpoint = TelegramMtProtoEndpoint(2, "127.0.0.1", 443),
            handshake = MtProtoHandshakeConfig(2, listOf("key")),
            cloud = CloudLayer223ConnectionConfig(1, "test", "test", "test", "en"),
        )
    }


    private class FakeAuthRepository(initial: AuthStep) : AuthRepository {
        val step = MutableStateFlow(initial)
        override val authState = step.asStateFlow()
        override val authUiStatus = MutableStateFlow<AuthUiStatus>(AuthUiStatus.Idle).asStateFlow()
        override val errors = MutableSharedFlow<AuthError>().asSharedFlow()
        override fun sendPhone(phone: String) = Unit
        override fun resendCode() = Unit
        override fun sendCode(code: String) = Unit
        override fun sendPassword(password: String) = Unit
        override fun signUp(firstName: String, lastName: String) = Unit
        override fun retryLastAction() = Unit
        override fun reset() = Unit
    }

    private class FakeStateStore : MtProtoRecoveryStateStore, MtProtoTransactionalUpdateStateStore {
        var state: MtProtoUpdateState? = null
        override suspend fun loadState(scope: MtProtoAuthKeyScope) = state?.let(MtProtoUpdateStateLoadResult::Found)
            ?: MtProtoUpdateStateLoadResult.Missing
        override suspend fun applyRecovery(scope: MtProtoAuthKeyScope, cursor: MtProtoUpdateCursor, applyEntities: suspend () -> Unit) {
            applyEntities()
            state = MtProtoUpdateState(cursor)
        }
        override suspend fun applyState(scope: MtProtoAuthKeyScope, state: MtProtoUpdateState, applyEntities: suspend () -> Unit) {
            applyEntities()
            this.state = state
        }
    }

    private class FakePendingStore : MtProtoPendingEnvelopeStore {
        override suspend fun enqueue(scope: MtProtoAuthKeyScope, envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5): MtProtoPendingEnvelope.Decoded =
            error("no live envelope expected")
        override suspend fun pending(scope: MtProtoAuthKeyScope) = emptyList<MtProtoPendingEnvelope>()
        override suspend fun delete(sequenceId: Long) = Unit
        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }

    private class RecordingTransport(
        private val responses: ArrayDeque<TlObject> = ArrayDeque(),
        private val onExecute: (suspend () -> TlObject)? = null,
    ) : MtProtoRpcTransport {
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R = (onExecute?.invoke() ?: responses.removeFirst()) as R
        override fun close() {
            closed = true
        }
    }
}
