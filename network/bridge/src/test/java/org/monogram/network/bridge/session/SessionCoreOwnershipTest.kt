package org.monogram.network.bridge.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.mtproto.MtprotoNative

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionCoreOwnershipTest {
    @Test
    fun bindingRejectionIsPacedPerAccountAndRetriesAfterCooldown() = runTest {
        var now = 0L
        var rejectedCalls = 0
        var otherCalls = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failing = object : MtprotoNative by MtprotoNative.Stub {
            override fun connect(handle: Long) {
                rejectedCalls++
                throw uniffi.monogram_mtproto.MtprotoException.Message(
                    "temp bind RPC 400: ENCRYPTED_MESSAGE_INVALID",
                )
            }
        }
        val working = object : MtprotoNative by MtprotoNative.Stub {
            override fun connect(handle: Long) {
                otherCalls++
            }
        }
        val first = SessionCore(
            TelegramCredentials(1, "fixture"), "first.session", failing,
            1_000L, dispatcher, {}, MonotonicClock { now },
        )
        val second = SessionCore(
            TelegramCredentials(1, "fixture"), "second.session", working,
            1_000L, dispatcher, {}, MonotonicClock { now },
        )
        try {
            val results = (1..8).map { async { first.connect() } }.map { it.await() }
            assertTrue(results.all { it is Outcome.Err })
            assertEquals(1, rejectedCalls)
            assertFalse(first.sessionDead)
            assertTrue(second.connect() is Outcome.Ok)
            assertEquals(1, otherCalls)
            now = 1_999
            assertTrue(first.connect() is Outcome.Err)
            assertEquals(1, rejectedCalls)
            now = 2_000
            assertTrue(first.connect() is Outcome.Err)
            assertEquals(2, rejectedCalls)
            assertFalse(first.sessionDead)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun temporaryKeyRecoveryDoesNotRequireAccountReauthorization() {
        val temporary = org.monogram.core.common.telegram.TelegramError.parse(
            "connection closed: temporary authorization renewal required",
        )
        assertEquals(org.monogram.core.common.telegram.TelegramError.Kind.Network, temporary.kind)
        assertFalse(temporary.requiresReauth)
        val revoked = org.monogram.core.common.telegram.TelegramError.parse("RPC 401: SESSION_REVOKED")
        assertTrue(revoked.requiresReauth)
    }

    @Test
    fun cancellingOneReaderKeepsTheProducerForTheRemainingReader() = runTest {
        val core = SessionCore(
            TelegramCredentials(1, "fixture"), "fixture.session", MtprotoNative.Stub,
            1_000L, StandardTestDispatcher(testScheduler), {},
        )
        val result = CompletableDeferred<Int>()
        var calls = 0
        var cancelled = false
        try {
            val first = async {
                core.coalesce("history:42") {
                    calls++
                    try {
                        result.await()
                    } finally {
                        cancelled = !result.isCompleted
                    }
                }
            }
            runCurrent()
            val second = async { core.coalesce<Int>("history:42") { error("duplicate producer") } }
            runCurrent()
            first.cancelAndJoin()
            assertFalse(cancelled)
            result.complete(7)
            assertEquals(7, second.await())
            assertEquals(1, calls)
        } finally {
            core.close()
        }
    }

    @Test
    fun finalReaderReleaseCancelsTheProducerAndRejoinStartsFreshWork() = runTest {
        val core = SessionCore(
            TelegramCredentials(1, "fixture"), "fixture.session", MtprotoNative.Stub,
            1_000L, StandardTestDispatcher(testScheduler), {},
        )
        var cancelled = false
        var calls = 0
        try {
            val first = async {
                core.coalesce<Int>("history:42") {
                    calls++
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
            runCurrent()
            first.cancelAndJoin()
            runCurrent()
            assertTrue(cancelled)
            val second = async { core.coalesce("history:42") { calls++; 8 } }
            assertEquals(8, second.await())
            assertEquals(2, calls)
        } finally {
            core.close()
        }
    }
}
