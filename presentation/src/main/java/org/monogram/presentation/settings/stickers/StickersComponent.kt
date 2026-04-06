package org.monogram.presentation.settings.stickers

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.EmojiRepository
import org.monogram.domain.repository.StickerRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface StickersComponent {
    val state: Value<State>

    fun onBackClicked()
    fun onStickerSetClicked(stickerSet: StickerSetModel)
    fun onToggleStickerSet(stickerSet: StickerSetModel)
    fun onArchiveStickerSet(stickerSet: StickerSetModel)
    fun onClearRecentStickers()
    fun onClearRecentEmojis()
    fun onTabSelected(index: Int)
    fun onSearchQueryChanged(query: String)
    fun onMoveStickerSet(fromIndex: Int, toIndex: Int)
    fun onAddStickersClicked()
    fun onDismissMiniApp()

    data class State(
        val stickerSets: List<StickerSetModel> = emptyList(),
        val emojiSets: List<StickerSetModel> = emptyList(),
        val archivedStickerSets: List<StickerSetModel> = emptyList(),
        val archivedEmojiSets: List<StickerSetModel> = emptyList(),
        val isLoading: Boolean = true,
        val selectedTabIndex: Int = 0,
        val searchQuery: String = "",
        val miniAppUrl: String? = null,
        val miniAppName: String? = null,
        val miniAppBotUserId: Long = 0L
    )
}

class DefaultStickersComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onStickerSetSelect: (StickerSetModel) -> Unit
) : StickersComponent, AppComponentContext by context {

    private val stickerRepository: StickerRepository = container.repositories.stickerRepository
    private val emojiRepository: EmojiRepository = container.repositories.emojiRepository
    private val _state = MutableValue(
        StickersComponent.State(
            stickerSets = stickerRepository.installedStickerSets.value,
            emojiSets = stickerRepository.customEmojiStickerSets.value,
            archivedStickerSets = stickerRepository.archivedStickerSets.value,
            archivedEmojiSets = stickerRepository.archivedEmojiSets.value,
            isLoading = stickerRepository.installedStickerSets.value.isEmpty()
        )
    )
    override val state: Value<StickersComponent.State> = _state
    private val scope = componentScope

    init {
        scope.launch {
            stickerRepository.loadInstalledStickerSets()
            stickerRepository.loadCustomEmojiStickerSets()
            stickerRepository.loadArchivedStickerSets()
            stickerRepository.loadArchivedEmojiSets()
        }

        scope.launch {
            stickerRepository.installedStickerSets.collectLatest { sets ->
                _state.update {
                    it.copy(
                        stickerSets = sets,
                        isLoading = false
                    )
                }
            }
        }

        scope.launch {
            stickerRepository.customEmojiStickerSets.collectLatest { sets ->
                _state.update {
                    it.copy(
                        emojiSets = sets
                    )
                }
            }
        }

        scope.launch {
            stickerRepository.archivedStickerSets.collectLatest { sets ->
                _state.update {
                    it.copy(
                        archivedStickerSets = sets
                    )
                }
            }
        }

        scope.launch {
            stickerRepository.archivedEmojiSets.collectLatest { sets ->
                _state.update {
                    it.copy(
                        archivedEmojiSets = sets
                    )
                }
            }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onStickerSetClicked(stickerSet: StickerSetModel) {
        onStickerSetSelect(stickerSet)
    }

    override fun onToggleStickerSet(stickerSet: StickerSetModel) {
        scope.launch {
            stickerRepository.toggleStickerSetInstalled(stickerSet.id, !stickerSet.isInstalled)
        }
    }

    override fun onArchiveStickerSet(stickerSet: StickerSetModel) {
        scope.launch {
            stickerRepository.toggleStickerSetArchived(stickerSet.id, !stickerSet.isArchived)
        }
    }

    override fun onClearRecentStickers() {
        scope.launch {
            stickerRepository.clearRecentStickers()
        }
    }

    override fun onClearRecentEmojis() {
        scope.launch {
            emojiRepository.clearRecentEmojis()
        }
    }

    override fun onTabSelected(index: Int) {
        _state.update { it.copy(selectedTabIndex = index) }
    }

    override fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    override fun onMoveStickerSet(fromIndex: Int, toIndex: Int) {
        val currentTabIndex = _state.value.selectedTabIndex
        val currentList = if (currentTabIndex == 0) _state.value.stickerSets else _state.value.emojiSets

        if (fromIndex !in currentList.indices || toIndex !in currentList.indices) return

        val newList = currentList.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }

        if (currentTabIndex == 0) {
            _state.update { it.copy(stickerSets = newList) }
        } else {
            _state.update { it.copy(emojiSets = newList) }
        }

        scope.launch {
            val type = if (currentTabIndex == 0) {
                StickerRepository.TdLibStickerType.REGULAR
            } else {
                StickerRepository.TdLibStickerType.CUSTOM_EMOJI
            }
            stickerRepository.reorderStickerSets(type, newList.map { it.id })
        }
    }

    override fun onAddStickersClicked() {
        _state.update {
            it.copy(
                miniAppUrl = "menu://https://webappinternal.telegram.org/stickers",
                miniAppName = "Stickers",
                miniAppBotUserId = 429000L
            )
        }
    }

    override fun onDismissMiniApp() {
        _state.update {
            it.copy(
                miniAppUrl = null,
                miniAppName = null,
                miniAppBotUserId = 0L
            )
        }
    }
}
