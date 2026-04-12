package org.monogram.domain.repository

import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel

sealed interface ProxyTestResult {
    data class Success(val ping: Long) : ProxyTestResult
    data class Failure(val message: String) : ProxyTestResult
}

interface ExternalProxyRepository {
    suspend fun getProxies(): List<ProxyModel>
    suspend fun addProxy(server: String, port: Int, enable: Boolean, type: ProxyTypeModel): ProxyModel?
    suspend fun editProxy(proxyId: Int, server: String, port: Int, enable: Boolean, type: ProxyTypeModel): ProxyModel?
    suspend fun enableProxy(proxyId: Int): Boolean
    suspend fun disableProxy(): Boolean
    suspend fun removeProxy(proxyId: Int): Boolean
    suspend fun pingProxy(proxyId: Int): Long?
    suspend fun pingProxyDetailed(proxyId: Int): ProxyTestResult
    suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long?
    suspend fun testProxyDetailed(server: String, port: Int, type: ProxyTypeModel): ProxyTestResult
    fun setPreferIpv6(enabled: Boolean)
}
