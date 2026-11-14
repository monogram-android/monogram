package org.monogram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AccountWorkManagerTest {
    @Test
    fun restartsRegisteredWorkerAfterSessionLossStopsIt() = runBlocking {
        val starts = AtomicInteger()
        var worker: AccountBackgroundWork? = null
        val manager = AccountWorkManager<String>(
            scope = CoroutineScope(Dispatchers.Unconfined),
            accountId = String::toLong,
            create = {
                object : AccountBackgroundWork {
                    var running = false

                    init {
                        worker = this
                    }

                    override fun isRunning(): Boolean = running
                    override fun start(scope: CoroutineScope) {
                        running = true
                        starts.incrementAndGet()
                    }

                    override fun resume() = Unit
                    override suspend fun stop() {
                        running = false
                    }
                }
            },
        )

        manager.start("11")
        worker!!.stop()
        manager.start("11")

        assertEquals(2, starts.get())
        assertEquals(setOf(11L), manager.runningAccountIds())
    }

    @Test
    fun startsAndStopsEachAccountWorkerIndependently() = runBlocking {
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val manager = AccountWorkManager<String>(
            scope = CoroutineScope(Dispatchers.Unconfined),
            accountId = String::toLong,
            create = {
                object : AccountBackgroundWork {
                    var running = false
                    override fun isRunning(): Boolean = running
                    override fun start(scope: CoroutineScope) {
                        running = true
                        started.incrementAndGet()
                    }

                    override fun resume() = Unit
                    override suspend fun stop() {
                        running = false
                        stopped.incrementAndGet()
                    }
                }
            },
        )

        manager.start("11")
        manager.start("22")
        assertEquals(setOf(11L, 22L), manager.runningAccountIds())
        manager.stop(11L)
        assertEquals(setOf(22L), manager.runningAccountIds())
        manager.start("11")
        assertEquals(setOf(11L, 22L), manager.runningAccountIds())
        assertEquals(3, started.get())
        assertEquals(1, stopped.get())
    }
}
