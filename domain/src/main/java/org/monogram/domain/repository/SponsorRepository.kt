package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow

interface SponsorRepository {
    val sponsorState: StateFlow<SponsorState>

    fun forceSponsorSync()
}

data class SponsorState(
    val supporterIds: Set<Long> = emptySet(),
    val supportersCount: Int = 0,
    val isLoaded: Boolean = false,
    val isSyncInProgress: Boolean = false,
    val lastSyncAt: Long = 0L
)
