package org.monogram.presentation.settings.privacy.userSelection

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultUserSelectionComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onUserSelected: (Long) -> Unit
) : UserSelectionComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository

    private val _state = MutableValue(UserSelectionComponent.State())
    override val state: Value<UserSelectionComponent.State> = _state
    private val scope = componentScope
    private var searchJob: Job? = null

    init {
        loadContacts()
    }

    private fun loadContacts() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val contacts = userRepository.getContacts()
                _state.update { it.copy(users = contacts) }
            } catch (_: Exception) {
                _state.update { it.copy(users = emptyList()) }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()

        if (query.isBlank()) {
            loadContacts()
            return
        }

        searchJob = scope.launch {
            delay(500) // Debounce
            _state.update { it.copy(isLoading = true) }
            try {
                val contactsDeferred = async { userRepository.searchContacts(query) }
                val directUserDeferred = async {
                    query.toLongOrNull()?.let { userRepository.getUser(it) }
                }

                val results = LinkedHashMap<Long, org.monogram.domain.models.UserModel>()
                contactsDeferred.await().forEach { user ->
                    results[user.id] = user
                }
                directUserDeferred.await()?.let { user ->
                    results[user.id] = user
                }

                _state.update { it.copy(users = results.values.toList()) }
            } catch (e: Exception) {
                _state.update { it.copy(users = emptyList()) }
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onUserClicked(userId: Long) {
        onUserSelected(userId)
    }
}
