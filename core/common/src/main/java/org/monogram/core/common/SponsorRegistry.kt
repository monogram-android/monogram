package org.monogram.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SponsorRegistry {
    private val ids = MutableStateFlow<Set<Long>>(emptySet())

    val sponsorIds: StateFlow<Set<Long>> = ids.asStateFlow()

    fun updateSponsorIds(values: Set<Long>) {
        if (ids.value != values) ids.value = values
    }
}
