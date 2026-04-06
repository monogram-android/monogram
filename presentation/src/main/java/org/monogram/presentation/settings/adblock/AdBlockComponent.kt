package org.monogram.presentation.settings.adblock

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.managers.AssetsManager
import org.monogram.domain.managers.ClipManager
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.ChatListRepository
import org.monogram.presentation.core.util.AppPreferences
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface AdBlockComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onAdBlockEnabledChanged(enabled: Boolean)
    fun onAddKeywords(keywords: String)
    fun onRemoveKeyword(keyword: String)
    fun onClearKeywords()
    fun onCopyKeywords()
    fun onRemoveWhitelistedChannel(channelId: Long)
    fun onClearWhitelistedChannels()
    fun onLoadFromAssets()
    fun onWhitelistedChannelsClicked()
    fun onAddKeywordClicked()
    fun onDismissBottomSheet()

    data class State(
        val isEnabled: Boolean = false,
        val keywords: Set<String> = emptySet(),
        val whitelistedChannels: Set<Long> = emptySet(),
        val isLoading: Boolean = false,
        val showWhitelistedSheet: Boolean = false,
        val showAddKeywordSheet: Boolean = false,
        val whitelistedChannelModels: List<ChatModel> = emptyList()
    )
}

class DefaultAdBlockComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : AdBlockComponent, AppComponentContext by context {

    private val appPreferences: AppPreferences = container.preferences.appPreferences
    private val chatsRepository: ChatListRepository = container.repositories.chatListRepository
    private val clipManager: ClipManager = container.utils.clipManager
    private val assetsManager: AssetsManager = container.utils.assetsManager()

    private val _state = MutableValue(
        AdBlockComponent.State(
            isEnabled = appPreferences.isAdBlockEnabled.value,
            keywords = appPreferences.adBlockKeywords.value,
            whitelistedChannels = appPreferences.adBlockWhitelistedChannels.value
        )
    )
    override val state: Value<AdBlockComponent.State> = _state
    private val scope = componentScope

    init {
        appPreferences.isAdBlockEnabled
            .onEach { enabled ->
                _state.update { it.copy(isEnabled = enabled) }
            }
            .launchIn(scope)

        appPreferences.adBlockKeywords
            .onEach { keywords ->
                _state.update { it.copy(keywords = keywords) }
            }
            .launchIn(scope)

        appPreferences.adBlockWhitelistedChannels
            .onEach { channels ->
                _state.update { it.copy(whitelistedChannels = channels) }
                if (_state.value.showWhitelistedSheet) {
                    loadWhitelistedModels(channels)
                }
            }
            .launchIn(scope)
    }

    private fun loadWhitelistedModels(channels: Set<Long>) {
        scope.launch {
            val models = channels.mapNotNull { chatsRepository.getChatById(it) }
            _state.update { it.copy(whitelistedChannelModels = models) }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onAdBlockEnabledChanged(enabled: Boolean) {
        appPreferences.setAdBlockEnabled(enabled)
    }

    override fun onAddKeywords(keywords: String) {
        if (keywords.isBlank()) return
        val newKeywords = keywords.split(Regex("[,\\n]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val current = appPreferences.adBlockKeywords.value.toMutableSet()
        if (current.addAll(newKeywords)) {
            appPreferences.setAdBlockKeywords(current)
        }
        onDismissBottomSheet()
    }

    override fun onRemoveKeyword(keyword: String) {
        val current = appPreferences.adBlockKeywords.value.toMutableSet()
        if (current.remove(keyword)) {
            appPreferences.setAdBlockKeywords(current)
        }
    }

    override fun onClearKeywords() {
        appPreferences.setAdBlockKeywords(emptySet())
    }

    override fun onCopyKeywords() {
        val text = _state.value.keywords.joinToString("\n")
        clipManager.copyToClipboard("AdBlock Keywords", text)
    }

    override fun onRemoveWhitelistedChannel(channelId: Long) {
        val current = appPreferences.adBlockWhitelistedChannels.value.toMutableSet()
        if (current.remove(channelId)) {
            appPreferences.setAdBlockWhitelistedChannels(current)
        }
    }

    override fun onClearWhitelistedChannels() {
        appPreferences.setAdBlockWhitelistedChannels(emptySet())
    }

    override fun onLoadFromAssets() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            val keywords = withContext(Dispatchers.IO) {
                try {
                    assetsManager.getAssets("adblock_keywords.txt").bufferedReader().useLines { lines ->
                        lines.map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    }
                } catch (e: Exception) {
                    emptySet()
                }
            }
            val current = appPreferences.adBlockKeywords.value.toMutableSet()
            current.addAll(keywords)
            appPreferences.setAdBlockKeywords(current)
            _state.update { it.copy(isLoading = false) }
        }
    }

    override fun onWhitelistedChannelsClicked() {
        _state.update { it.copy(showWhitelistedSheet = true) }
        loadWhitelistedModels(_state.value.whitelistedChannels)
    }

    override fun onAddKeywordClicked() {
        _state.update { it.copy(showAddKeywordSheet = true) }
    }

    override fun onDismissBottomSheet() {
        _state.update {
            it.copy(
                showWhitelistedSheet = false,
                showAddKeywordSheet = false,
                whitelistedChannelModels = emptyList()
            )
        }
    }
}