package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.monogram.domain.models.PrivacyRule
import org.monogram.mtproto.auth.MtProtoPasswordSrpProof
import org.monogram.mtproto.transport.MtProtoRpcException
import org.monogram.domain.repository.PrivacyKey
import org.monogram.domain.repository.PrivacyRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.*
import org.monogram.mtproto.tl.generated.cloud.layer223.PrivacyRule as CloudPrivacyRule
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.account.*
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetAccountTtl
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetPassword
import org.monogram.mtproto.tl.generated.cloud.layer223.account.Password_ac67a26d5c
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetContentSettings
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetAccountTtl
import org.monogram.mtproto.tl.generated.cloud.layer223.account.SetContentSettings
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Block
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Blocked_31657af965
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.BlockedSlice
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.GetBlocked
import org.monogram.mtproto.tl.generated.cloud.layer223.contacts.Unblock

internal class MtProtoPrivacyRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
    private val accountSlot: String = "default",
    private val accountStateResetter: MtProtoAccountStateResetter = MtProtoAccountStateResetter { _, _ -> },
    private val liveSessionResetter: MtProtoLiveSessionResetter = MtProtoLiveSessionResetter {},
    private val passwordProof: suspend (String, Password_ac67a26d5c) -> InputCheckPasswordSrp_1e0a258433 =
        MtProtoPasswordSrpProof::create,
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

    override fun getPrivacyRules(key: PrivacyKey): Flow<List<PrivacyRule>> = flow {
        emit(executePrivacy(GetPrivacy(key.toInputPrivacyKey())).rules.map { it.toPrivacyRule() })
    }

    override suspend fun setPrivacyRule(key: PrivacyKey, rules: List<PrivacyRule>) {
        val scope = scope()
        val inputRules = rules.map { it.toInputPrivacyRule(scope) }
        executePrivacy(SetPrivacy(key.toInputPrivacyKey(), inputRules))
    }
    override suspend fun deleteAccount(reason: String, password: String) {
        var retryFreshChallenge = true
        while (true) {
            try {
                transportFactory.open(accountSlot).use { transport ->
                    val configuration = transport.execute(GetPassword) as? Password_ac67a26d5c
                        ?: error("Unsupported MTProto password configuration")
                    val proof = if (configuration.hasPassword) {
                        passwordProof(password, configuration)
                    } else {
                        null
                    }
                    check(transport.execute(DeleteAccount(reason, proof))) {
                        "MTProto account deletion was rejected"
                    }
                }
                liveSessionResetter.resetLiveSession()
                accountStateResetter.deleteAccount(accountSlot, MtProtoEnvironment.PRODUCTION)
                return
            } catch (rpc: MtProtoRpcException) {
                if (!retryFreshChallenge || rpc.errorCode != 400 || rpc.rpcMessage != SRP_ID_INVALID) throw rpc
                retryFreshChallenge = false
            }
        }
    }
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
    override suspend fun getPasswordState(): Boolean = transportFactory.open(accountSlot).use { transport ->
        (transport.execute(GetPassword) as? Password_ac67a26d5c)?.hasPassword
            ?: error("Unsupported MTProto password response")
    }

    override suspend fun canShowSensitiveContent(): Boolean = contentSettings().sensitiveCanChange

    override suspend fun isShowSensitiveContentEnabled(): Boolean = contentSettings().sensitiveEnabled

    override suspend fun setShowSensitiveContent(enabled: Boolean) {
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(SetContentSettings(enabled))) {
                "MTProto sensitive content update was rejected"
            }
        }
    }

    private suspend fun executePrivacy(method: org.monogram.mtproto.tl.runtime.TlMethod<PrivacyRules_815e26275e>): PrivacyRules_41fcd5c348 {
        val result = transportFactory.open(accountSlot).use { transport ->
            transport.execute(method) as? PrivacyRules_41fcd5c348
                ?: error("Unsupported MTProto privacy rules response")
        }
        val scope = scope()
        users.upsert(scope, result.users)
        chats.upsert(scope, result.chats)
        return result
    }

    private fun PrivacyKey.toInputPrivacyKey(): InputPrivacyKey = when (this) {
        PrivacyKey.PHONE_NUMBER -> InputPrivacyKeyPhoneNumber
        PrivacyKey.PHONE_NUMBER_SEARCH -> InputPrivacyKeyAddedByPhone
        PrivacyKey.LAST_SEEN -> InputPrivacyKeyStatusTimestamp
        PrivacyKey.PROFILE_PHOTO -> InputPrivacyKeyProfilePhoto
        PrivacyKey.BIO -> InputPrivacyKeyAbout
        PrivacyKey.FORWARDED_MESSAGES -> InputPrivacyKeyForwards
        PrivacyKey.CALLS -> InputPrivacyKeyPhoneCall
        PrivacyKey.GROUPS_AND_CHANNELS -> InputPrivacyKeyChatInvite
    }

    private fun CloudPrivacyRule.toPrivacyRule(): PrivacyRule = when (this) {
        PrivacyValueAllowAll -> PrivacyRule.AllowAll
        PrivacyValueAllowContacts -> PrivacyRule.AllowContacts
        PrivacyValueDisallowAll -> PrivacyRule.AllowNone
        is PrivacyValueAllowUsers -> PrivacyRule.AllowUsers(users)
        is PrivacyValueAllowChatParticipants -> PrivacyRule.AllowChatMembers(chats)
        PrivacyValueDisallowContacts -> PrivacyRule.DisallowContacts
        is PrivacyValueDisallowUsers -> PrivacyRule.DisallowUsers(users)
        is PrivacyValueDisallowChatParticipants -> PrivacyRule.DisallowChatMembers(chats)
        PrivacyValueAllowBots -> PrivacyRule.AllowBots
        PrivacyValueAllowCloseFriends -> PrivacyRule.AllowCloseFriends
        PrivacyValueAllowPremium -> PrivacyRule.AllowPremium
        PrivacyValueDisallowBots -> PrivacyRule.DisallowBots
        else -> throw UnsupportedOperationException("MTProto privacy rule is not available")
    }

    private suspend fun PrivacyRule.toInputPrivacyRule(scope: MtProtoAuthKeyScope): InputPrivacyRule = when (this) {
        PrivacyRule.AllowAll -> InputPrivacyValueAllowAll
        PrivacyRule.AllowContacts -> InputPrivacyValueAllowContacts
        PrivacyRule.AllowNone -> InputPrivacyValueDisallowAll
        is PrivacyRule.AllowUsers -> InputPrivacyValueAllowUsers(userIds.map { it.toInputUser(scope) })
        is PrivacyRule.AllowChatMembers -> InputPrivacyValueAllowChatParticipants(chatIds)
        PrivacyRule.DisallowContacts -> InputPrivacyValueDisallowContacts
        is PrivacyRule.DisallowUsers -> InputPrivacyValueDisallowUsers(userIds.map { it.toInputUser(scope) })
        is PrivacyRule.DisallowChatMembers -> InputPrivacyValueDisallowChatParticipants(chatIds)
        PrivacyRule.AllowBots -> InputPrivacyValueAllowBots
        PrivacyRule.AllowCloseFriends -> InputPrivacyValueAllowCloseFriends
        PrivacyRule.AllowPremium -> InputPrivacyValueAllowPremium
        PrivacyRule.DisallowBots -> InputPrivacyValueDisallowBots
    }

    private suspend fun Long.toInputUser(scope: MtProtoAuthKeyScope): InputUser_0bd9c3151c {
        val user = requireNotNull(users.get(scope, this)) { "Missing MTProto user projection: $this" }
        return InputUser_4020eae812(this, requireNotNull(user.accessHash) { "Missing MTProto user access hash: $this" })
    }

    private suspend fun contentSettings(): ContentSettings_33d483dc78 =
        transportFactory.open(accountSlot).use { transport ->
            transport.execute(GetContentSettings) as? ContentSettings_33d483dc78
                ?: error("Unsupported MTProto content settings response")
        }
    private companion object {
        const val PAGE_SIZE = 100
        const val SRP_ID_INVALID = "SRP_ID_INVALID"
    }
}
