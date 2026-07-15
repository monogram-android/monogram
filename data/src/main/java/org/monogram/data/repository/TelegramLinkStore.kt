package org.monogram.data.repository

import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

internal const val TELEGRAM_OPTION_T_ME_URL = "t_me_url"
internal const val TELEGRAM_KEY_T_ME_URL = "telegram.t_me_url"
internal const val TELEGRAM_KEY_T_ME_URL_LOADED = "telegram.t_me_url_loaded"

internal suspend fun KeyValueDao.persistTelegramBaseUrl(
    baseUrl: String?,
    isLoadedFromOption: Boolean
) {
    val normalized = baseUrl.normalizeTelegramBaseUrl() ?: TelegramLinkDomains.DEFAULT_BASE_URL
    insertValue(KeyValueEntity(TELEGRAM_KEY_T_ME_URL, normalized))
    insertValue(KeyValueEntity(TELEGRAM_KEY_T_ME_URL_LOADED, isLoadedFromOption.toString()))
}

internal fun String?.toTelegramLinkLoadedFlag(): Boolean {
    return this?.toBooleanStrictOrNull() ?: false
}
