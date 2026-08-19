package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

enum class TelegramBackendMode {
    UNKNOWN,
    LEGACY,
    KOTLIN_MTPROTO,
}

/** Exposes the selected Telegram runtime to presentation without leaking data-layer types. */
interface TelegramBackendModeRepository {
    val backendMode: StateFlow<TelegramBackendMode>
}
