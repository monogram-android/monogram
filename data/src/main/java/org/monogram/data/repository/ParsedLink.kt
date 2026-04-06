package org.monogram.data.repository

import org.monogram.domain.models.ProxyTypeModel

sealed class ParsedLink {
    data class AddProxy(
        val server: String,
        val port: Int,
        val type: ProxyTypeModel
    ) : ParsedLink()

    data class OpenUser(val userId: Long) : ParsedLink()
    data class ResolveByPhone(
        val phoneNumber: String,
        val openProfile: Boolean
    ) : ParsedLink()

    data class OpenPublicChat(val username: String) : ParsedLink()
    data class JoinChat(val inviteLink: String) : ParsedLink()
    data class OpenExternal(val url: String) : ParsedLink()
    data object None : ParsedLink()
}