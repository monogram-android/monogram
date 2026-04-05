package org.monogram.presentation.settings.privacy

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.PrivacyValue
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.PrivacyKey
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.R
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

interface PrivacySettingComponent {
    val state: Value<State>
    fun onBackClicked()
    fun onPrivacyValueChanged(value: PrivacyValue)
    fun onSearchPrivacyValueChanged(value: PrivacyValue)
    fun onAddExceptionClicked(isAllow: Boolean)
    fun onUserClicked(userId: Long)
    fun onRemoveUser(userId: Long, isAllow: Boolean)
    fun onRemoveChat(chatId: Long, isAllow: Boolean)

    data class State(
        val titleRes: Int,
        val privacyKey: PrivacyKey,
        val selectedValue: PrivacyValue = PrivacyValue.EVERYBODY,
        val searchSelectedValue: PrivacyValue = PrivacyValue.EVERYBODY,
        val allowUsers: List<UserModel> = emptyList(),
        val disallowUsers: List<UserModel> = emptyList(),
        val allowChats: List<ChatModel> = emptyList(),
        val disallowChats: List<ChatModel> = emptyList(),
        val isLoading: Boolean = false
    )
}

class DefaultPrivacySettingComponent(
    context: AppComponentContext,
    private val privacyKey: PrivacyKey,
    private val onBack: () -> Unit,
    private val onProfileClick: (Long) -> Unit,
    private val onUserSelect: (Boolean) -> Unit
) : PrivacySettingComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    private val privacyRepository: PrivacyRepository = container.repositories.privacyRepository
    private val chatsRepository: ChatListRepository = container.repositories.chatListRepository

    private val _state =
        MutableValue(PrivacySettingComponent.State(titleRes = getTitleRes(privacyKey), privacyKey = privacyKey))
    override val state: Value<PrivacySettingComponent.State> = _state
    private val scope = componentScope

    init {
        observePrivacyRules()
        if (privacyKey == PrivacyKey.PHONE_NUMBER) {
            observeSearchPrivacyRules()
        }
    }

    private fun observePrivacyRules() {
        privacyRepository.getPrivacyRules(privacyKey)
            .onEach { rules ->
                val config = rules.toPrivacyRuleConfig()

                val allowUsers = config.allowUsers.mapNotNull { id ->
                    try {
                        userRepository.getUser(id)
                    } catch (e: Exception) {
                        null
                    }
                }

                val disallowUsers = config.disallowUsers.mapNotNull { id ->
                    try {
                        userRepository.getUser(id)
                    } catch (e: Exception) {
                        null
                    }
                }

                val allowChats = config.allowChats.mapNotNull { id ->
                    try {
                        chatsRepository.getChatById(id)
                    } catch (e: Exception) {
                        null
                    }
                }

                val disallowChats = config.disallowChats.mapNotNull { id ->
                    try {
                        chatsRepository.getChatById(id)
                    } catch (e: Exception) {
                        null
                    }
                }

                _state.update {
                    it.copy(
                        selectedValue = config.baseValue,
                        allowUsers = allowUsers,
                        disallowUsers = disallowUsers,
                        allowChats = allowChats,
                        disallowChats = disallowChats
                    )
                }
            }
            .launchIn(scope)
    }

    private fun observeSearchPrivacyRules() {
        privacyRepository.getPrivacyRules(PrivacyKey.PHONE_NUMBER_SEARCH)
            .onEach { rules ->
                _state.update { it.copy(searchSelectedValue = rules.toPrivacyValue()) }
            }
            .launchIn(scope)
    }

    override fun onBackClicked() {
        onBack()
    }

    override fun onPrivacyValueChanged(value: PrivacyValue) {
        scope.launch {
            val currentAllowUsers = _state.value.allowUsers.map { it.id }
            val currentDisallowUsers = _state.value.disallowUsers.map { it.id }
            val currentAllowChats = _state.value.allowChats.map { it.id }
            val currentDisallowChats = _state.value.disallowChats.map { it.id }
            updateRules(
                privacyKey,
                currentAllowUsers,
                currentDisallowUsers,
                currentAllowChats,
                currentDisallowChats,
                value
            )
        }
    }

    override fun onSearchPrivacyValueChanged(value: PrivacyValue) {
        scope.launch {
            updateRules(PrivacyKey.PHONE_NUMBER_SEARCH, emptyList(), emptyList(), emptyList(), emptyList(), value)
        }
    }

    override fun onAddExceptionClicked(isAllow: Boolean) {
        onUserSelect(isAllow)
    }

    override fun onUserClicked(userId: Long) {
        onProfileClick(userId)
    }

    override fun onRemoveUser(userId: Long, isAllow: Boolean) {
        scope.launch {
            val currentAllowUsers = _state.value.allowUsers.map { it.id }.toMutableList()
            val currentDisallowUsers = _state.value.disallowUsers.map { it.id }.toMutableList()
            val currentAllowChats = _state.value.allowChats.map { it.id }
            val currentDisallowChats = _state.value.disallowChats.map { it.id }

            if (isAllow) {
                currentAllowUsers.remove(userId)
            } else {
                currentDisallowUsers.remove(userId)
            }

            updateRules(
                privacyKey,
                currentAllowUsers,
                currentDisallowUsers,
                currentAllowChats,
                currentDisallowChats,
                _state.value.selectedValue
            )
        }
    }

    override fun onRemoveChat(chatId: Long, isAllow: Boolean) {
        scope.launch {
            val currentAllowUsers = _state.value.allowUsers.map { it.id }
            val currentDisallowUsers = _state.value.disallowUsers.map { it.id }
            val currentAllowChats = _state.value.allowChats.map { it.id }.toMutableList()
            val currentDisallowChats = _state.value.disallowChats.map { it.id }.toMutableList()

            if (isAllow) {
                currentAllowChats.remove(chatId)
            } else {
                currentDisallowChats.remove(chatId)
            }

            updateRules(
                privacyKey,
                currentAllowUsers,
                currentDisallowUsers,
                currentAllowChats,
                currentDisallowChats,
                _state.value.selectedValue
            )
        }
    }

    private suspend fun updateRules(
        key: PrivacyKey,
        allowUsers: List<Long>,
        disallowUsers: List<Long>,
        allowChats: List<Long>,
        disallowChats: List<Long>,
        value: PrivacyValue
    ) {
        privacyRepository.setPrivacyRule(
            key,
            buildPrivacyRules(key, value, allowUsers, disallowUsers, allowChats, disallowChats)
        )
    }

    private fun getTitleRes(key: PrivacyKey): Int {
        return when (key) {
            PrivacyKey.PHONE_NUMBER -> R.string.phone_number_title
            PrivacyKey.PHONE_NUMBER_SEARCH -> R.string.privacy_phone_number_search_title
            PrivacyKey.LAST_SEEN -> R.string.last_seen_title
            PrivacyKey.PROFILE_PHOTO -> R.string.profile_photos_title
            PrivacyKey.BIO -> R.string.bio_label
            PrivacyKey.FORWARDED_MESSAGES -> R.string.forwarded_messages_title
            PrivacyKey.CALLS -> R.string.calls_title
            PrivacyKey.GROUPS_AND_CHANNELS -> R.string.groups_channels_title
        }
    }
}
