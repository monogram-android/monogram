package org.monogram.presentation.features.chats.newChat

import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChatCreationRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultNewChatComponent(
    context: AppComponentContext,
    private val onBackClicked: () -> Unit,
    private val onChatCreated: (Long) -> Unit,
    private val onProfileClicked: (Long) -> Unit
) : NewChatComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    private val chatCreationRepository: ChatCreationRepository = container.repositories.chatCreationRepository
    private val chatSettingsRepository: ChatSettingsRepository = container.repositories.chatSettingsRepository

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

    override fun onOpenProfile(userId: Long) = store.accept(NewChatStore.Intent.OpenProfile(userId))

    override fun onEditContact(userId: Long, firstName: String, lastName: String) =
        store.accept(NewChatStore.Intent.EditContact(userId, firstName, lastName))

    override fun onRemoveContact(userId: Long) = store.accept(NewChatStore.Intent.RemoveContact(userId))

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

    override fun onConsumeValidationError() = store.accept(NewChatStore.Intent.ConsumeValidationError)

    // Internal logic called by Store Executor
    internal fun handleBack() {
        onBackClicked()
    }

    internal fun handleUserClicked(userId: Long) {
        if (_state.value.step == NewChatComponent.Step.CONTACTS) {
            onChatCreated(userId)
        }
    }

    internal fun handleOpenProfile(userId: Long) {
        if (_state.value.step == NewChatComponent.Step.CONTACTS) {
            onProfileClicked(userId)
        }
    }

    internal fun handleEditContact(userId: Long, firstName: String, lastName: String) {
        if (_state.value.step != NewChatComponent.Step.CONTACTS || firstName.isBlank()) return

        val currentContact = _state.value.contacts.firstOrNull { it.id == userId } ?: return
        val normalizedLastName = lastName.trim().ifBlank { null }
        val trimmedFirstName = firstName.trim()

        scope.launch {
            runCatching {
                userRepository.addContact(
                    currentContact.copy(
                        firstName = trimmedFirstName,
                        lastName = normalizedLastName,
                        isContact = true
                    )
                )
            }.onSuccess {
                _state.update { current ->
                    current.copy(
                        contacts = current.contacts.map { user ->
                            if (user.id == userId) {
                                user.copy(firstName = trimmedFirstName, lastName = normalizedLastName)
                            } else {
                                user
                            }
                        },
                        searchResults = current.searchResults.map { user ->
                            if (user.id == userId) {
                                user.copy(firstName = trimmedFirstName, lastName = normalizedLastName)
                            } else {
                                user
                            }
                        }
                    )
                }
                loadContacts()
            }
        }
    }

    internal fun handleRemoveContact(userId: Long) {
        if (_state.value.step != NewChatComponent.Step.CONTACTS) return

        scope.launch {
            userRepository.removeContact(userId)
            loadContacts()
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
        _state.update {
            it.copy(
                step = NewChatComponent.Step.GROUP_MEMBERS,
                selectedUserIds = emptySet(),
                searchQuery = "",
                searchResults = emptyList(),
                validationError = null
            )
        }
    }

    internal fun handleCreateChannel() {
        _state.update {
            it.copy(
                step = NewChatComponent.Step.CHANNEL_INFO,
                selectedUserIds = emptySet(),
                validationError = null
            )
        }
    }

    internal fun handleTitleChange(title: String) {
        _state.update { it.copy(title = title, validationError = null) }
    }

    internal fun handleDescriptionChange(description: String) {
        _state.update { it.copy(description = description, validationError = null) }
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
        if (currentState.isCreating) return

        when (currentState.step) {
            NewChatComponent.Step.GROUP_MEMBERS -> {
                if (currentState.selectedUserIds.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            step = NewChatComponent.Step.GROUP_INFO,
                            searchQuery = "",
                            searchResults = emptyList(),
                            validationError = null
                        )
                    }
                }
            }

            NewChatComponent.Step.GROUP_INFO -> {
                if (currentState.title.isBlank()) {
                    _state.update {
                        it.copy(validationError = NewChatComponent.ValidationError.GROUP_TITLE_REQUIRED)
                    }
                    return
                }

                scope.launch {
                    _state.update { it.copy(isCreating = true, validationError = null) }
                    try {
                        val chatId = chatCreationRepository.createGroup(
                            currentState.title,
                            currentState.selectedUserIds.toList(),
                            currentState.autoDeleteTime
                        )
                        if (chatId != 0L) {
                            if (currentState.photoPath != null) {
                                chatSettingsRepository.setChatPhoto(chatId, currentState.photoPath)
                            }
                            onChatCreated(chatId)
                        }
                    } finally {
                        _state.update { it.copy(isCreating = false) }
                    }
                }
            }

            NewChatComponent.Step.CHANNEL_INFO -> {
                if (currentState.title.isBlank()) {
                    _state.update {
                        it.copy(validationError = NewChatComponent.ValidationError.CHANNEL_TITLE_REQUIRED)
                    }
                    return
                }

                scope.launch {
                    _state.update { it.copy(isCreating = true, validationError = null) }
                    try {
                        val chatId = chatCreationRepository.createChannel(
                            currentState.title,
                            currentState.description,
                            messageAutoDeleteTime = currentState.autoDeleteTime
                        )
                        if (chatId != 0L) {
                            if (currentState.photoPath != null) {
                                chatSettingsRepository.setChatPhoto(chatId, currentState.photoPath)
                            }
                            onChatCreated(chatId)
                        }
                    } finally {
                        _state.update { it.copy(isCreating = false) }
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
                    selectedUserIds = emptySet(),
                    searchQuery = "",
                    searchResults = emptyList(),
                    validationError = null
                )

                NewChatComponent.Step.GROUP_INFO -> it.copy(
                    step = NewChatComponent.Step.GROUP_MEMBERS,
                    validationError = null
                )

                NewChatComponent.Step.CHANNEL_INFO -> it.copy(
                    step = NewChatComponent.Step.CONTACTS,
                    validationError = null
                )
                else -> it
            }
        }
    }

    internal fun handleConsumeValidationError() {
        _state.update { it.copy(validationError = null) }
    }

}
