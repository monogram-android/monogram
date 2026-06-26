package org.monogram.presentation.features.profile.contact

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.editing.EditorScreenState

interface ContactEditComponent {
    val state: Value<State>

    fun onBack()
    fun onDismissDiscardChanges()
    fun onConfirmDiscardChanges()
    fun onDismissError()
    fun onUpdateFirstName(firstName: String)
    fun onUpdateLastName(lastName: String)
    fun onToggleSharePhoneNumber(sharePhoneNumber: Boolean)
    fun onSave()
    fun onRemoveContact()

    data class State(
        val userId: Long,
        val user: UserModel? = null,
        val firstName: String = "",
        val lastName: String = "",
        val phoneNumber: String? = null,
        val sharePhoneNumber: Boolean = false,
        val needPhoneNumberPrivacyException: Boolean = false,
        val canEditPersonalPhoto: Boolean = false,
        val editor: EditorScreenState = EditorScreenState(),
        val isLoading: Boolean = false
    )
}
