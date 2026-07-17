package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi
import org.monogram.data.mapper.toApi
import org.monogram.domain.models.webapp.ThemeParams

internal fun buildDefaultWebAppOpenParameters(theme: ThemeParams?): TdApi.WebAppOpenParameters =
    TdApi.WebAppOpenParameters().apply {
        applicationName = "android"
        mode = TdApi.WebAppOpenModeFullSize()
        this.theme = theme?.toApi()
    }
