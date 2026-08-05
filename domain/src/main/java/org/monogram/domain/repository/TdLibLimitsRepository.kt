package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.TdLibLimits

interface TdLibLimitsRepository {
    val limits: StateFlow<TdLibLimits>

    suspend fun refresh()
}
