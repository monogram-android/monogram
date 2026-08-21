package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoStoryActiveListReader
import org.monogram.data.mtproto.MtProtoStoryComposerRepository
import org.monogram.data.mtproto.MtProtoStoryListRepository
import org.monogram.data.mtproto.MtProtoStoryReadRepository
import org.monogram.data.mtproto.MtProtoStoryStealthModeReader
import org.monogram.domain.models.stories.*
import org.monogram.domain.repository.StoryRepository

/** Keeps the TDLib/file-backed story contract isolated until MTProto story parity exists. */
internal class TelegramBackendStoryRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> StoryRepository,
    scope: CoroutineScope,
    private val mtProtoFactory: () -> MtProtoStoryListRepository = { throw UnsupportedOperationException("MTProto story mutations are not configured") },
    private val mtProtoActiveListFactory: () -> MtProtoStoryActiveListReader = { throw UnsupportedOperationException("MTProto active story lists are not configured") },
    private val mtProtoReadFactory: () -> MtProtoStoryReadRepository = { throw UnsupportedOperationException("MTProto story reads are not configured") },
    private val mtProtoComposerFactory: () -> MtProtoStoryComposerRepository = { throw UnsupportedOperationException("MTProto story composition is not configured") },
    private val mtProtoStealthModeFactory: () -> MtProtoStoryStealthModeReader = { throw UnsupportedOperationException("MTProto story stealth mode is not configured") },
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : StoryRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    private val mtProtoActiveLists by lazy(LazyThreadSafetyMode.NONE, mtProtoActiveListFactory)
    private val mtProtoReads by lazy(LazyThreadSafetyMode.NONE, mtProtoReadFactory)
    private val mtProtoComposer by lazy(LazyThreadSafetyMode.NONE, mtProtoComposerFactory)
    private val mtProtoStealthMode by lazy(LazyThreadSafetyMode.NONE, mtProtoStealthModeFactory)
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
                    launch {
                        mtProtoStealthMode.observe().collect { mode ->
                            emptyStealthMode.value = mode?.let {
                                StoryStealthModeModel(it.activeUntilDate, it.cooldownUntilDate)
                            } ?: StoryStealthModeModel()
                        }
                    }
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

    override suspend fun loadActiveStories(listType: StoryListType) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.loadActiveStories(listType)
        TelegramBackendKind.KOTLIN_MTPROTO -> {
            if (!emptyActiveStories.value.containsKey(listType)) {
                runCatching { mtProtoActiveLists.refreshAndRead() }
                    .onSuccess { active ->
                        emptyActiveStories.value = active
                        emptyStoryCounts.value = active.mapValues { it.value.size }
                    }
            }
            Unit
        }
    }
    override suspend fun refreshStoryOptions() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.refreshStoryOptions()
        // No complete owned options model exists; the router exposes its empty default state.
        TelegramBackendKind.KOTLIN_MTPROTO -> Unit
    }
    override suspend fun getChatActiveStories(chatId: Long): ActiveStoryListModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getChatActiveStories(chatId)
        TelegramBackendKind.KOTLIN_MTPROTO -> emptyActiveStories.value.values
            .asSequence()
            .flatten()
            .firstOrNull { it.chatId == chatId }
    }
    override suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean): StoryModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getStory(chatId, storyId, onlyLocal)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoReads.getStory(chatId, storyId, onlyLocal)
    }
    override suspend fun getStoryAlbum(chatId: Long, albumId: Int, offset: Int, limit: Int): List<StoryModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getStoryAlbum(chatId, albumId, offset, limit)
        TelegramBackendKind.KOTLIN_MTPROTO -> projectedStories(chatId)
            .filter { albumId in it.albumIds }
            .drop(offset)
            .take(limit)
    }
    override suspend fun getChatPostedToChatPageStories(chatId: Long, fromStoryId: Int, limit: Int): StoryPageModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getChatPostedToChatPageStories(chatId, fromStoryId, limit)
        TelegramBackendKind.KOTLIN_MTPROTO -> projectedStoryPage(chatId, StoryListType.MAIN, fromStoryId, limit) {
            it.isPostedToChatPage
        }
    }
    override suspend fun getChatArchivedStories(chatId: Long, fromStoryId: Int, limit: Int): StoryPageModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getChatArchivedStories(chatId, fromStoryId, limit)
        TelegramBackendKind.KOTLIN_MTPROTO -> projectedStoryPage(chatId, StoryListType.ARCHIVE, fromStoryId, limit) {
            true
        }
    }
    override suspend fun openStory(chatId: Long, storyId: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.openStory(chatId, storyId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.markRead(chatId, storyId)
    }
    override suspend fun closeStory(chatId: Long, storyId: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.closeStory(chatId, storyId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.close(chatId, storyId)
    }
    override suspend fun activateStealthMode(): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.activateStealthMode()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.activateStealthMode()
    }
    override suspend fun canPostStory(chatId: Long): StoryPostCapabilityModel = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.canPostStory(chatId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.canSend(chatId)
    }
    override suspend fun getStoryStatistics(chatId: Long, storyId: Int, isDark: Boolean): StoryStatisticsModel? = dispatch { legacy.getStoryStatistics(chatId, storyId, isDark) }
    override suspend fun getStoryAvailableReactions(rowSize: Int): StoryAvailableReactionsModel? = dispatch { legacy.getStoryAvailableReactions(rowSize) }
    override suspend fun setStoryReaction(chatId: Long, storyId: Int, reaction: StoryReactionModel): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setStoryReaction(chatId, storyId, reaction)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setReaction(chatId, storyId, reaction)
    }
    override suspend fun getStoryInteractions(
        chatId: Long,
        storyId: Int,
        offset: String,
        limit: Int,
        query: String,
        onlyContacts: Boolean,
        preferForwards: Boolean,
        preferWithReaction: Boolean,
    ): StoryInteractionPageModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getStoryInteractions(
            chatId,
            storyId,
            offset,
            limit,
            query,
            onlyContacts,
            preferForwards,
            preferWithReaction,
        )
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getInteractions(
            chatId,
            storyId,
            offset,
            limit,
            query,
            onlyContacts,
            preferForwards,
            preferWithReaction,
        )
    }
    override suspend fun postStory(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.postStory(chatId, draft)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoComposer.post(chatId, draft)
    }
    override suspend fun editStory(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.editStory(chatId, storyId, draft)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoComposer.edit(chatId, storyId, draft)
    }
    override suspend fun deleteStory(chatId: Long, storyId: Int): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.deleteStory(chatId, storyId)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.delete(chatId, storyId)
    }
    override suspend fun toggleStoryPostedToChatPage(
        chatId: Long,
        storyId: Int,
        isPostedToChatPage: Boolean,
    ): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.toggleStoryPostedToChatPage(chatId, storyId, isPostedToChatPage)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setPostedToChatPage(chatId, storyId, isPostedToChatPage)
    }
    override suspend fun setChatActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatActiveStoriesList(chatId, listType)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setActiveStoriesList(chatId, listType)
    }
    override fun clearLastPostResult() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.clearLastPostResult()
        TelegramBackendKind.KOTLIN_MTPROTO -> Unit
    }

    private suspend fun projectedStories(chatId: Long): List<StoryModel> {
        val summaries = emptyActiveStories.value.values
            .asSequence()
            .flatten()
            .firstOrNull { it.chatId == chatId }
            ?.stories
            .orEmpty()
        return summaries.mapNotNull { mtProtoReads.getStory(chatId, it.storyId, false) }
    }

    private suspend fun projectedStoryPage(
        chatId: Long,
        listType: StoryListType,
        fromStoryId: Int,
        limit: Int,
        predicate: (StoryModel) -> Boolean,
    ): StoryPageModel? {
        val active = emptyActiveStories.value[listType] ?: return null
        val summaries = active.firstOrNull { it.chatId == chatId }?.stories ?: return null
        val candidates = buildList {
            summaries
                .filter { fromStoryId == 0 || it.storyId < fromStoryId }
                .forEach { summary ->
                    mtProtoReads.getStory(chatId, summary.storyId, false)
                        ?.takeIf(predicate)
                        ?.let(::add)
                }
        }
        return StoryPageModel(
            totalCount = candidates.size,
            pinnedStoryIds = candidates.filter { it.isPostedToChatPage }.map { it.id },
            stories = candidates.take(limit),
        )
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
