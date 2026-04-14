package org.monogram.domain.repository

import org.monogram.domain.models.ProxyCheckResult
import org.monogram.domain.models.ProxyInput

interface ProxyDiagnosticsRepository {
    suspend fun pingProxy(proxyId: Int): ProxyCheckResult
    suspend fun testProxy(input: ProxyInput): ProxyCheckResult
    suspend fun testProxyAtDc(input: ProxyInput, dcId: Int): ProxyCheckResult
    suspend fun testDirectDc(dcId: Int): ProxyCheckResult
}
