package org.monogram.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatOpenSessionCoordinatorTest {
    @Test
    fun `failed first open rolls back ownership`() = runTest {
        val coordinator = coordinator(openRemote = { _, _, _ -> false })

        coordinator.open(10L, "cmp")

        assertFalse(coordinator.hasOwners(10L))
    }

    @Test
    fun `reopen during grace keeps tdlib chat open`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + SupervisorJob())
        var opens = 0
        var closes = 0
        val coordinator = ChatOpenSessionCoordinator(
            scope = scope,
            context = dispatcher,
            closeGraceMs = 100L,
            openRemote = { _, _, _ -> opens++; true },
            onOpened = {},
            closeRemote = { closes++ },
            onClosed = {}
        )

        coordinator.open(10L, "first")
        coordinator.close(10L, "first")
        advanceTimeBy(50L)
        coordinator.open(10L, "second")
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(1, opens)
        assertEquals(0, closes)
        assertTrue(coordinator.hasOwners(10L))
    }

    private fun TestScope.coordinator(
        openRemote: suspend (Long, String, Set<String>) -> Boolean
    ): ChatOpenSessionCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ChatOpenSessionCoordinator(
            scope = this,
            context = dispatcher,
            closeGraceMs = 100L,
            openRemote = openRemote,
            onOpened = {},
            closeRemote = {},
            onClosed = {}
        )
    }
}
