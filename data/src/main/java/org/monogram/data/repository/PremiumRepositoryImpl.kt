package org.monogram.data.repository

import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.mapper.user.toApi
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.PremiumFeatureType
import org.monogram.domain.models.PremiumLimitType
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel
import org.monogram.domain.repository.PremiumRepository

class PremiumRepositoryImpl(
    private val remote: UserRemoteDataSource
) : PremiumRepository {
    override suspend fun getPremiumState(): PremiumStateModel? {
        val state = remote.getPremiumState() ?: return null
        return state.toDomain()
    }

    override suspend fun getPremiumFeatures(source: PremiumSource): List<PremiumFeatureType> {
        val tdSource = source.toApi() ?: return emptyList()
        val result = remote.getPremiumFeatures(tdSource) ?: return emptyList()
        return result.features.map { it.toDomain() }
    }

    override suspend fun getPremiumLimit(limitType: PremiumLimitType): Int {
        val tdType = limitType.toApi() ?: return 0
        return remote.getPremiumLimit(tdType)?.premiumValue ?: 0
    }
}