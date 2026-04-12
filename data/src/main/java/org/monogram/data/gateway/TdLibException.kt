package org.monogram.data.gateway

import org.drinkless.tdlib.TdApi

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