package org.monogram.feature.dialog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.network.http.MediaPriority

class SelectedTargetWarmupTest {
    @Test
    fun warmupWaitsForTheFirstFrameThenUsesTheDeviceCap() = runTest {
        val seen = mutableListOf<Triple<Long, Int, Int>>()
        val warmup = SelectedTargetWarmup(this, tablet = { false }) { peer, anchor, limit, _ ->
            seen += Triple(peer, anchor, limit)
        }
        warmup.select(5L, 9)
        assertTrue(seen.isEmpty())
        warmup.onFirstFrame()
        testScheduler.runCurrent()
        assertEquals(listOf(Triple(5L, 9, PHONE_WARMUP_LIMIT)), seen)

        val tablet = SelectedTargetWarmup(this, tablet = { true }) { peer, _, limit, _ ->
            seen += Triple(peer, 0, limit)
        }
        tablet.onFirstFrame()
        tablet.select(8L)
        testScheduler.runCurrent()
        assertEquals(TABLET_WARMUP_LIMIT, seen.last().third)
    }

    @Test
    fun changingTheTargetCancelsThePreviousOwner() = runTest {
        val started = CompletableDeferred<Unit>()
        val warmup = SelectedTargetWarmup(this) { peer, _, _, _ ->
            if (peer == 1L) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        warmup.onFirstFrame()
        warmup.select(1L)
        testScheduler.runCurrent()
        started.await()
        warmup.select(2L)
        testScheduler.runCurrent()
        assertTrue(warmup.limit() == PHONE_WARMUP_LIMIT)
    }

    @Test
    fun pressureHoldsTheTargetUntilItClears() = runTest {
        var pressure = true
        val seen = mutableListOf<Int>()
        val warmup = SelectedTargetWarmup(this, underPressure = { pressure }) { _, _, _, priority ->
            seen += priority
        }
        warmup.onFirstFrame()
        warmup.select(4L)
        testScheduler.runCurrent()
        assertTrue(seen.isEmpty())
        pressure = false
        warmup.promote(4L)
        testScheduler.runCurrent()
        assertEquals(listOf(MediaPriority.VISIBLE), seen)
    }

    @Test
    fun promoteRestartsAnIdleWarmupAtVisiblePriority() = runTest {
        val seen = mutableListOf<Int>()
        val started = CompletableDeferred<Unit>()
        val warmup = SelectedTargetWarmup(this) { _, _, _, priority ->
            seen += priority
            if (priority < MediaPriority.VISIBLE) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        warmup.onFirstFrame()
        warmup.select(4L)
        testScheduler.runCurrent()
        started.await()
        warmup.promote(4L)
        assertEquals(listOf(MediaPriority.IDLE), seen)
        testScheduler.runCurrent()
        assertEquals(listOf(MediaPriority.IDLE, MediaPriority.VISIBLE), seen)
    }

    @Test
    fun clearReleasesInterestBeforeTheLoadStarts() = runTest {
        var ran = false
        val warmup = SelectedTargetWarmup(this) { _, _, _, _ -> ran = true }
        warmup.onFirstFrame()
        warmup.select(3L)
        warmup.clear()
        testScheduler.advanceUntilIdle()
        assertFalse(ran)
    }
}
