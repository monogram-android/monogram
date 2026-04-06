package org.monogram.domain.repository

import org.monogram.domain.models.PremiumFeatureType
import org.monogram.domain.models.PremiumLimitType
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel

interface PremiumRepository {
    suspend fun getPremiumState(): PremiumStateModel?
    suspend fun getPremiumFeatures(source: PremiumSource): List<PremiumFeatureType>
    suspend fun getPremiumLimit(limitType: PremiumLimitType): Int
}
