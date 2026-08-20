package org.monogram.data.mtproto

import android.util.Log
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.data.backend.TelegramBackendKind
import org.monogram.data.backend.TelegramBackendSelectionStore
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.transport.MtProtoRpcTransport

internal fun interface MtProtoSessionTransportFactory {
    suspend fun open(accountSlot: String): MtProtoRpcTransport
}

internal fun interface MtProtoLiveSessionResetter {
    fun resetLiveSession()
}

/**
 * Owns one selected account's update transport from authorization until deselection/reset.
 *
 * Recovery writes initial/difference entities through the Room transaction path. Live envelopes
 * remain durable until the transactional applier accepts them, so cancellation only closes the
 * transport and leaves replayable work in Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MtProtoLiveUpdateCoordinator(
    selectionStore: TelegramBackendSelectionStore,
    authRepository: AuthRepository,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val recovery: MtProtoRoomUpdateRecovery,
    private val liveUpdateApplier: MtProtoRoomLiveUpdateApplier,
    private val dialogs: DialogSnapshotRepository = object : DialogSnapshotRepository {
        override suspend fun getDialogs(accountId: String) = emptyList<org.monogram.domain.models.DialogSnapshotModel>()
    },
    scope: CoroutineScope,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoLiveSessionResetter {
    @Volatile
    private var activeTransport: MtProtoRpcTransport? = null

    init {
        scope.launchSelectedSession(
            selectionStore = selectionStore,
            authRepository = authRepository,
        )
    }

    override fun resetLiveSession() {
        activeTransport?.close()
        activeTransport = null
    }

    private fun CoroutineScope.launchSelectedSession(
        selectionStore: TelegramBackendSelectionStore,
        authRepository: AuthRepository,
    ) {
        launch {
            combine(
                selectionStore.observe(accountSlot),
                authRepository.authState,
            ) { backend, authStep -> backend to authStep }
                .collectLatest { (backend, authStep) ->
                    if (backend != TelegramBackendKind.KOTLIN_MTPROTO || authStep !is AuthStep.Ready) {
                        resetLiveSession()
                        return@collectLatest
                    }
                    while (runSelectedSession()) {
                        delay(RECONNECT_DELAY_MILLIS)
                    }
                }
        }
    }

    /** Returns true only when a transient transport termination should reconnect. */
    private suspend fun runSelectedSession(): Boolean {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(
            accountSlot = accountSlot,
            environment = MtProtoEnvironment.PRODUCTION,
            dcId = config.endpoint.dcId,
        )
        val transport = try {
            transportFactory.open(accountSlot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "MTProto session open failed; scheduling reconnect", failure)
            return true
        }
        activeTransport = transport
        try {
            val session = when (val opened = recovery.open(scope, transport) { }) {
                is MtProtoRoomRecoveryOpenResult.Opened -> opened.session
                MtProtoRoomRecoveryOpenResult.CorruptState -> {
                    Log.e(TAG, "MTProto update state is corrupt; refusing to start live updates")
                    return false
                }
            }
            session.initialize()
            when (session.recoverAndReplay { }) {
                is MtProtoRoomRecoveryResult.Completed -> Unit
                MtProtoRoomRecoveryResult.ResyncRequired -> {
                    Log.e(TAG, "MTProto difference requires a full resync; refusing live updates")
                    return false
                }
            }
            dialogs.getDialogs(accountSlot)
            val inbox = transport.updates ?: return true
            while (true) {
                val envelope = inbox.receive() ?: return true
                when (liveUpdateApplier.apply(scope, envelope) { }) {
                    is MtProtoLiveUpdateApplyResult.Applied,
                    MtProtoLiveUpdateApplyResult.Duplicate -> Unit

                    is MtProtoLiveUpdateApplyResult.Gap,
                    MtProtoLiveUpdateApplyResult.RecoveryRequired -> {
                        if (session.recoverAndReplay { } is MtProtoRoomRecoveryResult.ResyncRequired) {
                            Log.e(TAG, "MTProto live update requires a full resync; stopping session")
                            return false
                        }
                    }

                    is MtProtoLiveUpdateApplyResult.Unsupported,
                    is MtProtoLiveUpdateApplyResult.CorruptEnvelope,
                    MtProtoLiveUpdateApplyResult.CorruptState,
                    MtProtoLiveUpdateApplyResult.NotInitialized -> {
                        Log.e(TAG, "MTProto live update could not be applied; stopping session")
                        return false
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "MTProto live session failed; scheduling reconnect", failure)
            return true
        } finally {
            if (activeTransport === transport) activeTransport = null
            transport.close()
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val RECONNECT_DELAY_MILLIS = 1_000L
        const val TAG = "MtProtoLiveUpdates"
    }
}
