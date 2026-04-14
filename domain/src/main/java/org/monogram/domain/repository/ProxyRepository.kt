package org.monogram.domain.repository

import org.monogram.domain.models.Proxy
import org.monogram.domain.models.ProxyInput

interface ProxyRepository {
    suspend fun getProxies(): List<Proxy>
    suspend fun addProxy(input: ProxyInput, enable: Boolean): Proxy?
    suspend fun editProxy(proxyId: Int, input: ProxyInput, enable: Boolean): Proxy?
    suspend fun enableProxy(proxyId: Int): Boolean
    suspend fun disableProxy(): Boolean
    suspend fun removeProxy(proxyId: Int): Boolean
}
