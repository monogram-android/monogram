package org.monogram.data.repository

import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.data.mapper.user.toApi
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.PremiumFeaturesModel
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

    override suspend fun getPremiumFeatures(source: PremiumSource): PremiumFeaturesModel? {
        val tdSource = source.toApi() ?: return null
        return remote.getPremiumFeatures(tdSource)?.toDomain()
    }

    override suspend fun setSponsoredMessagesEnabled(enabled: Boolean) {
        remote.setSponsoredMessagesEnabled(enabled)
    }
}
