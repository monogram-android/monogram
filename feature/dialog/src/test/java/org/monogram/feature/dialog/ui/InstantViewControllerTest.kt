package org.monogram.feature.dialog.ui

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.InstantViewBlock
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.InstantViewRelatedArticle

class InstantViewControllerTest {
    @Test
    fun partialPageRefetchesOnceWithHashZero() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val controller = InstantViewController(
            loadPage = { url, hash ->
                calls += url to hash
                Outcome.Ok(
                    InstantViewPage(
                        url = url,
                        displayUrl = url,
                        hasInstantView = true,
                        part = hash != 0,
                        hash = 4,
                        blocks = listOf(InstantViewBlock.Text("paragraph", "body")),
                    ),
                )
            },
            initialUrl = "https://example.com/a",
            initialHash = 4,
        )
        controller.load()
        assertEquals(listOf("https://example.com/a" to 4, "https://example.com/a" to 0), calls)
        assertFalse(controller.state.value.page!!.part)
        assertEquals(1, controller.state.value.stack.size)
    }

    @Test
    fun relatedPagePushesStackAndAnchorStaysOnPage() = runBlocking {
        val controller = InstantViewController(
            loadPage = { url, _ ->
                Outcome.Ok(
                    InstantViewPage(
                        url = url,
                        displayUrl = url,
                        hasInstantView = true,
                        blocks = listOf(
                            InstantViewBlock.Text("paragraph", "one"),
                            InstantViewBlock.Anchor("intro"),
                        ),
                    ),
                )
            },
            initialUrl = "https://example.com/a",
            initialHash = 0,
        )
        controller.load()
        assertTrue(controller.openLink("https://example.com/b"))
        assertEquals(2, controller.state.value.stack.size)
        assertEquals("https://example.com/b", controller.state.value.page!!.url)
        assertTrue(controller.openLink("#intro"))
        assertEquals("intro", controller.state.value.pendingAnchor)
        assertEquals("intro", controller.consumeAnchor())
        assertEquals(null, controller.state.value.pendingAnchor)
    }

    @Test
    fun controllerIsInstanceKeeperAndSearchCoversRelatedCells() {
        assertTrue(InstantViewController(
            loadPage = { _, _ -> error("unused") },
            initialUrl = "https://example.com/a",
            initialHash = 0,
        ) is com.arkivanov.essenty.instancekeeper.InstanceKeeper.Instance)
        assertEquals(
            "Next",
            blockSearchText(
                InstantViewBlock.Related(
                    articles = listOf(InstantViewRelatedArticle("https://ex", title = "Next")),
                ),
            ),
        )
        assertEquals(
            "notes.pdf",
            blockSearchText(
                InstantViewBlock.Document(
                    kind = "document",
                    id = 8,
                    cacheKey = "doc:8",
                    fileName = "notes.pdf",
                ),
            ),
        )
        assertEquals("https://ex/go", entityHref("text_url", "https://ex/go", "Open"))
        assertEquals("#intro", entityHref("anchor", "intro", "here"))
    }

    @Test
    fun cancelledLoadCanBeRetried() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val controller = InstantViewController(
            loadPage = { url, _ ->
                val n = calls.incrementAndGet()
                if (n == 1) {
                    started.complete(Unit)
                    kotlinx.coroutines.delay(10_000)
                }
                Outcome.Ok(
                    InstantViewPage(
                        url = url,
                        displayUrl = url,
                        hasInstantView = true,
                        blocks = listOf(InstantViewBlock.Text("paragraph", "ok")),
                    ),
                )
            },
            initialUrl = "https://example.com/a",
            initialHash = 0,
        )
        val job = launch { controller.load() }
        started.await()
        job.cancel()
        job.join()
        assertEquals(null, controller.state.value.page)
        controller.load()
        assertEquals(2, calls.get())
        assertEquals("ok", (controller.state.value.page!!.blocks.single() as InstantViewBlock.Text).text)
    }
}
