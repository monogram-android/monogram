package org.monogram.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import org.monogram.data.datasource.FileDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.StoryInteractionMapper
import org.monogram.data.mapper.StoryMapper
import org.monogram.data.mapper.StoryMapper.toDomainStoryListType
import org.monogram.data.mapper.StoryMapper.toTdPrivacy
import org.monogram.data.mapper.StoryMapper.toTdStoryList
import org.monogram.domain.models.stories.ActiveStoryListModel
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryInteractionPageModel
import org.monogram.domain.models.stories.StoryListType
import org.monogram.domain.models.stories.StoryMediaModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryOptionsModel
import org.monogram.domain.models.stories.StoryPostCapabilityModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryReactionModel
import org.monogram.domain.models.stories.StoryStatisticsModel
import org.monogram.domain.models.stories.StoryStealthModeModel
import org.monogram.domain.repository.StoryRepository

class StoryRepositoryImpl(
    private val gateway: TelegramGateway,
    private val updates: UpdateDispatcher,
    private val scope: CoroutineScope,
    private val fileDataSource: FileDataSource,
    private val settingsRemoteDataSource: SettingsRemoteDataSource
) : StoryRepository {

    private val state = MutableStateFlow(StoryRepositoryState())

    private val _activeStories =
        MutableStateFlow<Map<StoryListType, List<ActiveStoryListModel>>>(emptyMap())
    override val activeStories: StateFlow<Map<StoryListType, List<ActiveStoryListModel>>> =
        _activeStories.asStateFlow()

    private val _storyListChatCounts = MutableStateFlow<Map<StoryListType, Int>>(emptyMap())
    override val storyListChatCounts: StateFlow<Map<StoryListType, Int>> =
        _storyListChatCounts.asStateFlow()

    private val _stealthMode = MutableStateFlow(StoryStealthModeModel())
    override val stealthMode: StateFlow<StoryStealthModeModel> = _stealthMode.asStateFlow()

    private val _storyOptions = MutableStateFlow(StoryOptionsModel())
    override val storyOptions: StateFlow<StoryOptionsModel> = _storyOptions.asStateFlow()

    private val _lastPostResult = MutableStateFlow<StoryPostResultModel?>(null)
    override val lastPostResult: StateFlow<StoryPostResultModel?> = _lastPostResult.asStateFlow()

    init {
        scope.launch {
            updates.all.collect(::handleUpdate)
        }
    }

    override suspend fun loadActiveStories(listType: StoryListType) {
        Log.d(TAG, "loadActiveStories request listType=$listType")
        runCatching {
            gateway.execute(TdApi.LoadActiveStories(listType.toTdStoryList()))
        }.onSuccess {
            Log.d(TAG, "loadActiveStories dispatched listType=$listType")
        }.onFailure {
            Log.d(TAG, "loadActiveStories failed for $listType: ${it.message}")
        }
    }

    override suspend fun refreshStoryOptions() {
        val options = StoryOptionsModel(
            captionLengthMax = getIntegerOption("story_caption_length_max"),
            linkAreaCountMax = getIntegerOption("story_link_area_count_max"),
            stealthModeCooldownPeriod = getIntegerOption("story_stealth_mode_cooldown_period"),
            stealthModeFuturePeriod = getIntegerOption("story_stealth_mode_future_period"),
            stealthModePastPeriod = getIntegerOption("story_stealth_mode_past_period"),
            suggestedReactionAreaCountMax = getIntegerOption("story_suggested_reaction_area_count_max"),
            viewersExpirationDelay = getIntegerOption("story_viewers_expiration_delay")
        )
        applyState(StoryRepositoryStateReducer.withStoryOptions(state.value, options))
    }

    override suspend fun getChatActiveStories(chatId: Long): ActiveStoryListModel? {
        val existing = state.value.activeStories.values
            .asSequence()
            .flatMap(List<ActiveStoryListModel>::asSequence)
            .firstOrNull { it.chatId == chatId }
        if (existing != null) {
            Log.d(
                TAG,
                "getChatActiveStories cache hit chatId=$chatId list=${existing.listType} stories=${existing.stories.size}"
            )
            return existing
        }

        return runCatching {
            Log.d(TAG, "getChatActiveStories remote fetch chatId=$chatId")
            StoryMapper.mapActiveStories(
                gateway.execute(TdApi.GetChatActiveStories(chatId))
            )
        }.onSuccess {
            Log.d(
                TAG,
                "getChatActiveStories fetched chatId=$chatId list=${it.listType} stories=${it.stories.size}"
            )
            applyState(StoryRepositoryStateReducer.withActiveStories(state.value, it))
        }
            .getOrNull()
    }

    override suspend fun getStory(chatId: Long, storyId: Int, onlyLocal: Boolean): StoryModel? {
        state.value.storyCache[StoryKey(chatId, storyId)]?.let { cached ->
            if (cached.hasRenderableMedia() || onlyLocal) {
                return cached
            }
        }
        return runCatching {
            val tdStory = gateway.execute(TdApi.GetStory(chatId, storyId, onlyLocal))
            val active = getChatActiveStories(chatId)
            val resolvedMedia = resolveStoryMedia(tdStory.content)
            StoryMapper.mapStory(tdStory, active, resolvedMedia)
        }.onSuccess {
            Log.d(
                TAG,
                "getStory mapped chatId=$chatId storyId=$storyId mediaType=${it.media.type} path=${it.media.path} preview=${it.media.previewPath}"
            )
            applyState(StoryRepositoryStateReducer.withStory(state.value, it))
        }
            .getOrNull()
    }

    override suspend fun getStoryAlbum(
        chatId: Long,
        albumId: Int,
        offset: Int,
        limit: Int
    ): List<StoryModel> {
        return runCatching {
            val stories =
                gateway.execute(TdApi.GetStoryAlbumStories(chatId, albumId, offset, limit))
            val active = getChatActiveStories(chatId)
            stories.stories.orEmpty().map { story ->
                StoryMapper.mapStory(story, active, resolveStoryMedia(story.content))
            }
        }.onSuccess { mapped ->
            var current = state.value
            mapped.forEach { story ->
                current = StoryRepositoryStateReducer.withStory(current, story)
            }
            applyState(current)
        }.getOrElse { emptyList() }
    }

    override suspend fun openStory(chatId: Long, storyId: Int) {
        runCatching { gateway.execute(TdApi.OpenStory(chatId, storyId)) }
    }

    override suspend fun closeStory(chatId: Long, storyId: Int) {
        runCatching { gateway.execute(TdApi.CloseStory(chatId, storyId)) }
    }

    override suspend fun activateStealthMode(): Boolean {
        return runCatching {
            gateway.execute(TdApi.ActivateStoryStealthMode())
            true
        }.getOrDefault(false)
    }

    override suspend fun canPostStory(chatId: Long): StoryPostCapabilityModel {
        return runCatching {
            StoryMapper.mapPostCapability(gateway.execute(TdApi.CanPostStory(chatId)))
        }.getOrElse { StoryPostCapabilityModel.Unknown(it.message ?: "Unable to check capability") }
    }

    override suspend fun getStoryStatistics(
        chatId: Long,
        storyId: Int,
        isDark: Boolean
    ): StoryStatisticsModel? {
        return runCatching {
            StoryInteractionMapper.mapStoryStatistics(
                gateway.execute(TdApi.GetStoryStatistics(chatId, storyId, isDark))
            )
        }.getOrNull()
    }

    override suspend fun setStoryReaction(
        chatId: Long,
        storyId: Int,
        reaction: StoryReactionModel
    ): Boolean {
        return runCatching {
            gateway.execute(
                TdApi.SetStoryReaction(
                    chatId,
                    storyId,
                    reaction.toTdReactionType(),
                    true
                )
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun getStoryInteractions(
        storyId: Int,
        offset: String,
        limit: Int,
        query: String,
        onlyContacts: Boolean,
        preferForwards: Boolean,
        preferWithReaction: Boolean
    ): StoryInteractionPageModel? {
        return runCatching {
            StoryInteractionMapper.mapStoryInteractions(
                gateway.execute(
                    TdApi.GetStoryInteractions(
                        storyId,
                        query,
                        onlyContacts,
                        preferForwards,
                        preferWithReaction,
                        offset,
                        limit
                    )
                )
            )
        }.getOrNull()
    }

    override suspend fun postStory(
        chatId: Long,
        draft: StoryComposerDraftModel
    ): StoryPostResultModel {
        return runCatching {
            val story = gateway.execute(
                TdApi.PostStory(
                    chatId,
                    draft.toTdContent(),
                    draft.widgetLink.toTdAreas(),
                    TdApi.FormattedText(draft.caption, emptyArray()),
                    draft.privacy.toTdPrivacy(),
                    intArrayOf(),
                    draft.activePeriodSeconds,
                    null,
                    draft.keepOnProfile,
                    draft.protectContent
                )
            )
            val mapped = StoryMapper.mapStory(story, getChatActiveStories(chatId))
            val result = StoryPostResultModel.Success(mapped)
            applyState(
                StoryRepositoryStateReducer.withStory(state.value, mapped)
                    .copy(lastPostResult = result)
            )
            result
        }.getOrElse { error ->
            val result = StoryPostResultModel.Failure(
                story = null,
                message = error.message ?: "Failed to post story"
            )
            applyState(state.value.copy(lastPostResult = result))
            result
        }
    }

    override suspend fun editStory(
        chatId: Long,
        storyId: Int,
        draft: StoryComposerDraftModel
    ): Boolean {
        return runCatching {
            gateway.execute(
                TdApi.EditStory(
                    chatId,
                    storyId,
                    draft.toTdContent(),
                    draft.widgetLink.toTdAreas(),
                    TdApi.FormattedText(draft.caption, emptyArray())
                )
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun deleteStory(chatId: Long, storyId: Int): Boolean {
        return runCatching {
            gateway.execute(TdApi.DeleteStory(chatId, storyId))
            applyState(StoryRepositoryStateReducer.withStoryDeleted(state.value, chatId, storyId))
            true
        }.getOrDefault(false)
    }

    override suspend fun setChatActiveStoriesList(chatId: Long, listType: StoryListType?): Boolean {
        return runCatching {
            gateway.execute(TdApi.SetChatActiveStoriesList(chatId, listType?.toTdStoryList()))
            true
        }.getOrDefault(false)
    }

    override fun clearLastPostResult() {
        applyState(StoryRepositoryStateReducer.clearLastPostResult(state.value))
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateChatActiveStories -> {
                applyState(
                    StoryRepositoryStateReducer.withActiveStories(
                        state.value,
                        StoryMapper.mapActiveStories(update.activeStories)
                    )
                )
            }

            is TdApi.UpdateStory -> {
                val active = state.value.activeStories.values
                    .asSequence()
                    .flatMap(List<ActiveStoryListModel>::asSequence)
                    .firstOrNull { it.chatId == update.story.posterChatId }
                applyState(
                    StoryRepositoryStateReducer.withStory(
                        state.value,
                        StoryMapper.mapStory(
                            update.story,
                            active,
                            mapStoryMediaBestEffort(update.story.content)
                        )
                    )
                )
            }

            is TdApi.UpdateStoryDeleted -> {
                applyState(
                    StoryRepositoryStateReducer.withStoryDeleted(
                        state.value,
                        update.storyPosterChatId,
                        update.storyId
                    )
                )
            }

            is TdApi.UpdateStoryPostSucceeded -> {
                val active = state.value.activeStories.values
                    .asSequence()
                    .flatMap(List<ActiveStoryListModel>::asSequence)
                    .firstOrNull { it.chatId == update.story.posterChatId }
                applyState(
                    StoryRepositoryStateReducer.withPostSucceeded(
                        state.value,
                        StoryMapper.mapStory(
                            update.story,
                            active,
                            mapStoryMediaBestEffort(update.story.content)
                        ),
                        update.oldStoryId
                    )
                )
            }

            is TdApi.UpdateStoryPostFailed -> {
                val active = state.value.activeStories.values
                    .asSequence()
                    .flatMap(List<ActiveStoryListModel>::asSequence)
                    .firstOrNull { it.chatId == update.story.posterChatId }
                applyState(
                    StoryRepositoryStateReducer.withPostFailed(
                        state.value,
                        StoryMapper.mapStory(
                            update.story,
                            active,
                            mapStoryMediaBestEffort(update.story.content)
                        ),
                        update.error.message,
                        StoryMapper.mapPostCapability(update.errorType)
                    )
                )
            }

            is TdApi.UpdateStoryListChatCount -> {
                update.storyList.toDomainStoryListType()?.let { listType ->
                    applyState(
                        StoryRepositoryStateReducer.withStoryListChatCount(
                            state.value,
                            listType,
                            update.chatCount
                        )
                    )
                }
            }

            is TdApi.UpdateStoryStealthMode -> {
                applyState(
                    StoryRepositoryStateReducer.withStealthMode(
                        state.value,
                        StoryMapper.mapStealthMode(update)
                    )
                )
            }
        }
    }

    private fun applyState(newState: StoryRepositoryState) {
        state.value = newState
        _activeStories.value = newState.activeStories
        _storyListChatCounts.value = newState.storyListChatCounts
        _stealthMode.value = newState.stealthMode
        _storyOptions.value = newState.storyOptions
        _lastPostResult.value = newState.lastPostResult
        Log.d(
            TAG,
            "applyState main=${newState.activeStories[StoryListType.MAIN].orEmpty().size} archive=${newState.activeStories[StoryListType.ARCHIVE].orEmpty().size} cache=${newState.storyCache.size}"
        )
    }

    private fun StoryComposerDraftModel.toTdContent(): TdApi.InputStoryContent {
        val inputFile = if (sourcePath.startsWith("http://") || sourcePath.startsWith("https://")) {
            TdApi.InputFileRemote(sourcePath)
        } else {
            TdApi.InputFileLocal(sourcePath)
        }
        return when (mediaType) {
            StoryMediaType.PHOTO -> TdApi.InputStoryContentPhoto(
                inputFile,
                intArrayOf()
            )

            StoryMediaType.VIDEO -> TdApi.InputStoryContentVideo(
                inputFile,
                intArrayOf(),
                0.0,
                0.0,
                false
            )
        }
    }

    private fun String?.toTdAreas(): TdApi.InputStoryAreas? {
        if (this.isNullOrBlank()) return null
        val url = parseWidgetUrl(this) ?: return null
        return TdApi.InputStoryAreas(
            arrayOf(
                TdApi.InputStoryArea(
                    TdApi.StoryAreaPosition(50.0, 82.0, 72.0, 14.0, 0.0, 8.0),
                    TdApi.InputStoryAreaTypeLink(url)
                )
            )
        )
    }

    private fun parseWidgetUrl(widgetLink: String): String? {
        return runCatching {
            val json = JSONObject(widgetLink)
            json.optString("url")
                .ifBlank { json.optString("link") }
                .ifBlank { null }
        }.getOrElse {
            widgetLink.takeIf { raw ->
                raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith(
                    "tg://"
                )
            }
        }
    }

    private fun StoryReactionModel.toTdReactionType(): TdApi.ReactionType {
        return when {
            emoji != null -> TdApi.ReactionTypeEmoji(emoji)
            customEmojiId != null -> TdApi.ReactionTypeCustomEmoji(customEmojiId ?: 0L)
            isPaid -> TdApi.ReactionTypePaid()
            else -> TdApi.ReactionTypeEmoji("❤")
        }
    }

    private suspend fun getIntegerOption(name: String): Int {
        return (settingsRemoteDataSource.getOption(name) as? TdApi.OptionValueInteger)?.value
            ?.toInt()
            ?: 0
    }

    private suspend fun resolveStoryMedia(content: TdApi.StoryContent): StoryMediaModel {
        return when (content) {
            is TdApi.StoryContentPhoto -> {
                val sortedSizes = content.photo.sizes.orEmpty()
                    .sortedByDescending { it.width * it.height }
                val bestSize = sortedSizes.firstOrNull()
                val previewSize = sortedSizes.lastOrNull() ?: bestSize
                logPhotoCandidates(sortedSizes)
                val previewPath =
                    resolveFilePath(previewSize?.photo, priority = 12, synchronous = true)
                val bestPath = resolveFilePath(bestSize?.photo, priority = 28, synchronous = false)
                StoryMediaModel(
                    type = StoryMediaType.PHOTO,
                    path = bestPath,
                    previewPath = previewPath ?: bestPath,
                    minithumbnail = content.photo.minithumbnail?.data?.takeIf { it.isNotEmpty() }
                )
            }

            is TdApi.StoryContentVideo -> {
                Log.d(
                    TAG,
                    "resolveStoryMedia video fileId=${content.video.video.id} thumbId=${content.video.thumbnail?.file?.id} localPath=${content.video.video.local.path}"
                )
                val previewPath = resolveFilePath(
                    content.video.thumbnail?.file,
                    priority = 12,
                    synchronous = true
                )
                val videoPath =
                    resolveFilePath(content.video.video, priority = 20, synchronous = false)
                StoryMediaModel(
                    type = StoryMediaType.VIDEO,
                    path = videoPath,
                    previewPath = previewPath,
                    minithumbnail = content.video.minithumbnail?.data?.takeIf { it.isNotEmpty() },
                    durationSeconds = content.video.duration,
                    isAnimation = content.video.isAnimation
                )
            }

            else -> StoryMediaModel(
                type = StoryMediaType.PHOTO,
                path = null,
                previewPath = null,
                minithumbnail = null
            )
        }
    }

    private fun mapStoryMediaBestEffort(content: TdApi.StoryContent): StoryMediaModel {
        return when (content) {
            is TdApi.StoryContentPhoto -> {
                val bestSize = content.photo.sizes?.maxByOrNull { it.width * it.height }
                val previewSize = content.photo.sizes?.firstOrNull()
                StoryMediaModel(
                    type = StoryMediaType.PHOTO,
                    path = bestSize?.photo?.local?.path?.ifBlank { null },
                    previewPath = previewSize?.photo?.local?.path?.ifBlank { null },
                    minithumbnail = content.photo.minithumbnail?.data?.takeIf { it.isNotEmpty() }
                )
            }

            is TdApi.StoryContentVideo -> StoryMediaModel(
                type = StoryMediaType.VIDEO,
                path = content.video.video.local.path.ifBlank { null },
                previewPath = content.video.thumbnail?.file?.local?.path?.ifBlank { null },
                minithumbnail = content.video.minithumbnail?.data?.takeIf { it.isNotEmpty() },
                durationSeconds = content.video.duration,
                isAnimation = content.video.isAnimation
            )

            else -> StoryMediaModel(
                type = StoryMediaType.PHOTO,
                path = null,
                previewPath = null,
                minithumbnail = null
            )
        }
    }

    private suspend fun resolveFilePath(
        file: TdApi.File?,
        priority: Int,
        synchronous: Boolean
    ): String? {
        if (file == null) return null
        file.local?.path?.takeIf { it.isNotBlank() }?.let { return it }
        if (file.id == 0) return null

        val initial = runCatching {
            fileDataSource.downloadFile(
                fileId = file.id,
                priority = priority,
                offset = 0,
                limit = 0,
                synchronous = synchronous
            )
        }.onFailure {
            Log.d(
                TAG,
                "resolveFilePath failed fileId=${file.id} sync=$synchronous message=${it.message}"
            )
        }.getOrNull()

        val initialPath = initial?.local?.path?.takeIf { it.isNotBlank() }
        if (initialPath != null) {
            Log.d(
                TAG,
                "resolveFilePath immediate fileId=${file.id} sync=$synchronous path=$initialPath"
            )
            return initialPath
        }

        repeat(if (synchronous) 3 else 6) { attempt ->
            delay(if (synchronous) 120L else 250L * (attempt + 1))
            val refreshed = runCatching { fileDataSource.getFile(file.id) }
                .onFailure {
                    Log.d(
                        TAG,
                        "resolveFilePath getFile failed fileId=${file.id} attempt=${attempt + 1} message=${it.message}"
                    )
                }
                .getOrNull()
            val refreshedPath = refreshed?.local?.path?.takeIf { it.isNotBlank() }
            Log.d(
                TAG,
                "resolveFilePath poll fileId=${file.id} attempt=${attempt + 1} sync=$synchronous path=$refreshedPath completed=${refreshed?.local?.isDownloadingCompleted} active=${refreshed?.local?.isDownloadingActive} downloaded=${refreshed?.local?.downloadedSize}"
            )
            if (refreshedPath != null) {
                return refreshedPath
            }
        }

        Log.d(
            TAG,
            "resolveFilePath unresolved fileId=${file.id} sync=$synchronous completed=${initial?.local?.isDownloadingCompleted} active=${initial?.local?.isDownloadingActive} canDownload=${initial?.local?.canBeDownloaded} downloaded=${initial?.local?.downloadedSize} path=${initial?.local?.path}"
        )
        return null
    }

    private fun logPhotoCandidates(sizes: List<TdApi.PhotoSize>) {
        if (sizes.isEmpty()) {
            Log.d(TAG, "resolveStoryMedia photo has no sizes")
            return
        }
        val summary = sizes.joinToString { size ->
            val local = size.photo.local
            "${size.type}:${size.width}x${size.height}:file=${size.photo.id}:path=${local.path}:done=${local.isDownloadingCompleted}:active=${local.isDownloadingActive}"
        }
        Log.d(TAG, "resolveStoryMedia photo sizes $summary")
    }

    private fun StoryModel.hasRenderableMedia(): Boolean {
        return !media.path.isNullOrBlank() ||
                !media.previewPath.isNullOrBlank() ||
                media.minithumbnail?.isNotEmpty() == true
    }

    companion object {
        private const val TAG = "StoryRepository"
    }
}
