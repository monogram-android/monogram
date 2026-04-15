package org.monogram.domain.models

data class Proxy(
    val id: Int,
    val server: String,
    val port: Int,
    val lastUsedDate: Int,
    val isEnabled: Boolean,
    val type: ProxyType
)

sealed interface ProxyType {
    data class Socks5(
        val username: String,
        val password: String
    ) : ProxyType

    data class Http(
        val username: String,
        val password: String,
        val httpOnly: Boolean
    ) : ProxyType

    data class Mtproto(
        val secret: String
    ) : ProxyType
}
