package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.users.GetUsers
import org.monogram.mtproto.transport.MtProtoRpcException

internal fun interface MtProtoAuthorizedSessionRestorer {
    suspend fun restore(accountSlot: String): Boolean
}

internal class TelegramMtProtoAuthorizedSessionRestorer(
    private val authorizationStore: MtProtoAccountAuthorizationStore,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val sessionFactory: TelegramMtProtoSessionFactory,
    private val userStore: MtProtoUserProjectionStore,
) : MtProtoAuthorizedSessionRestorer {
    override suspend fun restore(accountSlot: String): Boolean {
        if (!authorizationStore.isAuthorized(accountSlot)) return false
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        return try {
            val users = sessionFactory.open(accountSlot).use { transport ->
                transport.execute(GetUsers(listOf(InputUserSelf)))
            }
            check(users.isNotEmpty()) { "Authenticated self check returned no users" }
            userStore.upsert(scope, users)
            true
        } catch (failure: MtProtoRpcException) {
            if (failure.errorCode == 401) authorizationStore.clear(accountSlot)
            false
        }
    }
}
