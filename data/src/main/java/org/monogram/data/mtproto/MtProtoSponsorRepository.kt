package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.repository.SponsorRepository
import org.monogram.domain.repository.SponsorState

/** MTProto-backed sponsor repository; sponsor data is a client-side preference. */
internal class MtProtoSponsorRepository(
    private val scope: CoroutineScope,
) : SponsorRepository {
    private val _sponsorState = MutableStateFlow(SponsorState())
    override val sponsorState: StateFlow<SponsorState> = _sponsorState.asStateFlow()

    override fun forceSponsorSync() {
        scope.launch {
            _sponsorState.value = _sponsorState.value.copy(
                isLoaded = true,
                lastSyncAt = System.currentTimeMillis(),
            )
        }
    }
}
