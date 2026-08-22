package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyBroadcasts
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerNotifySettings_6185e07dc9
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerNotifySettings_474d6bbc59
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetNotifySettings
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateNotifySettings
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoNotificationSettingsRepositoryTest {
    @Test
    fun `maps global notification scopes and mute settings`() = runBlocking {
        val transport = RecordingTransport()
        val repository = MtProtoNotificationSettingsRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertTrue(repository.getNotificationSettings(TdNotificationScope.CHANNELS))
        repository.setNotificationSettings(TdNotificationScope.CHANNELS, enabled = false)

        assertEquals(InputNotifyBroadcasts, (transport.methods[0] as GetNotifySettings).peer)
        val update = transport.methods[1] as UpdateNotifySettings
        assertEquals(InputNotifyBroadcasts, update.peer)
        assertEquals(Int.MAX_VALUE, (update.settings as InputPeerNotifySettings_6185e07dc9).muteUntil)
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(4, "dc", 443),
        handshake = MtProtoHandshakeConfig(4, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(12345, "device", "system", "app", "en"),
    )

    private class RecordingTransport : MtProtoRpcTransport {
        val methods = mutableListOf<TlMethod<*>>()

        override suspend fun <R> execute(method: TlMethod<R>): R {
            methods += method
            @Suppress("UNCHECKED_CAST")
            return when (method) {
                is GetNotifySettings -> PeerNotifySettings_474d6bbc59(
                    showPreviews = null,
                    silent = false,
                    muteUntil = 0,
                    iosSound = null,
                    androidSound = null,
                    otherSound = null,
                    storiesMuted = null,
                    storiesHideSender = null,
                    storiesIosSound = null,
                    storiesAndroidSound = null,
                    storiesOtherSound = null,
                ) as R
                is UpdateNotifySettings -> true as R
                else -> error("Unexpected method: ${method::class.simpleName}")
            }
        }

        override fun close() = Unit
    }
}
