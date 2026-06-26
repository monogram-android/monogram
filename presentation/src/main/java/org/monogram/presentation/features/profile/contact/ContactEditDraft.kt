package org.monogram.presentation.features.profile.contact

internal data class ContactEditDraft(
    val firstName: String = "",
    val lastName: String = "",
    val sharePhoneNumber: Boolean = false
) {
    fun isValid(): Boolean = firstName.trim().isNotBlank()

    fun hasChanges(initial: ContactEditDraft): Boolean {
        return firstName.trim() != initial.firstName.trim() ||
                lastName.trim() != initial.lastName.trim() ||
                sharePhoneNumber != initial.sharePhoneNumber
    }
}
