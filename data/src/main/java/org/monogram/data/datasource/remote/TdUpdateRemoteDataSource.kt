package org.monogram.data.datasource.remote

import org.monogram.data.core.coRunCatching
import android.util.Log
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.mapper.toUpdateInfo
import org.monogram.domain.models.UpdateInfo

class TdUpdateRemoteDataSource(
    private val gateway: TelegramGateway,
    private val channelId: Long = -1003566234286L
) : UpdateRemoteDateSource {

    private val tag = "TdUpdateRemote"
    private val channelUsername = "monogram_apks"

    override suspend fun fetchLatestUpdate(): UpdateInfo? {
        return coRunCatching {
            val resolvedChatId = resolveUpdateChatId()
            val messages = gateway.execute(TdApi.GetChatHistory(resolvedChatId, 0, 0, 1, false))
            val doc = messages.messages
                .firstOrNull()
                ?.content as? TdApi.MessageDocument
            doc?.toUpdateInfo()
        }.getOrNull()
    }

    private suspend fun resolveUpdateChatId(): Long {
        val chat = coRunCatching {
            gateway.execute(TdApi.SearchPublicChat(channelUsername)) as? TdApi.Chat
        }.getOrNull()

        return if (chat?.id != null) {
            chat.id
        } else {
            Log.w(tag, "Unable to resolve @$channelUsername, using fallback chatId=$channelId")
            channelId
        }
    }

    override suspend fun getTdLibVersion(): String {
        return coRunCatching {
            (gateway.execute(TdApi.GetOption("version")) as? TdApi.OptionValueString)?.value
        }.getOrNull() ?: "Unknown"
    }

    override suspend fun getTdLibCommitHash(): String {
        return coRunCatching {
            (gateway.execute(TdApi.GetOption("commit_hash")) as? TdApi.OptionValueString)?.value
        }.getOrNull() ?: ""
    }
}
