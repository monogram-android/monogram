package org.monogram.data.mtproto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.TelegramLimits
import org.monogram.domain.repository.TelegramLimitsRepository

/** Uses protocol-neutral defaults until MTProto exposes account limit metadata. */
internal class MtProtoLimitsRepository : TelegramLimitsRepository {
    private val mtProtoLimits = MutableStateFlow(TelegramLimits.DEFAULTS)

    override val limits: StateFlow<TelegramLimits> get() = mtProtoLimits

    override suspend fun refresh() = Unit
}
