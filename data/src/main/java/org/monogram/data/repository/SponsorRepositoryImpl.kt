package org.monogram.data.repository

import org.monogram.data.infra.SponsorSyncManager
import org.monogram.domain.repository.SponsorRepository

class SponsorRepositoryImpl(
    private val sponsorSyncManager: SponsorSyncManager
) : SponsorRepository {
    override fun forceSponsorSync() {
        sponsorSyncManager.forceSync()
    }
}