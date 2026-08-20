package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.repository.LinkParser
import org.monogram.domain.repository.LinkAction
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatInviteAlready
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatPhotoEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatInvite_e5c19696c2
import org.monogram.mtproto.tl.generated.cloud.layer223.PhotoEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_65eab3b078
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolveUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.CheckChatInvite
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.ResolvedPeer_28e60b6802
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoLinkHandlerTest {
    @Test
    fun `resolves public username through owned transport`() = runTest {
        val transport = RecordingTransport()
        val handler = MtProtoLinkHandlerImpl(
            parser = LinkParser(),
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertEquals(LinkAction.OpenUser(7), handler.handle("https://t.me/alice"))
        assertEquals("alice", (transport.request as ResolveUsername).username)
        assertTrue(transport.closed)
    }

    @Test
    fun `confirms an unjoined invite without fabricating an avatar path`() = runTest {
        val transport = RecordingTransport(
            ChatInvite_e5c19696c2(
                channel = true, broadcast = true, public_ = false, megagroup = false,
                requestNeeded = false, verified = false, scam = false, fake = false,
                canRefulfillSubscription = false, title = "Channel", about = "Description",
                photo = PhotoEmpty(0), participantsCount = 42, participants = null, color = 0,
                subscriptionPricing = null, subscriptionFormId = null, botVerification = null,
            ),
        )
        val handler = MtProtoLinkHandlerImpl(
            parser = LinkParser(),
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = NoOpMtProtoChatProjectionStore,
        )

        assertEquals(
            LinkAction.ConfirmJoinInviteLink("https://t.me/+invite", "Channel", "Description", 42, null, true),
            handler.handle("https://t.me/+invite"),
        )
        assertEquals(CheckChatInvite("invite"), transport.request)
        assertTrue(transport.closed)
    }

    @Test
    fun `opens an already joined invite using the returned chat`() = runTest {
        val transport = RecordingTransport(ChatInviteAlready(chat()))
        val chats = RecordingChats()
        val handler = MtProtoLinkHandlerImpl(
            parser = LinkParser(),
            configSource = TelegramMtProtoBootstrapConfigSource { config() },
            transportFactory = MtProtoSessionTransportFactory { transport },
            users = NoOpMtProtoUserProjectionStore,
            chats = chats,
        )

        assertEquals(LinkAction.OpenChat(-9), handler.handle("https://t.me/+invite"))
        assertEquals(CheckChatInvite("invite"), transport.request)
        assertEquals(1, chats.upserted)
        assertTrue(transport.closed)
    }

    private fun chat() = Chat_65eab3b078(
        creator = false, left = false, deactivated = false, callActive = false, callNotEmpty = false,
        noforwards = false, id = 9, title = "Group", photo = ChatPhotoEmpty, participantsCount = 1,
        date = 0, version = 0, migratedTo = null, adminRights = null, defaultBannedRights = null,
    )

    private class RecordingTransport(private val response: Any = ResolvedPeer_28e60b6802(PeerUser(7), emptyList(), emptyList())) : MtProtoRpcTransport {
        lateinit var request: TlMethod<*>
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method
            return response as R
        }
        override fun close() { closed = true }
    }

    private class RecordingChats : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        var upserted = 0
        override suspend fun upsert(scope: MtProtoAuthKeyScope, chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>) {
            upserted += chats.size
        }
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )
}
