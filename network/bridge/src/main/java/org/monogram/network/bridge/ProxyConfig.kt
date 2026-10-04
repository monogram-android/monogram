package org.monogram.network.bridge

import android.util.Base64
import org.monogram.core.common.Outcome

enum class ProxyType { NONE, SOCKS5, HTTP, HTTPS, MTPROTO }

enum class MtprotoTransportMode { PADDED_INTERMEDIATE, HTTP }

data class ProxyConfig(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val secret: ByteArray = byteArrayOf(),
) {
    override fun toString(): String = "ProxyConfig(type=$type, [REDACTED])"

    fun validate(): Outcome<Unit> {
        if (type == ProxyType.NONE) return Outcome.Ok(Unit)
        if (host.isEmpty() || host.length > 255 || host.any { it <= ' ' }) return Outcome.Err("proxy host is invalid", IllegalArgumentException("proxy host is invalid"))
        if (port !in 1..65535) return Outcome.Err("proxy port is invalid", IllegalArgumentException("proxy port is invalid"))
        if ((username == null) != (password == null)) return Outcome.Err("proxy credentials are incomplete", IllegalArgumentException("proxy credentials are incomplete"))
        if (username != null && (username.length > 255 || password!!.length > 255)) return Outcome.Err("proxy credentials are too long", IllegalArgumentException("proxy credentials are too long"))
        if (username != null && (username.any { it.code < 0x20 || it.code == 0x7f } || password!!.any { it.code < 0x20 || it.code == 0x7f })) return Outcome.Err("proxy credentials contain control characters", IllegalArgumentException("proxy credentials contain control characters"))
        if (type == ProxyType.MTPROTO) {
            if (username != null || password != null) {
                return Outcome.Err("MTProto proxy credentials are not supported", IllegalArgumentException("MTProto proxy credentials are not supported"))
            }
            val classic = secret.size == 16
            val randomPadding = secret.size == 17 && secret[0] == 0xdd.toByte()
            val fakeTls = secret.size in 18..199 && secret[0] == 0xee.toByte() &&
                secret.copyOfRange(17, secret.size).toString(Charsets.US_ASCII).let { domain ->
                    domain.toByteArray(Charsets.US_ASCII).contentEquals(secret.copyOfRange(17, secret.size)) &&
                        domain.length <= 182 && domain.split('.').all { label ->
                            label.isNotEmpty() && label.length <= 63 &&
                                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                                label.all { it.isLetterOrDigit() || it == '-' }
                        }
                }
            if (!classic && !randomPadding && !fakeTls) {
                return Outcome.Err("MTProto secret encoding is invalid", IllegalArgumentException("MTProto secret encoding is invalid"))
            }
        }
        if (type != ProxyType.MTPROTO && secret.isNotEmpty()) return Outcome.Err("secret is only valid for MTProto proxy", IllegalArgumentException("secret is only valid for MTProto proxy"))
        return Outcome.Ok(Unit)
    }
}

fun decodeProxySecret(value: String): ByteArray? {
    if (value.isEmpty()) return null
    if (value.length % 2 == 0 && value.all { it.digitToIntOrNull(16) != null }) {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
    if (value.any { it.isWhitespace() || it == '+' || it == '/' || it == '=' }) return null
    return runCatching {
        val decoded = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val canonical = Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        decoded.takeIf { canonical == value }
    }.getOrNull()
}

fun decodeProxySecretHex(value: String): ByteArray? =
    decodeProxySecret(value)?.takeIf { value.length % 2 == 0 && value.all { it.digitToIntOrNull(16) != null } }



/** Parses Telegram MTProto proxy links without retaining the original URL. */
fun parseTelegramProxyLink(value: String): ProxyConfig? {
    val input = value.trim()
    if (input.isEmpty() || input.length > 4096) return null
    val uri = runCatching { java.net.URI(input) }.getOrNull() ?: return null
    val path = uri.path.orEmpty().trimEnd('/')
    val isTelegramProxy = (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
        (uri.host.equals("t.me", true) || uri.host.equals("telegram.me", true)) &&
        path.equals("/proxy", true)
    val isTelegramScheme = uri.scheme.equals("tg", true) && (path.equals("/proxy", true) || uri.host.equals("proxy", true))
    if (!isTelegramProxy && !isTelegramScheme) return null
    val query = uri.rawQuery ?: return null
    val values = query.split('&').mapNotNull { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = runCatching { java.net.URLDecoder.decode(pair.substring(0, separator), Charsets.UTF_8.name()) }.getOrNull()
        val valuePart = runCatching { java.net.URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8.name()) }.getOrNull()
        if (key == null || valuePart == null) null else key to valuePart
    }.toMap()
    val host = values["server"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 } ?: return null
    val port = values["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val secretText = values["secret"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val secret = decodeProxySecret(secretText)?.takeIf { it.size in 16..199 } ?: return null
    val config = ProxyConfig(ProxyType.MTPROTO, host, port, secret = secret)
    return config.takeIf { it.validate() is Outcome.Ok }
}
/** Parses supported proxy URIs and Telegram share links into a canonical contract. */
fun parseProxyImport(value: String): ProxyConfig? {
    parseTelegramProxyLink(value)?.let { return it }
    val input = value.trim().takeIf { it.length in 1..4096 } ?: return null
    val uri = runCatching { java.net.URI(input) }.getOrNull() ?: return null
    val type = when (uri.scheme?.lowercase()) {
        "mtproto" -> ProxyType.MTPROTO
        "socks5", "socks" -> ProxyType.SOCKS5
        "http" -> ProxyType.HTTP
        "https" -> ProxyType.HTTPS
        else -> return null
    }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val port = uri.port.takeIf { it in 1..65535 } ?: when (type) {
        ProxyType.SOCKS5 -> 1080
        ProxyType.HTTP -> 8080
        ProxyType.HTTPS, ProxyType.MTPROTO -> 443
        ProxyType.NONE -> return null
    }
    val user = uri.userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }
    val password = uri.userInfo?.substringAfter(':', "")?.takeIf { uri.userInfo.contains(':') }
    val secretText = uri.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "secret" }
        ?.substringAfter('=') ?: uri.userInfo?.takeIf { type == ProxyType.MTPROTO }
    val secret = if (type == ProxyType.MTPROTO) secretText?.let(::decodeProxySecret) else null
    val config = ProxyConfig(type, host, port, user, password, secret ?: byteArrayOf())
    return config.takeIf { it.validate() is Outcome.Ok }
}
