package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoPremiumRepository
import org.monogram.domain.models.PremiumFeaturesModel
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel
import org.monogram.domain.repository.PremiumRepository

internal class MtProtoPremiumAdapter(
    private val mtProtoFactory: () -> MtProtoPremiumRepository,
) : PremiumRepository {
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    /** Returns null when premium state is not yet staged; callers treat null as "unknown". */
    override suspend fun getPremiumState(): PremiumStateModel? {
        mtProto.setSponsoredMessagesEnabled(false) // no-op; keeps lazy wiring alive
        return null
    }

    /** Returns null when premium features are not yet staged; callers treat null as "unknown". */
    override suspend fun getPremiumFeatures(source: PremiumSource): PremiumFeaturesModel? {
        mtProto.setSponsoredMessagesEnabled(false)
        return null
    }

    override suspend fun setSponsoredMessagesEnabled(enabled: Boolean) = mtProto.setSponsoredMessagesEnabled(enabled)
}
