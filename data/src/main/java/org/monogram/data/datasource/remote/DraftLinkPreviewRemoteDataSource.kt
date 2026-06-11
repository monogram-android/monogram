package org.monogram.data.datasource.remote

import org.monogram.domain.models.DraftLinkPreview
import org.monogram.domain.models.DraftLinkPreviewRequest

interface DraftLinkPreviewRemoteDataSource {
    suspend fun getDraftLinkPreview(request: DraftLinkPreviewRequest): DraftLinkPreview?
}
