package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChatUploadedPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatReactionsAll
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatReactionsSome
import org.monogram.mtproto.tl.generated.cloud.layer223.ReactionEmoji
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditTitle
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.UpdateUsername
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleAntiSpam
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleJoinRequest
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleJoinToSend
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleParticipantsHidden
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleSlowMode
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleForum
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.ToggleSignatures
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatAbout
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatTitle
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SetChatAvailableReactions
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ToggleNoForwards

internal interface MtProtoChatSettingsRepository {
    suspend fun setPhoto(chatId: Long, photoPath: String)
    suspend fun setTitle(chatId: Long, title: String)
    suspend fun setDescription(chatId: Long, description: String)
    suspend fun setUsername(chatId: Long, username: String)
    suspend fun setSlowModeDelay(chatId: Long, seconds: Int)
    suspend fun setParticipantsHidden(chatId: Long, enabled: Boolean)
    suspend fun setAntiSpamEnabled(chatId: Long, enabled: Boolean)
    suspend fun setJoinToSend(chatId: Long, enabled: Boolean)
    suspend fun setJoinByRequest(chatId: Long, enabled: Boolean)
    suspend fun setSignMessages(chatId: Long, enabled: Boolean)
    suspend fun setForumEnabled(chatId: Long, enabled: Boolean)
    suspend fun setAvailableReactions(chatId: Long, reactions: List<String>)
    suspend fun setProtectedContent(chatId: Long, enabled: Boolean)
}

internal class MtProtoChatSettingsRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val uploader: MtProtoFileUploader,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val cloudObjectStager: MtProtoCloudObjectStager,
    private val accountSlot: String = "default",
) : MtProtoChatSettingsRepository {
    override suspend fun setPhoto(chatId: Long, photoPath: String) {
        val (scope, peer) = resolve(chatId)
        val photo = InputChatUploadedPhoto(uploader.upload(photoPath), null, null, null)
        transportFactory.open(accountSlot).use { transport ->
            val updates = when (peer.type) {
                DialogPeerType.BASIC_GROUP -> transport.execute(EditChatPhoto(peer.id, photo))
                DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                    val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                        "Missing MTProto channel access hash: ${peer.id}"
                    }
                    transport.execute(EditPhoto(InputChannel_d22292516d(peer.id, accessHash), photo))
                }
                DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto cannot edit this chat photo")
            }
            cloudObjectStager.stageLive(scope, updates)
        }
    }

    override suspend fun setTitle(chatId: Long, title: String) {
        require(title.isNotBlank()) { "MTProto chat title must not be blank" }
        val (scope, peer) = resolve(chatId)
        transportFactory.open(accountSlot).use { transport ->
            val updates = when (peer.type) {
                DialogPeerType.BASIC_GROUP -> transport.execute(EditChatTitle(peer.id, title))
                DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                    val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                        "Missing MTProto channel access hash: ${peer.id}"
                    }
                    transport.execute(EditTitle(InputChannel_d22292516d(peer.id, accessHash), title))
                }
                DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto cannot edit this chat title")
            }
            cloudObjectStager.stageLive(scope, updates)
        }
    }

    override suspend fun setUsername(chatId: Long, username: String) {
        val (scope, peer) = resolve(chatId)
        require(peer.type == DialogPeerType.SUPERGROUP || peer.type == DialogPeerType.CHANNEL) {
            "MTProto usernames require a supergroup or channel"
        }
        val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
            "Missing MTProto channel access hash: ${peer.id}"
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(UpdateUsername(InputChannel_d22292516d(peer.id, accessHash), username))) {
                "channels.updateUsername was rejected"
            }
        }
    }

    override suspend fun setSlowModeDelay(chatId: Long, seconds: Int) {
        require(seconds >= 0) { "MTProto slow mode delay must not be negative" }
        mutateChannel(chatId) { channel -> ToggleSlowMode(channel, seconds) }
    }

    override suspend fun setParticipantsHidden(chatId: Long, enabled: Boolean) =
        mutateChannel(chatId) { channel -> ToggleParticipantsHidden(channel, enabled) }

    override suspend fun setAntiSpamEnabled(chatId: Long, enabled: Boolean) =
        mutateChannel(chatId) { channel -> ToggleAntiSpam(channel, enabled) }

    override suspend fun setJoinToSend(chatId: Long, enabled: Boolean) =
        mutateChannel(chatId) { channel -> ToggleJoinToSend(channel, enabled) }

    override suspend fun setJoinByRequest(chatId: Long, enabled: Boolean) =
        mutateChannel(chatId) { channel -> ToggleJoinRequest(channel, enabled) }

    override suspend fun setSignMessages(chatId: Long, enabled: Boolean) {
        val (scope, peer, chat) = projectedChannel(chatId)
        require(peer.type == DialogPeerType.CHANNEL) { "MTProto signatures require a channel" }
        mutate(scope, peer.id, chat.accessHash, ToggleSignatures(enabled, chat.signatureProfilesEnabled, InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash))))
    }

    override suspend fun setForumEnabled(chatId: Long, enabled: Boolean) {
        val (scope, peer, chat) = projectedChannel(chatId)
        require(peer.type == DialogPeerType.SUPERGROUP) { "MTProto forums require a supergroup" }
        mutate(scope, peer.id, chat.accessHash, ToggleForum(InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash)), enabled, chat.forumTabs))
    }

    override suspend fun setAvailableReactions(chatId: Long, reactions: List<String>) {
        val (scope, peer) = resolve(chatId)
        val inputPeer = when (peer.type) {
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                    "Missing MTProto channel access hash: ${peer.id}"
                }
                InputPeerChannel(peer.id, accessHash)
            }
            DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto cannot set reactions for this peer")
        }
        val availableReactions: org.monogram.mtproto.tl.generated.cloud.layer223.ChatReactions =
            if (reactions.isEmpty()) ChatReactionsAll(allowCustom = false) else ChatReactionsSome(reactions.map(::ReactionEmoji))
        transportFactory.open(accountSlot).use { transport ->
            cloudObjectStager.stageLive(scope, transport.execute(SetChatAvailableReactions(inputPeer, availableReactions, null, null)))
        }
    }

    override suspend fun setProtectedContent(chatId: Long, enabled: Boolean) {
        val (scope, peer) = resolve(chatId)
        val inputPeer = when (peer.type) {
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                    "Missing MTProto channel access hash: ${peer.id}"
                }
                InputPeerChannel(peer.id, accessHash)
            }
            DialogPeerType.PRIVATE -> {
                val accessHash = requireNotNull(users.get(scope, peer.id)?.accessHash) {
                    "Missing MTProto user access hash: ${peer.id}"
                }
                InputPeerUser(peer.id, accessHash)
            }
            DialogPeerType.UNKNOWN -> error("MTProto cannot set protected content for this peer")
        }
        transportFactory.open(accountSlot).use { transport ->
            cloudObjectStager.stageLive(scope, transport.execute(ToggleNoForwards(inputPeer, enabled, null)))
        }
    }

    override suspend fun setDescription(chatId: Long, description: String) {
        val (scope, peer) = resolve(chatId)
        val inputPeer = when (peer.type) {
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                    "Missing MTProto channel access hash: ${peer.id}"
                }
                InputPeerChannel(peer.id, accessHash)
            }
            DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto cannot edit this chat description")
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(EditChatAbout(inputPeer, description))) {
                "messages.editChatAbout was rejected"
            }
        }
    }

    private suspend fun mutate(
        scope: MtProtoAuthKeyScope,
        peerId: Long,
        accessHash: Long?,
        request: org.monogram.mtproto.tl.runtime.TlMethod<org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5>,
    ) {
        requireNotNull(accessHash) { "Missing MTProto channel access hash: $peerId" }
        transportFactory.open(accountSlot).use { transport ->
            cloudObjectStager.stageLive(scope, transport.execute(request))
        }
    }

    private suspend fun projectedChannel(chatId: Long): Triple<MtProtoAuthKeyScope, TelegramPeerChatId.Peer, MtProtoChatReadModel> {
        val (scope, peer) = resolve(chatId)
        val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
        return Triple(scope, peer, chat)
    }

    private suspend fun mutateChannel(
        chatId: Long,
        request: (InputChannel_d22292516d) -> org.monogram.mtproto.tl.runtime.TlMethod<org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5>,
    ) {
        val (scope, peer) = resolve(chatId)
        require(peer.type == DialogPeerType.SUPERGROUP || peer.type == DialogPeerType.CHANNEL) {
            "MTProto setting requires a supergroup or channel"
        }
        val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
            "Missing MTProto channel access hash: ${peer.id}"
        }
        transportFactory.open(accountSlot).use { transport ->
            cloudObjectStager.stageLive(scope, transport.execute(request(InputChannel_d22292516d(peer.id, accessHash))))
        }
    }

    private suspend fun resolve(chatId: Long): Pair<MtProtoAuthKeyScope, TelegramPeerChatId.Peer> {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val isChannel = if (chatId <= -CHANNEL_OFFSET - 1L) {
            chats.get(scope, -(chatId + CHANNEL_OFFSET))?.type == MtProtoChatType.CHANNEL
        } else {
            false
        }
        return scope to TelegramPeerChatId.decode(chatId, isChannel)
    }

    private companion object {
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
