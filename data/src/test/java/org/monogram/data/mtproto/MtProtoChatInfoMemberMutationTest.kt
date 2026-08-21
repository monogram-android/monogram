package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.repository.ChatMemberStatus.Administrator
import org.monogram.domain.repository.ChatMemberStatus.Member
import org.monogram.domain.repository.ChatMemberStatus.Banned
import org.monogram.domain.repository.ChatMemberStatus.Restricted
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditAdmin
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditBanned
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatAdmin
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoChatInfoMemberMutationTest {
    @Test
    fun `maps basic group member and admin changes`() = runTest {
        val transport = RecordingTransport(true)
        val repository = repository(transport, MtProtoChatType.BASIC_GROUP)

        repository.setChatMemberStatus(-42, 7, Administrator(customTitle = "Mod"))
        assertEquals(EditChatAdmin(42, org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812(7, 9), true), transport.request)

        repository.setChatMemberStatus(-42, 7, Member)
        assertEquals(EditChatAdmin(42, org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812(7, 9), false), transport.request)
    }

    @Test
    fun `rejects non-representable reaction permissions`() = runTest {
        val repository = repository(RecordingTransport(UpdatesTooLong), MtProtoChatType.SUPERGROUP)

        val failure = runCatching {
            repository.setChatMemberStatus(
                -1_000_000_000_042L,
                7,
                Restricted(restrictedUntilDate = 123, permissions = ChatPermissionsModel(canReactToMessages = true)),
            )
        }.exceptionOrNull()

        assertEquals("MTProto cannot represent reaction permissions", failure?.message)
    }

    @Test
    fun `stages channel administrator updates`() = runTest {
        val transport = RecordingTransport(UpdatesTooLong)
        val stager = RecordingStager()
        val repository = repository(transport, MtProtoChatType.CHANNEL, stager)

        repository.setChatMemberStatus(-1_000_000_000_042L, 7, Administrator())

        assertTrue(transport.request is EditAdmin)
        assertEquals(1, stager.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `maps channel restrictions and bans to inverted rights`() = runTest {
        val transport = RecordingTransport(UpdatesTooLong)
        val repository = repository(transport, MtProtoChatType.SUPERGROUP)

        repository.setChatMemberStatus(
            -1_000_000_000_042L,
            7,
            Restricted(
                restrictedUntilDate = 123,
                permissions = ChatPermissionsModel(
                    canSendBasicMessages = false,
                    canSendPhotos = false,
                    canSendVideos = true,
                    canAddLinkPreviews = false,
                ),
            ),
        )
        val restricted = (transport.request as EditBanned).bannedRights as org.monogram.mtproto.tl.generated.cloud.layer223.ChatBannedRights_2339df02a7
        assertTrue(restricted.sendMessages)
        assertEquals(false, restricted.sendMedia)
        assertTrue(restricted.sendPhotos)
        assertEquals(false, restricted.sendVideos)
        assertTrue(restricted.embedLinks)
        assertEquals(123, restricted.untilDate)

        repository.setChatMemberStatus(-1_000_000_000_042L, 7, Banned(456))
        val banned = (transport.request as EditBanned).bannedRights as org.monogram.mtproto.tl.generated.cloud.layer223.ChatBannedRights_2339df02a7
        assertTrue(banned.viewMessages)
        assertTrue(banned.sendMessages)
        assertEquals(456, banned.untilDate)
    }

    private fun repository(
        transport: RecordingTransport,
        type: MtProtoChatType,
        stager: RecordingStager = RecordingStager(),
    ) = MtProtoChatInfoRepository(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        chats = object : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
            override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long) = MtProtoChatReadModel(
                chatId = chatId,
                type = type,
                accessHash = 11,
                title = "Chat",
                username = null,
                participantsCount = null,
                isDeleted = false,
                isForbidden = false,
                isLeft = false,
                isDeactivated = false,
                isVerified = false,
                isRestricted = false,
                isScam = false,
                isFake = false,
                isForum = false,
                signaturesEnabled = false,
                signatureProfilesEnabled = false,
                forumTabs = false,
                isMin = false,
            )
        },
        users = object : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
            override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
                userId = userId,
                accessHash = 9,
                firstName = "User",
                lastName = null,
                username = null,
                phone = null,
                isSelf = false,
                isContact = false,
                isMutualContact = false,
                isDeleted = false,
                isBot = false,
                isVerified = false,
                isRestricted = false,
                isScam = false,
                isFake = false,
                isPremium = false,
                isMin = false,
            )
        },
        cloudObjectStager = stager,
    )

    private class RecordingTransport(private val response: Any) : MtProtoRpcTransport {
        lateinit var request: TlMethod<*>
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method
            return response as R
        }
        override fun close() { closed = true }
    }

    private class RecordingStager : MtProtoCloudObjectStager by NoOpMtProtoCloudObjectStager {
        var calls = 0
        override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5) {
            calls++
        }
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        TelegramMtProtoEndpoint(2, "dc", 443),
        MtProtoHandshakeConfig(2, listOf("key")),
        CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )
}
