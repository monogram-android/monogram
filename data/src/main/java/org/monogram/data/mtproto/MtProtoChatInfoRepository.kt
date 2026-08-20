package org.monogram.data.mtproto

import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatFull_af753dccbf
import org.monogram.mtproto.tl.generated.cloud.layer223.ChannelFull
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_97ab856701
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ChatFull_86a406fd8f
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetFullChat
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.GetFullChannel

internal class MtProtoChatInfoRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory?,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : ChatInfoRepository {
    override suspend fun getChatFullInfo(chatId: Long): ChatFullInfoModel? {
        val peer = TelegramPeerChatId.decode(chatId)
        if (peer.type == org.monogram.domain.models.DialogPeerType.PRIVATE) return null
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

    override suspend fun searchPublicChat(username: String): ChatModel? = unsupported()
    override suspend fun getSimilarChatIds(chatId: Long): List<Long> = unsupported()
    override suspend fun getChatMembers(chatId: Long, offset: Int, limit: Int, filter: ChatMembersFilter): List<GroupMemberModel> = unsupported()
    override suspend fun getChatMember(chatId: Long, userId: Long): GroupMemberModel? = unsupported()
    override suspend fun setChatMemberStatus(chatId: Long, userId: Long, status: ChatMemberStatus) = unsupported()

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.StickerSet_e88393a32f.stickerSetId(): Long =
        (this as? StickerSet_97ab856701)?.id ?: 0L

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipants_798a3d6b54.participantCount(): Int = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipants_4110fea440 -> participants.size
        is org.monogram.mtproto.tl.generated.cloud.layer223.ChatParticipantsForbidden -> 0
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto chat info operation is not available")
    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
