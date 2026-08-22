package org.monogram.data.mtproto

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import org.monogram.domain.repository.AUTH_NETWORK_TIMEOUT_ERROR
import org.monogram.domain.repository.AuthError
import org.monogram.mtproto.handshake.MtProtoHandshakeException
import org.monogram.mtproto.handshake.MtProtoHandshakeFailure
import org.monogram.mtproto.transport.MtProtoRpcException

fun Throwable.isRecoverableMtProtoTransportFailure(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<MtProtoHandshakeException>()
        .any { it.failure == MtProtoHandshakeFailure.TRANSPORT }

fun Throwable.toAuthError(): AuthError {
    if (this is MtProtoSignUpRequiredException) return AuthError.SignUpRequired
    if (this is MtProtoPaidCodeRequiredException) {
        return AuthError.PaidCodeRequired(
            storeProduct = this.storeProduct,
            supportEmailAddress = this.supportEmailAddress,
        )
    }
    if (this is CancellationException) return AuthError.Unexpected
    if (message == AUTH_NETWORK_TIMEOUT_ERROR) return AuthError.NetworkTimeout
    if (this is SocketTimeoutException || this is SocketException || this is UnknownHostException) {
        return AuthError.NetworkTimeout
    }
    if (this is MtProtoHandshakeException &&
        (failure == MtProtoHandshakeFailure.TIMEOUT || failure == MtProtoHandshakeFailure.TRANSPORT)
    ) {
        return AuthError.NetworkTimeout
    }

    val errorMessage = when (this) {
        is MtProtoRpcException -> rpcMessage
        else -> null
    }
    val normalizedMessage = errorMessage.orEmpty().uppercase()

    return when {
        normalizedMessage.contains("PHONE_CODE_INVALID") ||
                normalizedMessage.contains("EMAIL_CODE_INVALID") -> AuthError.InvalidCode
        normalizedMessage.contains("PASSWORD_HASH_INVALID") -> AuthError.InvalidPassword
        normalizedMessage.contains("PHONE_CODE_EXPIRED") ||
                normalizedMessage.contains("EMAIL_CODE_EXPIRED") ||
                normalizedMessage.contains("CODE_EXPIRED") -> AuthError.CodeExpired

        normalizedMessage.startsWith("FLOOD_WAIT_") ->
            AuthError.RateLimited(normalizedMessage.substringAfterLast('_').toIntOrNull())

        else -> AuthError.Unexpected
    }
}
