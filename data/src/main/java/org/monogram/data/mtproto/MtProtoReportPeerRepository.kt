package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonChildAbuse
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonCopyright
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonFake
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonGeoIrrelevant
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonIllegalDrugs
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonOther
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonPersonalDetails
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonPornography
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonSpam
import org.monogram.mtproto.tl.generated.cloud.layer223.InputReportReasonViolence
import org.monogram.mtproto.tl.generated.cloud.layer223.ReportReason
import org.monogram.mtproto.tl.generated.cloud.layer223.account.ReportPeer

internal fun interface MtProtoReportPeerRepository {
    suspend fun report(chatIds: Set<Long>, reason: String, messageIds: List<Long>)
}

internal class MtProtoReportPeerRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoReportPeerRepository {
    override suspend fun report(chatIds: Set<Long>, reason: String, messageIds: List<Long>) {
        require(messageIds.isEmpty()) { "MTProto message reports require an interactive report flow" }
        if (chatIds.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            chatIds.forEach { chatId ->
                transport.execute(ReportPeer(resolvePeer(scope, chatId), reason.toReportReason(), reason.takeUnless { it.isPredefinedReason() }.orEmpty()))
            }
        } finally {
            transport.close()
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto chat access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("Cannot report an unknown peer")
        }
    }

    private fun String.toReportReason(): ReportReason = when (lowercase()) {
        "spam" -> InputReportReasonSpam
        "violence" -> InputReportReasonViolence
        "pornography" -> InputReportReasonPornography
        "child_abuse" -> InputReportReasonChildAbuse
        "copyright" -> InputReportReasonCopyright
        "unrelated_location" -> InputReportReasonGeoIrrelevant
        "fake" -> InputReportReasonFake
        "illegal_drugs" -> InputReportReasonIllegalDrugs
        "personal_details" -> InputReportReasonPersonalDetails
        else -> InputReportReasonOther
    }

    private fun String.isPredefinedReason() = lowercase() in PREDEFINED_REASONS

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        val PREDEFINED_REASONS = setOf("spam", "violence", "pornography", "child_abuse", "copyright", "unrelated_location", "fake", "illegal_drugs", "personal_details")
    }
}
