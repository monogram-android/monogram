package org.monogram.data.mtproto

import org.monogram.mtproto.auth.MtProtoQrLoginExecutor
import org.monogram.mtproto.auth.MtProtoQrLoginClient
import org.monogram.mtproto.auth.MtProtoQrLoginState
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport

/** Opens QR-login transports, following DC migrations for `auth.exportLoginToken`. */
internal class MtProtoQrLoginSession(
    private val sessionFactory: TelegramMtProtoSessionFactory,
    private val apiId: Int,
    private val apiHash: String,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) {
    suspend fun poll(): MtProtoQrLoginState =
        sessionFactory.open(accountSlot).use { home ->
            when (val state = clientOn(home).export()) {
                is MtProtoQrLoginState.MigrationNeeded ->
                    // Re-import the token on the redirected DC with a fresh transport.
                    sessionFactory.open(accountSlot, state.dcId).use { target ->
                        clientOn(target).import(state.token)
                    }
                else -> state
            }
        }

    private fun clientOn(transport: MtProtoRpcTransport): MtProtoQrLoginClient =
        MtProtoQrLoginClient(
            executor = { method ->
                @Suppress("UNCHECKED_CAST")
                transport.execute(method as TlMethod<TlObject>)
            },
            apiId = apiId,
            apiHash = apiHash,
        )

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
