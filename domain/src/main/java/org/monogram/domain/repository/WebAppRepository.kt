package org.monogram.domain.repository

import org.monogram.domain.models.webapp.ThemeParams
import org.monogram.domain.models.webapp.WebAppInfoModel

interface WebAppRepository {
    suspend fun openWebApp(
        chatId: Long,
        botUserId: Long,
        url: String,
        themeParams: ThemeParams? = null
    ): WebAppInfoModel?

    suspend fun closeWebApp(launchId: Long)

    suspend fun sendWebAppResult(launchId: Long, queryId: String)
}