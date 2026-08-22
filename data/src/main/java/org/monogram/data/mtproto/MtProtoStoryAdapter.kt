package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.stories.*
import org.monogram.domain.repository.StoryRepository

/** Keeps the Telegram/file-backed story contract isolated until MTProto story parity exists. */
internal class MtProtoStoryAdapter(
    scope: CoroutineScope,
    private val mtProtoFactory: () -> MtProtoStoryListRepository,
    private val mtProtoActiveListFactory: () -> MtProtoStoryActiveListReader ,
    private val mtProtoReadFactory: () -> MtProtoStoryReadRepository ,
    private val mtProtoComposerFactory: () -> MtProtoStoryComposerRepository ,
    private val mtProtoStealthModeFactory: () -> MtProtoStoryStealthModeReader,
    private val mtProtoAvailableReactionsFactory: () -> MtProtoStoryAvailableReactionsReader? = { null },
    private val mtProtoStoryChangesFactory: (suspend () -> Flow<Unit>)? = null,
    private val accountId: String = "default",
) : StoryRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    private val mtProtoActiveLists by lazy(LazyThreadSafetyMode.NONE, mtProtoActiveListFactory)
    private val mtProtoReads by lazy(LazyThreadSafetyMode.NONE, mtProtoReadFactory)
    private val mtProtoComposer by lazy(LazyThreadSafetyMode.NONE, mtProtoComposerFactory)
    private val mtProtoStealthMode by lazy(LazyThreadSafetyMode.NONE, mtProtoStealthModeFactory)
    private val mtProtoAvailableReactions by lazy(LazyThreadSafetyMode.NONE, mtProtoAvailableReactionsFactory)
    private val emptyActiveStories = MutableStateFlow<Map<StoryListType, List<ActiveStoryListModel>>>(emptyMap())
    private val emptyStoryCounts = MutableStateFlow<Map<StoryListType, Int>>(emptyMap())
    private val emptyStealthMode = MutableStateFlow(StoryStealthModeModel())
    private val emptyOptions = MutableStateFlow(StoryOptionsModel())
    private val emptyPostResult = MutableStateFlow<StoryPostResultModel?>(null)

    init {
        scope.launch {
            emptyStealthMode.value = StoryStealthModeModel()
            launch {
                mtProtoStealthMode.observe().collect { mode ->
                    emptyStealthMode.value = mode?.let {
                        StoryStealthModeModel(it.activeUntilDate, it.cooldownUntilDate)
                    } ?: StoryStealthModeModel()
                }
            }
            // Live stories: projection writes republish the strip immediately from Room, then a
            // throttled server refresh picks up brand-new peers. Local re-reads are deduped by the
            // StateFlow; the throttle prevents refresh-driven writes from looping back into
            // unbounded network refreshes.
            launch {
                val changes = mtProtoStoryChangesFactory?.invoke() ?: return@launch
                changes.collect {
                    runCatching { mtProtoActiveLists.readLocal() }.onSuccess { active ->
                        publishActiveLists(active)
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkRefreshMillis >= STORY_NETWORK_REFRESH_MIN_INTERVAL_MILLIS) {
                        lastNetworkRefreshMillis = now
                        runCatching {
                            mtProtoActiveLists.refreshAndRead()
                        }.onSuccess { active -> publishActiveLists(active) }
                            .onFailure { lastNetworkRefreshMillis = 0L }
                    }
                }
            }
        }
    }

    private var lastNetworkRefreshMillis = 0L

    private fun publishActiveLists(active: Map<StoryListType, List<ActiveStoryListModel>>) {
        emptyActiveStories.value = active
        emptyStoryCounts.value = active.mapValues { it.value.size }
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

    override suspend fun loadActiveStories(listType: StoryListType) {
        if (!emptyActiveStories.value.containsKey(listType)) {
            runCatching { mtProtoActiveLists.refreshAndRead() }
                .onSuccess { active -> publishActiveLists(active) }
        }
    }
    override suspend fun refreshStoryOptions() {
        // No complete owned options model exists; the router exposes its empty default state.
    }
    override suspend fun getChatActiveStories(chatId: Long): ActiveStoryListModel? =
        emptyActiveStories.value.values
            .asSequence()
            .flatten()
            .firstOrNull { it.chatId == chatId }
    override suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean): StoryModel? = mtProtoReads.getStory(chatId, storyId, onlyLocal)
    override suspend fun getStoryAlbum(chatId: Long, albumId: Int, offset: Int, limit: Int): List<StoryModel> =
        projectedStories(chatId)
            .filter { albumId in it.albumIds }
            .drop(offset)
            .take(limit)
    override suspend fun getChatPostedToChatPageStories(chatId: Long, fromStoryId: Int, limit: Int): StoryPageModel? =
        projectedStoryPage(chatId, StoryListType.MAIN, fromStoryId, limit) {
            it.isPostedToChatPage
        }
    override suspend fun getChatArchivedStories(chatId: Long, fromStoryId: Int, limit: Int): StoryPageModel? =
        projectedStoryPage(chatId, StoryListType.ARCHIVE, fromStoryId, limit) {
            true
        }
    override suspend fun openStory(chatId: Long, storyId: Int) = mtProto.markRead(chatId, storyId)
    override suspend fun closeStory(chatId: Long, storyId: Int) = mtProto.close(chatId, storyId)
    override suspend fun activateStealthMode(): Boolean = mtProto.activateStealthMode()
    override suspend fun canPostStory(chatId: Long): StoryPostCapabilityModel = mtProto.canSend(chatId)
    override suspend fun getStoryStatistics(chatId: Long, storyId: Int, isDark: Boolean): StoryStatisticsModel? =
        throw UnsupportedOperationException(
            "stories.getStoryStatistics does not exist in the pinned MTProto cloud layer 223 schema",
        )
    override suspend fun getStoryAvailableReactions(rowSize: Int): StoryAvailableReactionsModel? =
        mtProtoAvailableReactions?.get(rowSize)
    override suspend fun setStoryReaction(chatId: Long, storyId: Int, reaction: StoryReactionModel): Boolean = mtProto.setReaction(chatId, storyId, reaction)
    override suspend fun getStoryInteractions(
        chatId: Long,
        storyId: Int,
        offset: String,
        limit: Int,
        query: String,
        onlyContacts: Boolean,
        preferForwards: Boolean,
        preferWithReaction: Boolean,
    ): StoryInteractionPageModel? = mtProto.getInteractions(
        chatId,
        storyId,
        offset,
        limit,
        query,
        onlyContacts,
        preferForwards,
        preferWithReaction,
    )
    override suspend fun postStory(chatId: Long, draft: StoryComposerDraftModel): StoryPostResultModel = mtProtoComposer.post(chatId, draft)
    override suspend fun editStory(chatId: Long, storyId: Int, draft: StoryComposerDraftModel): Boolean = mtProtoComposer.edit(chatId, storyId, draft)
    override suspend fun deleteStory(chatId: Long, storyId: Int): Boolean = mtProto.delete(chatId, storyId)
    override suspend fun toggleStoryPostedToChatPage(
        chatId: Long,
        storyId: Int,
        isPostedToChatPage: Boolean,
    ): Boolean = mtProto.setPostedToChatPage(chatId, storyId, isPostedToChatPage)
    override suspend fun setChatActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean = mtProto.setActiveStoriesList(chatId, listType)
    override fun clearLastPostResult() = Unit

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



    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
        const val STORY_NETWORK_REFRESH_MIN_INTERVAL_MILLIS = 15_000L
    }
}
