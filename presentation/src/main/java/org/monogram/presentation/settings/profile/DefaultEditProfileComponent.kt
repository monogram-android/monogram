package org.monogram.presentation.settings.profile

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.LocationRepository
import org.monogram.domain.repository.UserProfileEditRepository
import org.monogram.domain.repository.UserRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.editing.EditorPrimitives
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
    private var initialBio: String = ""
    private var initialBirthdate: BirthdateModel? = null
    private var initialPersonalChatId: Long = 0L
    private var initialBusinessBio: String = ""
    private var initialBusinessAddress: String = ""
    private var initialBusinessLatitude: Double = 0.0
    private var initialBusinessLongitude: Double = 0.0
    private var initialBusinessOpeningHours: BusinessOpeningHoursModel? = null
    private var initialAvatarPath: String? = null

    init {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val me = userRepository.getMe()
                val fullInfo = chatInfoRepository.getChatFullInfo(me.id)
                val linkedChat =
                    fullInfo?.linkedChatId?.let { if (it != 0L) chatListRepository.getChatById(it) else null }
                initialBio = fullInfo?.description ?: ""
                initialBirthdate = fullInfo?.birthdate
                initialPersonalChatId = fullInfo?.linkedChatId ?: 0L
                initialBusinessBio = fullInfo?.businessInfo?.startPage?.message ?: ""
                initialBusinessAddress = fullInfo?.businessInfo?.location?.address ?: ""
                initialBusinessLatitude = fullInfo?.businessInfo?.location?.latitude ?: 0.0
                initialBusinessLongitude = fullInfo?.businessInfo?.location?.longitude ?: 0.0
                initialBusinessOpeningHours = fullInfo?.businessInfo?.openingHours
                initialAvatarPath = me.avatarPath

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
        if (_state.value.editor.isDirty) {
            _state.update {
                it.copy(editor = it.editor.copy(showDiscardChangesDialog = true))
            }
        } else {
            onBackClicked()
        }
    }

    override fun onUpdateFirstName(firstName: String) {
        _state.update {
            it.copy(
                firstName = firstName,
                editor = it.editor.copy(isDirty = hasChanges(firstName = firstName))
            )
        }
    }

    override fun onUpdateLastName(lastName: String) {
        _state.update {
            it.copy(
                lastName = lastName,
                editor = it.editor.copy(isDirty = hasChanges(lastName = lastName))
            )
        }
    }

    override fun onUpdateBio(bio: String) {
        _state.update {
            it.copy(
                bio = bio,
                editor = it.editor.copy(isDirty = hasChanges(bio = bio))
            )
        }
    }

    override fun onUpdateUsername(username: String) {
        _state.update {
            it.copy(
                username = username,
                editor = it.editor.copy(isDirty = hasChanges(username = username))
            )
        }
    }

    override fun onUpdateBirthdate(birthdate: BirthdateModel?) {
        _state.update {
            it.copy(
                birthdate = birthdate,
                editor = it.editor.copy(isDirty = hasChanges(birthdate = birthdate))
            )
        }
    }

    override fun onUpdatePersonalChatId(chatId: Long) {
        _state.update { it.copy(personalChatId = chatId) }
        _state.update { it.copy(editor = it.editor.copy(isDirty = hasChanges(personalChatId = chatId))) }
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
        _state.update {
            it.copy(
                businessBio = bio,
                editor = it.editor.copy(isDirty = hasChanges(businessBio = bio))
            )
        }
    }

    override fun onUpdateBusinessAddress(address: String, latitude: Double, longitude: Double) {
        _state.update {
            it.copy(
                businessAddress = address,
                businessLatitude = latitude,
                businessLongitude = longitude,
                editor = it.editor.copy(
                    isDirty = hasChanges(
                        businessAddress = address,
                        businessLatitude = latitude,
                        businessLongitude = longitude
                    )
                )
            )
        }
    }

    override fun onUpdateBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?) {
        _state.update {
            it.copy(
                businessOpeningHours = openingHours,
                editor = it.editor.copy(isDirty = hasChanges(businessOpeningHours = openingHours))
            )
        }
    }

    override fun onChangeAvatar(path: String) {
        _state.update { it.copy(avatarPath = path, editor = it.editor.copy(isDirty = true)) }
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

    override fun onDismissDiscardChanges() {
        _state.update { it.copy(editor = it.editor.copy(showDiscardChangesDialog = false)) }
    }

    override fun onConfirmDiscardChanges() {
        _state.update { it.copy(editor = it.editor.copy(showDiscardChangesDialog = false)) }
        onBackClicked()
    }

    override fun onDismissError() {
        _state.update { it.copy(editor = it.editor.copy(error = null), error = null) }
    }

    override fun onReverseGeocode(lat: Double, lon: Double) {
        scope.launch {
            try {
                val response = locationRepository.reverseGeocode(lat, lon)
                response?.let { address ->
                    val resolvedAddress =
                        (address.address?.fullAddress ?: address.display_name).toString()
                    _state.update {
                        it.copy(
                            businessAddress = resolvedAddress,
                            businessLatitude = lat,
                            businessLongitude = lon,
                            editor = it.editor.copy(
                                isDirty = hasChanges(
                                    businessAddress = resolvedAddress,
                                    businessLatitude = lat,
                                    businessLongitude = lon
                                )
                            )
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
                _state.update {
                    it.copy(
                        user = me,
                        editor = it.editor.copy(isDirty = true, error = null)
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message,
                        editor = it.editor.copy(error = e.message)
                    )
                }
            }
        }
    }

    override fun onReorderUsernames(usernames: List<String>) {
        scope.launch {
            try {
                userProfileEditRepository.reorderActiveUsernames(usernames)
                val me = userRepository.getMe()
                _state.update {
                    it.copy(
                        user = me,
                        username = me.username ?: "",
                        editor = it.editor.copy(isDirty = true, error = null)
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message,
                        editor = it.editor.copy(error = e.message)
                    )
                }
            }
        }
    }

    override fun onSave() {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    editor = it.editor.copy(isSaving = true, error = null)
                )
            }
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

                userRepository.refreshUserFullInfo(user.id)
                initialBio = currentState.bio
                initialBirthdate = currentState.birthdate
                initialPersonalChatId = currentState.personalChatId
                initialBusinessBio = currentState.businessBio
                initialBusinessAddress = currentState.businessAddress
                initialBusinessLatitude = currentState.businessLatitude
                initialBusinessLongitude = currentState.businessLongitude
                initialBusinessOpeningHours = currentState.businessOpeningHours
                initialAvatarPath = currentState.avatarPath
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        editor = EditorPrimitives.updateState(isDirty = false)
                    )
                }
                onBackClicked()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                        editor = it.editor.copy(isSaving = false, error = e.message)
                    )
                }
            }
        }
    }

    private fun hasChanges(
        firstName: String = _state.value.firstName,
        lastName: String = _state.value.lastName,
        bio: String = _state.value.bio,
        username: String = _state.value.username,
        birthdate: BirthdateModel? = _state.value.birthdate,
        personalChatId: Long = _state.value.personalChatId,
        businessBio: String = _state.value.businessBio,
        businessAddress: String = _state.value.businessAddress,
        businessLatitude: Double = _state.value.businessLatitude,
        businessLongitude: Double = _state.value.businessLongitude,
        businessOpeningHours: BusinessOpeningHoursModel? = _state.value.businessOpeningHours
    ): Boolean {
        val state = _state.value
        val user = state.user ?: return false
        return firstName != user.firstName ||
                lastName != (user.lastName ?: "") ||
                bio != initialBio ||
                username != (user.username ?: "") ||
                birthdate != initialBirthdate ||
                personalChatId != initialPersonalChatId ||
                businessBio != initialBusinessBio ||
                businessAddress != initialBusinessAddress ||
                businessLatitude != initialBusinessLatitude ||
                businessLongitude != initialBusinessLongitude ||
                businessOpeningHours != initialBusinessOpeningHours ||
                state.avatarPath != initialAvatarPath
    }
}
