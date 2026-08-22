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
) : MtProtoAuthSessionHandle {
    private val closed = AtomicBoolean(false)

    override fun currentState(): AuthStep {
        check(!closed.get()) { CLOSED_MESSAGE }
        return session.currentState()
    }

    override suspend fun requestCode(phone: String) = withOpen { session.requestCode(phone) }

    override suspend fun resendCode() = withOpen { session.resendCode() }

    override suspend fun submitCode(code: String) = withOpen { session.submitCode(code) }

    override suspend fun submitPassword(password: String) = withOpen { session.submitPassword(password) }

    override suspend fun submitSignUp(firstName: String, lastName: String) =
        withOpen { session.submitSignUp(firstName, lastName) }

    override suspend fun submitLoginEmail(email: String) = withOpen { session.submitLoginEmail(email) }

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

internal interface MtProtoAuthSessionHandle : AutoCloseable {
    fun currentState(): AuthStep
    suspend fun requestCode(phone: String): AuthStep
    suspend fun resendCode(): AuthStep
    suspend fun submitCode(code: String): AuthStep
    suspend fun submitPassword(password: String): AuthStep
    suspend fun submitSignUp(firstName: String, lastName: String): AuthStep
    suspend fun submitLoginEmail(email: String): AuthStep
}

internal fun interface MtProtoAuthSessionHandleFactory {
    suspend fun open(accountSlot: String): MtProtoAuthSessionHandle

    suspend fun open(accountSlot: String, dcId: Int): MtProtoAuthSessionHandle = open(accountSlot)
}

internal class MtProtoPhoneAuthSessionFactory(
    private val openTransport: suspend (String) -> MtProtoRpcTransport,
    private val openTransportForDc: suspend (String, Int) -> MtProtoRpcTransport = { _, dcId ->
        error("MTProto auth transport does not support DC migration to $dcId")
    },
    private val apiId: Int,
    private val apiHash: String,
    private val codeSettings: CodeSettings_fb610807ca,
) : MtProtoAuthSessionHandleFactory {
    init {
        require(apiId > 0) { "apiId must be positive" }
        require(apiHash.isNotBlank()) { "apiHash must not be blank" }
    }

    override suspend fun open(accountSlot: String): MtProtoPhoneAuthSessionHandle = openSession(accountSlot, null)

    override suspend fun open(accountSlot: String, dcId: Int): MtProtoPhoneAuthSessionHandle = openSession(accountSlot, dcId)

    private suspend fun openSession(accountSlot: String, dcId: Int?): MtProtoPhoneAuthSessionHandle {
        val transport = openTransport(accountSlot, dcId)
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

    suspend fun open(): MtProtoPhoneAuthSessionHandle = open(DEFAULT_ACCOUNT_SLOT)

    private suspend fun openTransport(accountSlot: String, dcId: Int?): MtProtoRpcTransport =
        if (dcId == null) openTransport(accountSlot) else openTransportForDc(accountSlot, dcId)

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
