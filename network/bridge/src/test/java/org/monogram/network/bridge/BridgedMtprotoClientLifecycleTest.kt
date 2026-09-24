package org.monogram.network.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.models.PeerId
import org.monogram.mtproto.MtprotoNative
import uniffi.monogram_mtproto.AuthSignedIn
import uniffi.monogram_mtproto.ChatDto
import uniffi.monogram_mtproto.MtprotoException
import uniffi.monogram_mtproto.MessageDto
import uniffi.monogram_mtproto.UpdateEventDto
import uniffi.monogram_mtproto.UpdatesStateDto
import org.monogram.network.bridge.session.nativeFailureLogLine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BridgedMtprotoClientLifecycleTest {
    @Test
    fun localAuthorizationRestoresSessionWithoutConnecting() = runTest {
        for (authorized in listOf(true, false)) {
            val native = object : RecordingNative() {
                override fun connect(handle: Long): Unit = error("Startup must not connect")
                override fun isAuthorized(handle: Long): Boolean {
                    assertEquals(1L, handle)
                    return authorized
                }
            }
            val client = client(native, StandardTestDispatcher(testScheduler))
            try {
                assertEquals(Outcome.Ok(authorized), client.isLocallyAuthorized())
                assertEquals(1, native.created)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun localAuthorizationPreservesCancellation() = runTest {
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long): Boolean = throw CancellationException("cancelled")
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            client.isLocallyAuthorized()
            error("Expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun restoredAccountSendsOnlineOfflineAndOnlineAgain() = runTest {
        val statuses = mutableListOf<Boolean>()
        var connects = 0
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long) = true
            override fun connect(handle: Long) { connects++ }
            override fun updateStatus(handle: Long, offline: Boolean) {
                statuses += offline
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertEquals(Outcome.Ok(true), client.isLocallyAuthorized())
            assertEquals(0, connects)
            for (offline in listOf(false, true, false)) {
                assertEquals(Outcome.Ok(Unit), client.updateStatus(offline))
            }
            assertEquals(listOf(false, true, false), statuses)
            assertEquals(1, connects)
        } finally {
            client.close()
        }
    }

    @Test
    fun unauthenticatedAccountDoesNotSendStatus() = runTest {
        val native = object : RecordingNative() {
            override fun updateStatus(handle: Long, offline: Boolean): Unit =
                error("Unauthenticated status must not reach native")
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertEquals(Outcome.Ok(Unit), client.updateStatus(offline = false))
            assertEquals(Outcome.Ok(Unit), client.updateStatus(offline = true))
        } finally {
            client.close()
        }
    }

    @Test
    fun signInPreservesCancellation() = runTest {
        val cancelled = CancellationException("cancelled")
        val native = object : RecordingNative() {
            override fun signIn(handle: Long, phone: String, phoneCodeHash: String, phoneCode: String): AuthSignedIn =
                throw cancelled
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            client.signIn("", "", "")
            error("Expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(cancelled.message, e.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun historyPreservesOuterTimeout() = runTest {
        val native = object : RecordingNative() {
            override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> {
                testScheduler.advanceTimeBy(10)
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            withTimeout(5) { client.getHistory(PeerId(1)) }
            error("Expected caller timeout")
        } catch (_: TimeoutCancellationException) {
            // The caller timeout must not become an Outcome.Err.
        } finally {
            client.close()
        }
    }

    @Test
    fun closeIsIdempotentAndCannotRecreateHandle() = runTest {
        val native = RecordingNative()
        val client = client(native, StandardTestDispatcher(testScheduler))
        assertTrue(client.connect() is Outcome.Ok)
        client.close()
        client.close()
        assertTrue(client.connect() is Outcome.Err)
        assertEquals(1, native.created)
        assertEquals(listOf(1L), native.destroyed)
    }

    @Test
    fun repeatConnectReachesNativeOnlyOnce() = runTest {
        var connects = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            repeat(5) { assertTrue(client.connect() is Outcome.Ok) }
            assertEquals(1, connects)
        } finally {
            client.close()
        }
    }

    @Test
    fun readsDoNotReconnectWhileAHandleIsLive() = runTest {
        var connects = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }

            override fun getChats(handle: Long): List<uniffi.monogram_mtproto.ChatDto> = emptyList()

            override fun getFolders(handle: Long): List<uniffi.monogram_mtproto.FolderDto> = emptyList()
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            assertTrue(client.getChats() is Outcome.Ok)
            assertTrue(client.getFolders() is Outcome.Ok)
            assertEquals(1, connects)
        } finally {
            client.close()
        }
    }

    @Test
    fun repeatedReadsStartUpdatesOncePerHandle() = runTest {
        var starts = 0
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long) = true
            override fun startUpdates(handle: Long) {
                starts++
            }
            override fun getChats(handle: Long): List<uniffi.monogram_mtproto.ChatDto> = emptyList()
            override fun getFolders(handle: Long): List<uniffi.monogram_mtproto.FolderDto> = emptyList()
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            assertTrue(client.getChats() is Outcome.Ok)
            assertTrue(client.getFolders() is Outcome.Ok)
            assertEquals(1, starts)
        } finally {
            client.close()
        }
    }

    @Test
    fun reconnectAfterTransportFailureReusesTheSameHandleAndSession() = runTest {
        var connects = 0
        var chats = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }

            override fun getChats(handle: Long): List<uniffi.monogram_mtproto.ChatDto> {
                chats++
                if (chats == 1) throw IllegalStateException("connection closed")
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            assertTrue(client.getChats() is Outcome.Err)
            // Recovery must not create a second client or re-authenticate.
            assertTrue(client.connect() is Outcome.Ok)
            assertTrue(client.getChats() is Outcome.Ok)
            assertEquals(1, native.created)
            assertTrue(connects <= 2)
        } finally {
            client.close()
        }
    }

    @Test
    fun downloadConcurrencySettingReachesNativeAndReadsBack() = runTest {
        val native = object : RecordingNative() {
            var lanes = 0
            var parts = 0
            override fun setDownloadConcurrency(lanes: Int, parts: Int) {
                this.lanes = lanes
                this.parts = parts
            }

            override fun downloadConcurrency(): List<Int> = listOf(lanes, parts)
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            client.applyDownloadConcurrency(8, 16)
            assertEquals(8, native.lanes)
            assertEquals(16, native.parts)
            assertEquals(listOf(8, 16), client.downloadConcurrency())
            client.applyDownloadConcurrency(2, 2)
            assertEquals(listOf(2, 2), client.downloadConcurrency())
        } finally {
            client.close()
        }
    }

    @Test
    fun hibernateWaitsForInFlightRequestsBeforeDroppingTheHandle() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val native = object : RecordingNative() {
            override fun getChats(handle: Long): List<uniffi.monogram_mtproto.ChatDto> {
                started.countDown()
                release.await()
                return emptyList()
            }
        }
        val client = client(native, Dispatchers.IO)
        try {
            assertTrue(client.connect() is Outcome.Ok)
            val call = async(Dispatchers.IO) { client.getChats() }
            assertTrue("request never reached native", started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Backgrounding the app must not destroy a handle a request is using.
            client.hibernate()
            assertEquals(emptyList<Long>(), native.destroyed)
            release.countDown()
            assertTrue(call.await() is Outcome.Ok)
            // The deferred hibernate applies on the last completion.
            val deadline = System.currentTimeMillis() + 5_000
            while (native.destroyed.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf(1L), native.destroyed)
        } finally {
            client.close()
        }
    }

    @Test
    fun staleHandleIsRecreatedAndTheCallRetriedOnce() = runTest {
        var calls = 0
        val native = object : RecordingNative() {
            override fun getChats(handle: Long): List<uniffi.monogram_mtproto.ChatDto> {
                calls++
                if (calls == 1) throw MtprotoException.UnknownClient()
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            val result = client.getChats()
            assertTrue("expected a retry, got $result", result is Outcome.Ok)
            assertEquals(2, calls)
            assertEquals(listOf(1L), native.destroyed)
        } finally {
            client.close()
        }
    }

    @Test
    fun logoutAllowsNewAuthorizationOnSameClient() = runTest {
        val native = RecordingNative()
        val client = client(native, StandardTestDispatcher(testScheduler))
        assertTrue(client.connect() is Outcome.Ok)
        assertTrue(client.logout() is Outcome.Ok)
        assertTrue(client.connect() is Outcome.Ok)
        client.close()
        assertEquals(2, native.created)
        assertEquals(listOf(1L, 2L), native.destroyed)
    }

    @Test
    fun updateDrainStartsWithoutStartupDelay() = runTest {
        var drains = 0
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long) = true
            override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                drains++
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
            assertTrue("drain must start before the old 1500ms delay, drains=$drains", drains > 0)
        } finally {
            client.close()
        }
    }

    @Test
    fun reconnectDoesNotStartASecondDrainLoop() = runTest {
        var drains = 0
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long) = true
            override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                drains++
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            val firstWindow = drains
            assertTrue("expected ~10 drains in 250ms, was $firstWindow", firstWindow in 5..20)
            client.hibernate()
            testScheduler.runCurrent()
            assertTrue(client.connect() is Outcome.Ok)
            val afterReconnect = drains
            testScheduler.advanceTimeBy(250)
            testScheduler.runCurrent()
            val extra = drains - afterReconnect
            assertTrue("duplicate drainers extra=$extra first=$firstWindow", extra in 5..20)
        } finally {
            client.close()
        }
    }

    @Test
    fun updatesSurviveTemporaryUiUnsubscription() = runTest {
        val pending = ArrayDeque<UpdateEventDto>()
        pending.add(newMessageEvent(7))
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long) = true
            override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                val next = pending.removeFirstOrNull() ?: return emptyList()
                return listOf(next)
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        val persisted = mutableListOf<Int>()
        val sessionCollector = backgroundScope.launch {
            client.updates().filterIsInstance<MtprotoUpdate.NewMessage>().collect {
                persisted += it.message.id.id
            }
        }
        try {
            assertTrue(client.connect() is Outcome.Ok)
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
            assertEquals(listOf(7), persisted.toList())
            val ui = launch { client.updates().collect() }
            testScheduler.runCurrent()
            ui.cancel()
            testScheduler.runCurrent()
            pending.add(newMessageEvent(8))
            testScheduler.advanceTimeBy(50)
            testScheduler.runCurrent()
            assertEquals(listOf(7, 8), persisted.toList())
        } finally {
            sessionCollector.cancel()
            client.close()
        }
    }

    @Test
    fun connectRecordsASingleDebugStat() = runTest {
        org.monogram.core.common.DebugStats.resetForTests(true)
        try {
            var connects = 0
            val native = object : RecordingNative() {
                override fun connect(handle: Long) {
                    connects++
                }
            }
            val client = client(native, StandardTestDispatcher(testScheduler))
            try {
                assertTrue(client.connect() is Outcome.Ok)
                assertEquals(1, connects)
                val rows = org.monogram.core.common.DebugStats.snapshot().records.filter { it.op == "bridge:connect" }
                assertEquals(1, rows.size)
            } finally {
                client.close()
            }
        } finally {
            org.monogram.core.common.DebugStats.resetForTests(false)
        }
    }

    @Test
    fun parallelConnectReachesNativeOnlyOnce() = runTest {
        var connects = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            val results = (1..8).map { async { client.connect() } }.map { it.await() }
            assertTrue(results.all { it is Outcome.Ok })
            assertEquals(1, connects)
            assertEquals(1, native.created)
        } finally {
            client.close()
        }
    }

    @Test
    fun parallelGetChatsReachesNativeOnlyOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var chats = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) = Unit
            override fun getChats(handle: Long): List<ChatDto> {
                chats++
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return emptyList()
            }
        }
        val client = client(native, Dispatchers.IO)
        try {
            assertTrue(client.connect() is Outcome.Ok)
            val first = async(Dispatchers.IO) { client.getChats() }
            val second = async(Dispatchers.IO) { client.getChats() }
            check(entered.await(2, TimeUnit.SECONDS))
            Thread.sleep(50)
            release.countDown()
            assertTrue(first.await() is Outcome.Ok)
            assertTrue(second.await() is Outcome.Ok)
            assertEquals(1, chats)
        } finally {
            client.close()
        }
    }

    @Test
    fun sendAndSideRpcsDoNotReconnectWhileConnected() = runTest {
        var connects = 0
        var sidecar = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }
            override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> = emptyList()
            override fun getChats(handle: Long) = emptyList<uniffi.monogram_mtproto.ChatDto>()
            override fun getUpdatesState(handle: Long) = UpdatesStateDto(1, 0, 1, 0)
            override fun sendTextMessage(
                handle: Long,
                chatId: Long,
                text: String,
                replyToMsgId: Int,
                entitiesJson: String?,
                topMsgId: Int,
                webpageUrl: String?,
            ) = sampleMessage(chatId, 9)
        }
        val client = client(native, StandardTestDispatcher(testScheduler)) { sidecar++ }
        try {
            assertTrue(client.connect() is Outcome.Ok)
            assertEquals(1, connects)
            assertEquals(1, sidecar)
            val history = async { client.getHistory(PeerId(1)) }
            val send = async { client.sendText(PeerId(1), "hi") }
            val chats = async { client.getChats() }
            val state = async { client.getUpdatesState() }
            assertTrue(history.await() is Outcome.Ok)
            assertTrue(send.await() is Outcome.Ok)
            assertTrue(chats.await() is Outcome.Ok)
            assertTrue(state.await() is Outcome.Ok)
            assertEquals(1, connects)
            assertEquals(1, sidecar)
        } finally {
            client.close()
        }
    }

    @Test
    fun transportFailureInvalidatesHandleAndReconnects() = runTest {
        var connects = 0
        var chats = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }
            override fun getChats(handle: Long): List<uniffi.monogram_mtproto.ChatDto> {
                chats++
                if (chats == 1) throw IllegalStateException("connection closed")
                return emptyList()
            }
            override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> = emptyList()
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            assertEquals(1, connects)
            assertTrue(client.getChats() is Outcome.Err)
            assertTrue(client.getHistory(PeerId(1)) is Outcome.Ok)
            assertEquals(2, connects)
            assertEquals(1, native.created)
        } finally {
            client.close()
        }
    }

    @Test
    fun dcMigrationDoesNotClearTheWarmHandle() = runTest {
        var connects = 0
        var history = 0
        val native = object : RecordingNative() {
            override fun connect(handle: Long) {
                connects++
            }
            override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> {
                history++
                if (history == 1) throw MtprotoException.Message("RPC 303: USER_MIGRATE_2")
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            assertTrue(client.getHistory(PeerId(1)) is Outcome.Err)
            assertTrue(client.getHistory(PeerId(1)) is Outcome.Ok)
            assertEquals(1, connects)
        } finally {
            client.close()
        }
    }

    @Test
    fun closeStopsUpdateDraining() = runTest {
        var drains = 0
        val native = object : RecordingNative() {
            override fun drainUpdates(handle: Long): List<uniffi.monogram_mtproto.UpdateEventDto> {
                drains++
                return emptyList()
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        client.connect()
        val collector = backgroundScope.launch { client.updates().collect() }
        testScheduler.advanceTimeBy(2_000)
        assertTrue(drains > 0)
        val beforeClose = drains
        client.close()
        testScheduler.advanceTimeBy(30_000)
        assertEquals(beforeClose, drains)
        collector.cancel()
    }

    @Test
    fun unknownNativeFailureDoesNotLeakPayloadIntoLogs() {
        val error = org.monogram.core.common.telegram.TelegramError.parse(
            "decode failed via body=private-message password=secret snapshot=private-session",
        )
        val line = nativeFailureLogLine(error)
        assertTrue(!line.contains("private", ignoreCase = true))
        assertTrue(!line.contains("secret", ignoreCase = true))
    }

    @Test
    fun degradedSessionKeepsRpcCauseWithoutRawPayload() {
        val error = org.monogram.core.common.telegram.TelegramError.parse("RPC 406: AUTH_KEY_DUPLICATED")
        assertTrue(nativeFailureLogLine(error).contains("cause=rpc_rejected"))
    }

    @Test
    fun localPeerFailureHasSafeCauseWithoutPeerIdentifier() {
        val raw = "unknown peer 123456789"
        val error = org.monogram.core.common.telegram.TelegramError.parse(raw)
        val line = nativeFailureLogLine(error, raw)
        assertTrue(line.contains("cause=peer_resolution"))
        assertTrue(!line.contains("123456789"))
    }

    @Test
    fun badMessageLogsOnlyNumericProtocolCode() {
        val raw = "bad_msg_notification 32 recv=123 body=private-message password=secret"
        val error = org.monogram.core.common.telegram.TelegramError.parse(raw)
        val line = nativeFailureLogLine(error, raw)
        assertTrue(line.contains("cause=bad_message bad_msg_code=32"))
        assertTrue(!line.contains("private"))
        assertTrue(!line.contains("secret"))
        assertTrue(!line.contains("recv="))
    }

    @Test
    fun nativeSignOutLogsItsReasonAndNotAFakeServerRejection() {
        val raw = "session invalidated: AUTH_KEY_DUPLICATED"
        val error = org.monogram.core.common.telegram.TelegramError.parse(raw)
        val line = nativeFailureLogLine(error, raw)
        assertTrue(line.contains("type=AUTH_KEY_DUPLICATED"))
        assertTrue(line.contains("cause=session_invalidated reason=AUTH_KEY_DUPLICATED"))
        assertTrue(!line.contains("cause=rpc_rejected"))
        assertTrue(!line.contains("AUTH_KEY_UNREGISTERED"))
    }

    @Test
    fun revokedHomeSessionStopsUpdateDraining() = runTest {
        var drains = 0
        val native = object : RecordingNative() {
            override fun drainUpdates(handle: Long): List<uniffi.monogram_mtproto.UpdateEventDto> {
                drains++
                throw uniffi.monogram_mtproto.MtprotoException.Message("RPC 406: AUTH_KEY_DUPLICATED")
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        client.connect()
        val collector = backgroundScope.launch { client.updates().collect() }
        testScheduler.advanceTimeBy(60_000)
        assertEquals(1, drains)
        client.close()
        collector.cancel()
    }

    @Test
    fun networkFailurePausesUpdateDrainingUntilReconnect() = runTest {
        var drains = 0
        val native = object : RecordingNative() {
            override fun drainUpdates(handle: Long): List<uniffi.monogram_mtproto.UpdateEventDto> {
                drains++
                throw uniffi.monogram_mtproto.MtprotoException.Message("RPC 500: transport closed")
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        try {
            assertTrue(client.connect() is Outcome.Ok)
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            val afterFailure = drains
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertEquals(afterFailure, drains)
        } finally {
            client.close()
        }
    }

    @Test
    fun reconnectWakesUpdateDrainOnTheSameHandle() = runTest {
        var starts = 0
        var drains = 0
        val received = mutableListOf<Int>()
        val native = object : RecordingNative() {
            override fun isAuthorized(handle: Long) = true

            override fun startUpdates(handle: Long) {
                starts++
            }

            override fun drainUpdates(handle: Long): List<UpdateEventDto> {
                drains++
                if (drains == 1) {
                    throw MtprotoException.Message("RPC 500: transport closed")
                }
                return listOf(newMessageEvent(9))
            }
        }
        val client = client(native, StandardTestDispatcher(testScheduler))
        val collector = backgroundScope.launch {
            client.updates().filterIsInstance<MtprotoUpdate.NewMessage>().collect {
                received += it.message.id.id
            }
        }
        try {
            assertTrue(client.connect() is Outcome.Ok)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(4_000)
            testScheduler.runCurrent()
            assertEquals(1, drains)
            assertEquals(1, starts)

            assertTrue(client.connect() is Outcome.Ok)
            testScheduler.runCurrent()

            assertEquals(1, native.created)
            assertEquals(1, starts)
            assertEquals(listOf(9), received)
        } finally {
            collector.cancel()
            client.close()
        }
    }

    @Test
    fun rpcErrorsKeepTypedClassification() = runTest {
        for ((raw, kind) in listOf(
            "RPC 401: SESSION_REVOKED" to org.monogram.core.common.telegram.TelegramError.Kind.Session,
            "RPC 303: FILE_MIGRATE_2" to org.monogram.core.common.telegram.TelegramError.Kind.SeeOther,
            "RPC 420: FLOOD_WAIT_60" to org.monogram.core.common.telegram.TelegramError.Kind.Flood,
        )) {
            val native = object : RecordingNative() {
                override fun getHistory(handle: Long, chatId: Long, limit: Int): List<MessageDto> =
                    throw uniffi.monogram_mtproto.MtprotoException.Message(raw)
            }
            val client = client(native, StandardTestDispatcher(testScheduler))
            try {
                val outcome = client.getHistory(PeerId(1))
                assertTrue(outcome is Outcome.Err)
                assertEquals(kind, (outcome as Outcome.Err).telegramError.kind)
            } finally {
                client.close()
            }
        }
    }

    private fun client(
        native: MtprotoNative,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        refreshDcSidecar: (String) -> Unit = {},
    ) = BridgedMtprotoClient(
        credentials = TelegramCredentials(1, "test"),
        sessionPath = "build/lifecycle-test.session",
        native = native,
        nativeDispatcher = dispatcher,
        refreshDcSidecar = refreshDcSidecar,
    )

    private fun newMessageEvent(id: Int): UpdateEventDto =
        UpdateEventDto.NewMessage(sampleMessage(42L, id))

    private fun sampleMessage(chatId: Long, id: Int) = MessageDto(
        chatId = chatId,
        id = id,
        senderId = 1L,
        text = "n",
        date = 1L,
        editDate = null,
        outgoing = true,
        mediaKind = null,
        mediaCacheKey = null,
        thumbCacheKey = null,
        mediaDuration = null,
        mediaWidth = null,
        mediaHeight = null,
        replyQuote = null,
        entitiesJson = null,
        noforwards = false,
        replyToMsgId = null,
        replyToTopId = null,
        fwdFrom = null,
        fwdFromId = null,
        fwdDate = null,
        viaBot = null,
        senderName = null,
        senderEmojiStatusDocumentId = null,
        groupedId = null,
        fileName = null,
        fileSize = null,
        supportsStreaming = false,
        reactionsJson = null,
        repliesCount = 0,
        discussionPeerId = null,
        replyMarkupJson = null,
    )

    private open class RecordingNative : MtprotoNative by MtprotoNative.Stub {
        var created = 0
        val destroyed = mutableListOf<Long>()
        override fun createClient(apiId: Int, apiHash: String, sessionPath: String): Long = (++created).toLong()
        override fun connect(handle: Long) = Unit
        override fun isAuthorized(handle: Long) = false
        override fun logout(handle: Long) = Unit
        override fun destroyClient(handle: Long) { destroyed += handle }
    }
}
