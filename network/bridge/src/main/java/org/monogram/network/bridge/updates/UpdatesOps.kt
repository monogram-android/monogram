package org.monogram.network.bridge.updates

import kotlinx.coroutines.flow.Flow
import org.monogram.core.common.Outcome
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor

interface UpdatesOps {
    suspend fun getUpdatesState(): Outcome<UpdatesCursor>
    fun updates(): Flow<MtprotoUpdate>
    fun sessionLost(): Flow<Unit>
}
