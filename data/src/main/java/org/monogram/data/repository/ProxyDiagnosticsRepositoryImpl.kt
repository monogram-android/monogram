package org.monogram.data.repository

import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.ProxyRemoteDataSource
import org.monogram.data.gateway.toProxyFailureMessage
import org.monogram.domain.models.ProxyCheckResult
import org.monogram.domain.models.ProxyFailureReason
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.models.toProxyTypeModel
import org.monogram.domain.repository.ProxyDiagnosticsRepository

class ProxyDiagnosticsRepositoryImpl(
    private val remote: ProxyRemoteDataSource
) : ProxyDiagnosticsRepository {
    private companion object {
        const val DIAGNOSTIC_TIMEOUT_MS = 10_000L
    }

    override suspend fun pingProxy(proxyId: Int): ProxyCheckResult =
        withTimeoutOrNull(DIAGNOSTIC_TIMEOUT_MS) {
            coRunCatching {
                val proxy =
                    remote.getProxies().find { it.id == proxyId } ?: return@coRunCatching null
                remote.pingProxy(proxy.server, proxy.port, proxy.type)
            }.fold(
                onSuccess = { latencyMs ->
                    if (latencyMs == null) {
                        ProxyCheckResult.Failure(
                            reason = ProxyFailureReason.UNREACHABLE,
                            message = "Proxy is unreachable"
                        )
                    } else {
                        ProxyCheckResult.Success(latencyMs = latencyMs)
                    }
                },
                onFailure = { error ->
                    val message = error.toProxyFailureMessage() ?: "Proxy is unreachable"
                    ProxyCheckResult.Failure(
                        reason = message.toFailureReason(),
                        message = message
                    )
                }
            )
        } ?: ProxyCheckResult.Failure(
            reason = ProxyFailureReason.TIMEOUT,
            message = "Proxy check timed out"
        )

    override suspend fun testProxy(input: ProxyInput): ProxyCheckResult =
        withTimeoutOrNull(DIAGNOSTIC_TIMEOUT_MS) {
            coRunCatching {
                remote.testProxy(
                    server = input.server,
                    port = input.port,
                    type = input.type.toProxyTypeModel()
                )
            }.fold(
                onSuccess = { latencyMs -> ProxyCheckResult.Success(latencyMs = latencyMs) },
                onFailure = { error ->
                    val message = error.toProxyFailureMessage() ?: "Proxy is unreachable"
                    ProxyCheckResult.Failure(
                        reason = message.toFailureReason(),
                        message = message
                    )
                }
            )
        } ?: ProxyCheckResult.Failure(
            reason = ProxyFailureReason.TIMEOUT,
            message = "Proxy check timed out"
        )

    override suspend fun testProxyAtDc(input: ProxyInput, dcId: Int): ProxyCheckResult =
        withTimeoutOrNull(DIAGNOSTIC_TIMEOUT_MS) {
            coRunCatching {
                remote.testProxyAtDc(
                    server = input.server,
                    port = input.port,
                    type = input.type.toProxyTypeModel(),
                    dcId = dcId
                )
            }.fold(
                onSuccess = { latencyMs -> ProxyCheckResult.Success(latencyMs = latencyMs) },
                onFailure = { error ->
                    val message = error.toProxyFailureMessage() ?: "Proxy is unreachable"
                    ProxyCheckResult.Failure(
                        reason = message.toFailureReason(),
                        message = message
                    )
                }
            )
        } ?: ProxyCheckResult.Failure(
            reason = ProxyFailureReason.TIMEOUT,
            message = "Proxy check timed out"
        )

    override suspend fun testDirectDc(dcId: Int): ProxyCheckResult =
        withTimeoutOrNull(DIAGNOSTIC_TIMEOUT_MS) {
            coRunCatching { remote.testDirectDc(dcId) }.fold(
                onSuccess = { latencyMs -> ProxyCheckResult.Success(latencyMs = latencyMs) },
                onFailure = { error ->
                    val message =
                        error.toProxyFailureMessage() ?: "Direct route to DC $dcId is unreachable"
                    ProxyCheckResult.Failure(
                        reason = message.toFailureReason(),
                        message = message
                    )
                }
            )
        } ?: ProxyCheckResult.Failure(
            reason = ProxyFailureReason.TIMEOUT,
            message = "Direct route check timed out"
        )

    private fun String.toFailureReason(): ProxyFailureReason {
        val normalized = lowercase()
        return when {
            normalized.contains("resolved") -> ProxyFailureReason.DNS_FAILURE
            normalized.contains("secret") -> ProxyFailureReason.INVALID_SECRET
            normalized.contains("auth") || normalized.contains("password") -> ProxyFailureReason.AUTH_FAILED
            normalized.contains("timed out") || normalized.contains("timeout") -> ProxyFailureReason.TIMEOUT
            normalized.contains("unreachable") || normalized.contains("refused") -> ProxyFailureReason.UNREACHABLE
            else -> ProxyFailureReason.UNKNOWN
        }
    }
}
