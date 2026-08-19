package org.monogram.data.backend

import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.domain.repository.TelegramBackendSwitchRepository

internal class TelegramBackendSwitchRepositoryImpl(
    private val switchService: TelegramBackendSwitchService,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : TelegramBackendSwitchRepository {
    override suspend fun switchTo(backendMode: TelegramBackendMode) {
        switchService.switch(accountId, backendMode.toData())
    }

    private fun TelegramBackendMode.toData() = when (this) {
        TelegramBackendMode.LEGACY -> TelegramBackendKind.LEGACY
        TelegramBackendMode.KOTLIN_MTPROTO -> TelegramBackendKind.KOTLIN_MTPROTO
        TelegramBackendMode.UNKNOWN -> error("Cannot switch to an unknown Telegram backend")
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
