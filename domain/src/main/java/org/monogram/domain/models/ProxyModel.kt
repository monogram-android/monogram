package org.monogram.domain.models

data class ProxyModel(
    val id: Int,
    val server: String,
    val port: Int,
    val lastUsedDate: Int,
    val isEnabled: Boolean,
    val comment: String? = null,
    val type: ProxyTypeModel,
    val ping: Long? = null
) {
    val secret: String?
        get() = (type as? ProxyTypeModel.Mtproto)?.secret
}

sealed class ProxyTypeModel {
    data class Socks5(
        val username: String,
        val password: String
    ) : ProxyTypeModel()

    data class Http(
        val username: String,
        val password: String,
        val httpOnly: Boolean
    ) : ProxyTypeModel()

    data class Mtproto(
        val secret: String
    ) : ProxyTypeModel()
}
