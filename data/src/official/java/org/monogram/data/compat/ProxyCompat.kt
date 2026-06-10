package org.monogram.data.compat

import org.drinkless.tdlib.TdApi

internal fun buildAddProxy(
    proxy: TdApi.Proxy,
    enable: Boolean,
    comment: String?
): TdApi.AddProxy = TdApi.AddProxy(proxy, enable, comment.orEmpty())

internal fun buildEditProxy(
    proxyId: Int,
    proxy: TdApi.Proxy,
    enable: Boolean,
    comment: String?
): TdApi.EditProxy = TdApi.EditProxy(proxyId, proxy, enable, comment.orEmpty())

internal fun TdApi.AddedProxy.toDomainComment(): String? = comment.ifEmpty { null }
