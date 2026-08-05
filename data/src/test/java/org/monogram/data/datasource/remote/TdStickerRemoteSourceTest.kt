package org.monogram.data.datasource.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.gateway.TelegramGateway
import java.util.Locale

class TdStickerRemoteSourceTest {

    @Test
    fun `buildTdInputLanguageCodes adds locale tag and english fallback`() {
        val result = buildTdInputLanguageCodes(Locale.forLanguageTag("ru-RU"))

        assertArrayEquals(arrayOf("ru-RU", "ru", "en"), result)
    }

    @Test
    fun `buildTdInputLanguageCodes keeps english once`() {
        val result = buildTdInputLanguageCodes(Locale.ENGLISH)

        assertArrayEquals(arrayOf("en"), result)
    }

    @Test
    fun `searchStickers passes non-empty inputLanguageCodes`() = runBlocking {
        val gateway = CapturingTelegramGateway()
        val remote = TdStickerRemoteSource(gateway)

        remote.searchStickers("thinking monkey")

        val request = gateway.lastSearchStickers
        assertEquals("thinking monkey", request?.query)
        assertTrue(request?.inputLanguageCodes?.isNotEmpty() == true)
        assertTrue(request?.inputLanguageCodes?.contains("en") == true)
    }

    @Test
    fun `getStickerEmojiHints maps tdlib emojis`() = runBlocking {
        val gateway = CapturingTelegramGateway()
        val remote = TdStickerRemoteSource(gateway)

        val result = remote.getStickerEmojiHints("thinking monkey")

        assertEquals(listOf("🤔", "🐵"), result)
    }

    private class CapturingTelegramGateway : TelegramGateway {
        override fun lane(
            name: String,
            scope: kotlinx.coroutines.CoroutineScope,
            context: kotlin.coroutines.CoroutineContext,
            filter: (TdApi.Update) -> Boolean,
            handler: suspend (TdApi.Update) -> Unit,
        ) = org.monogram.data.testing.fakeUpdateLane(updates, scope, context, filter, handler)

        override val updates = MutableSharedFlow<TdApi.Update>()
        override val isAuthenticated = MutableStateFlow(false)

        var lastSearchStickers: TdApi.SearchStickers? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            return when (function) {
                is TdApi.SearchStickers -> {
                    lastSearchStickers = function
                    TdApi.Stickers(emptyArray()) as T
                }

                is TdApi.GetAllStickerEmojis -> TdApi.Emojis(arrayOf("🤔", "🐵")) as T
                else -> error("Unexpected function: ${function.javaClass.simpleName}")
            }
        }
    }
}
