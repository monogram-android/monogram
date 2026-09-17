package org.monogram.network.bridge.web

import org.monogram.core.models.InstantViewDtoLike
import org.monogram.core.models.InstantViewPage
import org.monogram.core.models.InstantViewPages
import uniffi.monogram_mtproto.InstantViewDto

internal fun InstantViewDto.toModel(): InstantViewPage = InstantViewPages.parse(
    InstantViewDtoLike(
        url = url,
        displayUrl = displayUrl,
        title = title,
        siteName = siteName,
        description = description,
        webpageType = webpageType,
        hash = hash,
        hasInstantView = hasInstantView,
        part = part,
        rtl = rtl,
        v2 = v2,
        notModified = notModified,
        blocksJson = blocksJson,
    ),
)
