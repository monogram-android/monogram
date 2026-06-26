package org.monogram.presentation.features.profile.contact

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ContactEditRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.editing.EditorPrimitives
import org.monogram.presentation.root.AppComponentContext

class DefaultContactEditComponent(
    context: AppComponentContext,
    private val userId: Long,
    private val onBackClicked: () -> Unit
) : ContactEditComponent, AppComponentContext by context {

    private val contactEditRepository: ContactEditRepository =
        container.repositories.contactEditRepository
    private val stringProvider = container.utils.stringProvider()
    private val scope = componentScope
    private var initialDraft = ContactEditDraft()

    private val _state = MutableValue(ContactEditComponent.State(userId = userId, isLoading = true))
    override val state: Value<ContactEditComponent.State> = _state

    init {
        scope.launch {
            val user = contactEditRepository.getContact(userId)
            val needPhoneNumberPrivacyException =
                contactEditRepository.getNeedPhoneNumberPrivacyException(userId)
            initialDraft = ContactEditDraft(
                firstName = user?.firstName.orEmpty(),
                lastName = user?.lastName.orEmpty(),
                sharePhoneNumber = !needPhoneNumberPrivacyException
            )
            _state.update {
                it.copy(
                    user = user,
                    firstName = initialDraft.firstName,
                    lastName = initialDraft.lastName,
                    phoneNumber = user?.phoneNumber,
                    sharePhoneNumber = initialDraft.sharePhoneNumber,
                    needPhoneNumberPrivacyException = needPhoneNumberPrivacyException,
                    canEditPersonalPhoto = contactEditRepository.supportsPersonalPhotoEditing,
                    isLoading = false,
                    editor = EditorPrimitives.cleanState()
                )
            }
        }
    }

    override fun onBack() {
        if (_state.value.editor.isDirty) {
            _state.update {
                it.copy(editor = EditorPrimitives.showDiscardChanges(it.editor))
            }
        } else {
            onBackClicked()
        }
    }

    override fun onDismissDiscardChanges() {
        _state.update {
            it.copy(editor = EditorPrimitives.hideDiscardChanges(it.editor))
        }
    }

    override fun onConfirmDiscardChanges() {
        _state.update {
            it.copy(editor = EditorPrimitives.hideDiscardChanges(it.editor))
        }
        onBackClicked()
    }

    override fun onDismissError() {
        _state.update {
            it.copy(editor = EditorPrimitives.endSave(it.editor.copy(error = null)))
        }
    }

    override fun onUpdateFirstName(firstName: String) {
        _state.update { current ->
            current.copy(
                firstName = firstName,
                editor = EditorPrimitives.markDirty(
                    current.editor,
                    currentDraft(firstName = firstName).hasChanges(initialDraft)
                )
            )
        }
    }

    override fun onUpdateLastName(lastName: String) {
        _state.update { current ->
            current.copy(
                lastName = lastName,
                editor = EditorPrimitives.markDirty(
                    current.editor,
                    currentDraft(lastName = lastName).hasChanges(initialDraft)
                )
            )
        }
    }

    override fun onToggleSharePhoneNumber(sharePhoneNumber: Boolean) {
        _state.update { current ->
            current.copy(
                sharePhoneNumber = sharePhoneNumber,
                editor = EditorPrimitives.markDirty(
                    current.editor,
                    currentDraft(sharePhoneNumber = sharePhoneNumber).hasChanges(initialDraft)
                )
            )
        }
    }

    override fun onSave() {
        val snapshot = _state.value
        val user = snapshot.user ?: return
        val draft = currentDraft()
        if (!draft.isValid()) {
            _state.update {
                it.copy(
                    editor = EditorPrimitives.fail(
                        it.editor,
                        stringProvider.getString("contact_edit_first_name_required")
                    )
                )
            }
            return
        }
        val firstName = draft.firstName.trim()
        val lastName = draft.lastName.trim()

        scope.launch {
            _state.update {
                it.copy(editor = EditorPrimitives.beginSave(it.editor))
            }
            runCatching {
                val updatedUser = user.copy(
                    firstName = firstName,
                    lastName = lastName.ifBlank { null },
                    isContact = true
                )
                val savedUser = contactEditRepository.upsertContact(
                    user = updatedUser,
                    sharePhoneNumber = snapshot.sharePhoneNumber
                ) ?: updatedUser
                savedUser.copy(
                    firstName = firstName,
                    lastName = lastName.ifBlank { null },
                    isContact = true
                )
            }.onSuccess { updatedUser ->
                initialDraft = draft.copy(firstName = firstName, lastName = lastName)
                _state.update {
                    it.copy(
                        user = updatedUser,
                        firstName = firstName,
                        lastName = lastName,
                        sharePhoneNumber = draft.sharePhoneNumber,
                        editor = EditorPrimitives.cleanState(),
                        needPhoneNumberPrivacyException = !draft.sharePhoneNumber
                    )
                }
                onBackClicked()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        editor = EditorPrimitives.fail(
                            it.editor,
                            error.message ?: stringProvider.getString("contact_edit_save_failed")
                        )
                    )
                }
            }
        }
    }

    override fun onRemoveContact() {
        val currentUserId = _state.value.user?.id ?: return
        scope.launch {
            _state.update {
                it.copy(editor = EditorPrimitives.beginSave(it.editor))
            }
            runCatching {
                contactEditRepository.removeContact(currentUserId)
            }.onSuccess {
                _state.update {
                    it.copy(
                        user = it.user?.copy(isContact = false),
                        editor = EditorPrimitives.cleanState()
                    )
                }
                onBackClicked()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        editor = EditorPrimitives.fail(
                            it.editor,
                            error.message ?: stringProvider.getString("contact_edit_remove_failed")
                        )
                    )
                }
            }
        }
    }

    private fun currentDraft(
        firstName: String = _state.value.firstName,
        lastName: String = _state.value.lastName,
        sharePhoneNumber: Boolean = _state.value.sharePhoneNumber
    ): ContactEditDraft = ContactEditDraft(
        firstName = firstName,
        lastName = lastName,
        sharePhoneNumber = sharePhoneNumber
    )
}
