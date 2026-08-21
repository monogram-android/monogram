package org.monogram.data.mtproto

import android.util.Log
import kotlinx.coroutines.CancellationException
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
        if (!authorizationStore.isAuthorized(accountSlot)) {
            Log.i(TAG, "No persisted MTProto authorization to restore")
            return false
        }
        Log.i(TAG, "Validating persisted MTProto authorization")
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        return try {
            val users = sessionFactory.open(accountSlot).use { transport ->
                transport.execute(GetUsers(listOf(InputUserSelf)))
            }
            check(users.isNotEmpty()) { "Authenticated self check returned no users" }
            userStore.upsert(scope, users)
            Log.i(TAG, "Persisted MTProto authorization is valid")
            true
        } catch (failure: MtProtoRpcException) {
            if (failure.errorCode == 401) {
                Log.w(TAG, "Persisted MTProto authorization was rejected")
                authorizationStore.clear(accountSlot)
            } else {
                Log.w(TAG, "Persisted MTProto authorization validation failed code=${failure.errorCode}")
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Persisted MTProto authorization validation failed", failure)
            false
        }
    }

    private companion object {
        const val TAG = "MtProtoSessionRestore"
    }
}
