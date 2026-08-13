package org.monogram.data.datasource.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.gateway.TelegramGateway

class TdGifRemoteSourceTest {

    @Test
    fun `searchGifs keeps inline query id for sending`() = runBlocking {
        val gateway = CapturingTelegramGateway()
        val remote = TdGifRemoteSource(gateway)

        val result = remote.searchGifs("cat")

        assertEquals(1, result.size)
        assertEquals("gif_result_1", result.first().id)
        assertEquals(777L, result.first().inlineQueryId)
        assertEquals(101L, result.first().fileId)
        assertEquals(202L, result.first().thumbFileId)
    }

    @Test
    fun `getSavedGifs keeps file based send data`() = runBlocking {
        val gateway = CapturingTelegramGateway()
        val remote = TdGifRemoteSource(gateway)

        val result = remote.getSavedGifs()

        assertEquals(1, result.size)
        assertEquals("saved_remote_id", result.first().id)
        assertNull(result.first().inlineQueryId)
        assertEquals(303L, result.first().fileId)
        assertEquals(404L, result.first().thumbFileId)
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

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            return when (function) {
                is TdApi.GetSavedAnimations -> TdApi.Animations(
                    arrayOf(
                        TdApi.Animation(
                            1,
                            320,
                            240,
                            "saved.mp4",
                            "video/mp4",
                            false,
                            null,
                            TdApi.Thumbnail(
                                TdApi.ThumbnailFormatJpeg(),
                                320,
                                240,
                                tdFile(404, path = "thumb.jpg")
                            ),
                            tdFile(303, remoteId = "saved_remote_id", path = "saved.mp4")
                        )
                    )
                ) as T

                is TdApi.SearchPublicChat -> TdApi.Chat().apply {
                    id = 555L
                    type = TdApi.ChatTypePrivate(666L)
                } as T

                is TdApi.GetInlineQueryResults -> TdApi.InlineQueryResults(
                    777L,
                    null,
                    arrayOf(
                        TdApi.InlineQueryResultAnimation(
                            "gif_result_1",
                            TdApi.Animation(
                                1,
                                320,
                                240,
                                "gif.mp4",
                                "video/mp4",
                                false,
                                null,
                                TdApi.Thumbnail(
                                    TdApi.ThumbnailFormatJpeg(),
                                    320,
                                    240,
                                    tdFile(202, path = "thumb2.jpg")
                                ),
                                tdFile(101, remoteId = "remote_animation_id", path = "gif.mp4")
                            ),
                            "title"
                        )
                    ),
                    ""
                ) as T

                else -> error("Unexpected function: ${function.javaClass.simpleName}")
            }
        }

        private fun tdFile(id: Int, remoteId: String = "", path: String = "") = TdApi.File().apply {
            this.id = id
            this.local = TdApi.LocalFile().apply {
                this.path = path
                this.isDownloadingCompleted = path.isNotBlank()
            }
            this.remote = TdApi.RemoteFile().apply {
                this.id = remoteId
            }
        }
    }
}
