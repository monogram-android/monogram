package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel

interface ProxyRemoteDataSource {
    suspend fun getProxies(): List<ProxyModel>
    suspend fun addProxy(server: String, port: Int, enable: Boolean, type: ProxyTypeModel): ProxyModel
    suspend fun editProxy(proxyId: Int, server: String, port: Int, enable: Boolean, type: ProxyTypeModel): ProxyModel
    suspend fun enableProxy(proxyId: Int): Boolean
    suspend fun disableProxy()
    suspend fun removeProxy(proxyId: Int)
    suspend fun pingProxy(server: String, port: Int, type: ProxyTypeModel): Long
    suspend fun testProxy(server: String, port: Int, type: ProxyTypeModel): Long
    suspend fun testProxyAtDc(server: String, port: Int, type: ProxyTypeModel, dcId: Int): Long
    suspend fun testDirectDc(dcId: Int): Long
    suspend fun setOption(key: String, value: TdApi.OptionValue)
    suspend fun getOption(key: String): TdApi.OptionValue?
}