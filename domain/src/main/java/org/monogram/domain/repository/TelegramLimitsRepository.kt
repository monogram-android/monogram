package org.monogram.domain.repository

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.TelegramLimits

interface TelegramLimitsRepository {
    val limits: StateFlow<TelegramLimits>

    suspend fun refresh()
}
