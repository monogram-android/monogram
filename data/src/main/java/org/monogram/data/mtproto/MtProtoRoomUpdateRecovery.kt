package org.monogram.data.mtproto

import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch
import org.monogram.mtproto.updates.MtProtoUpdateRecovery
import org.monogram.mtproto.updates.MtProtoUpdateRecoveryExecutor

internal sealed interface MtProtoRoomRecoveryOpenResult {
    data class Opened(val recovery: MtProtoUpdateRecovery) : MtProtoRoomRecoveryOpenResult
    data object CorruptState : MtProtoRoomRecoveryOpenResult
}

/** Binds difference recovery to the scoped Room transaction boundary without selecting a backend. */
internal class MtProtoRoomUpdateRecovery(
    private val stateStore: MtProtoRoomUpdateStateStore,
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
            MtProtoUpdateRecovery(
                executor = MtProtoUpdateRecoveryExecutor { method: TlMethod<*> ->
                    execute(transport, method)
                },
                applyBatch = { batch ->
                    stateStore.apply(scope, batch.cursor) {
                        applyEntities(batch)
                    }
                },
                initialCursor = state?.cursor,
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun execute(transport: MtProtoRpcTransport, method: TlMethod<*>): TlObject =
        transport.execute(method as TlMethod<TlObject>)
}
