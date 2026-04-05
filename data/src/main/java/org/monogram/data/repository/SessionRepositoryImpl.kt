package org.monogram.data.repository

import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.mapper.toDomain
import org.monogram.domain.models.SessionModel
import org.monogram.domain.repository.SessionRepository

class SessionRepositoryImpl(
    private val remote: SettingsRemoteDataSource
) : SessionRepository {

    override suspend fun getActiveSessions(): List<SessionModel> {
        return remote.getActiveSessions()?.sessions?.map { it.toDomain() } ?: emptyList()
    }

    override suspend fun terminateSession(sessionId: Long): Boolean {
        return remote.terminateSession(sessionId)
    }

    override suspend fun confirmQrCode(link: String): Boolean {
        return remote.confirmQrCode(link)
    }
}