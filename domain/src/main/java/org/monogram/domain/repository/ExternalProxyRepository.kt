package org.monogram.domain.repository

import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel

interface ExternalProxyRepository {
    suspend fun getProxies(): List<ProxyModel>
    suspend fun addProxy(server: String, port: Int, enable: Boolean, type: ProxyTypeModel): ProxyModel?
    suspend fun editProxy(proxyId: Int, server: String, port: Int, enable: Boolean, type: ProxyTypeModel): ProxyModel?
    suspend fun enableProxy(proxyId: Int): Boolean
    suspend fun disableProxy(): Boolean
    suspend fun removeProxy(proxyId: Int): Boolean
    suspend fun pingProxy(proxyId: Int): Long?
    suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long?
    fun setPreferIpv6(enabled: Boolean)
}
