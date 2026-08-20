package org.monogram.data.mtproto

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatAbout
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatTitle
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleForum
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleSignatures
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatReactionsAll
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatReactionsSome
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SetChatAvailableReactions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ToggleNoForwards
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_ef0db4e0fa
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoChatSettingsRepositoryTest {
    @Test
    fun `uploads and sets basic group photo through authoritative updates`() = runTest {
        val transport = RecordingTransport()
        val stager = RecordingStager()
        val repository = repository(transport, stager, uploader = RecordingUploader)

        repository.setPhoto(-42, "avatar.jpg")

        val request = transport.requests.single() as EditChatPhoto
        assertEquals(42L, request.chatId)
        assertEquals(RecordingUploader.file, (request.photo as org.monogram.mtproto.tl.generated.cloud.layer223.InputChatUploadedPhoto).file_)
        assertEquals(1, stager.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `edits basic group title and stages authoritative updates`() = runTest {
        val transport = RecordingTransport()
        val stager = RecordingStager()
        val repository = repository(transport, stager)

        repository.setTitle(-42, "Team")

        val request = transport.requests.single() as EditChatTitle
        assertEquals(42L, request.chatId)
        assertEquals("Team", request.title)
        assertEquals(1, stager.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `edits basic group description without fabricating projection`() = runTest {
        val transport = RecordingTransport()
        val stager = RecordingStager()
        val repository = repository(transport, stager)

        repository.setDescription(-42, "Owned MTProto")

        val request = transport.requests.single() as EditChatAbout
        assertEquals("Owned MTProto", request.about)
        assertEquals(0, stager.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `preserves projected signature profile setting`() = runTest {
        val transport = RecordingTransport()
        val repository = repository(transport, RecordingStager(), chats = channel(MtProtoChatType.CHANNEL, signatureProfiles = true))

        repository.setSignMessages(TelegramPeerChatId.encode(DialogPeerType.CHANNEL, 42), true)

        val request = transport.requests.single() as ToggleSignatures
        assertTrue(request.signaturesEnabled)
        assertTrue(request.profilesEnabled)
    }

    @Test
    fun `preserves projected forum tabs setting`() = runTest {
        val transport = RecordingTransport()
        val repository = repository(transport, RecordingStager(), chats = channel(MtProtoChatType.SUPERGROUP, forumTabs = true))

        repository.setForumEnabled(TelegramPeerChatId.encode(DialogPeerType.SUPERGROUP, 42), true)

        val request = transport.requests.single() as ToggleForum
        assertTrue(request.enabled)
        assertTrue(request.tabs)
    }

    @Test
    fun `sets protected content for projected private chat`() = runTest {
        val transport = RecordingTransport()
        val repository = repository(transport, RecordingStager(), users = user(77, 123))

        repository.setProtectedContent(77, true)

        val request = transport.requests.single() as ToggleNoForwards
        assertEquals(
            org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser(77, 123),
            request.peer,
        )
        assertTrue(request.enabled)
        assertTrue(transport.closed)
    }

    @Test
    fun `sets protected content and stages authoritative updates`() = runTest {
        val transport = RecordingTransport()
        val stager = RecordingStager()
        val repository = repository(transport, stager)

        repository.setProtectedContent(-42, true)

        val request = transport.requests.single() as ToggleNoForwards
        assertEquals(42L, (request.peer as org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat).chatId)
        assertTrue(request.enabled)
        assertEquals(null, request.requestMsgId)
        assertEquals(1, stager.calls)
        assertTrue(transport.closed)
    }

    @Test
    fun `maps configured emoji reactions and empty list to protocol semantics`() = runTest {
        val transport = RecordingTransport()
        val repository = repository(transport, RecordingStager())

        repository.setAvailableReactions(-42, listOf("👍"))
        repository.setAvailableReactions(-42, emptyList())

        val some = transport.requests[0] as SetChatAvailableReactions
        val all = transport.requests[1] as SetChatAvailableReactions
        assertEquals(listOf("👍"), (some.availableReactions as ChatReactionsSome).reactions.map { (it as org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji).emoticon })
        assertEquals(false, (all.availableReactions as ChatReactionsAll).allowCustom)
    }

    private fun repository(
        transport: RecordingTransport,
        stager: RecordingStager,
        uploader: MtProtoFileUploader = MtProtoFileUploader { error("unexpected upload") },
        users: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
        chats: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    ) = MtProtoChatSettingsRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        uploader = uploader,
        users = users,
        chats = chats,
        cloudObjectStager = stager,
    )

    private fun user(userId: Long, accessHash: Long) = object : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long) = MtProtoUserReadModel(
            userId = userId,
            accessHash = accessHash,
            firstName = null,
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
    }

    private fun channel(type: MtProtoChatType, signatureProfiles: Boolean = false, forumTabs: Boolean = false) = object : MtProtoChatProjectionStore by NoOpMtProtoChatProjectionStore {
        override suspend fun get(scope: MtProtoAuthKeyScope, chatId: Long) = MtProtoChatReadModel(
            chatId = chatId,
            type = type,
            accessHash = 99L,
            title = "Group",
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
            signatureProfilesEnabled = signatureProfiles,
            forumTabs = forumTabs,
            isMin = false,
        )
    }

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private object RecordingUploader : MtProtoFileUploader {
        val file = InputFile_ef0db4e0fa(1L, 1, "avatar.jpg", "checksum")
        override suspend fun upload(path: String) = file
    }

    private class RecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return when (method) {
                is EditChatAbout -> true as R
                else -> UpdatesTooLong as R
            }
        }
        override fun close() { closed = true }
    }

    private class RecordingStager : MtProtoCloudObjectStager by NoOpMtProtoCloudObjectStager {
        var calls = 0
        override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5) {
            calls++
        }
    }
}
