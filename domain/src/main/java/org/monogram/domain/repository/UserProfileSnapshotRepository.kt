package org.monogram.domain.repository

import org.monogram.domain.models.UserProfileSnapshotModel

/** Read-only user projection used by backend candidates before full UserRepository parity. */
interface UserProfileSnapshotRepository {
    suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel?
    suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel?
}
