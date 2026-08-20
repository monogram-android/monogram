package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.PrivacyRule
import org.monogram.domain.repository.PrivacyKey
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.AccountDaysTtl_f6ad918c54
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetAccountTtl
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetAccountTtl
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Block
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Blocked_31657af965
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.BlockedSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.GetBlocked
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Unblock

internal class MtProtoPrivacyRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val accountSlot: String = "default",
) : PrivacyRepository {
    override suspend fun getBlockedUsers(): List<Long> {
        val scope = scope()
        val transport = transportFactory.open(accountSlot)
        try {
            val blocked = mutableListOf<org.monogram.mtproto.tl.generated.cloud.layer223.PeerBlocked_cbb8d362a3>()
            val projectedUsers = mutableListOf<org.monogram.mtproto.tl.generated.cloud.layer223.User_655b5dfc57>()
            var offset = 0
            var total: Int? = null
            do {
                val result = transport.execute(GetBlocked(false, offset, PAGE_SIZE))
                val (pageBlocked, pageUsers, pageTotal) = when (result) {
                    is Blocked_31657af965 -> Triple(result.blocked, result.users, result.blocked.size)
                    is BlockedSlice -> Triple(result.blocked, result.users, result.count)
                }
                require(pageBlocked.isNotEmpty() || offset >= pageTotal) { "MTProto blocked-user page made no progress" }
                blocked += pageBlocked
                projectedUsers += pageUsers
                offset += pageBlocked.size
                total = pageTotal
            } while (offset < requireNotNull(total))
            users.upsert(scope, projectedUsers)
            return blocked.mapNotNull { (it as? org.monogram.mtproto.tl.generated.cloud.layer223.PeerBlocked_161238e123)?.peerId.let { peer -> (peer as? PeerUser)?.userId } }
        } finally { transport.close() }
    }

    override suspend fun blockUser(userId: Long) = setBlocked(userId, true)
    override suspend fun unblockUser(userId: Long) = setBlocked(userId, false)

    private suspend fun setBlocked(userId: Long, blocked: Boolean) {
        val scope = scope()
        val user = requireNotNull(users.get(scope, userId)) { "Missing MTProto user projection: $userId" }
        val peer = InputPeerUser(userId, requireNotNull(user.accessHash) { "Missing MTProto user access hash: $userId" })
        val transport = transportFactory.open(accountSlot)
        try {
            check(if (blocked) transport.execute(Block(false, peer)) else transport.execute(Unblock(false, peer)))
        } finally { transport.close() }
    }

    private suspend fun scope(): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountSlot)
        return MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }

    override fun getPrivacyRules(key: PrivacyKey): Flow<List<PrivacyRule>> = unsupported()
    override suspend fun setPrivacyRule(key: PrivacyKey, rules: List<PrivacyRule>) = unsupported()
    override suspend fun deleteAccount(reason: String, password: String) = unsupported()
    override suspend fun getAccountTtl(): Int = transportFactory.open(accountSlot).use { transport ->
        (transport.execute(GetAccountTtl) as? AccountDaysTtl_f6ad918c54)?.days
            ?: error("Unsupported MTProto account TTL response")
    }

    override suspend fun setAccountTtl(days: Int) {
        require(days > 0) { "MTProto account TTL must be positive" }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(SetAccountTtl(AccountDaysTtl_f6ad918c54(days)))) {
                "MTProto account TTL update was rejected"
            }
        }
    }
    override suspend fun getPasswordState(): Boolean = unsupported()
    override suspend fun canShowSensitiveContent(): Boolean = unsupported()
    override suspend fun isShowSensitiveContentEnabled(): Boolean = unsupported()
    override suspend fun setShowSensitiveContent(enabled: Boolean) = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto privacy setting is not available")
    private companion object { const val PAGE_SIZE = 100 }
}
