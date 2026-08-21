package org.monogram.data.mtproto

import android.util.Log
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.domain.repository.AuthRepository
import org.monogram.domain.repository.AuthStep
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.transport.MtProtoRpcTransport

internal fun interface MtProtoSessionTransportFactory {
    suspend fun open(accountSlot: String): MtProtoRpcTransport

    suspend fun open(accountSlot: String, dcId: Int): MtProtoRpcTransport = open(accountSlot)
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
    authRepository: AuthRepository,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val recovery: MtProtoRoomUpdateRecovery,
    private val liveUpdateApplier: MtProtoRoomLiveUpdateApplier,
    private val storyRefresh: MtProtoStoryRefreshRepository = NoOpMtProtoStoryRefreshRepository,
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
            authRepository = authRepository,
        )
    }

    override fun resetLiveSession() {
        activeTransport?.close()
        activeTransport = null
    }

    private fun CoroutineScope.launchSelectedSession(
        authRepository: AuthRepository,
    ) {
        launch {
            authRepository.authState
                .collectLatest { authStep ->
                    if (authStep !is AuthStep.Ready) {
                        Log.i(TAG, "MTProto authorization is not ready; live updates stopped")
                        resetLiveSession()
                        return@collectLatest
                    }
                    Log.i(TAG, "MTProto authorization ready; starting live updates")
                    while (runSelectedSession()) {
                        Log.i(TAG, "MTProto live session reconnect scheduled")
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
        Log.i(TAG, "MTProto live transport opened")
        try {
            val session = when (val opened = recovery.open(scope, transport) { }) {
                is MtProtoRoomRecoveryOpenResult.Opened -> opened.session
                MtProtoRoomRecoveryOpenResult.CorruptState -> {
                    Log.e(TAG, "MTProto update state is corrupt; refusing to start live updates")
                    return false
                }
            }
            session.initialize()
            Log.i(TAG, "MTProto update recovery initialized")
            when (session.recoverAndReplay { }) {
                is MtProtoRoomRecoveryResult.Completed -> Log.i(TAG, "MTProto update recovery completed")
                MtProtoRoomRecoveryResult.ResyncRequired -> {
                    Log.e(TAG, "MTProto difference requires a full resync; refusing live updates")
                    return false
                }
            }
            runCatching { storyRefresh.refreshInitialLists() }
                .onFailure { Log.w(TAG, "MTProto story refresh failed; retaining previous projections", it) }
            dialogs.getDialogs(accountSlot)
            val inbox = transport.updates ?: run {
                Log.w(TAG, "MTProto live transport has no update inbox")
                return true
            }
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
            Log.i(TAG, "MTProto live transport closed")
            transport.close()
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val RECONNECT_DELAY_MILLIS = 1_000L
        const val TAG = "MtProtoLiveUpdates"
    }
}
