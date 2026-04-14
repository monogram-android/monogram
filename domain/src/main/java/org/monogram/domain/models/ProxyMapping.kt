package org.monogram.domain.models

fun ProxyModel.toDomainProxy(): Proxy = Proxy(
    id = id,
    server = server,
    port = port,
    lastUsedDate = lastUsedDate,
    isEnabled = isEnabled,
    type = type.toDomainProxyType()
)

fun Proxy.toProxyModel(): ProxyModel = ProxyModel(
    id = id,
    server = server,
    port = port,
    lastUsedDate = lastUsedDate,
    isEnabled = isEnabled,
    type = type.toProxyTypeModel()
)

fun ProxyTypeModel.toDomainProxyType(): ProxyType = when (this) {
    is ProxyTypeModel.Socks5 -> ProxyType.Socks5(username = username, password = password)
    is ProxyTypeModel.Http -> ProxyType.Http(
        username = username,
        password = password,
        httpOnly = httpOnly
    )

    is ProxyTypeModel.Mtproto -> ProxyType.Mtproto(secret = secret)
}

fun ProxyType.toProxyTypeModel(): ProxyTypeModel = when (this) {
    is ProxyType.Socks5 -> ProxyTypeModel.Socks5(username = username, password = password)
    is ProxyType.Http -> ProxyTypeModel.Http(
        username = username,
        password = password,
        httpOnly = httpOnly
    )

    is ProxyType.Mtproto -> ProxyTypeModel.Mtproto(secret = secret)
}
