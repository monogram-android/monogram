package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.AttachMenuBotModel

interface AttachMenuBotRepository {
    fun getAttachMenuBots(): Flow<List<AttachMenuBotModel>>
}