package org.monogram.mtproto.updates

sealed interface MtProtoUpdateStateTransitionResult {
    data class Applied(val state: MtProtoUpdateState) : MtProtoUpdateStateTransitionResult
    data object Duplicate : MtProtoUpdateStateTransitionResult
    data class GlobalGap(val gap: MtProtoUpdateOrderingResult.Gap) : MtProtoUpdateStateTransitionResult
    data class ChannelGap(val gap: MtProtoChannelUpdateOrderingResult.Gap) : MtProtoUpdateStateTransitionResult
}

/** Pure envelope transition used to calculate one atomic Room commit. */
object MtProtoUpdateStateTransition {
    suspend fun apply(
        initial: MtProtoUpdateState,
        metadata: MtProtoUpdateEnvelopeMetadata,
    ): MtProtoUpdateStateTransitionResult {
        var changed = false
        val global = MtProtoUpdateOrderingCoordinator(initial.cursor) {}
        when (val result = global.acceptBatch(metadata.global)) {
            is MtProtoUpdateOrderingResult.Applied -> changed = true
            MtProtoUpdateOrderingResult.Duplicate -> Unit
            is MtProtoUpdateOrderingResult.Gap -> return MtProtoUpdateStateTransitionResult.GlobalGap(result)
        }

        val afterGlobal = initial.copy(cursor = global.currentCursor())
        val channels = MtProtoChannelUpdateOrderingCoordinator(afterGlobal) {}
        when (val result = channels.acceptBatch(metadata.channels)) {
            is MtProtoChannelUpdateOrderingResult.Applied -> changed = true
            MtProtoChannelUpdateOrderingResult.Duplicate -> Unit
            is MtProtoChannelUpdateOrderingResult.Gap -> return MtProtoUpdateStateTransitionResult.ChannelGap(result)
        }

        val afterChannels = channels.currentState()
        metadata.envelope?.let { envelope ->
            val outer = MtProtoUpdateOrderingCoordinator(afterChannels.cursor) {}
            when (val result = outer.accept(envelope)) {
                is MtProtoUpdateOrderingResult.Applied -> {
                    changed = true
                    return MtProtoUpdateStateTransitionResult.Applied(
                        afterChannels.copy(cursor = outer.currentCursor())
                    )
                }
                MtProtoUpdateOrderingResult.Duplicate -> Unit
                is MtProtoUpdateOrderingResult.Gap -> return MtProtoUpdateStateTransitionResult.GlobalGap(result)
            }
        }

        return if (changed) {
            MtProtoUpdateStateTransitionResult.Applied(afterChannels)
        } else {
            MtProtoUpdateStateTransitionResult.Duplicate
        }
    }
}
