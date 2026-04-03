package org.monogram.presentation.settings.privacy

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface BlockedUsersComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onUnblockUserClicked(userId: Long)
    fun onAddBlockedUserClicked()
    fun onUserClicked(userId: Long)

    data class State(
        val isLoading: Boolean = false,
        val blockedUsers: List<UserModel> = emptyList()
    )
}

class DefaultBlockedUsersComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit,
    private val onProfileClick: (Long) -> Unit,
    private val onAddBlockedUser: () -> Unit
) : BlockedUsersComponent, AppComponentContext by context {

    private val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    private val userRepository: UserRepository = container.repositories.userRepository

    private val _state = MutableValue(BlockedUsersComponent.State())
    override val state: Value<BlockedUsersComponent.State> = _state
    private val scope = componentScope

    init {
        loadBlockedUsers()
    }

    private fun loadBlockedUsers() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val blockedIds = privacyRepository.getBlockedUsers()
                val users = blockedIds.mapNotNull { id ->
                    try {
                        userRepository.getUser(id)
                    } catch (e: Exception) {
                        null
                    }
                }
                _state.update { it.copy(blockedUsers = users) }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onUnblockUserClicked(userId: Long) {
        scope.launch {
            try {
                privacyRepository.unblockUser(userId)
                loadBlockedUsers()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    override fun onAddBlockedUserClicked() {
        onAddBlockedUser()
    }

    override fun onUserClicked(userId: Long) {
        onProfileClick(userId)
    }
}