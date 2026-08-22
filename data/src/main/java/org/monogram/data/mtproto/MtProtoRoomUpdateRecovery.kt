package org.monogram.data.mtproto

import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.updates.MtProtoChannelDifferenceBatch
import org.monogram.mtproto.updates.MtProtoChannelUpdateRecovery
import org.monogram.mtproto.updates.MtProtoChannelUpdateRecoveryResult
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch
import org.monogram.mtproto.updates.MtProtoUpdateState
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
    private val stateStore: MtProtoTransactionalUpdateStateStore,
    private val channelRecovery: MtProtoChannelUpdateRecovery,
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

    /** Repairs one channel pts gap through `updates.getChannelDifference` and replays blocked envelopes. */
    suspend fun recoverChannel(channelId: Long): MtProtoChannelUpdateRecoveryResult {
        val currentPts = when (val loaded = stateStore.loadState(scope)) {
            MtProtoUpdateStateLoadResult.Missing,
            MtProtoUpdateStateLoadResult.Corrupt,
            -> return MtProtoChannelUpdateRecoveryResult.ResyncRequired
            is MtProtoUpdateStateLoadResult.Found -> loaded.state.channelPts[channelId] ?: 0
        }
        val result = channelRecovery.recover(channelId, currentPts)
        if (result is MtProtoChannelUpdateRecoveryResult.Completed) {
            liveUpdateApplier.replayPending(scope) { }
        }
        return result
    }
}

/** Binds difference recovery to the scoped Room transaction boundary without selecting a backend. */
internal class MtProtoRoomUpdateRecovery(
    private val stateStore: MtProtoTransactionalUpdateStateStore,
    private val liveUpdateApplier: MtProtoRoomLiveUpdateApplier,
    private val cloudObjectStager: MtProtoCloudObjectStager = NoOpMtProtoCloudObjectStager,
    private val chatProjectionStore: MtProtoChatProjectionStore = NoOpMtProtoChatProjectionStore,
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
                stateStore = stateStore,
                channelRecovery = MtProtoChannelUpdateRecovery(
                    executor = MtProtoUpdateRecoveryExecutor { method: TlMethod<*> ->
                        execute(transport, method)
                    },
                    resolveChannel = { channelId ->
                        chatProjectionStore.get(scope, channelId)?.accessHash
                            ?.takeIf { it != 0L }
                            ?.let { InputChannel_d22292516d(channelId, it) }
                    },
                    applyBatch = { batch ->
                        val current = when (val loaded = stateStore.loadState(scope)) {
                            MtProtoUpdateStateLoadResult.Missing,
                            MtProtoUpdateStateLoadResult.Corrupt,
                            -> error("MTProto channel difference arrived without usable update state")
                            is MtProtoUpdateStateLoadResult.Found -> loaded.state
                        }
                        val next = current.copy(
                            channelPts = current.channelPts + (batch.channelId to batch.pts),
                        )
                        stateStore.applyState(scope, next) {
                            cloudObjectStager.stageChannelDifference(scope, batch)
                        }
                    },
                ),
                recovery = MtProtoUpdateRecovery(
                    executor = MtProtoUpdateRecoveryExecutor { method: TlMethod<*> ->
                        execute(transport, method)
                    },
                    applyBatch = { batch ->
                        applyCursorBatch(scope, batch.cursor) {
                            cloudObjectStager.stageDifference(scope, batch)
                            applyEntities(batch)
                        }
                    },
                    initialCursor = state?.cursor,
                ),
            ),
        )
    }

    /** Advances the global cursor transactionally while preserving persisted channel pts. */
    private suspend fun applyCursorBatch(
        scope: MtProtoAuthKeyScope,
        cursor: MtProtoUpdateCursor,
        applyEntities: suspend () -> Unit,
    ) {
        val base = when (val loaded = stateStore.loadState(scope)) {
            is MtProtoUpdateStateLoadResult.Found -> loaded.state
            MtProtoUpdateStateLoadResult.Missing,
            MtProtoUpdateStateLoadResult.Corrupt,
            -> MtProtoUpdateState(cursor)
        }
        stateStore.applyState(scope, base.copy(cursor = cursor), applyEntities)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun execute(transport: MtProtoRpcTransport, method: TlMethod<*>): TlObject =
        transport.execute(method as TlMethod<TlObject>)
}
