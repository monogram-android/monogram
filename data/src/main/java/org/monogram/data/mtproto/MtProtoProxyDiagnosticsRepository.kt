package org.monogram.data.mtproto

import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.domain.models.ProxyCheckResult
import org.monogram.domain.models.ProxyFailureReason
import org.monogram.domain.models.ProxyInput
import org.monogram.domain.models.ProxyType
import org.monogram.domain.repository.ProxyDiagnosticsRepository

/**
 * MTProto-native proxy diagnostics.
 *
 * Measures TCP reachability and latency of Telegram data centers, either
 * directly or through SOCKS5 / HTTP / MTProto proxies, using plain
 * [java.net.Socket] connections - no Telegram involved.
 *
 * Note: saved-proxy persistence was removed together with the Telegram stack,
 * so [pingProxy] cannot resolve stored proxies by id yet and reports them as
 * unreachable until proxy persistence is reintroduced.
 */
internal class MtProtoProxyDiagnosticsRepository : ProxyDiagnosticsRepository {

    override suspend fun pingProxy(proxyId: Int): ProxyCheckResult =
        // No persisted proxy store exists after the Telegram removal; every id is
        // unknown to this implementation until proxy persistence returns.
        ProxyCheckResult.Failure(
            reason = ProxyFailureReason.UNREACHABLE,
            message = "Proxy not found: $proxyId",
        )

    override suspend fun testProxy(input: ProxyInput): ProxyCheckResult =
        testProxyAtDc(input, DEFAULT_DC_ID)

    override suspend fun testProxyAtDc(input: ProxyInput, dcId: Int): ProxyCheckResult =
        runWithTimeout {
            val target = resolveDcAddress(dcId)
            when (val type = input.type) {
                is ProxyType.Socks5 -> checkSocks5Proxy(input, type, target)
                is ProxyType.Http -> checkHttpProxy(input, type, target)
                is ProxyType.Mtproto -> checkMtProtoProxy(input, type)
            }
        }

    override suspend fun testDirectDc(dcId: Int): ProxyCheckResult = runWithTimeout {
        val target = resolveDcAddress(dcId)
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
        }
        elapsedMs(startedAt)
    }

    private suspend fun runWithTimeout(block: () -> Long): ProxyCheckResult {
        val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            try {
                withContext(Dispatchers.IO) { block() }
                    .let { latencyMs -> ProxyCheckResult.Success(latencyMs) }
            } catch (failure: Exception) {
                failure.toCheckResult()
            }
        }
        return result ?: ProxyCheckResult.Failure(
            reason = ProxyFailureReason.TIMEOUT,
            message = "Check timed out after ${CHECK_TIMEOUT_MS}ms",
        )
    }

    private fun Exception.toCheckResult(): ProxyCheckResult = when (this) {
        is InvalidProxySecretException -> ProxyCheckResult.Failure(ProxyFailureReason.INVALID_SECRET, message.orEmpty())
        is SocksAuthenticationException -> ProxyCheckResult.Failure(ProxyFailureReason.AUTH_FAILED, message.orEmpty())
        is UnknownHostException -> ProxyCheckResult.Failure(ProxyFailureReason.DNS_FAILURE, message ?: "Unknown host")
        is SocketTimeoutException -> ProxyCheckResult.Failure(ProxyFailureReason.TIMEOUT, message ?: "Connection timed out")
        else -> ProxyCheckResult.Failure(ProxyFailureReason.UNREACHABLE, message ?: "Unreachable")
    }

    private fun resolveDcAddress(dcId: Int): TelegramMtProtoEndpoint = try {
        telegramMtProtoEndpointForDc(dcId)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unsupported DC id: $dcId")
    }

    private fun checkSocks5Proxy(
        input: ProxyInput,
        type: ProxyType.Socks5,
        target: TelegramMtProtoEndpoint,
    ): Long {
        val hasCredentials = type.username.isNotBlank() || type.password.isNotBlank()
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(input.server, input.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val output = DataOutputStream(socket.getOutputStream())
            val input_ = DataInputStream(socket.getInputStream())

            // Greeting: offer NO_AUTH and, when credentials are present, USERNAME/PASSWORD.
            val methods = if (hasCredentials) {
                byteArrayOf(METHOD_NO_AUTH.toByte(), METHOD_USERNAME_PASSWORD.toByte())
            } else {
                byteArrayOf(METHOD_NO_AUTH.toByte())
            }
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), methods.size.toByte()) + methods)
            output.flush()
            output.flush()

            val chosenMethod = input_.readUnsignedByte().let { version ->
                require(version == SOCKS_VERSION) { "Invalid SOCKS version $version" }
                input_.readUnsignedByte()
            }
            if (chosenMethod != METHOD_NO_AUTH && !hasCredentials) {
                throw SocksAuthenticationException("Proxy requires an unsupported auth method")
            }
            if (chosenMethod == METHOD_USERNAME_PASSWORD) {
                performRfc1929Auth(output, input_, type.username, type.password)
            }

            // CONNECT request to dcIp:443 using a domain-name style address field.
            val addressBytes = target.host.encodeToByteArray()
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(),
                    SOCKS_COMMAND_CONNECT.toByte(),
                    0x00,
                    ADDR_TYPE_IPV4.toByte(),
                    addressBytes.size.toByte(),
                ) +
                    addressBytes +
                    byteArrayOf((target.port shr 8).toByte(), target.port.toByte()),
            )
            output.flush()

            val replyCode = input_.readUnsignedByte().let { version ->
                require(version == SOCKS_VERSION) { "Invalid SOCKS reply version $version" }
                input_.readUnsignedByte()
            }
            if (replyCode != SOCKS_REPLY_SUCCESS) {
                if (replyCode == SOCKS_REPLY_AUTH_FAILED) {
                    throw SocksAuthenticationException("SOCKS5 CONNECT refused: not allowed by ruleset")
                }
                throw IllegalStateException("SOCKS5 CONNECT failed with reply code $replyCode")
            }
            // Drain the remainder of the reply: RSV, ATYP, BND.ADDR, BND.PORT.
            input_.readUnsignedByte() // RSV
            when (input_.readUnsignedByte()) { // ATYP
                ADDR_TYPE_IPV4 -> input_.skipBytes(4)
                ADDR_TYPE_DOMAIN -> input_.skipBytes(input_.readUnsignedByte())
                ADDR_TYPE_IPV6 -> input_.skipBytes(16)
            }
            input_.readFully(ByteArray(2))
        }
        return elapsedMs(startedAt)
    }

    private fun performRfc1929Auth(
        output: DataOutputStream,
        input_: DataInputStream,
        username: String,
        password: String,
    ) {
        val userBytes = username.encodeToByteArray()
        val passBytes = password.encodeToByteArray()
        require(userBytes.size <= MAX_AUTH_FIELD_BYTES && passBytes.size <= MAX_AUTH_FIELD_BYTES) {
            "SOCKS5 credentials too long"
        }
        output.writeByte(AUTH_VERSION)
        output.writeByte(userBytes.size)
        output.write(userBytes)
        output.writeByte(passBytes.size)
        output.write(passBytes)
        output.flush()

        val status = input_.readUnsignedByte().let { version ->
            require(version == AUTH_VERSION) { "Invalid auth sub-negotiation version $version" }
            input_.readUnsignedByte()
        }
        if (status != AUTH_STATUS_SUCCESS) {
            throw SocksAuthenticationException("SOCKS5 username/password authentication failed")
        }
    }

    private fun checkHttpProxy(
        input: ProxyInput,
        type: ProxyType.Http,
        target: TelegramMtProtoEndpoint,
    ): Long {
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(input.server, input.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val authority = "${target.host}:${target.port}"
            val request = buildString {
                append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                append("Host: ").append(authority).append("\r\n")
                if (type.username.isNotBlank() || type.password.isNotBlank()) {
                    val credentials = Base64.encodeToString(
                        "${type.username}:${type.password}".encodeToByteArray(),
                        Base64.NO_WRAP,
                    )
                    append("Proxy-Authorization: Basic ").append(credentials).append("\r\n")
                }
                append("\r\n")
            }
            socket.getOutputStream().write(request.encodeToByteArray())
            socket.getOutputStream().flush()

            val statusLine = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                ?: throw IllegalStateException("Empty HTTP proxy response")
            val statusCode = statusLine.substringAfter(' ', "").substringBefore(' ').toIntOrNull()
                ?: throw IllegalStateException("Malformed HTTP proxy response: $statusLine")
            if (statusCode == HTTP_PROXY_AUTH_REQUIRED) {
                throw SocksAuthenticationException("HTTP proxy authentication failed ($statusCode)")
            }
            if (statusCode != HTTP_OK) {
                throw IllegalStateException("HTTP proxy CONNECT failed with $statusCode")
            }
        }
        return elapsedMs(startedAt)
    }

    private fun checkMtProtoProxy(
        input: ProxyInput,
        type: ProxyType.Mtproto,
    ): Long {
        val secret = parseMtProtoSecret(type.secret)
        val startedAt = System.nanoTime()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(input.server, input.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            // Minimal obfuscated2-style client preamble: init block derived from the
            // secret followed by a transport tag. A connectivity+latency probe only -
            // we do not drive a full MTProto session through the tunnel.
            val preamble = ByteArray(INIT_BLOCK_LENGTH + TRANSPORT_TAG_LENGTH)
            secureRandom.nextBytes(preamble)
            if (preamble[0].toInt() in FORBIDDEN_FIRST_BYTES) {
                preamble[0] = (preamble[0].toInt() and 0x7F).toByte()
            }
            System.arraycopy(secret, 0, preamble, INIT_BLOCK_LENGTH, TRANSPORT_TAG_LENGTH)
            socket.getOutputStream().write(preamble)
            socket.getOutputStream().flush()

            // Any bytes back (or a clean close after our preamble) proves the tunnel
            // accepted traffic; silence past the read timeout surfaces as TIMEOUT.
            // Server bytes (or even a clean close after our preamble) prove the tunnel
            // accepted traffic; treat EOF as success, silence surfaces as TIMEOUT below.
            socket.getInputStream().read()
        }
        return elapsedMs(startedAt)
    }

    private fun parseMtProtoSecret(rawSecret: String): ByteArray {
        val cleaned = rawSecret.trim().removePrefix(SECRET_URL_SCHEME_PREFIX)
        val hexSecret = if (cleaned.length == ENCODED_SECRET_LENGTH_WITH_DC) {
            cleaned.substring(DC_PREFIX_LENGTH)
        } else {
            cleaned
        }
        if (hexSecret.length != MIN_HEX_SECRET_LENGTH && hexSecret.length != MEDIUM_HEX_SECRET_LENGTH) {
            throw InvalidProxySecretException(
                "MTProto secret must be ${MIN_HEX_SECRET_LENGTH} or $MEDIUM_HEX_SECRET_LENGTH hex chars",
            )
        }
        return try {
            hexSecret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: NumberFormatException) {
            throw InvalidProxySecretException("MTProto secret is not valid hex")
        }
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val DEFAULT_DC_ID = 2

        const val SOCKS_VERSION = 0x05
        const val METHOD_NO_AUTH = 0x00
        const val METHOD_USERNAME_PASSWORD = 0x02
        const val SOCKS_COMMAND_CONNECT = 0x01
        const val SOCKS_REPLY_SUCCESS = 0x00
        const val SOCKS_REPLY_AUTH_FAILED = 0x02
        const val ADDR_TYPE_IPV4 = 0x01
        const val ADDR_TYPE_DOMAIN = 0x03
        const val ADDR_TYPE_IPV6 = 0x04
        const val AUTH_VERSION = 0x01
        const val AUTH_STATUS_SUCCESS = 0x00
        const val MAX_AUTH_FIELD_BYTES = 255

        const val HTTP_OK = 200
        const val HTTP_PROXY_AUTH_REQUIRED = 407

        const val SECRET_URL_SCHEME_PREFIX = "ee"
        const val ENCODED_SECRET_LENGTH_WITH_DC = 34
        const val DC_PREFIX_LENGTH = 2
        const val MIN_HEX_SECRET_LENGTH = 16
        const val MEDIUM_HEX_SECRET_LENGTH = 32

        const val INIT_BLOCK_LENGTH = 64
        const val TRANSPORT_TAG_LENGTH = 4
        val FORBIDDEN_FIRST_BYTES = 0x00..0x07

        val secureRandom = java.security.SecureRandom()

        fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000
    }

    private class SocksAuthenticationException(message: String) : IllegalStateException(message)

    private class InvalidProxySecretException(message: String) : IllegalStateException(message)
}
