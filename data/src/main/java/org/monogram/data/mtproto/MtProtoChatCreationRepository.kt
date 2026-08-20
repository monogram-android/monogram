package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.Channel
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_65eab3b078
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_0bd9c3151c
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUser_4020eae812
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.CreateChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.CreateChat
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.InvitedUsers_51fedff432

internal interface MtProtoChatCreationRepository {
    suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int): Long
    suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int): Long
}

internal class MtProtoChatCreationRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val cloudObjectStager: MtProtoCloudObjectStager,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoChatCreationRepository {
    override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int): Long {
        require(title.isNotBlank()) { "MTProto group title must not be blank" }
        require(userIds.isNotEmpty()) { "MTProto group creation requires at least one user" }
        require(userIds.all { it > 0L } && userIds.distinct().size == userIds.size) {
            "MTProto group users must be distinct positive IDs"
        }
        require(messageAutoDeleteTime >= 0) { "MTProto auto-delete time must not be negative" }
        val (scope, inputUsers) = inputUsers(userIds)
        val result = transportFactory.open(accountSlot).use { transport ->
            transport.execute(CreateChat(inputUsers, title, messageAutoDeleteTime.takeIf { it > 0 }))
        } as? InvitedUsers_51fedff432 ?: error("Unsupported MTProto group creation response")
        cloudObjectStager.stageLive(scope, result.updates)
        return requireCreatedChat(result.updates, expected = DialogPeerType.BASIC_GROUP)
    }

    override suspend fun createChannel(
        title: String,
        description: String,
        isMegagroup: Boolean,
        messageAutoDeleteTime: Int,
    ): Long {
        require(title.isNotBlank()) { "MTProto channel title must not be blank" }
        require(messageAutoDeleteTime >= 0) { "MTProto auto-delete time must not be negative" }
        val scope = scope()
        val updates = transportFactory.open(accountSlot).use { transport ->
            transport.execute(
                CreateChannel(
                    broadcast = !isMegagroup,
                    megagroup = isMegagroup,
                    forImport = false,
                    forum = false,
                    title = title,
                    about = description,
                    geoPoint = null,
                    address = null,
                    ttlPeriod = messageAutoDeleteTime.takeIf { it > 0 },
                ),
            )
        }
        cloudObjectStager.stageLive(scope, updates)
        return requireCreatedChat(
            updates,
            expected = if (isMegagroup) DialogPeerType.SUPERGROUP else DialogPeerType.CHANNEL,
        )
    }

    private suspend fun inputUsers(userIds: List<Long>): Pair<MtProtoAuthKeyScope, List<InputUser_0bd9c3151c>> {
        val scope = scope()
        return scope to userIds.map { userId ->
            val user = requireNotNull(users.get(scope, userId)) { "Missing MTProto user projection: $userId" }
            if (user.isSelf) InputUserSelf else InputUser_4020eae812(
                userId,
                requireNotNull(user.accessHash) { "Missing MTProto user access hash: $userId" },
            )
        }
    }

    private fun requireCreatedChat(envelope: Updates_faf6aaa3d5, expected: DialogPeerType): Long {
        val chats = when (envelope) {
            is Updates_02c952992b -> envelope.chats
            is UpdatesCombined -> envelope.chats
            else -> emptyList()
        }
        val created = chats.singleOrNull() ?: error("MTProto creation response did not contain one created chat")
        return when (created) {
            is Chat_65eab3b078 -> {
                require(expected == DialogPeerType.BASIC_GROUP) { "MTProto creation returned a basic group unexpectedly" }
                TelegramPeerChatId.encode(DialogPeerType.BASIC_GROUP, created.id)
            }
            is Channel -> {
                val type = if (created.megagroup) DialogPeerType.SUPERGROUP else DialogPeerType.CHANNEL
                require(type == expected) { "MTProto creation returned an unexpected channel type" }
                TelegramPeerChatId.encode(type, created.id)
            }
            else -> error("Unsupported MTProto created chat payload: ${created::class.simpleName}")
        }
    }

    private suspend fun scope(): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountSlot)
        return MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
