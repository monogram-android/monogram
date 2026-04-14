package org.monogram.domain.models

sealed interface ProxyCheckResult {
    data class Success(val latencyMs: Long) : ProxyCheckResult
    data class Failure(val reason: ProxyFailureReason, val message: String) : ProxyCheckResult
}

enum class ProxyFailureReason {
    UNREACHABLE,
    INVALID_SECRET,
    DNS_FAILURE,
    AUTH_FAILED,
    TIMEOUT,
    UNKNOWN
}
