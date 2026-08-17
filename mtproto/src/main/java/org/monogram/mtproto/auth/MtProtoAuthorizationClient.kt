package org.monogram.mtproto.auth

import org.monogram.mtproto.tl.generated.cloud.layer223.CodeSettings_fb610807ca
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.Authorization_fb75ff221f
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.ResendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SendCode
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SentCode_250764ccd9
import org.monogram.mtproto.tl.generated.cloud.layer223.auth.SignIn
import org.monogram.mtproto.transport.MtProtoRpcTransport

/** Typed cloud-auth requests over an already initialized MTProto RPC transport. */
class MtProtoAuthorizationClient(
    private val transport: MtProtoRpcTransport,
) {
    suspend fun sendCode(
        phoneNumber: String,
        settings: CodeSettings_fb610807ca,
        apiId: Int,
        apiHash: String,
    ): SentCode_250764ccd9 {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(apiId > 0) { "apiId must be positive" }
        require(apiHash.isNotBlank()) { "apiHash must not be blank" }
        return transport.execute(SendCode(phoneNumber, apiId, apiHash, settings))
    }

    suspend fun resendCode(
        phoneNumber: String,
        phoneCodeHash: String,
        reason: String? = null,
    ): SentCode_250764ccd9 {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        return transport.execute(ResendCode(phoneNumber, phoneCodeHash, reason))
    }

    suspend fun signIn(
        phoneNumber: String,
        phoneCodeHash: String,
        phoneCode: String,
    ): Authorization_fb75ff221f {
        require(phoneNumber.isNotBlank()) { "phoneNumber must not be blank" }
        require(phoneCodeHash.isNotBlank()) { "phoneCodeHash must not be blank" }
        require(phoneCode.isNotBlank()) { "phoneCode must not be blank" }
        return transport.execute(SignIn(phoneNumber, phoneCodeHash, phoneCode, emailVerification = null))
    }
}
