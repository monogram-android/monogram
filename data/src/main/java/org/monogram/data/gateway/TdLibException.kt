package org.monogram.data.gateway

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import org.drinkless.tdlib.TdApi
import org.monogram.domain.repository.AUTH_NETWORK_TIMEOUT_ERROR
import org.monogram.domain.repository.AuthError
import org.monogram.mtproto.handshake.MtProtoHandshakeException
import org.monogram.mtproto.handshake.MtProtoHandshakeFailure
import org.monogram.mtproto.transport.MtProtoRpcException

class TdLibException(val error: TdApi.Error) : Exception(error.message)

private val proxyResolveHostErrors = listOf(
    "failed to resolve host",
    "no address associated with hostname"
)

private val proxyConnectivityErrors = listOf(
    "response hash mismatch",
    "connection refused",
    "network is unreachable",
    "timed out"
)

fun TdApi.Error.isExpectedProxyFailure(): Boolean {
    val text = message.orEmpty().lowercase()
    return proxyResolveHostErrors.any(text::contains) || proxyConnectivityErrors.any(text::contains)
}

fun Throwable.isExpectedProxyFailure(): Boolean {
    val tdError = (this as? TdLibException)?.error
    return tdError?.isExpectedProxyFailure() == true
}

fun Throwable.toProxyFailureMessage(): String? {
    val text = (this as? TdLibException)?.error?.message?.lowercase() ?: return null
    return when {
        proxyResolveHostErrors.any(text::contains) -> "Proxy host can't be resolved"
        text.contains("response hash mismatch") -> "Invalid MTProto secret"
        text.contains("connection refused") -> "Proxy connection refused"
        text.contains("network is unreachable") || text.contains("timed out") -> "Proxy is unreachable"
        else -> null
    }
}

fun Throwable.toUserMessage(defaultMessage: String = "Unknown error"): String {
    val tdMessage = (this as? TdLibException)?.error?.message.orEmpty()
    return tdMessage.ifEmpty { message ?: defaultMessage }
}

fun Throwable.toAuthError(): AuthError {
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
        is TdLibException -> error.message
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

fun Throwable.isUnexpectedAuthStateError(functionName: String): Boolean {
    val tdError = (this as? TdLibException)?.error ?: return false
    val normalizedMessage = tdError.message.orEmpty().lowercase()
    val normalizedFunction = functionName.lowercase()
    return tdError.code == 400 &&
            normalizedMessage.contains("call to $normalizedFunction") &&
            normalizedMessage.contains("unexpected")
}
