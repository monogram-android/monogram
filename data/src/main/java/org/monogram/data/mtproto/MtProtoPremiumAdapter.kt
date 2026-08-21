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

    override suspend fun getPremiumState(): PremiumStateModel? = unsupported()

    override suspend fun getPremiumFeatures(source: PremiumSource): PremiumFeaturesModel? = unsupported()

    override suspend fun setSponsoredMessagesEnabled(enabled: Boolean) = mtProto.setSponsoredMessagesEnabled(enabled)

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto premium operations are not available")
}
