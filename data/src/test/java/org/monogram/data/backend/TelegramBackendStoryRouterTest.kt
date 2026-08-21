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
import org.monogram.data.mtproto.MtProtoStoryActiveListReader
import org.monogram.data.mtproto.MtProtoStoryListRepository
import org.monogram.data.mtproto.MtProtoStoryReadRepository
import org.monogram.data.mtproto.MtProtoStoryStealthMode
import org.monogram.data.mtproto.MtProtoStoryStealthModeReader
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
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
    fun `selected MTProto story host refreshes are inert without legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoActiveListFactory = { MtProtoStoryActiveListReader { emptyStoryLists() } },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.refreshStoryOptions()
        router.loadActiveStories(StoryListType.MAIN)
        router.loadActiveStories(StoryListType.ARCHIVE)
        router.clearLastPostResult()

        assertTrue(router.activeStories.value.values.all(List<*>::isEmpty))
        assertEquals(0, router.storyOptions.value.captionLengthMax)
    }

    @Test
    fun `selected MTProto story list mutation avoids legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean {
                        assertEquals(7L, chatId)
                        assertEquals(StoryListType.ARCHIVE, listType)
                        return true
                    }
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
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean {
                        mtProtoCalls += 1
                        return true
                    }
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
    fun `selected MTProto opening a story marks it read without legacy repository`() = runBlocking {
        var read: Pair<Long, Int>? = null
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?) = true
                    override suspend fun markRead(chatId: Long, storyId: Int) { read = chatId to storyId }
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.openStory(7L, 2)

        assertEquals(7L to 2, read)
    }

    @Test
    fun `selected MTProto stealth activation avoids legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?) = true
                    override suspend fun activateStealthMode() = true
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.activateStealthMode())
    }

    @Test
    fun `selected MTProto story close acknowledgment avoids legacy repository`() = runBlocking {
        var closed: Pair<Long, Int>? = null
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?) = true
                    override suspend fun close(chatId: Long, storyId: Int) { closed = chatId to storyId }
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.closeStory(7L, 2)

        assertEquals(7L to 2, closed)
    }

    @Test
    fun `selected MTProto story send capability avoids legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?) = true
                    override suspend fun canSend(chatId: Long) = StoryPostCapabilityModel.Allowed(3)
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(StoryPostCapabilityModel.Allowed(3), router.canPostStory(7L))
    }

    @Test
    fun `selected MTProto story deletion avoids legacy repository`() = runBlocking {
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?) = true
                    override suspend fun delete(chatId: Long, storyId: Int) = chatId == 7L && storyId == 2
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.deleteStory(7L, 2))
    }

    @Test
    fun `selected MTProto story reactions avoid legacy repository`() = runBlocking {
        var capturedReaction: Triple<Long, Int, StoryReactionModel>? = null
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoFactory = {
                object : MtProtoStoryListRepository {
                    override suspend fun setActiveStoriesList(chatId: Long, listType: StoryListType?) = true
                    override suspend fun setReaction(chatId: Long, storyId: Int, reaction: StoryReactionModel): Boolean {
                        capturedReaction = Triple(chatId, storyId, reaction)
                        return true
                    }
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(router.setStoryReaction(7L, 2, StoryReactionModel(emoji = "👍")))
        assertEquals(Triple(7L, 2, StoryReactionModel(emoji = "👍")), capturedReaction)
    }

    @Test
    fun `selected MTProto reads projected story without creating legacy repository`() = runBlocking {
        val projected = StoryModel(
            id = 2,
            posterChatId = 7L,
            date = 3,
            caption = "caption",
            media = StoryMediaModel(StoryMediaType.PHOTO, path = null, previewPath = null),
            privacy = null,
        )
        val router = TelegramBackendStoryRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy story repository must not be created") },
            mtProtoReadFactory = {
                MtProtoStoryReadRepository { chatId, storyId, _ ->
                    if (chatId == 7L && storyId == 2) projected else null
                }
            },
            mtProtoStealthModeFactory = { stealthReader(0, 0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(projected, router.getStory(chatId = 7L, storyId = 2))
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

    private fun emptyStoryLists(): Map<StoryListType, List<ActiveStoryListModel>> = mapOf(
        StoryListType.MAIN to emptyList(),
        StoryListType.ARCHIVE to emptyList(),
    )

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
