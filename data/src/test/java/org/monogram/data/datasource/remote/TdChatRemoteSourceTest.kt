package org.monogram.data.datasource.remote

import android.net.ConnectivityManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.gateway.TelegramGateway
import org.monogram.domain.repository.TelegramLinkRepository
import sun.misc.Unsafe
import java.lang.reflect.Field

class TdChatRemoteSourceTest {

    @Test
    fun `setChatDescription uses chat id directly`() = runBlocking {
        val gateway = CapturingTelegramGateway()
        val remote = TdChatRemoteSource(
            gateway = gateway,
            connectivityManager = unsafeConnectivityManager(),
            telegramLinkRepository = FakeTelegramLinkRepository()
        )

        remote.setChatDescription(chatId = 123L, description = "New description")

        assertEquals(123L, gateway.lastSetChatDescription?.chatId)
        assertEquals("New description", gateway.lastSetChatDescription?.description)
    }

    private class CapturingTelegramGateway : TelegramGateway {
        override val updates = MutableSharedFlow<TdApi.Update>()
        override val isAuthenticated = MutableStateFlow(false)
        var lastSetChatDescription: TdApi.SetChatDescription? = null

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            return when (function) {
                is TdApi.SetChatDescription -> {
                    lastSetChatDescription = function
                    TdApi.Ok() as T
                }

                else -> error("Unexpected function: ${function.javaClass.simpleName}")
            }
        }
    }

    private fun unsafeConnectivityManager(): ConnectivityManager {
        val field: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(ConnectivityManager::class.java) as ConnectivityManager
    }

    private class FakeTelegramLinkRepository : TelegramLinkRepository {
        override val baseUrl: StateFlow<String> = MutableStateFlow("https://t.me")

        override suspend fun buildUrl(path: String): String {
            return "${baseUrl.value}/${path.trimStart('/')}"
        }
    }
}
