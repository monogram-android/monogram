package org.monogram.data.mtproto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.updates.MtProtoUpdateMetadataExtractor
import org.monogram.mtproto.updates.MtProtoUpdateMetadataResult
import org.monogram.mtproto.updates.MtProtoUpdateRecoveryResult
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
    data class CorruptEnvelope(val sequenceId: Long) : MtProtoLiveUpdateApplyResult
}

internal sealed interface MtProtoPendingReplayResult {
    data class Completed(val processedCount: Int) : MtProtoPendingReplayResult
    data class Blocked(
        val processedCount: Int,
        val result: MtProtoLiveUpdateApplyResult,
    ) : MtProtoPendingReplayResult
}

/** Serial live-envelope boundary. No entities or cursors are committed unless all counters are contiguous. */
internal class MtProtoRoomLiveUpdateApplier(
    private val stateStore: MtProtoTransactionalUpdateStateStore,
    private val pendingStore: MtProtoPendingEnvelopeStore,
    private val cloudObjectStager: MtProtoCloudObjectStager = NoOpMtProtoCloudObjectStager,
) {
    private val mutex = Mutex()

    suspend fun apply(
        scope: MtProtoAuthKeyScope,
        envelope: Updates_faf6aaa3d5,
        applyEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoLiveUpdateApplyResult = mutex.withLock {
        val pending = pendingStore.enqueue(scope, envelope)
        processPending(scope, pending, applyEntities)
    }

    suspend fun replayPending(
        scope: MtProtoAuthKeyScope,
        applyEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoPendingReplayResult = mutex.withLock {
        replayPendingLocked(scope, acknowledgeRecoveryMarkers = false, applyEntities)
    }

    suspend fun recoverAndReplay(
        scope: MtProtoAuthKeyScope,
        recover: suspend () -> MtProtoUpdateRecoveryResult,
        applyEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoRoomRecoveryResult = mutex.withLock {
        when (val result = recover()) {
            is MtProtoUpdateRecoveryResult.Completed -> MtProtoRoomRecoveryResult.Completed(
                cursor = result.cursor,
                replay = replayPendingLocked(scope, acknowledgeRecoveryMarkers = true, applyEntities),
            )

            MtProtoUpdateRecoveryResult.ResyncRequired -> MtProtoRoomRecoveryResult.ResyncRequired
        }
    }

    private suspend fun replayPendingLocked(
        scope: MtProtoAuthKeyScope,
        acknowledgeRecoveryMarkers: Boolean,
        applyEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoPendingReplayResult {
        var processedCount = 0
        for (pending in pendingStore.pending(scope)) {
            if (
                acknowledgeRecoveryMarkers &&
                pending is MtProtoPendingEnvelope.Decoded &&
                pending.envelope === UpdatesTooLong
            ) {
                pendingStore.delete(pending.sequenceId)
                processedCount++
                continue
            }
            val result = processPending(scope, pending, applyEntities)
            if (result is MtProtoLiveUpdateApplyResult.Applied || result == MtProtoLiveUpdateApplyResult.Duplicate) {
                processedCount++
            } else {
                return MtProtoPendingReplayResult.Blocked(processedCount, result)
            }
        }
        return MtProtoPendingReplayResult.Completed(processedCount)
    }

    private suspend fun processPending(
        scope: MtProtoAuthKeyScope,
        pending: MtProtoPendingEnvelope,
        applyEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoLiveUpdateApplyResult {
        if (pending is MtProtoPendingEnvelope.Corrupt) {
            return MtProtoLiveUpdateApplyResult.CorruptEnvelope(pending.sequenceId)
        }
        pending as MtProtoPendingEnvelope.Decoded
        val envelope = pending.envelope
        val initial = when (val loaded = stateStore.loadState(scope)) {
            MtProtoUpdateStateLoadResult.Missing -> return MtProtoLiveUpdateApplyResult.NotInitialized
            MtProtoUpdateStateLoadResult.Corrupt -> return MtProtoLiveUpdateApplyResult.CorruptState
            is MtProtoUpdateStateLoadResult.Found -> loaded.state
        }
        val metadata = when (val extracted = MtProtoUpdateMetadataExtractor.extract(envelope)) {
            MtProtoUpdateMetadataResult.RecoveryRequired -> {
                return MtProtoLiveUpdateApplyResult.RecoveryRequired
            }
            is MtProtoUpdateMetadataResult.Unsupported -> {
                return MtProtoLiveUpdateApplyResult.Unsupported(extracted.constructorId)
            }
            is MtProtoUpdateMetadataResult.Ordered -> extracted.metadata
        }
        return when (val transition = MtProtoUpdateStateTransition.apply(initial, metadata)) {
            MtProtoUpdateStateTransitionResult.Duplicate -> {
                pendingStore.delete(pending.sequenceId)
                MtProtoLiveUpdateApplyResult.Duplicate
            }
            is MtProtoUpdateStateTransitionResult.GlobalGap,
            is MtProtoUpdateStateTransitionResult.ChannelGap -> MtProtoLiveUpdateApplyResult.Gap(transition)
            is MtProtoUpdateStateTransitionResult.Applied -> {
                stateStore.applyState(scope, transition.state) {
                    cloudObjectStager.stageLive(scope, envelope)
                    applyEntities(envelope)
                }
                pendingStore.delete(pending.sequenceId)
                MtProtoLiveUpdateApplyResult.Applied(transition.state)
            }
        }
    }
}
