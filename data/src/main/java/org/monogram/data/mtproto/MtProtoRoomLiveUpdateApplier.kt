package org.monogram.data.mtproto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.updates.MtProtoUpdateMetadataExtractor
import org.monogram.mtproto.updates.MtProtoUpdateMetadataResult
import org.monogram.mtproto.updates.MtProtoUpdateState
import org.monogram.mtproto.updates.MtProtoUpdateStateTransition
import org.monogram.mtproto.updates.MtProtoUpdateStateTransitionResult

internal sealed interface MtProtoLiveUpdateApplyResult {
    data class Applied(val state: MtProtoUpdateState) : MtProtoLiveUpdateApplyResult
    data object Duplicate : MtProtoLiveUpdateApplyResult
    data object NotInitialized : MtProtoLiveUpdateApplyResult
    data object CorruptState : MtProtoLiveUpdateApplyResult
    data object RecoveryRequired : MtProtoLiveUpdateApplyResult
    data class Unsupported(val constructorId: UInt) : MtProtoLiveUpdateApplyResult
    data class Gap(val transition: MtProtoUpdateStateTransitionResult) : MtProtoLiveUpdateApplyResult
}

/** Serial live-envelope boundary. No entities or cursors are committed unless all counters are contiguous. */
internal class MtProtoRoomLiveUpdateApplier(
    private val stateStore: MtProtoTransactionalUpdateStateStore,
) {
    private val mutex = Mutex()

    suspend fun apply(
        scope: MtProtoAuthKeyScope,
        envelope: Updates_faf6aaa3d5,
        applyEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoLiveUpdateApplyResult = mutex.withLock {
        val initial = when (val loaded = stateStore.loadState(scope)) {
            MtProtoUpdateStateLoadResult.Missing -> return@withLock MtProtoLiveUpdateApplyResult.NotInitialized
            MtProtoUpdateStateLoadResult.Corrupt -> return@withLock MtProtoLiveUpdateApplyResult.CorruptState
            is MtProtoUpdateStateLoadResult.Found -> loaded.state
        }
        val metadata = when (val extracted = MtProtoUpdateMetadataExtractor.extract(envelope)) {
            MtProtoUpdateMetadataResult.RecoveryRequired -> {
                return@withLock MtProtoLiveUpdateApplyResult.RecoveryRequired
            }
            is MtProtoUpdateMetadataResult.Unsupported -> {
                return@withLock MtProtoLiveUpdateApplyResult.Unsupported(extracted.constructorId)
            }
            is MtProtoUpdateMetadataResult.Ordered -> extracted.metadata
        }
        when (val transition = MtProtoUpdateStateTransition.apply(initial, metadata)) {
            MtProtoUpdateStateTransitionResult.Duplicate -> MtProtoLiveUpdateApplyResult.Duplicate
            is MtProtoUpdateStateTransitionResult.GlobalGap,
            is MtProtoUpdateStateTransitionResult.ChannelGap -> MtProtoLiveUpdateApplyResult.Gap(transition)
            is MtProtoUpdateStateTransitionResult.Applied -> {
                stateStore.applyState(scope, transition.state) { applyEntities(envelope) }
                MtProtoLiveUpdateApplyResult.Applied(transition.state)
            }
        }
    }
}
