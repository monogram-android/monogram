package org.monogram.presentation.settings.privacy.userSelection

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.root.AppComponentContext

class DefaultUserSelectionComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onUserSelected: (Long) -> Unit
) : UserSelectionComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    override val videoPlayerPool: VideoPlayerPool = container.utils.videoPlayerPool

    private val _state = MutableValue(UserSelectionComponent.State())
    override val state: Value<UserSelectionComponent.State> = _state
    private val scope = componentScope
    private var searchJob: Job? = null

    override fun onBackClicked() {
        onBack()
    }

    override fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()

        if (query.isBlank()) {
            _state.update { it.copy(users = emptyList()) }
            return
        }

        searchJob = scope.launch {
            delay(500) // Debounce
            _state.update { it.copy(isLoading = true) }
            try {
                val userId = query.toLongOrNull()
                if (userId != null) {
                    val user = userRepository.getUser(userId)
                    if (user != null) {
                        _state.update { it.copy(users = listOf(user)) }
                    } else {
                        _state.update { it.copy(users = emptyList()) }
                    }
                } else {
                    // TODO: Implement proper search in UserRepository
                    _state.update { it.copy(users = emptyList()) }
                }
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
