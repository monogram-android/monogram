package org.monogram.data.mtproto

import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.ChatMemberStatus.Administrator
import org.monogram.domain.repository.ChatMemberStatus.Banned
import org.monogram.domain.repository.ChatMemberStatus.Restricted as RestrictedStatus
import org.monogram.domain.repository.ChatMemberStatus.Creator
import org.monogram.domain.repository.ChatMemberStatus.Left
import org.monogram.domain.repository.ChatMemberStatus.Member
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.domain.repository.ChatMembersFilter.Administrators
import org.monogram.domain.repository.ChatMembersFilter.Banned as BannedFilter
import org.monogram.domain.repository.ChatMembersFilter.Bots
import org.monogram.domain.repository.ChatMembersFilter.Recent
import org.monogram.domain.repository.ChatMembersFilter.Restricted
import org.monogram.domain.repository.ChatMembersFilter.Search
import org.monogram.mtproto.tl.generated.cloud.layer223.Birthday_aa6c995ca2
import org.monogram.mtproto.tl.generated.cloud.layer223.TextWithEntities_d094604bd3
import org.monogram.mtproto.tl.generated.cloud.layer223.UserFull_c1c6b6f92b
import org.monogram.mtproto.tl.generated.cloud.layer223.users.GetFullUser
import org.monogram.mtproto.tl.generated.cloud.layer223.users.UserFull_a7968baaa4
import org.monogram.domain.models.BirthdateModel
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantAdmin
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantBanned
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantCreator
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantLeft
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipant_6287cfc333
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipant_fa363e4647
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantsAdmins
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantsBanned
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantsBots
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantsKicked
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantsRecent
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipantsSearch
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ChannelParticipants_cbdd012578
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.GetParticipants
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.GetChannelRecommendations
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatFull_af753dccbf
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelFull
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatAdminRights_6ef21779c3
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatBannedRights_2339df02a7
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ChatFull_86a406fd8f
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatAdmin
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetFullChat
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Chats_1cc0cbc238
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ChatsSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditAdmin
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditBanned
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.GetFullChannel

internal class MtProtoChatInfoRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory?,
    private val chats: MtProtoChatProjectionStore,
    private val users: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
    private val search: ChatSearchRepository? = null,
    private val cloudObjectStager: MtProtoCloudObjectStager = NoOpMtProtoCloudObjectStager,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : ChatInfoRepository {
    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel? {
        val peer = TelegramPeerChatId.decode(chatId)
        if (peer.type == org.monogram.domain.models.DialogPeerType.PRIVATE) return userFullInfo(peer.id)
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
        val transport = requireNotNull(transportFactory) { "MTProto chat info transport is unavailable" }.open(accountSlot)
        val result = try {
            when (chat.type) {
                MtProtoChatType.BASIC_GROUP -> transport.execute(GetFullChat(peer.id))
                MtProtoChatType.SUPERGROUP,
                MtProtoChatType.CHANNEL -> transport.execute(
                    GetFullChannel(InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash)))
                )
            }
        } finally {
            transport.close()
        }
        val full = (result as ChatFull_86a406fd8f).fullChat
        return when (full) {
            is ChatFull_af753dccbf -> ChatFullInfoModel(
                description = full.about,
                memberCount = full.participants.participantCount(),
                canGetMembers = true,
                isAllHistoryAvailable = true,
            )
            is ChannelFull -> ChatFullInfoModel(
                description = full.about,
                memberCount = full.participantsCount ?: 0,
                onlineCount = full.onlineCount ?: 0,
                administratorCount = full.adminsCount ?: 0,
                restrictedCount = full.kickedCount ?: 0,
                bannedCount = full.bannedCount ?: 0,
                isBlocked = full.blocked,
                canGetMembers = full.canViewParticipants,
                canGetStatistics = full.canViewStats,
                canSetLocation = full.canSetLocation,
                hasHiddenMembers = full.participantsHidden,
                linkedChatId = full.linkedChatId ?: 0L,
                slowModeDelay = full.slowmodeSeconds ?: 0,
                canSetStickerSet = full.canSetStickers,
                isAllHistoryAvailable = !full.hiddenPrehistory,
                hasAggressiveAntiSpamEnabled = full.antispam,
                hasPaidMediaAllowed = full.paidMediaAllowed,
                stickerSetId = full.stickerset?.stickerSetId() ?: 0L,
                customEmojiStickerSetId = full.emojiset?.stickerSetId() ?: 0L,
                myBoostCount = full.boostsApplied ?: 0,
                unrestrictBoostCount = full.boostsUnrestrict ?: 0,
                directMessagesChatId = full.linkedChatId ?: 0L,
            )
        }
    }

    private suspend fun userFullInfo(userId: Long): ChatFullInfoModel? {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val accessHash = users.get(scope, userId)?.accessHash ?: return null
        val transport = requireNotNull(transportFactory) { "MTProto chat info transport is unavailable" }.open(accountSlot)
        val result = try {
            transport.execute(GetFullUser(InputUser_4020eae812(userId, accessHash)))
        } finally {
            transport.close()
        }
        val full = (result as UserFull_a7968baaa4).fullUser as UserFull_c1c6b6f92b
        return ChatFullInfoModel(
            description = full.about,
            isBlocked = full.blocked,
            commonGroupsCount = full.commonChatsCount,
            giftCount = full.stargiftsCount ?: 0,
            birthdate = (full.birthday as? Birthday_aa6c995ca2)?.let { BirthdateModel(day = it.day, month = it.month, year = it.year) },
            note = (full.note as? TextWithEntities_d094604bd3)?.text,
        )
    }

    override suspend fun searchPublicChat(username: String): ChatModel? {
        val searchRepository = search ?: unsupported()
        return searchRepository.searchPublicChats(username.trim()).firstOrNull()
    }
    override suspend fun getSimilarChatIds(chatId: Long): List<Long> {
        val peer = TelegramPeerChatId.decode(chatId)
        require(peer.type == org.monogram.domain.models.DialogPeerType.CHANNEL) {
            "MTProto similar-chat recommendations require a channel"
        }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
        val transport = requireNotNull(transportFactory) { "MTProto chat info transport is unavailable" }.open(accountSlot)
        try {
            val result = transport.execute(
                GetChannelRecommendations(InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash)))
            )
            val recommendations = when (result) {
                is Chats_1cc0cbc238 -> result.chats
                is ChatsSlice -> result.chats
            }
            chats.upsert(scope, recommendations)
            return recommendations.mapNotNull { recommended ->
                (recommended as? org.monogram.mtproto.tl.generated.cloud.layer223.Channel)?.id
                    ?.let { TelegramPeerChatId.encode(org.monogram.domain.models.DialogPeerType.CHANNEL, it) }
            }
        } finally {
            transport.close()
        }
    }
    override suspend fun getChatMembers(chatId: Long, offset: Int, limit: Int, filter: ChatMembersFilter): List<GroupMemberModel> {
        require(offset >= 0) { "Member offset must not be negative" }
        require(limit in 1..PAGE_SIZE) { "Member limit must be between 1 and $PAGE_SIZE" }
        val peer = TelegramPeerChatId.decode(chatId)
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = requireNotNull(transportFactory) { "MTProto chat member transport is unavailable" }.open(accountSlot)
        try {
            val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
            val records = when (chat.type) {
                MtProtoChatType.BASIC_GROUP -> basicGroupMembers(transport, scope, peer.id, offset, limit, filter)
                MtProtoChatType.SUPERGROUP, MtProtoChatType.CHANNEL -> channelMembers(transport, scope, peer.id, requireNotNull(chat.accessHash), offset, limit, filter)
            }
            return records.mapNotNull { record ->
                val user = users.get(scope, record.userId)?.toUserModel() ?: return@mapNotNull null
                GroupMemberModel(user = user, rank = record.rank, status = record.status)
            }
        } finally {
            transport.close()
        }
    }

    override suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel? =
        getChatMembers(chatId, 0, PAGE_SIZE, Recent).firstOrNull { it.user.id == userId }
    override suspend fun setChatMemberStatus(chatId: Long, userId: Long, status: ChatMemberStatus) {
        require(status is Member || status is Administrator || status is Banned || status is RestrictedStatus) {
            "MTProto chat member status is not available: ${status::class.simpleName}"
        }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val isChannelRange = chatId <= -CHANNEL_OFFSET - 1L
        val peer = TelegramPeerChatId.decode(chatId, isChannelRange)
        val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
        val user = requireNotNull(users.get(scope, userId)) { "Missing MTProto user projection: $userId" }
        val accessHash = requireNotNull(user.accessHash) { "Missing MTProto user access hash: $userId" }
        val transport = requireNotNull(transportFactory) { "MTProto chat member transport is unavailable" }.open(accountSlot)
        try {
            when (chat.type) {
                MtProtoChatType.BASIC_GROUP -> {
                    require(status is Member || status is Administrator) {
                        "MTProto basic groups cannot restrict or ban members"
                    }
                    check(
                        transport.execute(
                            EditChatAdmin(
                                peer.id,
                                InputUser_4020eae812(userId, accessHash),
                                status is Administrator,
                            )
                        )
                    ) { "MTProto basic-group member update was rejected" }
                }
                MtProtoChatType.SUPERGROUP, MtProtoChatType.CHANNEL -> {
                    val request = if (status is Administrator) {
                        val admin = status
                        EditAdmin(
                            InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash)),
                            InputUser_4020eae812(userId, accessHash),
                            ChatAdminRights_6ef21779c3(
                                admin.canChangeInfo,
                                admin.canPostMessages,
                                admin.canEditMessages,
                                admin.canDeleteMessages,
                                admin.canRestrictMembers,
                                admin.canInviteUsers,
                                admin.canPinMessages,
                                admin.canPromoteMembers,
                                admin.isAnonymous,
                                admin.canManageVideoChats,
                                admin.canManageChat,
                                admin.canManageTopics,
                                admin.canPostStories,
                                admin.canEditStories,
                                admin.canDeleteStories,
                                admin.canManageDirectMessages,
                                admin.canPromoteMembers,
                            ),
                            admin.customTitle,
                        )
                    } else {
                        val rights = when (status) {
                            Member -> ChatBannedRights_2339df02a7(
                                false, false, false, false, false, false, false, false,
                                false, false, false, false, false, false, false, false,
                                false, false, false, false, false, 0,
                            )
                            is Banned -> ChatBannedRights_2339df02a7(
                                true, true, true, true, true, true, true, true,
                                true, true, true, true, true, true, true, true,
                                true, true, true, true, true, status.bannedUntilDate,
                            )
                            is RestrictedStatus -> status.permissions.toMtProtoBannedRights(status.restrictedUntilDate)
                        }
                        EditBanned(
                            InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash)),
                            InputPeerUser(userId, accessHash),
                            rights,
                        )
                    }
                    cloudObjectStager.stageLive(scope, transport.execute(request))
                }
            }
        } finally {
            transport.close()
        }
    }

    private data class MemberRecord(val userId: Long, val rank: String?, val status: org.monogram.domain.repository.ChatMemberStatus)

    private suspend fun basicGroupMembers(
        transport: org.monogram.mtproto.transport.MtProtoRpcTransport,
        scope: MtProtoAuthKeyScope,
        chatId: Long,
        offset: Int,
        limit: Int,
        filter: ChatMembersFilter,
    ): List<MemberRecord> {
        if (offset > 0) return emptyList()
        val result = transport.execute(GetFullChat(chatId)) as ChatFull_86a406fd8f
        users.upsert(scope, result.users)
        val participants = (result.fullChat as? ChatFull_af753dccbf)?.participants
            as? org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipants_4110fea440
            ?: return emptyList()
        return participants.participants.mapNotNull { participant ->
            val basicParticipant = participant as? ChatParticipant_fa363e4647 ?: return@mapNotNull null
            val record = MemberRecord(basicParticipant.userId, basicParticipant.rank, Member)
            if (filter == Administrators && record.status !is Administrator && record.status !is Creator) null else record
        }.take(limit)
    }

    private suspend fun channelMembers(
        transport: org.monogram.mtproto.transport.MtProtoRpcTransport,
        scope: MtProtoAuthKeyScope,
        channelId: Long,
        accessHash: Long,
        offset: Int,
        limit: Int,
        filter: ChatMembersFilter,
    ): List<MemberRecord> {
        val participantFilter = when (filter) {
            Recent -> ChannelParticipantsRecent
            Administrators -> ChannelParticipantsAdmins
            BannedFilter -> ChannelParticipantsBanned("")
            Restricted -> ChannelParticipantsKicked("")
            Bots -> ChannelParticipantsBots
            is Search -> ChannelParticipantsSearch(filter.query)
        }
        val result = transport.execute(GetParticipants(
            InputChannel_d22292516d(channelId, accessHash), participantFilter, offset, limit, 0L
        )) as? ChannelParticipants_cbdd012578 ?: return emptyList()
        users.upsert(scope, result.users)
        return result.participants.map { it.toMemberRecord() }
    }

    private fun ChannelParticipant_6287cfc333.toMemberRecord() = when (this) {
        is ChannelParticipantAdmin -> MemberRecord(userId, rank, Administrator(customTitle = rank.orEmpty()))
        is ChannelParticipantCreator -> MemberRecord(userId, rank, Creator)
        is ChannelParticipantBanned -> MemberRecord((peer as? org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser)?.userId ?: 0L, rank, Banned(date))
        is ChannelParticipantLeft -> MemberRecord((peer as? org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser)?.userId ?: 0L, null, Left)
        is ChannelParticipantSelf -> MemberRecord(userId, rank, Member)
        is org.monogram.mtproto.tl.generated.cloud.layer223.ChannelParticipant_de8ee603c1 -> MemberRecord(userId, rank, Member)
    }

    private fun MtProtoUserReadModel.toUserModel() = UserModel(
        id = userId,
        firstName = firstName.orEmpty(),
        lastName = lastName,
        username = username,
        phoneNumber = phone,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isPremium = isPremium,
        isVerified = isVerified,
        isScam = isScam,
        isFake = isFake,
        type = when {
            isDeleted -> UserTypeEnum.DELETED
            isBot -> UserTypeEnum.BOT
            else -> UserTypeEnum.REGULAR
        },
    )

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_e88393a32f.stickerSetId(): Long =
        (this as? StickerSet_97ab856701)?.id ?: 0L

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipants_798a3d6b54.participantCount(): Int = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipants_4110fea440 -> participants.size
        is org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipantsForbidden -> 0
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto chat info operation is not available")
    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val CHANNEL_OFFSET = 1_000_000_000_000L
        const val PAGE_SIZE = 200
    }
}
