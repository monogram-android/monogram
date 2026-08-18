package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateRecovery
import org.monogram.mtproto.updates.MtProtoUpdateRecoveryExecutor

internal sealed interface MtProtoRoomRecoveryOpenResult {
    data class Opened(val session: MtProtoRoomRecoverySession) : MtProtoRoomRecoveryOpenResult
    data object CorruptState : MtProtoRoomRecoveryOpenResult
}

internal sealed interface MtProtoRoomRecoveryResult {
    data class Completed(
        val cursor: MtProtoUpdateCursor,
        val replay: MtProtoPendingReplayResult,
    ) : MtProtoRoomRecoveryResult

    data object ResyncRequired : MtProtoRoomRecoveryResult
}

internal class MtProtoRoomRecoverySession(
    private val scope: MtProtoAuthKeyScope,
    private val recovery: MtProtoUpdateRecovery,
    private val liveUpdateApplier: MtProtoRoomLiveUpdateApplier,
) {
    fun currentCursor(): MtProtoUpdateCursor? = recovery.currentCursor()

    suspend fun initialize(): MtProtoUpdateCursor = recovery.initialize()

    suspend fun recoverAndReplay(
        applyLiveEntities: suspend (Updates_faf6aaa3d5) -> Unit,
    ): MtProtoRoomRecoveryResult = liveUpdateApplier.recoverAndReplay(
        scope = scope,
        recover = recovery::recover,
        applyEntities = applyLiveEntities,
    )
}

/** Binds difference recovery to the scoped Room transaction boundary without selecting a backend. */
internal class MtProtoRoomUpdateRecovery(
    private val stateStore: MtProtoRecoveryStateStore,
    private val liveUpdateApplier: MtProtoRoomLiveUpdateApplier,
    private val cloudObjectStager: MtProtoCloudObjectStager = NoOpMtProtoCloudObjectStager,
) {
    suspend fun open(
        scope: MtProtoAuthKeyScope,
        transport: MtProtoRpcTransport,
        applyEntities: suspend (MtProtoUpdateDifferenceBatch) -> Unit,
    ): MtProtoRoomRecoveryOpenResult {
        val state = when (val loaded = stateStore.loadState(scope)) {
            MtProtoUpdateStateLoadResult.Missing -> null
            MtProtoUpdateStateLoadResult.Corrupt -> return MtProtoRoomRecoveryOpenResult.CorruptState
            is MtProtoUpdateStateLoadResult.Found -> loaded.state
        }
        return MtProtoRoomRecoveryOpenResult.Opened(
            MtProtoRoomRecoverySession(
                scope = scope,
                liveUpdateApplier = liveUpdateApplier,
                recovery = MtProtoUpdateRecovery(
                    executor = MtProtoUpdateRecoveryExecutor { method: TlMethod<*> ->
                        execute(transport, method)
                    },
                    applyBatch = { batch ->
                        stateStore.applyRecovery(scope, batch.cursor) {
                            cloudObjectStager.stageDifference(scope, batch)
                            applyEntities(batch)
                        }
                    },
                    initialCursor = state?.cursor,
                ),
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun execute(transport: MtProtoRpcTransport, method: TlMethod<*>): TlObject =
        transport.execute(method as TlMethod<TlObject>)
}
