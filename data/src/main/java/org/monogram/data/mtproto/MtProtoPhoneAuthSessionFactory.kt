package org.monogram.data.mtproto

import java.util.concurrent.atomic.AtomicBoolean
import org.monogram.domain.repository.AuthStep
import org.monogram.mtproto.auth.MtProtoAuthorizationClient
import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.transport.MtProtoRpcTransport

/** Owns an initialized RPC transport for one MTProto phone-auth attempt. */
internal class MtProtoPhoneAuthSessionHandle internal constructor(
    private val transport: MtProtoRpcTransport,
    val session: MtProtoPhoneAuthSession,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun currentState(): AuthStep {
        check(!closed.get()) { CLOSED_MESSAGE }
        return session.currentState()
    }

    suspend fun requestCode(phone: String) = withOpen { session.requestCode(phone) }

    suspend fun resendCode() = withOpen { session.resendCode() }

    suspend fun submitCode(code: String) = withOpen { session.submitCode(code) }

    suspend fun submitPassword(password: String) = withOpen { session.submitPassword(password) }

    override fun close() {
        if (closed.compareAndSet(false, true)) transport.close()
    }

    private suspend fun <T> withOpen(block: suspend () -> T): T {
        check(!closed.get()) { CLOSED_MESSAGE }
        return block()
    }

    private companion object {
        const val CLOSED_MESSAGE = "MTProto phone auth session is closed"
    }
}

internal class MtProtoPhoneAuthSessionFactory(
    private val openTransport: suspend (String) -> MtProtoRpcTransport,
    private val apiId: Int,
    private val apiHash: String,
    private val codeSettings: CodeSettings_fb610807ca,
) {
    init {
        require(apiId > 0) { "apiId must be positive" }
        require(apiHash.isNotBlank()) { "apiHash must not be blank" }
    }

    suspend fun open(accountSlot: String = DEFAULT_ACCOUNT_SLOT): MtProtoPhoneAuthSessionHandle {
        val transport = openTransport(accountSlot)
        return try {
            MtProtoPhoneAuthSessionHandle(
                transport = transport,
                session = MtProtoPhoneAuthSession(
                    api = MtProtoAuthorizationClient(transport),
                    apiId = apiId,
                    apiHash = apiHash,
                    codeSettings = codeSettings,
                ),
            )
        } catch (failure: Throwable) {
            transport.close()
            throw failure
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
