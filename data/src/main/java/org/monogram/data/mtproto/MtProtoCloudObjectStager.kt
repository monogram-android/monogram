package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.model.MtProtoCloudObjectEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShort
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortChatMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateShortSentMessage
import org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e
import org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.updates.MtProtoChannelDifferenceBatch
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch

internal interface MtProtoCloudObjectStager {
    suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5)

    suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch)

    suspend fun stageChannelDifference(scope: MtProtoAuthKeyScope, batch: MtProtoChannelDifferenceBatch)

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal object NoOpMtProtoCloudObjectStager : MtProtoCloudObjectStager {
    override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) = Unit

    override suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch) = Unit

    override suspend fun stageChannelDifference(scope: MtProtoAuthKeyScope, batch: MtProtoChannelDifferenceBatch) = Unit

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal class MtProtoRoomCloudObjectStager(
    private val dao: MtProtoCloudObjectDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val userProjectionStore: MtProtoUserProjectionStore = NoOpMtProtoUserProjectionStore,
    private val chatProjectionStore: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val messageProjectionStore: MtProtoMessageProjectionStore = NoOpMtProtoMessageProjectionStore,
    private val draftStore: MtProtoDraftStore = NoOpMtProtoDraftStore,
    private val storyResultStager: MtProtoStoryResultStager? = null,
) : MtProtoCloudObjectStager {
    override suspend fun stageLive(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5) {
        stage(scope, envelope.liveObjects())
        messageProjectionStore.stageLive(scope, envelope)
        draftStore.stageLive(scope, envelope)
        storyResultStager?.stageLive(scope, envelope)
    }

    override suspend fun stageDifference(scope: MtProtoAuthKeyScope, batch: MtProtoUpdateDifferenceBatch) {
        stage(
            scope,
            buildList {
                batch.users.forEach { add(MtProtoCloudObject("user", it)) }
                batch.chats.forEach { add(MtProtoCloudObject("chat", it)) }
                batch.newMessages.forEach { add(MtProtoCloudObject("message", it)) }
                batch.newEncryptedMessages.forEach { add(MtProtoCloudObject("encrypted_message", it)) }
                batch.otherUpdates.forEach { add(MtProtoCloudObject("update", it)) }
            },
        )
        messageProjectionStore.stageDifference(scope, batch)
        storyResultStager?.stageDifference(scope, batch)
    }

    override suspend fun stageChannelDifference(scope: MtProtoAuthKeyScope, batch: MtProtoChannelDifferenceBatch) {
        stage(
            scope,
            buildList {
                batch.users.forEach { add(MtProtoCloudObject("user", it)) }
                batch.chats.forEach { add(MtProtoCloudObject("chat", it)) }
                batch.newMessages.forEach { add(MtProtoCloudObject("message", it)) }
                batch.otherUpdates.forEach { add(MtProtoCloudObject("update", it)) }
            },
        )
        // Channel-gap repair must land repaired messages in Room projections too.
        messageProjectionStore.stageMessages(scope, batch.newMessages)
    }

    private suspend fun stage(scope: MtProtoAuthKeyScope, objects: List<MtProtoCloudObject>) {
        if (objects.isEmpty()) return
        dao.insertAll(
            objects.map { objectToStage ->
                val payload = CloudTlObjectCodec.encode(objectToStage.value)
                MtProtoCloudObjectEntity(
                    accountSlot = scope.accountSlot,
                    environment = scope.environment.storageName,
                    dcId = scope.dcId,
                    objectType = objectToStage.type,
                    payloadHash = MtProtoPayloadHash.sha256(payload),
                    payload = payload,
                    createdAt = nowMillis(),
                )
            }
        )
        userProjectionStore.upsert(scope, objects.mapNotNull { it.value as? User_655b5dfc57 })
        chatProjectionStore.upsert(scope, objects.mapNotNull { it.value as? Chat_7fdd7beb6e })
    }

    private fun Updates_faf6aaa3d5.liveObjects(): List<MtProtoCloudObject> = when (this) {
        is UpdatesCombined -> buildList {
            users.forEach { add(MtProtoCloudObject("user", it)) }
            chats.forEach { add(MtProtoCloudObject("chat", it)) }
            updates.forEach { add(MtProtoCloudObject("update", it)) }
        }

        is Updates_02c952992b -> buildList {
            users.forEach { add(MtProtoCloudObject("user", it)) }
            chats.forEach { add(MtProtoCloudObject("chat", it)) }
            updates.forEach { add(MtProtoCloudObject("update", it)) }
        }

        is UpdateShort -> listOf(MtProtoCloudObject("update", update))
        is UpdateShortChatMessage,
        is UpdateShortMessage,
        is UpdateShortSentMessage -> listOf(MtProtoCloudObject("live_updates", this))
        UpdatesTooLong -> emptyList()
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        dao.deleteAccount(accountSlot, environment.storageName)
        userProjectionStore.deleteAccount(accountSlot, environment)
        chatProjectionStore.deleteAccount(accountSlot, environment)
        messageProjectionStore.deleteAccount(accountSlot, environment)
        draftStore.deleteAccount(accountSlot, environment)
    }

    private data class MtProtoCloudObject(
        val type: String,
        val value: TlObject,
    )
}
