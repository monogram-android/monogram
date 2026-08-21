package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.mtproto.MtProtoStoryListRepository
import org.monogram.data.mtproto.MtProtoStoryStealthMode
import org.monogram.data.mtproto.MtProtoStoryStealthModeReader
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.repository.StoryRepository
import java.lang.reflect.Proxy

class TelegramBackendStoryRouterTest {
    @Test
    fun `story state is available before backend selection loads`() {
        val router = TelegramBackendStoryRouter(
            selectionStore = UnloadedSelectionStore(),
            legacyFactory = { error("legacy story repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(false, router.stealthMode.value.isActive)
        assertTrue(router.activeStories.value.isEmpty())
    }

    @Test
    fun `selected MTProto story list mutation avoids legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                MtProtoStoryListRepository { chatId, listType ->
                    assertEquals(7L, chatId)
                    assertEquals(StoryListType.ARCHIVE, listType)
                    true
                }
            },
            mtProtoStealthModeFactory = { stealthReader(100, 200) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.setChatActiveStoriesList(7L, StoryListType.ARCHIVE))
    }

    @Test
    fun `story list mutation follows rollback selection without eager legacy creation`() = runBlocking {
        val selection = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO)
        var mtProtoCalls = 0
        var legacyCreated = 0
        val router = TelegramBackendStoryRouter(
            selectionStore = selection,
            legacyFactory = {
                legacyCreated += 1
                legacyStoryRepository()
            },
            mtProtoFactory = {
                MtProtoStoryListRepository { _, _ ->
                    mtProtoCalls += 1
                    true
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.setChatActiveStoriesList(7L, StoryListType.MAIN))
        assertEquals(1, mtProtoCalls)

        selection.select("default", TelegramBackendKind.LEGACY)
        assertEquals(false, router.setChatActiveStoriesList(7L, StoryListType.ARCHIVE))

        assertEquals(1, legacyCreated)
        assertEquals(1, mtProtoCalls)
    }

    @Test
    fun `selected MTProto exposes persisted stealth mode without legacy repository`() {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoStealthModeFactory = { stealthReader(100, 200) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(100, router.stealthMode.value.activeUntilDate)
        assertEquals(200, router.stealthMode.value.cooldownUntilDate)
    }

    @Test
    fun `selected MTProto stories fail closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.getStory(chatId = 1L, storyId = 2) }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    @Suppress("UNCHECKED_CAST")
    private fun legacyStoryRepository(): StoryRepository = Proxy.newProxyInstance(
        StoryRepository::class.java.classLoader,
        arrayOf(StoryRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getActiveStories", "getStoryListChatCounts" -> MutableStateFlow(emptyMap<StoryListType, List<Any>>())
            "getStealthMode", "getStoryOptions", "getLastPostResult" -> MutableStateFlow(null)
            "setChatActiveStoriesList" -> false
            else -> throw UnsupportedOperationException("Unexpected legacy story method: ${method.name}")
        }
    } as StoryRepository

    private fun stealthReader(activeUntilDate: Int, cooldownUntilDate: Int) =
        MtProtoStoryStealthModeReader { flowOf(MtProtoStoryStealthMode(activeUntilDate, cooldownUntilDate)) }

    private class UnloadedSelectionStore : TelegramBackendSelectionStore {
        private val events = MutableSharedFlow<TelegramBackendKind>()
        override suspend fun get(accountId: String) = TelegramBackendKind.KOTLIN_MTPROTO
        override fun observe(accountId: String): Flow<TelegramBackendKind> = events
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
