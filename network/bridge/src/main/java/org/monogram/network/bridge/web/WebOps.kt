package org.monogram.network.bridge.web

import org.monogram.core.common.Outcome
import org.monogram.core.models.InstantViewPage

interface WebOps {
    suspend fun getWebPage(url: String, hash: Int = 0): Outcome<InstantViewPage> =
        Outcome.Err("unsupported")

    suspend fun getWebPagePreview(message: String): Outcome<InstantViewPage> =
        getWebPage(message, 0)
}
