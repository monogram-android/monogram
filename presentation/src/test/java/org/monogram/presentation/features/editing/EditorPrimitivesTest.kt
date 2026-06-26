package org.monogram.presentation.features.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrimitivesTest {

    @Test
    fun `canSave is true only when dirty and not saving`() {
        assertTrue(EditorPrimitives.updateState(isDirty = true).canSave)
        assertFalse(EditorPrimitives.updateState(isDirty = false).canSave)
        assertFalse(EditorPrimitives.updateState(isDirty = true, isSaving = true).canSave)
    }

    @Test
    fun `beginSave clears error and disables save`() {
        val state = EditorPrimitives.beginSave(
            EditorPrimitives.updateState(isDirty = true, error = "boom")
        )

        assertTrue(state.isSaving)
        assertNull(state.error)
        assertFalse(state.canSave)
    }

    @Test
    fun `fail stops saving and keeps error`() {
        val state = EditorPrimitives.fail(
            EditorPrimitives.beginSave(EditorPrimitives.updateState(isDirty = true)),
            "boom"
        )

        assertFalse(state.isSaving)
        assertEquals("boom", state.error)
        assertTrue(state.canSave)
    }

    @Test
    fun `discard dialog helpers toggle dialog flag`() {
        val shown = EditorPrimitives.showDiscardChanges(EditorPrimitives.cleanState())
        val hidden = EditorPrimitives.hideDiscardChanges(shown)

        assertTrue(shown.showDiscardChangesDialog)
        assertFalse(hidden.showDiscardChangesDialog)
    }
}
