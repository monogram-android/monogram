package org.monogram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupSequenceTest {
    @Test
    fun firstPaintWaitsForClientInitialization() = runBlocking {
        val releaseClient = CompletableDeferred<Unit>()
        var cleanupStarted = false
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            runFirstPaintStartup(
                this, StartupSteps(
                    openDatabase = {},
                    createClient = { releaseClient.await() },
                    installImageCache = {},
                    cleanup = { cleanupStarted = true },
                    prewarm = {},
                    mediaMigration = {},
                    installDownloadSettings = {},
                    createMedia = {},
                    createPush = {},
                )
            )
        }

        assertFalse(startup.isCompleted)
        assertFalse(cleanupStarted)
        releaseClient.complete(Unit)
        startup.await()
        assertTrue(cleanupStarted)
    }

    @Test
    fun databaseAndClientAreReadyBeforeCleanup() = runBlocking {
        val order = mutableListOf<String>()
        runFirstPaintStartup(
            this, StartupSteps(
                openDatabase = { order += "database" },
                createClient = { order += "client" },
                installImageCache = {},
                cleanup = { order += "cleanup" },
                prewarm = {},
                mediaMigration = {},
                installDownloadSettings = {},
                createMedia = {},
                createPush = {},
            )
        )
        assertEquals(listOf("database", "client", "cleanup"), order)
    }

    @Test
    fun cacheMaintenanceIsPostReadyAndDoesNotBlockPrewarmOrServices() = runBlocking {
        val releaseMaintenance = CompletableDeferred<Unit>()
        var prewarmStarted = false
        var maintenanceStarted = false
        var servicesStarted = false
        val steps = StartupSteps(
            openDatabase = {},
            createClient = {},
            installImageCache = {},
            cleanup = {},
            prewarm = { prewarmStarted = true },
            mediaMigration = {},
            maintainCache = {
                maintenanceStarted = true
                releaseMaintenance.await()
            },
            installDownloadSettings = {},
            createMedia = {},
            createPush = {},
            startPush = { servicesStarted = true },
        )
        try {
            runFirstPaintStartup(this, steps)
            yield()
            assertTrue(prewarmStarted)
            assertFalse(maintenanceStarted)
            startAfterFirstPaint(steps, this)
            yield()
            assertTrue(maintenanceStarted)
            assertTrue(servicesStarted)
            assertFalse(releaseMaintenance.isCompleted)
        } finally {
            releaseMaintenance.complete(Unit)
        }
    }

    @Test
    fun firstPaintCancellationDoesNotStartPrewarm() = runBlocking {
        val cancellation = CancellationException("cancelled")
        var prewarmStarted = false
        val thrown = try {
            runFirstPaintStartup(
                this, StartupSteps(
                    openDatabase = {},
                    createClient = {},
                    installImageCache = {},
                    cleanup = { throw cancellation },
                    prewarm = { prewarmStarted = true },
                    mediaMigration = {},
                    installDownloadSettings = {},
                    createMedia = {},
                    createPush = {},
                )
            )
            null
        } catch (caught: CancellationException) {
            caught
        }
        assertEquals(cancellation.message, thrown?.message)
        assertFalse(prewarmStarted)
    }

    @Test
    fun unavailableSecondaryAccountDoesNotBlockOtherAccounts() = runBlocking {
        val accounts = (1L..3L).map(::account)
        val attempted = mutableListOf<Long>()
        var unavailable = 0

        val ready =
            prepareStartupAccounts(accounts, activeUserId = 1, onUnavailable = { unavailable++ }) {
                attempted += it.userId
                if (it.userId == 2L) throw IllegalStateException("storage unavailable")
            }

        assertEquals(listOf(1L, 2L, 3L), attempted)
        assertEquals(setOf(1L, 3L), ready)
        assertEquals(1, unavailable)
    }

    @Test
    fun activeAccountFailureAndCancellationPropagate() = runBlocking {
        val failure = IllegalStateException("active account unavailable")
        val cancellation = CancellationException("cancelled")
        for (error in listOf(failure, cancellation)) {
            val thrown = try {
                prepareStartupAccounts(listOf(account(1)), 1, {}) { throw error }
                null
            } catch (caught: Exception) {
                caught
            }
            assertTrue(thrown === error)
        }
    }

    private fun account(id: Long) =
        AccountRecord(id, 1, File("session-$id"), "account-$id.db", File("media-$id"))
}
