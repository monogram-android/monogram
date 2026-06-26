package org.monogram.presentation.features.profile.contact

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEditDraftTest {

    @Test
    fun `hasChanges detects share phone disable`() {
        val initial = ContactEditDraft(
            firstName = "Ada",
            lastName = "Lovelace",
            sharePhoneNumber = true
        )

        val updated = initial.copy(sharePhoneNumber = false)

        assertTrue(updated.hasChanges(initial))
    }

    @Test
    fun `hasChanges ignores surrounding whitespace only`() {
        val initial = ContactEditDraft(
            firstName = "Ada",
            lastName = "Lovelace",
            sharePhoneNumber = true
        )

        val updated = initial.copy(firstName = "  Ada  ", lastName = " Lovelace ")

        assertFalse(updated.hasChanges(initial))
    }

    @Test
    fun `isValid requires non blank first name`() {
        assertFalse(ContactEditDraft(firstName = "   ").isValid())
        assertTrue(ContactEditDraft(firstName = "Ada").isValid())
    }
}
