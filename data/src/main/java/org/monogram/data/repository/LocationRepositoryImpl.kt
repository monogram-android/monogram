package org.monogram.data.repository

import org.monogram.data.datasource.remote.NominatimRemoteDataSource
import org.monogram.domain.repository.LocationRepository

class LocationRepositoryImpl(
    private val remote: NominatimRemoteDataSource
) : LocationRepository {
    override suspend fun reverseGeocode(lat: Double, lon: Double) =
        remote.reverseGeocode(lat, lon)
}