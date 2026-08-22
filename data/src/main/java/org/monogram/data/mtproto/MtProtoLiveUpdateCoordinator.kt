package org.monogram.data.mtproto

import android.util.Log
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.mtproto.updates.MtProtoChannelUpdateRecoveryResult
import org.monogram.mtproto.updates.MtProtoUpdateStateTransitionResult
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
        override suspend fun getDialogs(accountId: String) =
            emptyList<org.monogram.domain.models.DialogSnapshotModel>()
    }, 
    private val fullResync: (suspend (MtProtoAuthKeyScope) -> Unit)? = null,
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
                    var reconnectAttempts = 0
                    while (runSelectedSession()) {
                        reconnectAttempts++
                        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                            Log.e(TAG, "MTProto live session reconnect limit reached")
                            return@collectLatest
                        }
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
        var newSessionWatcher: Job? = null
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
                MtProtoRoomRecoveryResult.ResyncRequired -> return handleResyncRequired(scope)
            }
            refreshStoriesWithRetry()
            dialogs.getDialogs(accountSlot)
            newSessionWatcher = transport.newSessions?.let { events ->
                CoroutineScope(currentCoroutineContext()).launch {
                    events.collect { event ->
                        Log.w(TAG, "MTProto server created a new session after msg ${event.firstMessageId}; reconnecting for recovery")
                        transport.close()
                    }
                }
            }
            val inbox = transport.updates ?: run {
                Log.w(TAG, "MTProto live transport has no update inbox")
                return true
            }
            while (true) {
                val envelope = inbox.receive() ?: return true
                when (val result = liveUpdateApplier.apply(scope, envelope) { }) {
                    is MtProtoLiveUpdateApplyResult.Applied,
                    MtProtoLiveUpdateApplyResult.Duplicate -> Unit

                    is MtProtoLiveUpdateApplyResult.Gap -> {
                        val transition = result.transition
                        if (transition is MtProtoUpdateStateTransitionResult.ChannelGap) {
                            Log.i(TAG, "MTProto channel pts gap detected for channel ${transition.gap.channelId}; running channel difference recovery")
                            when (session.recoverChannel(transition.gap.channelId)) {
                                is MtProtoChannelUpdateRecoveryResult.Completed -> Unit
                                MtProtoChannelUpdateRecoveryResult.ResyncRequired -> return handleResyncRequired(scope)
                            }
                        } else if (session.recoverAndReplay { } is MtProtoRoomRecoveryResult.ResyncRequired) {
                            return handleResyncRequired(scope)
                        }
                    }

                    MtProtoLiveUpdateApplyResult.RecoveryRequired -> {
                        if (session.recoverAndReplay { } is MtProtoRoomRecoveryResult.ResyncRequired) {
                            return handleResyncRequired(scope)
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
            newSessionWatcher?.cancel()
            if (activeTransport === transport) activeTransport = null
            Log.i(TAG, "MTProto live transport closed")
            transport.close()
        }
    }

    private suspend fun refreshStoriesWithRetry() {
        repeat(STORY_REFRESH_ATTEMPTS) { attempt ->
            try {
                storyRefresh.refreshInitialLists()
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (attempt == STORY_REFRESH_ATTEMPTS - 1) {
                    Log.w(TAG, "MTProto story refresh failed; retaining previous projections", failure)
                } else {
                    delay(STORY_REFRESH_RETRY_DELAY_MILLIS * (attempt + 1))
                }
            }
        }
    }

    /**
     * Crash-safe `differenceTooLong` handling: clears durable update state and pending envelopes so
     * the reconnect re-initializes from a fresh `updates.getState` and dialog refresh. Without a
     * resync hook the session stops fail-closed.
     */
    private suspend fun handleResyncRequired(scope: MtProtoAuthKeyScope): Boolean {
        val resync = fullResync
        if (resync == null) {
            Log.e(TAG, "MTProto difference requires a full resync; refusing live updates")
            return false
        }
        Log.w(TAG, "MTProto difference requires a full resync; clearing durable update state")
        resync(scope)
        return true
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val RECONNECT_DELAY_MILLIS = 1_000L
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val STORY_REFRESH_ATTEMPTS = 3
        const val STORY_REFRESH_RETRY_DELAY_MILLIS = 1_000L
        const val TAG = "MtProtoLiveUpdates"
    }
}
