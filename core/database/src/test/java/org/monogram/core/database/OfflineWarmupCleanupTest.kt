package org.monogram.core.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.PeerId
import org.monogram.core.models.PeerId as ChatId

class OfflineWarmupCleanupTest {
    private class CountingWarmup : OfflineWarmup(db = null) {
        var drops = 0

        override suspend fun dropUnsentAfterRestart() {
            drops++
        }
    }

    @Test
    fun cleanupRunsOnceOnTheFirstRead() = runBlocking {
        val warmup = CountingWarmup()

        assertEquals("no read must not clean up", 0, warmup.drops)

        warmup.chats()
        assertEquals(1, warmup.drops)

        warmup.chats()
        warmup.folders()
        warmup.messages(PeerId(42))
        warmup.olderMessages(PeerId(42), beforeId = 10)
        assertEquals("cleanup must not repeat", 1, warmup.drops)
    }

    @Test
    fun cleanupCompletesBeforeTheGatedReadReturns() = runBlocking {
        val order = mutableListOf<String>()
        val warmup = object : OfflineWarmup(db = null) {
            override suspend fun dropUnsentAfterRestart() {
                order += "cleanup"
            }
        }

        warmup.chats()
        order += "after-read"

        assertEquals(listOf("cleanup", "after-read"), order)
    }

    @Test
    fun eachInstanceCleansUpItsOwnState() = runBlocking {
        val first = CountingWarmup()
        val second = CountingWarmup()
        first.messages(ChatId(1))
        second.messages(ChatId(1))
        assertEquals(1, first.drops)
        assertEquals(1, second.drops)
    }

    @Test
    fun concurrentReadsRunTheCleanupOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var drops = 0
        val warmup = object : OfflineWarmup(db = null) {
            override suspend fun dropUnsentAfterRestart() {
                drops++
                started.complete(Unit)
                release.await()
            }
        }

        val first = launch { warmup.chats() }
        started.await()
        val second = launch { warmup.messages(PeerId(1)) }
        yield()
        release.complete(Unit)
        first.join()
        second.join()

        assertEquals("both readers must share one cleanup", 1, drops)
    }

    @Test
    fun failingCleanupIsLoggedOnceAndDoesNotPoisonReads() = runBlocking {
        var attempts = 0
        val warmup = object : OfflineWarmup(db = null) {
            override suspend fun dropUnsentAfterRestart() {
                attempts++
                error("database unavailable")
            }
        }

        warmup.chats()
        warmup.messages(PeerId(1))
        warmup.folders()

        assertEquals("a failing cleanup must not be retried on every read", 1, attempts)
    }

    @Test
    fun cancelledCleanupIsRethrownAndRetriedByTheNextRead() = runBlocking {
        var attempts = 0
        val warmup = object : OfflineWarmup(db = null) {
            override suspend fun dropUnsentAfterRestart() {
                attempts++
                if (attempts == 1) throw CancellationException("scope died during startup")
            }
        }

        val first = runCatching { warmup.chats() }
        assertTrue(
            "cancellation must not be swallowed, got ${first.exceptionOrNull()}",
            first.exceptionOrNull() is CancellationException,
        )

        warmup.chats()
        assertEquals("a cancelled sweep must be retried, not marked done", 2, attempts)
    }
}
