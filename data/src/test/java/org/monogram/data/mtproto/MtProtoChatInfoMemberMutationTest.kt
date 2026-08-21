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
    fun `resolves private chat full info via users getFullUser`() = runTest {
        val userFull = org.monogram.mtproto.tl.generated.cloud.layer223.UserFull_c1c6b6f92b(
            blocked = true,
            phoneCallsAvailable = false,
            phoneCallsPrivate = false,
            canPinMessage = false,
            hasScheduled = false,
            videoCallsAvailable = false,
            voiceMessagesForbidden = false,
            translationsDisabled = false,
            storiesPinnedAvailable = false,
            blockedMyStoriesFrom = false,
            wallpaperOverridden = false,
            contactRequirePremium = false,
            readDatesPrivate = false,
            sponsoredEnabled = false,
            canViewRevenue = false,
            botCanManageEmojiStatus = false,
            displayGiftsButton = false,
            noforwardsMyEnabled = false,
            noforwardsPeerEnabled = false,
            id = 42L,
            about = "bio",
            settings = org.monogram.mtproto.tl.generated.cloud.layer223.PeerSettings_936a3e31f4(
                reportSpam = false, addContact = false, blockContact = false, shareContact = false,
                needContactsException = false, reportGeo = false, autoarchived = false, inviteMembers = false,
                requestChatBroadcast = false, businessBotPaused = false, businessBotCanReply = false,
                geoDistance = null, requestChatTitle = null, requestChatDate = null, businessBotId = null,
                businessBotManageUrl = null, chargePaidMessageStars = null, registrationMonth = null,
                phoneCountry = null, nameChangeDate = null, photoChangeDate = null,
            ),
            personalPhoto = null,
            profilePhoto = null,
            fallbackPhoto = null,
            notifySettings = org.monogram.mtproto.tl.generated.cloud.layer223.PeerNotifySettings_474d6bbc59(
                showPreviews = null, silent = null, muteUntil = null, iosSound = null,
                androidSound = null, otherSound = null, storiesMuted = null, storiesHideSender = null,
                storiesIosSound = null, storiesAndroidSound = null, storiesOtherSound = null,
            ),
            botInfo = null,
            pinnedMsgId = null,
            commonChatsCount = 3,
            folderId = null,
            ttlPeriod = null,
            theme = null,
            privateForwardName = null,
            botGroupAdminRights = null,
            botBroadcastAdminRights = null,
            wallpaper = null,
            stories = null,
            businessWorkHours = null,
            businessLocation = null,
            businessGreetingMessage = null,
            businessAwayMessage = null,
            businessIntro = null,
            birthday = null,
            personalChannelId = null,
            personalChannelMessage = null,
            stargiftsCount = 7,
            starrefProgram = null,
            botVerification = null,
            sendPaidMessagesStars = null,
            disallowedGifts = null,
            starsRating = null,
            starsMyPendingRating = null,
            starsMyPendingRatingDate = null,
            mainTab = null,
            savedMusic = null,
            note = null,
        )
        val transport = RecordingTransport(
            org.monogram.mtproto.tl.generated.cloud.layer223.users.UserFull_a7968baaa4(userFull, emptyList(), emptyList()),
        )
        val repository = repository(transport, MtProtoChatType.BASIC_GROUP)

        val info = repository.getChatFullInfo(42L)

        assertEquals("bio", info?.description)
        assertTrue(info!!.isBlocked)
        assertEquals(3, info.commonGroupsCount)
        assertEquals(7, info.giftCount)
    }

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
