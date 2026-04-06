package org.monogram.domain.repository

import org.monogram.domain.models.SessionModel

interface SessionRepository {
    suspend fun getActiveSessions(): List<SessionModel>
    suspend fun terminateSession(sessionId: Long): Boolean
    suspend fun confirmQrCode(link: String): Boolean
}