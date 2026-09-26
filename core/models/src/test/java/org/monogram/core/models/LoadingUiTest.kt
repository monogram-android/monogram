package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingUiTest {

    @Test
    fun `start becomes visible as an indeterminate process`() {
        val state = LoadingUi.Idle.start()

        assertTrue(state.visible)
        assertNull(state.progress)
        assertFalse(state.determinate)
        assertFalse(state.error)
    }

    @Test
    fun `stop is not visible and clears progress and error`() {
        val state = LoadingUi.Idle.startDeterminate().tick(0.6f).fail()

        val stopped = state.stop()

        assertFalse(stopped.visible)
        assertNull(stopped.progress)
        assertFalse(stopped.error)
    }

    @Test
    fun `cancel is not visible so a late result cannot re-show the loader`() {
        val started = LoadingUi.Idle.start()
        val generation = started.generation

        val cancelled = started.cancel()

        assertFalse(cancelled.visible)
        assertFalse(cancelled.isCurrent(generation))
    }

    @Test
    fun `start stop start is visible again with an increased generation`() {
        val first = LoadingUi.Idle.start()
        val firstGeneration = first.generation

        val second = first.stop().start()

        assertTrue(second.visible)
        assertTrue("generation must advance on every start", second.generation > firstGeneration)
        assertTrue(second.isCurrent(second.generation))
        assertFalse("the previous generation is stale", second.isCurrent(firstGeneration))
    }

    @Test
    fun `double start does not spawn a second instance but does re-key`() {
        val once = LoadingUi.Idle.start()
        val twice = once.start()

        assertTrue(twice.visible)
        assertEquals(1, twice.generation - once.generation)
        assertFalse(twice.isCurrent(once.generation))
    }

    @Test
    fun `determinate progress fails then retries from zero`() {
        val loading = LoadingUi.Idle.startDeterminate().tick(0.4f)

        assertEquals(0.4f, loading.fraction, 0.0001f)

        val failed = loading.fail()

        assertFalse(failed.visible)
        assertTrue(failed.error)
        assertTrue("determinacy survives the failure so retry knows to count", failed.determinate)

        val retried = failed.retry()

        assertTrue(retried.visible)
        assertFalse(retried.error)
        assertEquals("retry must reset the target to 0f", 0f, retried.fraction, 0.0001f)
        assertTrue(retried.generation > failed.generation)
    }

    @Test
    fun `indeterminate retry stays indeterminate instead of inventing a fraction`() {
        val retried = LoadingUi.Idle.start().fail().retry()

        assertTrue(retried.visible)
        assertNull(retried.progress)
    }

    @Test
    fun `progress is clamped into the legal range`() {
        assertEquals(1f, LoadingUi.Idle.startDeterminate().tick(3.5f).fraction, 0.0001f)
        assertEquals(0f, LoadingUi.Idle.startDeterminate().tick(-2f).fraction, 0.0001f)
    }

    @Test
    fun `completion reaches one so the caller can hold for the settle delay`() {
        val state = LoadingUi.Idle.startDeterminate().complete()

        assertTrue(state.visible)
        assertEquals(1f, state.fraction, 0.0001f)
    }

    @Test
    fun `success hides the loader and clears progress`() {
        val state = LoadingUi.Idle.startDeterminate().complete().stop()

        assertFalse(state.visible)
        assertNull(state.progress)
        assertFalse(state.error)
    }

    @Test
    fun `dismiss error clears the affordance without starting again`() {
        val state = LoadingUi.Idle.start().fail().dismissError()

        assertFalse(state.visible)
        assertFalse(state.error)
    }

    @Test
    fun `flag bridges a plain isLoading boolean and stays re-entrant`() {
        val first = LoadingUi.flag(visible = true, generation = 1)
        val stopped = LoadingUi.flag(visible = false, generation = 1)
        val second = LoadingUi.flag(visible = true, generation = 2)

        assertTrue(first.visible)
        assertFalse(stopped.visible)
        assertTrue(second.visible)
        assertTrue(second.generation > first.generation)
        assertFalse(second.isCurrent(first.generation))
        assertNull(second.progress)
    }

    @Test
    fun `generation advances across many restarts so every re-entry replays`() {
        var state = LoadingUi.Idle
        val seen = mutableListOf<Int>()
        repeat(5) {
            state = state.start()
            seen += state.generation
            state = state.stop()
        }

        assertEquals(seen.sorted(), seen)
        assertEquals(seen.toSet().size, seen.size)
    }
}
