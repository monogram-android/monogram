package org.monogram.domain.repository

/** Controlled backend selection for developer validation of an account migration. */
interface TelegramBackendSwitchRepository {
    suspend fun switchTo(backendMode: TelegramBackendMode)
}
