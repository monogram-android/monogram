package org.monogram.presentation.features.chats.chatList

import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChatsListRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.newChat.NewChatComponent
import org.monogram.presentation.features.chats.newChat.NewChatStore
import org.monogram.presentation.features.chats.newChat.NewChatStoreFactory
import org.monogram.presentation.root.AppComponentContext

class DefaultNewChatComponent(
    context: AppComponentContext,
    private val onBackClicked: () -> Unit,
    private val onChatCreated: (Long) -> Unit
) : NewChatComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    private val chatsListRepository: ChatsListRepository = container.repositories.chatsListRepository
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool

    private val scope = componentScope
    private val _state = MutableStateFlow(NewChatComponent.State())

    private val store = instanceKeeper.getStore {
        NewChatStoreFactory(
            storeFactory = DefaultStoreFactory(),
            component = this
        ).create()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<NewChatComponent.State> = store.stateFlow

    private var searchJob: Job? = null

    init {
        loadContacts()

        _state.onEach {
            store.accept(NewChatStore.Intent.UpdateState(it))
        }.launchIn(scope)
    }

    private fun loadContacts() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            val contacts = userRepository.getContacts()
            _state.update { it.copy(contacts = contacts, isLoading = false) }
        }
    }

    override fun onBack() = store.accept(NewChatStore.Intent.Back)

    override fun onUserClicked(userId: Long) = store.accept(NewChatStore.Intent.UserClicked(userId))

    override fun onSearchQueryChange(query: String) = store.accept(NewChatStore.Intent.SearchQueryChange(query))

    override fun onCreateGroup() = store.accept(NewChatStore.Intent.CreateGroup)

    override fun onCreateChannel() = store.accept(NewChatStore.Intent.CreateChannel)

    override fun onTitleChange(title: String) = store.accept(NewChatStore.Intent.TitleChange(title))

    override fun onDescriptionChange(description: String) =
        store.accept(NewChatStore.Intent.DescriptionChange(description))

    override fun onPhotoSelected(path: String?) = store.accept(NewChatStore.Intent.PhotoSelected(path))

    override fun onToggleUserSelection(userId: Long) = store.accept(NewChatStore.Intent.ToggleUserSelection(userId))

    override fun onAutoDeleteTimeChange(seconds: Int) = store.accept(NewChatStore.Intent.AutoDeleteTimeChange(seconds))

    override fun onConfirmCreate() = store.accept(NewChatStore.Intent.ConfirmCreate)

    override fun onStepBack() = store.accept(NewChatStore.Intent.StepBack)

    // Internal logic called by Store Executor
    internal fun handleBack() {
        onBackClicked()
    }

    internal fun handleUserClicked(userId: Long) {
        if (_state.value.step == NewChatComponent.Step.CONTACTS) {
            onChatCreated(userId)
        }
    }

    internal fun handleSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            if (query.isNotEmpty()) {
                val results = userRepository.searchContacts(query)
                _state.update { it.copy(searchResults = results) }
            } else {
                _state.update { it.copy(searchResults = emptyList()) }
            }
        }
    }

    internal fun handleCreateGroup() {
        _state.update { it.copy(step = NewChatComponent.Step.GROUP_INFO) }
    }

    internal fun handleCreateChannel() {
        _state.update { it.copy(step = NewChatComponent.Step.CHANNEL_INFO) }
    }

    internal fun handleTitleChange(title: String) {
        _state.update { it.copy(title = title) }
    }

    internal fun handleDescriptionChange(description: String) {
        _state.update { it.copy(description = description) }
    }

    internal fun handlePhotoSelected(path: String?) {
        _state.update { it.copy(photoPath = path) }
    }

    internal fun handleToggleUserSelection(userId: Long) {
        _state.update {
            val newSelected = if (it.selectedUserIds.contains(userId)) {
                it.selectedUserIds - userId
            } else {
                it.selectedUserIds + userId
            }
            it.copy(selectedUserIds = newSelected)
        }
    }

    internal fun handleAutoDeleteTimeChange(seconds: Int) {
        _state.update { it.copy(autoDeleteTime = seconds) }
    }

    internal fun handleConfirmCreate() {
        val currentState = _state.value
        when (currentState.step) {
            NewChatComponent.Step.GROUP_MEMBERS -> {
                if (currentState.selectedUserIds.isNotEmpty()) {
                    _state.update { it.copy(step = NewChatComponent.Step.GROUP_INFO) }
                }
            }

            NewChatComponent.Step.GROUP_INFO -> {
                if (currentState.title.isNotBlank()) {
                    scope.launch {
                        val chatId = chatsListRepository.createGroup(
                            currentState.title,
                            currentState.selectedUserIds.toList(),
                            currentState.autoDeleteTime
                        )
                        if (chatId != 0L) {
                            if (currentState.photoPath != null) {
                                chatsListRepository.setChatPhoto(chatId, currentState.photoPath)
                            }
                            onChatCreated(chatId)
                        }
                    }
                }
            }

            NewChatComponent.Step.CHANNEL_INFO -> {
                if (currentState.title.isNotBlank()) {
                    scope.launch {
                        val chatId = chatsListRepository.createChannel(
                            currentState.title,
                            currentState.description,
                            messageAutoDeleteTime = currentState.autoDeleteTime
                        )
                        if (chatId != 0L) {
                            if (currentState.photoPath != null) {
                                chatsListRepository.setChatPhoto(chatId, currentState.photoPath)
                            }
                            onChatCreated(chatId)
                        }
                    }
                }
            }

            else -> {}
        }
    }

    internal fun handleStepBack() {
        _state.update {
            when (it.step) {
                NewChatComponent.Step.GROUP_MEMBERS -> it.copy(
                    step = NewChatComponent.Step.CONTACTS,
                    selectedUserIds = emptySet()
                )

                NewChatComponent.Step.GROUP_INFO -> it.copy(step = NewChatComponent.Step.CONTACTS)
                NewChatComponent.Step.CHANNEL_INFO -> it.copy(step = NewChatComponent.Step.CONTACTS)
                else -> it
            }
        }
    }
}
