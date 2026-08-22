package org.monogram.data.mtproto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.domain.repository.TelegramLinkRepository

/** Keeps link construction available without reading remote options for MTProto accounts. */
internal class MtProtoTelegramLinkRepository : TelegramLinkRepository {
    private val mtProtoBaseUrl = MutableStateFlow(TelegramLinkDomains.DEFAULT_BASE_URL)

    override val baseUrl: StateFlow<String> get() = mtProtoBaseUrl

    override suspend fun buildUrl(path: String): String = buildTelegramUrl(mtProtoBaseUrl.value, path)
}

internal fun String?.normalizeTelegramBaseUrl(): String? {
    val value = this?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}"
        else -> "https://$value"
    }
}

internal fun buildTelegramUrl(baseUrl: String, path: String): String {
    val normalizedBaseUrl =
        baseUrl.normalizeTelegramBaseUrl() ?: TelegramLinkDomains.DEFAULT_BASE_URL
    val normalizedPath = path.trim().trimStart('/')
    return if (normalizedPath.isEmpty()) normalizedBaseUrl else "$normalizedBaseUrl/$normalizedPath"
}
