package org.monogram.feature.dialog

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelReadyPolicyTest {
    @Test
    fun initialIdleAndEitherScrollWithholdNativeWorkAndGifPlayers() {
        val clock = PanelSettleClock()
        val idle = clock.advance(
            panelEpoch = "emoji",
            visible = true,
            started = true,
            gridScrolling = false,
            stripScrolling = false,
            nowMs = 0L,
        )
        assertFalse(idle)
        assertFalse(panelNativeAdmitted(idle))
        assertFalse(constructsGifPlayer(idle))

        assertFalse(
            clock.advance(
                panelEpoch = "emoji",
                visible = true,
                started = true,
                gridScrolling = false,
                stripScrolling = false,
                nowMs = PanelSettleMs - 1,
            ),
        )
        val settled = clock.advance(
            panelEpoch = "emoji",
            visible = true,
            started = true,
            gridScrolling = false,
            stripScrolling = false,
            nowMs = PanelSettleMs,
        )
        assertTrue(settled)
        assertTrue(panelNativeAdmitted(settled))
        assertTrue(constructsGifPlayer(settled))

        val gridMoving = clock.advance(
            panelEpoch = "emoji",
            visible = true,
            started = true,
            gridScrolling = true,
            stripScrolling = false,
            nowMs = PanelSettleMs,
        )
        assertFalse(gridMoving)
        assertFalse(panelNativeAdmitted(gridMoving))
        assertFalse(constructsGifPlayer(gridMoving))

        assertFalse(
            clock.advance(
                panelEpoch = "emoji",
                visible = true,
                started = true,
                gridScrolling = false,
                stripScrolling = false,
                nowMs = PanelSettleMs,
            ),
        )
        assertTrue(
            clock.advance(
                panelEpoch = "emoji",
                visible = true,
                started = true,
                gridScrolling = false,
                stripScrolling = false,
                nowMs = PanelSettleMs * 2,
            ),
        )
        val stripMoving = clock.advance(
            panelEpoch = "emoji",
            visible = true,
            started = true,
            gridScrolling = false,
            stripScrolling = true,
            nowMs = PanelSettleMs * 2,
        )
        assertFalse(stripMoving)
        assertFalse(constructsGifPlayer(stripMoving))
        assertFalse(
            clock.advance(
                panelEpoch = "gifs",
                visible = true,
                started = true,
                gridScrolling = false,
                stripScrolling = false,
                nowMs = PanelSettleMs * 2,
            ),
        )
        assertFalse(
            panelAdmitsWork(
                visible = false,
                started = true,
                gridScrolling = false,
                stripScrolling = false,
                settled = true,
            ),
        )
        assertFalse(
            panelAdmitsWork(
                visible = true,
                started = false,
                gridScrolling = false,
                stripScrolling = false,
                settled = true,
            ),
        )
    }

    @Test
    fun savedGifPlaybackRequiresALease() {
        assertEquals(
            GifCellPlayer.Still,
            gifCellPlayer(webm = true, mp4 = false, gzip = false, playable = true, lease = false),
        )
        assertEquals(
            GifCellPlayer.Video,
            gifCellPlayer(webm = true, mp4 = false, gzip = false, playable = true, lease = true),
        )
        assertEquals(
            GifCellPlayer.Still,
            gifCellPlayer(webm = false, mp4 = false, gzip = true, playable = true, lease = false),
        )
        assertFalse(
            savedGifConstructsPlayer(
                gifCellPlayer(
                    webm = true,
                    mp4 = false,
                    gzip = false,
                    playable = true,
                    lease = false
                ),
            ),
        )
        assertTrue(
            savedGifConstructsPlayer(
                gifCellPlayer(
                    webm = false,
                    mp4 = true,
                    gzip = false,
                    playable = true,
                    lease = true
                ),
            ),
        )
        assertFalse(
            panelAnimationAdmitted(ready = true, animations = false, visible = true, lease = true),
        )
    }

    @Test
    fun staticPosterSkipsNativeExtractionForTgsAndAlpha() {
        assertEquals(
            PickerPoster.CachedStill,
            pickerPoster(
                tgs = false,
                alphaVideo = false,
                hasTransparentPoster = false,
                hasCachedStill = true,
                admitColdExtraction = false,
            ),
        )
        assertEquals(
            PickerPoster.None,
            pickerPoster(
                tgs = false,
                alphaVideo = false,
                hasTransparentPoster = false,
                hasCachedStill = false,
                admitColdExtraction = false,
            ),
        )
        assertEquals(
            PickerPoster.ColdStill,
            pickerPoster(
                tgs = false,
                alphaVideo = false,
                hasTransparentPoster = false,
                hasCachedStill = false,
                admitColdExtraction = true,
            ),
        )
        assertEquals(
            PickerPoster.None,
            pickerPoster(
                tgs = true,
                alphaVideo = false,
                hasTransparentPoster = false,
                hasCachedStill = false,
                admitColdExtraction = true,
            ),
        )
        assertEquals(
            PickerPoster.CachedTransparent,
            pickerPoster(
                tgs = false,
                alphaVideo = true,
                hasTransparentPoster = true,
                hasCachedStill = true,
                admitColdExtraction = true,
            ),
        )
        assertEquals(
            PickerPoster.None,
            pickerPoster(
                tgs = false,
                alphaVideo = true,
                hasTransparentPoster = false,
                hasCachedStill = true,
                admitColdExtraction = true,
            ),
        )
        assertEquals("gif:4@216", posterRequestKey("gif:4", 216))
    }

    @Test
    fun cachedStillDrawsWhileTgsOrWebmAlphaAdmissionIsWithheld() {
        assertEquals(
            PickerPoster.CachedStill,
            savedGifPoster(
                tgs = true,
                webm = false,
                knownAlpha = null,
                hasCachedPoster = true,
                posterIsPng = false,
                admitPoster = false,
            ),
        )
        assertEquals(
            PickerPoster.None,
            savedGifPoster(
                tgs = true,
                webm = false,
                knownAlpha = null,
                hasCachedPoster = false,
                posterIsPng = false,
                admitPoster = true,
            ),
        )
        assertEquals(
            PickerPoster.CachedStill,
            savedGifPoster(
                tgs = false,
                webm = true,
                knownAlpha = null,
                hasCachedPoster = true,
                posterIsPng = false,
                admitPoster = false,
            ),
        )
        assertEquals(
            PickerPoster.None,
            savedGifPoster(
                tgs = false,
                webm = true,
                knownAlpha = null,
                hasCachedPoster = false,
                posterIsPng = false,
                admitPoster = true,
            ),
        )
        assertEquals(
            PickerPoster.CachedTransparent,
            savedGifPoster(
                tgs = false,
                webm = true,
                knownAlpha = true,
                hasCachedPoster = true,
                posterIsPng = true,
                admitPoster = true,
            ),
        )
        assertEquals(
            PickerPoster.None,
            savedGifPoster(
                tgs = false,
                webm = true,
                knownAlpha = true,
                hasCachedPoster = true,
                posterIsPng = false,
                admitPoster = true,
            ),
        )
        assertEquals(
            PickerPoster.CachedStill,
            savedGifPoster(
                tgs = false,
                webm = false,
                knownAlpha = false,
                hasCachedPoster = true,
                posterIsPng = false,
                admitPoster = false,
            ),
        )
        assertEquals(
            PickerPoster.ColdStill,
            savedGifPoster(
                tgs = false,
                webm = true,
                knownAlpha = false,
                hasCachedPoster = false,
                posterIsPng = false,
                admitPoster = true,
            ),
        )
    }

    @Test
    fun posterGateDedupesAndSerializesColdLoads() = runTest {
        val gate = PosterLoadGate<String>(maxEntries = 4)
        var overlap = 0
        var maxOverlap = 0
        var productions = 0
        suspend fun produce(value: String): String {
            productions += 1
            overlap += 1
            maxOverlap = maxOf(maxOverlap, overlap)
            delay(20)
            overlap -= 1
            return value
        }

        val first = List(2) {
            async { gate.load("same") { produce("poster") } }
        }
        val second = async { gate.load("other") { produce("other") } }
        val loaded = (first + second).awaitAll()
        assertEquals(listOf("poster", "poster", "other"), loaded)
        assertEquals(2, productions)
        assertEquals(1, maxOverlap)
        assertEquals("poster", gate.cached("same"))
    }

    private fun constructsGifPlayer(ready: Boolean): Boolean {
        val lease = panelAnimationAdmitted(
            ready = ready,
            animations = true,
            visible = true,
            lease = true,
        )
        return savedGifConstructsPlayer(
            gifCellPlayer(webm = true, mp4 = false, gzip = false, playable = true, lease = lease),
        )
    }
}
