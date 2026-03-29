package org.monogram.data.datasource.remote

import org.monogram.data.core.coRunCatching
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway

class TdPrivacyRemoteDataSource(
    private val gateway: TelegramGateway
) : PrivacyRemoteDataSource {

    override suspend fun getPrivacyRules(
        setting: TdApi.UserPrivacySetting
    ): List<TdApi.UserPrivacySettingRule> =
        coRunCatching {
            gateway.execute(TdApi.GetUserPrivacySettingRules(setting)).rules.toList()
        }.getOrDefault(emptyList())

    override suspend fun setPrivacyRules(
        setting: TdApi.UserPrivacySetting,
        rules: TdApi.UserPrivacySettingRules
    ) {
        gateway.execute(TdApi.SetUserPrivacySettingRules(setting, rules))
    }

    override suspend fun getBlockedUsers(): List<Long> =
        coRunCatching {
            gateway.execute(TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), 0, 100))
                .senders
                .mapNotNull { (it as? TdApi.MessageSenderUser)?.userId }
        }.getOrDefault(emptyList())

    override suspend fun blockUser(userId: Long) {
        gateway.execute(
            TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), TdApi.BlockListMain())
        )
    }

    override suspend fun unblockUser(userId: Long) {
        gateway.execute(
            TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), null)
        )
    }

    override suspend fun deleteAccount(reason: String, password: String) {
        gateway.execute(TdApi.DeleteAccount(reason, password))
    }

    override suspend fun getAccountTtl(): Int =
        coRunCatching {
            gateway.execute(TdApi.GetAccountTtl()).days
        }.getOrDefault(180)

    override suspend fun setAccountTtl(days: Int) {
        gateway.execute(TdApi.SetAccountTtl(TdApi.AccountTtl(days)))
    }

    override suspend fun getPasswordState(): Boolean =
        coRunCatching {
            gateway.execute(TdApi.GetPasswordState()).hasPassword
        }.getOrDefault(false)

    override suspend fun getOption(name: String): Boolean =
        coRunCatching {
            (gateway.execute(TdApi.GetOption(name)) as? TdApi.OptionValueBoolean)?.value ?: false
        }.getOrDefault(false)

    override suspend fun setOption(name: String, value: Boolean) {
        gateway.execute(TdApi.SetOption(name, TdApi.OptionValueBoolean(value)))
    }
}