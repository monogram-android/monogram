package org.monogram.data.mtproto

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBot_4414eda380
import org.monogram.mtproto.tl.generated.cloud.layer223.AttachMenuBots_41a96079b0
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAttachMenuBots
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoAttachMenuBotRepositoryTest {
    @Test
    fun `loads attach menu bot flags from MTProto`() = runBlocking {
        val repository = MtProtoAttachMenuBotRepository(
            transportFactory = MtProtoSessionTransportFactory { RecordingTransport() },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )

        val bot = repository.getAttachMenuBots().first().single()

        assertEquals(7L, bot.botUserId)
        assertEquals("weather", bot.name)
        assertTrue(bot.requestWriteAccess)
        assertTrue(bot.showInAttachMenu)
        assertTrue(bot.showInSideMenu)
        assertTrue(bot.isAdded)
        assertEquals(null, bot.icon)
    }

    private class RecordingTransport : MtProtoRpcTransport {
        override suspend fun <R> execute(method: TlMethod<R>): R {
            require(method === GetAttachMenuBots(0L) || method is GetAttachMenuBots)
            @Suppress("UNCHECKED_CAST")
            return AttachMenuBots_41a96079b0(
                hash = 12L,
                bots = listOf(
                    AttachMenuBot_4414eda380(
                        inactive = false,
                        hasSettings = false,
                        requestWriteAccess = true,
                        showInAttachMenu = true,
                        showInSideMenu = true,
                        sideMenuDisclaimerNeeded = false,
                        botId = 7L,
                        shortName = "weather",
                        peerTypes = null,
                        icons = emptyList(),
                    ),
                ),
                users = emptyList(),
            ) as R
        }

        override fun close() = Unit
    }
}
