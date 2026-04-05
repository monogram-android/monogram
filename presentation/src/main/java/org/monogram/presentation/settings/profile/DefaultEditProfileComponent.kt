package org.monogram.presentation.settings.profile

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.*
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultEditProfileComponent(
    context: AppComponentContext,
    private val onBackClicked: () -> Unit
) : EditProfileComponent, AppComponentContext by context {

    private val userRepository: UserRepository = container.repositories.userRepository
    private val userProfileEditRepository: UserProfileEditRepository = container.repositories.userProfileEditRepository
    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository
    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val locationRepository: LocationRepository = container.repositories.locationRepository

    private val _state = MutableValue(EditProfileComponent.State())
    override val state: Value<EditProfileComponent.State> = _state
    private val scope = componentScope

    init {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val me = userRepository.getMe()
                val fullInfo = chatInfoRepository.getChatFullInfo(me.id)
                val linkedChat =
                    fullInfo?.linkedChatId?.let { if (it != 0L) chatListRepository.getChatById(it) else null }

                _state.update {
                    it.copy(
                        user = me,
                        firstName = me.firstName,
                        lastName = me.lastName ?: "",
                        bio = fullInfo?.description ?: "",
                        username = me.username ?: "",
                        birthdate = fullInfo?.birthdate,
                        personalChatId = fullInfo?.linkedChatId ?: 0L,
                        linkedChat = linkedChat,
                        businessBio = fullInfo?.businessInfo?.startPage?.message ?: "",
                        businessAddress = fullInfo?.businessInfo?.location?.address ?: "",
                        businessLatitude = fullInfo?.businessInfo?.location?.latitude ?: 0.0,
                        businessLongitude = fullInfo?.businessInfo?.location?.longitude ?: 0.0,
                        businessOpeningHours = fullInfo?.businessInfo?.openingHours,
                        avatarPath = me.avatarPath,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    override fun onBack() {
        onBackClicked()
    }

    override fun onUpdateFirstName(firstName: String) {
        _state.update { it.copy(firstName = firstName) }
    }

    override fun onUpdateLastName(lastName: String) {
        _state.update { it.copy(lastName = lastName) }
    }

    override fun onUpdateBio(bio: String) {
        _state.update { it.copy(bio = bio) }
    }

    override fun onUpdateUsername(username: String) {
        _state.update { it.copy(username = username) }
    }

    override fun onUpdateBirthdate(birthdate: BirthdateModel?) {
        _state.update { it.copy(birthdate = birthdate) }
    }

    override fun onUpdatePersonalChatId(chatId: Long) {
        _state.update { it.copy(personalChatId = chatId) }
        if (chatId != 0L) {
            scope.launch {
                val chat = chatListRepository.getChatById(chatId)
                _state.update { it.copy(linkedChat = chat) }
            }
        } else {
            _state.update { it.copy(linkedChat = null) }
        }
    }

    override fun onUpdateBusinessBio(bio: String) {
        _state.update { it.copy(businessBio = bio) }
    }

    override fun onUpdateBusinessAddress(address: String, latitude: Double, longitude: Double) {
        _state.update {
            it.copy(
                businessAddress = address,
                businessLatitude = latitude,
                businessLongitude = longitude
            )
        }
    }

    override fun onUpdateBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) {
        _state.update { it.copy(businessOpeningHours = openingHours) }
    }

    override fun onChangeAvatar(path: String) {
        _state.update { it.copy(avatarPath = path) }
        scope.launch {
            try {
                userProfileEditRepository.setProfilePhoto(path)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    override fun onShowAvatarPicker(show: Boolean) {
        _state.update { it.copy(showAvatarPicker = show) }
    }

    override fun onReverseGeocode(lat: Double, lon: Double) {
        scope.launch {
            try {
                val response = locationRepository.reverseGeocode(lat, lon)
                response?.let { address ->
                    _state.update {
                        it.copy(
                            businessAddress = (address.address?.fullAddress ?: address.display_name).toString(),
                            businessLatitude = lat,
                            businessLongitude = lon
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore geocode errors for now
            }
        }
    }

    override fun onToggleUsername(username: String, active: Boolean) {
        scope.launch {
            try {
                userProfileEditRepository.toggleUsernameIsActive(username, active)
                val me = userRepository.getMe()
                _state.update { it.copy(user = me) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    override fun onReorderUsernames(usernames: List<String>) {
        scope.launch {
            try {
                userProfileEditRepository.reorderActiveUsernames(usernames)
                val me = userRepository.getMe()
                _state.update { it.copy(user = me, username = me.username ?: "") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    override fun onSave() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val currentState = _state.value
                val user = currentState.user ?: return@launch

                if (currentState.firstName != user.firstName || currentState.lastName != (user.lastName ?: "")) {
                    userProfileEditRepository.setName(currentState.firstName, currentState.lastName)
                }

                val fullInfo = chatInfoRepository.getChatFullInfo(user.id)
                if (currentState.bio != (fullInfo?.description ?: "")) {
                    userProfileEditRepository.setBio(currentState.bio)
                }

                if (currentState.username != (user.username ?: "")) {
                    userProfileEditRepository.setUsername(currentState.username)
                }

                if (currentState.birthdate != fullInfo?.birthdate) {
                    userProfileEditRepository.setBirthdate(currentState.birthdate)
                }

                if (currentState.personalChatId != (fullInfo?.linkedChatId ?: 0L)) {
                    userProfileEditRepository.setPersonalChat(currentState.personalChatId)
                }

                if (currentState.businessBio != (fullInfo?.businessInfo?.startPage?.message ?: "")) {
                    userProfileEditRepository.setBusinessBio(currentState.businessBio)
                }

                if (currentState.businessAddress != (fullInfo?.businessInfo?.location?.address ?: "") ||
                    currentState.businessLatitude != (fullInfo?.businessInfo?.location?.latitude ?: 0.0) ||
                    currentState.businessLongitude != (fullInfo?.businessInfo?.location?.longitude ?: 0.0)
                ) {
                    userProfileEditRepository.setBusinessLocation(
                        currentState.businessAddress,
                        currentState.businessLatitude,
                        currentState.businessLongitude
                    )
                }

                if (currentState.businessOpeningHours != fullInfo?.businessInfo?.openingHours) {
                    userProfileEditRepository.setBusinessOpeningHours(currentState.businessOpeningHours)
                }

                onBack()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
}
