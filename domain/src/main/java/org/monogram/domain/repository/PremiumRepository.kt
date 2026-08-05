package org.monogram.domain.repository

import org.monogram.domain.models.PremiumFeaturesModel
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel

interface PremiumRepository {
    suspend fun getPremiumState(): PremiumStateModel?
    suspend fun getPremiumFeatures(source: PremiumSource): PremiumFeaturesModel?
    suspend fun setSponsoredMessagesEnabled(enabled: Boolean)
}
