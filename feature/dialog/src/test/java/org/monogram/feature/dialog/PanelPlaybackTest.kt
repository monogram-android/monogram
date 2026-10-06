package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.network.http.MediaPriority
import java.io.File

class PanelPlaybackTest {
    @Test
    fun visibleBandAddsOneRowAndStopsAtTheList() {
        val cells = listOf(
            GridPlacement(index = 4, row = 1, column = 0),
            GridPlacement(index = 5, row = 1, column = 1),
            GridPlacement(index = 6, row = 1, column = 2),
            GridPlacement(index = 7, row = 1, column = 3),
        )
        assertEquals(4..11, panelPlaybackBand(cells, itemCount = 20))
        assertEquals(4..9, panelPlaybackBand(cells, itemCount = 10))
        assertEquals(IntRange.EMPTY, panelPlaybackBand(emptyList(), itemCount = 10))
    }

    @Test
    fun scrollingReleasesPlaybackUntilTheGridSettles() {
        val band = 0..8
        assertFalse(panelLoops(1, band, scrolling = true, settled = false, animationEnabled = true))
        assertFalse(
            panelLoops(
                1,
                band,
                scrolling = false,
                settled = false,
                animationEnabled = true
            )
        )
        assertTrue(panelLoops(1, band, scrolling = false, settled = true, animationEnabled = true))
        assertFalse(
            panelLoops(
                1,
                band,
                scrolling = false,
                settled = true,
                animationEnabled = false
            )
        )
        assertFalse(
            panelLoops(
                30,
                band,
                scrolling = false,
                settled = true,
                animationEnabled = true
            )
        )
        assertFalse(animationScaleAllowsPlayback(0f))
        assertTrue(animationScaleAllowsPlayback(1f))
    }

    @Test
    fun budgetCountsAlphaAsTwoContextsAndSharesOneDocument() {
        val ledger = PanelBudgetLedger()
        val first = ledger.tryLoop(1L, PanelTag.WebmAlpha)
        val shared = ledger.tryLoop(1L, PanelTag.WebmAlpha)
        assertEquals(first, shared)
        assertEquals(2, ledger.liveVpx)
        assertEquals(1, ledger.liveLoops)
        assertNull(ledger.tryLoop(1L, PanelTag.Webm))
        assertEquals(2, ledger.liveVpx)

        assertTrue(ledger.tryLoop(2L, PanelTag.Webm) != null)
        assertEquals(3, ledger.liveVpx)
        assertNull(ledger.tryLoop(3L, PanelTag.WebmAlpha))
        assertEquals(3, ledger.liveVpx)

        repeat(4) { index ->
            assertTrue(ledger.tryLoop(10L + index, PanelTag.Tgs) != null)
        }
        assertNull(ledger.tryLoop(20L, PanelTag.Tgs))
        assertEquals(4, ledger.liveTgs)
        assertNull(ledger.tryLoop(99L, PanelTag.Gif))
        assertFalse(routesToVpx(PanelTag.Gif))
        assertFalse(routesToVpx(PanelTag.Tgs))
        assertFalse(routesToVpx(PanelTag.Webp))
        assertEquals(2, vpxContextCost(PanelTag.WebmAlpha))

        ledger.stopLoop(1L, first!!)
        assertEquals(1, ledger.liveVpx)
        ledger.stopLoop(1L, first)
        assertEquals(1, ledger.liveVpx)
    }

    @Test
    fun cachedPlaybackStillStopsAtSixLoops() {
        val ledger = PanelBudgetLedger()
        val tickets = (1L..6L).map { ledger.tryAnimate(it) }
        assertTrue(tickets.all { it != null })
        assertEquals(6, ledger.liveLoops)
        assertEquals(0, ledger.liveVpx)
        assertEquals(0, ledger.liveTgs)
        assertNull(ledger.tryAnimate(7L))
        assertNull(ledger.tryLoop(8L, PanelTag.Webm))
        ledger.stopAnimate(1L, tickets.first()!!)
        assertEquals(5, ledger.liveLoops)
        assertTrue(ledger.tryAnimate(7L) != null)
    }

    @Test
    fun loopCapStopsBeforeTheContextCaps() {
        val ledger = PanelBudgetLedger(maxTgs = 10, maxVpx = 10, maxLoops = 6)
        repeat(6) { index ->
            assertTrue(ledger.tryLoop(index.toLong(), PanelTag.Webm) != null)
        }
        assertNull(ledger.tryLoop(8L, PanelTag.Webm))
        assertEquals(6, ledger.liveLoops)
        assertEquals(6, ledger.liveVpx)
    }

    @Test
    fun cacheNamesKeepEmojiStickerAndAlphaApart() {
        val emoji = panelCacheName(7L, PanelBucket.KeyboardEmoji, PanelTag.Webm)
        val sticker = panelCacheName(7L, PanelBucket.KeyboardSticker, PanelTag.Webm)
        val alpha = panelCacheName(7L, PanelBucket.KeyboardSticker, PanelTag.WebmAlpha)
        val tgs = panelCacheName(7L, PanelBucket.KeyboardEmoji, PanelTag.Tgs)
        assertNotEquals(emoji, sticker)
        assertNotEquals(sticker, alpha)
        assertNotEquals(emoji, tgs)
        assertFalse(panelNeedsNativeDecoder(3))
        assertTrue(panelNeedsNativeDecoder(0))
        assertEquals(1, panelFrameLimit(looping = false))
        assertEquals(16, panelFrameLimit(looping = true))
    }

    @Test
    fun frameCacheRoundTripScalesAndDoesNotCollide() {
        val dir = File(System.getProperty("java.io.tmpdir"), "panel-frames-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val full = ByteArray(4 * 4 * 4) { index -> (index + 1).toByte() }
            val (width, height) = scaledSize(4, 4, 2)
            val scaled = scaleRgba(full, 4, 4, width, height)
            val clip = PanelFrameClip(width, height, 33, listOf(scaled, scaled))
            val emoji = PanelFrameCache.file(dir, 7L, PanelBucket.KeyboardEmoji, PanelTag.Webm)
            val sticker =
                PanelFrameCache.file(dir, 7L, PanelBucket.KeyboardSticker, PanelTag.WebmAlpha)
            PanelFrameCache.write(emoji, clip)
            PanelFrameCache.write(sticker, clip.copy(delayMs = 40))
            val read = PanelFrameCache.read(emoji)
            assertEquals(2, read?.width)
            assertEquals(2, read?.height)
            assertEquals(33, read?.delayMs)
            assertEquals(2, read?.frames?.size)
            assertFalse(emoji.name == sticker.name)
            assertTrue(panelNeedsNativeDecoder(0))
            assertFalse(panelNeedsNativeDecoder(read?.frames?.size ?: 0))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preloadKeepsThumbsWideAndFullDocumentsNarrow() {
        val items = (0L..40L).map { id ->
            PickerMediaPreload.Item(
                documentId = id,
                cacheKey = "emoji:$id",
                thumbCacheKey = "emoji:$id:thumb",
                kind = if (id == 20L) PickerMediaPreload.DocKind.Webm else PickerMediaPreload.DocKind.Tgs,
            )
        }
        val plan = PickerMediaPreload.plan(items, visibleDocumentIds = setOf(20L))
        assertEquals(0..40, plan.window)
        assertTrue(plan.tasks.any { it.item.documentId == 12L && it.fetch == PickerMediaPreload.Fetch.Document })
        assertTrue(plan.tasks.any { it.item.documentId == 30L && it.fetch == PickerMediaPreload.Fetch.Thumb })
        assertFalse(plan.tasks.any { it.item.documentId == 30L && it.fetch == PickerMediaPreload.Fetch.Document })
        val webm = plan.tasks.first {
            it.item.documentId == 20L && it.fetch == PickerMediaPreload.Fetch.Document
        }
        val idleTgs =
            plan.tasks.first { it.item.documentId == 28L && it.fetch == PickerMediaPreload.Fetch.Document }
        assertTrue(plan.tasks.indexOf(webm) < plan.tasks.indexOf(idleTgs))
        assertEquals(MediaPriority.VISIBLE, webm.priority)
        assertEquals(MediaPriority.IDLE, idleTgs.priority)
        val moved = PickerMediaPreload.plan(items, visibleDocumentIds = setOf(40L))
        assertFalse(moved.tasks.any { it.item.documentId == 12L })
        assertTrue(plan.keys.contains("emoji:12"))
    }

    @Test
    fun gifFullFileWaitsUntilIdle() {
        val gifs =
            listOf(SavedGif(documentId = 3L, cacheKey = "gif:3", thumbCacheKey = "gif:3:thumb"))
        val moving = PickerMediaPreload.plan(PickerMediaPreload.gifs(gifs), setOf(3L), idle = false)
        val idle = PickerMediaPreload.plan(PickerMediaPreload.gifs(gifs), setOf(3L), idle = true)
        assertTrue(moving.tasks.all { it.fetch == PickerMediaPreload.Fetch.Thumb })
        assertTrue(idle.tasks.any { it.fetch == PickerMediaPreload.Fetch.Document && it.cacheKey == "gif:3" })
        assertFalse(routesToVpx(PanelTag.Gif))
        val players = listOf(true, false).flatMap { webm ->
            listOf(true, false).flatMap { mp4 ->
                listOf(true, false).flatMap { gzip ->
                    listOf(true, false).map { playable ->
                        gifCellPlayer(webm, mp4, gzip, playable)
                    }
                }
            }
        }
        assertEquals(16, players.size)
        assertEquals(
            GifCellPlayer.Video,
            gifCellPlayer(webm = true, mp4 = false, gzip = false, playable = true)
        )
        assertEquals(
            GifCellPlayer.Tgs,
            gifCellPlayer(webm = false, mp4 = false, gzip = true, playable = true)
        )
        assertEquals(
            GifCellPlayer.Still,
            gifCellPlayer(webm = true, mp4 = false, gzip = false, playable = false)
        )
        assertEquals(
            GifCellPlayer.Image,
            gifCellPlayer(webm = false, mp4 = false, gzip = false, playable = true)
        )
        assertTrue(players.none { player -> player.name == "Vpx" })
    }

    @Test
    fun blockAdditionalCountsAsAlphaWithoutTheAlphaModeElement() {
        assertTrue(webmHasAlpha(alphaMode = false, blockAdditional = true))
        assertTrue(webmHasAlpha(alphaMode = true, blockAdditional = false))
        assertFalse(webmHasAlpha(alphaMode = false, blockAdditional = false))
    }

    @Test
    fun emojiListKeepsUnicodeGlyphsAndPackStart() {
        val categories = listOf(
            SystemEmojiCategory(
                kind = SystemEmojiCategoryKind.Smileys,
                icon = "☺",
                glyphs = listOf("😀", "😁"),
            ),
        )
        val pack = StickerPack(
            id = 9L,
            title = "Cats",
            shortName = "cats",
            count = 2,
            isEmoji = true,
            previewDocumentIds = listOf(4L, 5L),
        )
        val collapsed = emojiPanelLayout(
            recent = listOf("😀"),
            categories = categories,
            packs = listOf(pack),
            loaded = mapOf(9L to pack),
            expanded = emptySet(),
            loadingPackIds = emptySet(),
            failedPackIds = emptySet(),
        )
        assertTrue(collapsed.cells.filterIsInstance<PickerGlyphCell>().any { it.glyph == "😀" })
        assertTrue(collapsed.cells.any { it is PickerExpandCell })
        assertTrue(collapsed.cells.none { it is PickerDocumentCell })
        val expanded = emojiPanelLayout(
            recent = emptyList(),
            categories = categories,
            packs = listOf(pack),
            loaded = mapOf(9L to pack),
            expanded = setOf(9L),
            loadingPackIds = emptySet(),
            failedPackIds = emptySet(),
        )
        assertEquals(listOf(4L, 5L), expanded.cells.mapNotNull { it.documentId })
        val start = expanded.packStart.getValue(9L)
        assertEquals(expanded.categoryStart.getValue(SystemEmojiCategoryKind.Smileys) + 3, start)
        assertTrue(expanded.cells[start] is PickerHeaderCell)
    }
}
