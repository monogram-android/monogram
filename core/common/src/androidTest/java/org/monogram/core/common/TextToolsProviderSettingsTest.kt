package org.monogram.core.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextToolsProviderSettingsTest {
    private val accountOne = 9_100_001L
    private val accountTwo = 9_100_002L

    @Before
    fun setUp() {
        TextToolsProviderSettings.install(InstrumentationRegistry.getInstrumentation().targetContext)
        TextToolsProviderSettings.clear(accountOne)
        TextToolsProviderSettings.clear(accountTwo)
    }

    @After
    fun tearDown() {
        TextToolsProviderSettings.clear(accountOne)
        TextToolsProviderSettings.clear(accountTwo)
    }

    @Test
    fun encryptedSettingsRoundTripAndRemainAccountScoped() {
        val first =
            TextToolsProviderConfig("openai-compatible", "https://one.example/v1", "one", "key-one")
        val second =
            TextToolsProviderConfig("openai-compatible", "https://two.example/v1", "two", "key-two")

        TextToolsProviderSettings.save(accountOne, first)
        TextToolsProviderSettings.save(accountTwo, second)

        assertEquals(first, TextToolsProviderSettings.load(accountOne))
        assertEquals(second, TextToolsProviderSettings.load(accountTwo))
        assertTrue(TextToolsProviderSettings.load(9_100_003L).apiKey.isEmpty())
        assertTrue(first.toString().contains("[REDACTED]"))
        assertTrue(!first.toString().contains("key-one"))

        TextToolsProviderSettings.clear(accountOne)
        assertEquals(TextToolsProviderConfig(), TextToolsProviderSettings.load(accountOne))
        assertEquals(second, TextToolsProviderSettings.load(accountTwo))
    }
}
