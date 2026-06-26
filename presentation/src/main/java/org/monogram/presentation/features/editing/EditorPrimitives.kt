package org.monogram.presentation.features.editing

data class EditorScreenState(
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val showDiscardChangesDialog: Boolean = false
) {
    val canSave: Boolean
        get() = isDirty && !isSaving
}

object EditorPrimitives {
    fun cleanState(): EditorScreenState = EditorScreenState()

    fun updateState(
        isDirty: Boolean,
        isSaving: Boolean = false,
        error: String? = null,
        showDiscardChangesDialog: Boolean = false
    ): EditorScreenState = EditorScreenState(
        isDirty = isDirty,
        isSaving = isSaving,
        error = error,
        showDiscardChangesDialog = showDiscardChangesDialog
    )

    fun markDirty(
        current: EditorScreenState,
        isDirty: Boolean
    ): EditorScreenState = current.copy(
        isDirty = isDirty,
        error = null
    )

    fun beginSave(current: EditorScreenState): EditorScreenState = current.copy(
        isSaving = true,
        error = null
    )

    fun endSave(current: EditorScreenState): EditorScreenState = current.copy(
        isSaving = false
    )

    fun fail(current: EditorScreenState, error: String?): EditorScreenState = current.copy(
        isSaving = false,
        error = error
    )

    fun showDiscardChanges(current: EditorScreenState): EditorScreenState = current.copy(
        showDiscardChangesDialog = true
    )

    fun hideDiscardChanges(current: EditorScreenState): EditorScreenState = current.copy(
        showDiscardChangesDialog = false
    )
}
