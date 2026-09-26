package org.monogram.network.bridge.message

import org.monogram.core.models.MessageViewer
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.pollOptionHex
import uniffi.monogram_mtproto.OutboxReadDto
import uniffi.monogram_mtproto.PollVoterDto
import uniffi.monogram_mtproto.ReactionPeerDto
import uniffi.monogram_mtproto.ReadParticipantsDto
import uniffi.monogram_mtproto.ReadReceiptConfigDto

internal fun readParticipantsOutcome(dto: ReadParticipantsDto, played: Boolean): MessageViewers =
    when (dto.error) {
        null -> MessageViewers.Ready(
            viewers = dto.participants.map {
                MessageViewer(
                    peerId = PeerId(it.peerId),
                    date = it.date.toLong()
                )
            },
            played = played,
        )

        "MSG_TOO_OLD" -> MessageViewers.Expired
        "CHAT_TOO_BIG" -> MessageViewers.TooBig
        else -> MessageViewers.Unavailable
    }

internal fun outboxReadOutcome(dto: OutboxReadDto): OutboxReadState = when (dto.error) {
    null -> if (dto.date > 0) OutboxReadState.Read(dto.date.toLong()) else OutboxReadState.Unavailable
    "MESSAGE_NOT_READ_YET" -> OutboxReadState.Unread
    "MESSAGE_TOO_OLD" -> OutboxReadState.Expired
    "USER_PRIVACY_RESTRICTED" -> OutboxReadState.PeerPrivacyHidden
    "YOUR_PRIVACY_RESTRICTED" -> OutboxReadState.MyPrivacyHidden
    else -> OutboxReadState.Unavailable
}

internal fun ReadReceiptConfigDto.toModel(): ReadReceiptConfig = ReadReceiptConfig(
    chatReadMarkSizeThreshold = chatReadMarkSizeThreshold,
    chatReadMarkExpirePeriod = chatReadMarkExpirePeriod,
    pmReadDateExpirePeriod = pmReadDateExpirePeriod,
    fromServer = fromServer,
)

internal fun ReactionPeerDto.toViewer(): MessageViewer = MessageViewer(
    peerId = PeerId(peerId),
    date = date.toLong(),
    title = title.ifBlank { null },
    avatarCacheKey = peerAvatarCacheKey(PeerId(peerId)),
    emoticon = emoticon.ifBlank { null },
    documentId = documentId.takeIf { it != 0L },
)

internal fun PollVoterDto.toViewer(): MessageViewer = MessageViewer(
    peerId = PeerId(peerId),
    date = date.toLong(),
    title = title.ifBlank { null },
    avatarCacheKey = peerAvatarCacheKey(PeerId(peerId)),
    pollOptionHex = options.map(::pollOptionHex).filter { it.isNotEmpty() },
)

internal fun attachViewerProfiles(
    viewers: List<MessageViewer>,
    members: List<ProfileMember>,
): List<MessageViewer> {
    if (viewers.isEmpty() || members.isEmpty()) return viewers
    val byId = members.associateBy { it.id.value }
    return viewers.map { viewer ->
        val member = byId[viewer.peerId.value] ?: return@map viewer
        viewer.copy(title = member.title, avatarCacheKey = member.avatarCacheKey)
    }
}
