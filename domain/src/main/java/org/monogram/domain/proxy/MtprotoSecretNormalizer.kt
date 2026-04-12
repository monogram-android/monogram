package org.monogram.domain.proxy

import java.util.Base64

object MtprotoSecretNormalizer {
    private val hexRegex = Regex("^[0-9a-fA-F]+$")

    fun normalize(secret: String): String? {
        val candidate = secret.trim()
        if (candidate.isEmpty()) return null

        if (hexRegex.matches(candidate)) {
            if (candidate.length % 2 != 0) return null
            return candidate.lowercase()
        }

        decodeBase64Like(candidate)?.let { decoded ->
            if (decoded.isNotEmpty()) return decoded.toHexLowercase()
        }

        return null
    }

    fun isValid(secret: String): Boolean = normalize(secret) != null

    private fun decodeBase64Like(value: String): ByteArray? {
        val padded = value.padEnd(value.length + ((4 - value.length % 4) % 4), '=')
        return runCatching { Base64.getUrlDecoder().decode(padded) }
            .recoverCatching { Base64.getDecoder().decode(padded) }
            .getOrNull()
    }

    private fun ByteArray.toHexLowercase(): String {
        val hexDigits = "0123456789abcdef"
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hexDigits[value ushr 4]
            chars[index * 2 + 1] = hexDigits[value and 0x0F]
        }
        return String(chars)
    }
}
