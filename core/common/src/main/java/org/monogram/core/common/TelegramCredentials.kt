package org.monogram.core.common

data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String,
) {
    init {
        require(apiId > 0) { "apiId must be positive" }
        require(apiHash.isNotBlank()) { "apiHash must not be blank" }
    }
}
