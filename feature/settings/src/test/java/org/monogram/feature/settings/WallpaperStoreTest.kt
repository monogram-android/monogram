package org.monogram.feature.settings

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.Wallpaper
import org.monogram.core.models.WallpaperCatalog

@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperStoreTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val wallpaper = Wallpaper(1, 2, "sample", false, false, "", null, listOf(0), null, 0, false, false)

    @Test fun notModifiedRetainsCachedCatalog() = runTest {
        val source = FakeSource()
        source.result = Outcome.Ok(WallpaperCatalog(7, true, emptyList()))
        val store = WallpaperStoreFactory(DefaultStoreFactory()) { source }.create()
        try {
            store.accept(WallpaperStore.Intent.Open(File("unused")))
            advanceUntilIdle()
            assertEquals(7L, source.requestedHash)
            assertEquals(listOf(wallpaper), store.state.wallpapers)
            assertFalse(store.state.loading)
            assertFalse(store.state.error)
        } finally { store.dispose() }
    }

    @Test fun refreshFailurePreservesCachedCatalogAndRetryRecovers() = runTest {
        val source = FakeSource()
        source.result = Outcome.Err("offline")
        val store = WallpaperStoreFactory(DefaultStoreFactory()) { source }.create()
        try {
            store.accept(WallpaperStore.Intent.Open(File("unused")))
            advanceUntilIdle()
            assertTrue(store.state.error)
            assertEquals(listOf(wallpaper), store.state.wallpapers)
            source.result = Outcome.Ok(WallpaperCatalog(8, false, listOf(wallpaper.copy(id = 3))))
            store.accept(WallpaperStore.Intent.Retry)
            advanceUntilIdle()
            assertFalse(store.state.error)
            assertEquals(3L, store.state.wallpapers.single().id)
        } finally { store.dispose() }
    }

    @Test fun closingCancelsPreviewAndAllowsReopen() = runTest {
        val source = FakeSource()
        val store = WallpaperStoreFactory(DefaultStoreFactory()) { source }.create()
        try {
            store.accept(WallpaperStore.Intent.Open(File("unused")))
            advanceUntilIdle()
            store.accept(WallpaperStore.Intent.Preview(wallpaper))
            runCurrent()
            assertEquals(1, source.previewStarted)
            store.accept(WallpaperStore.Intent.Close)
            runCurrent()
            assertEquals(1, source.previewCancelled)
            assertTrue(store.state.failedPreviews.isEmpty())
            store.accept(WallpaperStore.Intent.Open(File("unused")))
            store.accept(WallpaperStore.Intent.Preview(wallpaper))
            runCurrent()
            assertEquals(2, source.previewStarted)
        } finally { store.dispose() }
    }

    private inner class FakeSource : WallpaperSource {
        var requestedHash = 0L
        var previewStarted = 0
        var previewCancelled = 0
        var result: Outcome<WallpaperCatalog> = Outcome.Ok(WallpaperCatalog(7, true, emptyList()))
        override suspend fun cachedCatalog() = WallpaperCatalog(7, false, listOf(wallpaper))
        override suspend fun refresh(hash: Long): Outcome<WallpaperCatalog> {
            requestedHash = hash
            return result
        }
        override suspend fun preview(wallpaper: Wallpaper): Outcome<File> {
            previewStarted++
            try { awaitCancellation() }
            finally { previewCancelled++ }
        }
    }
}
