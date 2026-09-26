package org.monogram.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SponsorRegistry {
    private val ids = MutableStateFlow<Set<Long>>(emptySet())
    private val ready = MutableStateFlow(false)

    val sponsorIds: StateFlow<Set<Long>> = ids.asStateFlow()
    val loaded: StateFlow<Boolean> = ready.asStateFlow()

    fun updateSponsorIds(values: Set<Long>) {
        if (ids.value != values) ids.value = values
        ready.value = true
    }
}
