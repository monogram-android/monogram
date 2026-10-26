package org.monogram.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.monogram.core.models.formatServiceMessage
import org.monogram.core.ui.theme.MonogramTheme

class OwnershipServiceMessageTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun everyLocaleNamesNewOwnerForIncomingAndOutgoingTransfers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (tag in listOf("en", "es", "hy", "ja", "pt-BR", "ru", "sk", "tr", "uk", "zh")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val resources = context.createConfigurationContext(configuration).resources
            for (kind in listOf("creator_pending", "creator_change")) {
                for (outgoing in listOf(false, true)) {
                    val text = formatServiceMessage(
                        raw = "$kind\u001fAlice\u001fBob",
                        youLabel = "You",
                        outgoing = outgoing,
                        someoneLabel = "Someone",
                        templateFor = serviceMessageCatalog(resources),
                    )
                    assertTrue("$tag $kind outgoing=$outgoing: $text", text.contains("Bob"))
                    assertFalse(text.contains("Alice"))
                    assertFalse(text.contains("You"))
                }
            }
        }
    }

    @Test
    fun renderedOwnershipMessagesNameNewOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val resources = context.createConfigurationContext(configuration).resources
        rule.setContent {
            MonogramTheme {
                Column {
                    for (kind in listOf("creator_pending", "creator_change")) {
                        Text(
                            formatServiceMessage(
                                raw = "$kind\u001fAlice\u001fBob",
                                youLabel = "You",
                                outgoing = true,
                                someoneLabel = "Someone",
                                templateFor = serviceMessageCatalog(resources),
                            ),
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Bob will become the new owner").assertIsDisplayed()
        rule.onNodeWithText("Bob became the new owner").assertIsDisplayed()
    }
}
