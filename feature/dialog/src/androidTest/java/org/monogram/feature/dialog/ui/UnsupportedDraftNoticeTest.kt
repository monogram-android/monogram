package org.monogram.feature.dialog.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.feature.dialog.R

class UnsupportedDraftNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun discardRequiresConfirmationAndCancelPreservesTheDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val discard = context.getString(R.string.dialog_discard_draft)
        var discarded = 0
        compose.setContent {
            MonogramTheme(dynamicColor = false) {
                UnsupportedDraftNotice("Synthetic draft") { discarded++ }
            }
        }
        compose.onNodeWithText("Synthetic draft").assertIsDisplayed()
        compose.onNode(hasText(discard) and hasClickAction()).performClick()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle { assertEquals(0, discarded) }
        compose.onNodeWithText(context.getString(R.string.dialog_cancel)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, discarded) }
        compose.onNodeWithText("Synthetic draft").assertIsDisplayed()
        compose.onNode(hasText(discard) and hasClickAction()).performClick()
        compose.onNode(hasText(discard) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performClick()
        compose.runOnIdle { assertEquals(1, discarded) }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
