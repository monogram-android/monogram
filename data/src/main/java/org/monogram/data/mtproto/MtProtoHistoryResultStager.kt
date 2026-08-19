package org.monogram.data.mtproto

import androidx.room.withTransaction
import org.monogram.data.db.MonogramDatabase
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Messages_08524391b7
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MessagesNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MessagesSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.Messages_3c331441fb

internal class MtProtoHistoryResultStager(
    private val userStore: MtProtoUserProjectionStore? = null,
    private val chatStore: MtProtoChatProjectionStore? = null,
    private val messageStore: MtProtoMessageProjectionStore? = null,
    private val database: MonogramDatabase? = null,
) {
    suspend fun stage(scope: MtProtoAuthKeyScope, result: Messages_08524391b7): List<org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4> {
        val payload = when (result) {
            is Messages_3c331441fb -> HistoryPayload(result.messages, result.users, result.chats)
            is MessagesSlice -> HistoryPayload(result.messages, result.users, result.chats)
            is MessagesNotModified -> return emptyList()
            else -> error("Unsupported messages.getHistory result")
        }
        suspend fun persist() {
            userStore?.upsert(scope, payload.users)
            chatStore?.upsert(scope, payload.chats)
            messageStore?.stageMessages(scope, payload.messages)
        }
        database?.withTransaction { persist() } ?: persist()
        return payload.messages
    }

    private data class HistoryPayload(
        val messages: List<org.monogram.mtproto.tl.generated.cloud.layer223.Message_73e57f95e4>,
        val users: List<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>,
        val chats: List<org.monogram.mtproto.tl.generated.cloud.layer223.Chat_7fdd7beb6e>,
    )
}
