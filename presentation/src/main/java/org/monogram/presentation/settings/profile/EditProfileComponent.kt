package org.monogram.presentation.settings.profile

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.TdLibLimits
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.editing.EditorScreenState

interface EditProfileComponent {
    val state: Value<State>

    fun onBack()
    fun onUpdateFirstName(firstName: String)
    fun onUpdateLastName(lastName: String)
    fun onUpdateBio(bio: String)
    fun onUpdateUsername(username: String)
    fun onUpdateBirthdate(birthdate: BirthdateModel?)
    fun onUpdatePersonalChatId(chatId: Long)
    fun onUpdateBusinessBio(bio: String)
    fun onUpdateBusinessAddress(address: String, latitude: Double = 0.0, longitude: Double = 0.0)
    fun onUpdateBusinessOpeningHours(openingHours: BusinessOpeningHoursModel?)
    fun onChangeAvatar(path: String)
    fun onSave()
    fun onReverseGeocode(lat: Double, lon: Double)
    fun onToggleUsername(username: String, active: Boolean)
    fun onReorderUsernames(usernames: List<String>)
    fun onDismissDiscardChanges()
    fun onConfirmDiscardChanges()
    fun onDismissError()

    data class State(
        val user: UserModel? = null,
        val firstName: String = "",
        val lastName: String = "",
        val bio: String = "",
        val username: String = "",
        val birthdate: BirthdateModel? = null,
        val personalChatId: Long = 0L,
        val linkedChat: ChatModel? = null,
        val businessBio: String = "",
        val businessAddress: String = "",
        val businessLatitude: Double = 0.0,
        val businessLongitude: Double = 0.0,
        val businessOpeningHours: BusinessOpeningHoursModel? = null,
        val tdLibLimits: TdLibLimits = TdLibLimits(),
        val avatarPath: String? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val editor: EditorScreenState = EditorScreenState(),
        val showAvatarPicker: Boolean = false
    ) {
        val isBioOverLimit: Boolean
            get() = tdLibLimits.bioLengthMax?.let { bio.length > it } == true

        val isBusinessBioOverLimit: Boolean
            get() = tdLibLimits.businessStartPageMessageLengthMax
                ?.let { businessBio.length > it } == true

        val canSave: Boolean
            get() = editor.canSave && !isBioOverLimit && !isBusinessBioOverLimit
    }

    fun onShowAvatarPicker(show: Boolean)
}
