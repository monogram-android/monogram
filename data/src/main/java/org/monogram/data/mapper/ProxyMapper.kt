package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ProxyModel
import org.monogram.domain.models.ProxyTypeModel
import org.monogram.domain.proxy.MtprotoSecretNormalizer

fun TdApi.AddedProxy.toDomain(): ProxyModel = ProxyModel(
    id = id,
    server = proxy.server,
    port = proxy.port,
    lastUsedDate = lastUsedDate,
    isEnabled = isEnabled,
    type = proxy.type.toDomain()
)

fun TdApi.ProxyType.toDomain(): ProxyTypeModel = when (this) {
    is TdApi.ProxyTypeSocks5 -> ProxyTypeModel.Socks5(username, password)
    is TdApi.ProxyTypeHttp -> ProxyTypeModel.Http(username, password, httpOnly)
    is TdApi.ProxyTypeMtproto -> ProxyTypeModel.Mtproto(secret)
    else -> throw IllegalArgumentException("Unknown proxy type: $this")
}

fun ProxyTypeModel.toApi(): TdApi.ProxyType = when (this) {
    is ProxyTypeModel.Socks5 -> TdApi.ProxyTypeSocks5(username, password)
    is ProxyTypeModel.Http -> TdApi.ProxyTypeHttp(username, password, httpOnly)
    is ProxyTypeModel.Mtproto -> {
        val normalized = MtprotoSecretNormalizer.normalize(secret)
            ?: throw IllegalArgumentException("Invalid MTProto proxy secret")
        TdApi.ProxyTypeMtproto(normalized)
    }
}
