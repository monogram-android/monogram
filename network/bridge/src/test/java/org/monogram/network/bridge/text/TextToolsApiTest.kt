package org.monogram.network.bridge.text

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.models.PeerId
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TextToolText
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.session.SessionCore
import uniffi.monogram_mtproto.TextToolTextDto

class TextToolsApiTest {
    @Test
    fun translationValidationRejectsOversizedOrAmbiguousBatchesBeforeConnecting() =
        runTest {
            var connections = 0
            val native =
                object : MtprotoNative by MtprotoNative.Stub {
                    override fun connect(handle: Long) {
                        connections++
                    }
                }
            val api =
                TextToolsApi(
                    SessionCore(
                        TelegramCredentials(1, "hash"),
                        "text-tools.session",
                        native,
                        5_000,
                        StandardTestDispatcher(testScheduler),
                        {},
                    ),
                )
            assertTrue(
                api.translateMessageTexts(
                    PeerId(42),
                    List(21) { it + 1 },
                    "es"
                ) is Outcome.Err
            )
            assertTrue(api.translateMessageTexts(PeerId(42), listOf(1, 1), "es") is Outcome.Err)
            assertTrue(api.translateMessageTexts(PeerId(42), listOf(-1), "es") is Outcome.Err)
            assertTrue(
                api.translateTexts(
                    listOf(TextToolText("😀".repeat(12_501))),
                    "es"
                ) is Outcome.Err
            )
            assertTrue(api.translateTexts(listOf(TextToolText("hello")), " ") is Outcome.Err)
            assertTrue(
                api.composeTextWithAi(
                    TextToolText("hello"),
                    false,
                    false,
                    null,
                    null
                ) is Outcome.Err
            )
            assertEquals(0, connections)
        }

    @Test
    fun partialTranslationResponseIsRejectedInsteadOfAssigningItToMessages() =
        runTest {
            var requestedLanguage: String? = null
            var requestedIds = emptyList<Int>()
            val native =
                object : MtprotoNative by MtprotoNative.Stub {
                    override fun connect(handle: Long) = Unit

                    override fun isAuthorized(handle: Long) = false

                    override fun translateMessageTexts(
                        handle: Long,
                        chatId: Long,
                        messageIds: List<Int>,
                        toLang: String,
                    ): List<TextToolTextDto> {
                        requestedLanguage = toLang
                        requestedIds = messageIds
                        return listOf(TextToolTextDto("hola", null))
                    }
                }
            val core =
                SessionCore(
                    TelegramCredentials(1, "hash"),
                    "text-tools.session",
                    native,
                    5_000,
                    StandardTestDispatcher(testScheduler),
                    {})
            try {
                val result =
                    TextToolsApi(core).translateMessageTexts(PeerId(42), listOf(3, 7), "pt_br")
                assertTrue(result is Outcome.Err)
                assertEquals(listOf(3, 7), requestedIds)
                assertEquals("pt-BR", requestedLanguage)
            } finally {
                core.close()
            }
        }

    @Test
    fun languageTagsMatchTelegramNormalization() {
        assertEquals("pt-BR", normalizeTextToolLanguage("pt_br"))
        assertEquals("zh-TW", normalizeTextToolLanguage("zh-tw"))
        assertEquals("en", normalizeTextToolLanguage("en"))
        assertEquals("en", normalizeTextToolLanguage("EN"))
        assertEquals("zh-hans", normalizeTextToolLanguage("zh-hans"))
        assertEquals("zh-HANS-CN", normalizeTextToolLanguage("zh-HANS-CN"))
    }

    @Test
    fun richTranslationMetadataRoundTripsWithoutChangingUtf16Offsets() {
        val original =
            TextToolText(
                "😀 example",
                listOf(
                    TextEntity("bold", 0, 2),
                    TextEntity("text_url", 3, 7, "https://example.org/?a=1&b=2")
                )
            )
        assertEquals(original, original.toNative().toModel())
    }

    @Test
    fun proofreadDiffKindsArePreservedInTheBridge() {
        val diff =
            TextToolTextDto(
                "some edit",
                """[{"kind":"diff_insert","offset":5,"length":4},{"kind":"diff_delete","offset":0,"length":4},{"kind":"diff_replace","offset":5,"length":4,"text":"original"}]""",
            )
        assertEquals(
            listOf("diff_insert", "diff_delete", "diff_replace"),
            diff.toModel().entities.map { it.kind })
        assertEquals(
            "original",
            diff
                .toModel()
                .entities
                .last()
                .oldText,
        )
        assertEquals(
            5,
            diff
                .toModel()
                .entities
                .first()
                .offset,
        )
    }
}
