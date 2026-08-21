package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoStoryListRepository
import org.monogram.domain.models.stories.*
import org.monogram.domain.repository.StoryRepository

/** Keeps the TDLib/file-backed story contract isolated until MTProto story parity exists. */
internal class TelegramBackendStoryRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> StoryRepository,
    scope: CoroutineScope,
    private val mtProtoFactory: () -> MtProtoStoryListRepository = { throw UnsupportedOperationException("MTProto story mutations are not configured") },
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : StoryRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    private val emptyActiveStories = MutableStateFlow<Map<StoryListType, List<ActiveStoryListModel>>>(emptyMap())
    private val emptyStoryCounts = MutableStateFlow<Map<StoryListType, Int>>(emptyMap())
    private val emptyStealthMode = MutableStateFlow(StoryStealthModeModel())
    private val emptyOptions = MutableStateFlow(StoryOptionsModel())
    private val emptyPostResult = MutableStateFlow<StoryPostResultModel?>(null)

    init {
        scope.launch {
            selectionStore.observe(accountId).collectLatest { backend ->
                selectedBackend.value = backend
                if (backend == TelegramBackendKind.LEGACY) {
                    launch { legacy.activeStories.collect { emptyActiveStories.value = it } }
                    launch { legacy.storyListChatCounts.collect { emptyStoryCounts.value = it } }
                    launch { legacy.stealthMode.collect { emptyStealthMode.value = it } }
                    launch { legacy.storyOptions.collect { emptyOptions.value = it } }
                    launch { legacy.lastPostResult.collect { emptyPostResult.value = it } }
                } else {
                    emptyActiveStories.value = emptyMap()
                    emptyStoryCounts.value = emptyMap()
                    emptyStealthMode.value = StoryStealthModeModel()
                    emptyOptions.value = StoryOptionsModel()
                    emptyPostResult.value = null
                }
            }
        }
    }

    override val activeStories: StateFlow<Map<StoryListType, List<ActiveStoryListModel>>>
        get() = emptyActiveStories
    override val storyListChatCounts: StateFlow<Map<StoryListType, Int>>
        get() = emptyStoryCounts
    override val stealthMode: StateFlow<StoryStealthModeModel>
        get() = emptyStealthMode
    override val storyOptions: StateFlow<StoryOptionsModel>
        get() = emptyOptions
    override val lastPostResult: StateFlow<StoryPostResultModel?>
        get() = emptyPostResult

    override suspend fun loadActiveStories(listType: StoryListType) = dispatch { legacy.loadActiveStories(listType) }
    override suspend fun refreshStoryOptions() = dispatch { legacy.refreshStoryOptions() }
    override suspend fun getChatActiveStories(chatId: Long): ActiveStoryListModel? = dispatch { legacy.getChatActiveStories(chatId) }
    override suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean): StoryModel? = dispatch { legacy.getStory(chatId, storyId, onlyLocal) }
    override suspend fun getStoryAlbum(chatId: Long, albumId: Int, offset: Int, limit: Int): List<StoryModel> = dispatch { legacy.getStoryAlbum(chatId, albumId, offset, limit) }
    override suspend fun getChatPostedToChatPageStories(chatId: Long, fromStoryId: Int, limit: Int): StoryPageModel? = dispatch { legacy.getChatPostedToChatPageStories(chatId, fromStoryId, limit) }
    override suspend fun getChatArchivedStories(chatId: Long, fromStoryId: Int, limit: Int): StoryPageModel? = dispatch { legacy.getChatArchivedStories(chatId, fromStoryId, limit) }
    override suspend fun openStory(chatId: Long, storyId: Int) = dispatch { legacy.openStory(chatId, storyId) }
    override suspend fun closeStory(chatId: Long, storyId: Int) = dispatch { legacy.closeStory(chatId, storyId) }
    override suspend fun activateStealthMode(): Boolean = dispatch { legacy.activateStealthMode() }
    override suspend fun canPostStory(chatId: Long): StoryPostCapabilityModel = dispatch { legacy.canPostStory(chatId) }
    override suspend fun getStoryStatistics(chatId: Long, storyId: Int, isDark: Boolean): StoryStatisticsModel? = dispatch { legacy.getStoryStatistics(chatId, storyId, isDark) }
    override suspend fun getStoryAvailableReactions(rowSize: Int): StoryAvailableReactionsModel? = dispatch { legacy.getStoryAvailableReactions(rowSize) }
    override suspend fun setStoryReaction(chatId: Long, storyId: Int, reaction: StoryReactionModel): Boolean = dispatch { legacy.setStoryReaction(chatId, storyId, reaction) }
    override suspend fun getStoryInteractions(storyId: Int, offset: String, limit: Int, query: String, onlyContacts: Boolean, preferForwards: Boolean, preferWithReaction: Boolean): StoryInteractionPageModel? = dispatch { legacy.getStoryInteractions(storyId, offset, limit, query, onlyContacts, preferForwards, preferWithReaction) }
    override suspend fun postStory(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel = dispatch { legacy.postStory(chatId, draft) }
    override suspend fun editStory(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean = dispatch { legacy.editStory(chatId, storyId, draft) }
    override suspend fun deleteStory(chatId: Long, storyId: Int): Boolean = dispatch { legacy.deleteStory(chatId, storyId) }
    override suspend fun toggleStoryPostedToChatPage(chatId: Long, storyId: Int, isPostedToChatPage: Boolean): Boolean = dispatch { legacy.toggleStoryPostedToChatPage(chatId, storyId, isPostedToChatPage) }
    override suspend fun setChatActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatActiveStoriesList(chatId, listType)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setActiveStoriesList(chatId, listType)
    }
    override fun clearLastPostResult() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.clearLastPostResult()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private suspend fun <T> dispatch(legacyOperation: suspend () -> T): T = when (selected()) {
        TelegramBackendKind.LEGACY -> legacyOperation()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "MTProto stories are not available"
    )

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
