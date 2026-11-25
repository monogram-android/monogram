package org.monogram.network.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.bridge.updates.UpdatesPump
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.UpdateDrainDto
import uniffi.monogram_mtproto.UpdateEventDto

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusTest {
    @Test
    fun onlyNativeRecoveryChangesTheLabelAndClosingResetsJustThatAccount() =
        runTest {
            var events = emptyList<UpdateEventDto>()
            var failDrain = false
            val native =
                object : MtprotoNative by MtprotoNative.Stub {
                    override fun connect(handle: Long) = Unit

                    override fun isAuthorized(handle: Long) = true

                    override fun startUpdates(handle: Long) = Unit

                    override fun drainUpdateBatches(handle: Long): UpdateDrainDto {
                        if (failDrain) throw MtprotoException.Message("connection closed")
                        return UpdateDrainDto(
                            emptyList(),
                            events.also { events = emptyList() },
                            false
                        )
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val first = SessionCore(
                TelegramCredentials(1, "fixture"),
                "first.session",
                native,
                1_000L,
                dispatcher,
                {})
            val second = SessionCore(
                TelegramCredentials(1, "fixture"),
                "second.session",
                native,
                1_000L,
                dispatcher,
                {})
            UpdatesPump(first)
            try {
                assertTrue(first.connect() is Outcome.Ok)
                assertTrue(second.connect() is Outcome.Ok)
                assertTrue(first.syncState.value.isSyncing)
                assertEquals(ConnectionStatus.Connected, first.connectionStatus.value)

                events = listOf(UpdateEventDto.SyncState(true))
                advanceTimeBy(100)
                runCurrent()
                assertEquals(ConnectionStatus.Updating, first.connectionStatus.value)
                assertEquals(ConnectionStatus.Connected, second.connectionStatus.value)

                events = listOf(UpdateEventDto.SyncState(false))
                advanceTimeBy(100)
                runCurrent()
                assertEquals(ConnectionStatus.Connected, first.connectionStatus.value)

                failDrain = true
                advanceTimeBy(100)
                runCurrent()
                assertEquals(ConnectionStatus.Connecting, first.connectionStatus.value)
                assertEquals(ConnectionStatus.Connected, second.connectionStatus.value)
                failDrain = false
                advanceTimeBy(4_000)
                runCurrent()
                assertEquals(ConnectionStatus.Connected, first.connectionStatus.value)

                events = listOf(UpdateEventDto.SyncState(true))
                advanceTimeBy(100)
                runCurrent()
                assertEquals(ConnectionStatus.Updating, first.connectionStatus.value)
                val oldHandle = first.activeHandleOrZero()
                first.close()
                first.publishDifferenceRecovery(oldHandle, true)
                assertEquals(ConnectionStatus.Idle, first.connectionStatus.value)
                assertEquals(ConnectionStatus.Connected, second.connectionStatus.value)
            } finally {
                first.close()
                second.close()
            }
        }

    @Test
    fun directAndProxyConnectUseNativeAttemptAndCancellationDoesNotLeaveALabel() =
        runTest {
            lateinit var core: SessionCore
            var expected = ConnectionStatus.Connecting
            var cancel = true
            val native =
                object : MtprotoNative by MtprotoNative.Stub {
                    override fun connect(handle: Long) {
                        assertEquals(expected, core.connectionStatus.value)
                        if (cancel) throw CancellationException("fixture")
                    }
                }
            core =
                SessionCore(
                    TelegramCredentials(1, "fixture"),
                    "fixture.session",
                    native,
                    1_000L,
                    StandardTestDispatcher(testScheduler),
                    {})
            try {
                assertTrue(runCatching { core.connect() }.exceptionOrNull() is CancellationException)
                assertEquals(ConnectionStatus.Idle, core.connectionStatus.value)
                assertTrue(
                    core.configureProxy(
                        ProxyConfig(
                            ProxyType.SOCKS5,
                            "localhost",
                            1080
                        )
                    ) is Outcome.Ok
                )
                expected = ConnectionStatus.ConnectingToProxy
                cancel = false
                assertTrue(core.connect() is Outcome.Ok)
                assertEquals(ConnectionStatus.Connected, core.connectionStatus.value)
                core.hibernate()
                assertEquals(ConnectionStatus.Idle, core.connectionStatus.value)
            } finally {
                core.close()
            }
        }

    @Test
    fun networkLossMasksActiveStatesButNotIdleAndRestoresTheLatestState() =
        runTest {
            val status = MutableStateFlow(ConnectionStatus.ConnectingToProxy)
            val online = MutableStateFlow(true)
            val observed = mutableListOf<ConnectionStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                status.withNetworkAvailability(online).collect { observed += it }
            }
            runCurrent()
            online.value = false
            runCurrent()
            status.value = ConnectionStatus.Updating
            runCurrent()
            online.value = true
            runCurrent()
            online.value = false
            runCurrent()
            status.value = ConnectionStatus.Idle
            runCurrent()
            assertEquals(
                listOf(
                    ConnectionStatus.ConnectingToProxy,
                    ConnectionStatus.WaitingForNetwork,
                    ConnectionStatus.Updating,
                    ConnectionStatus.WaitingForNetwork,
                    ConnectionStatus.Idle,
                ),
                observed,
            )
        }
}
