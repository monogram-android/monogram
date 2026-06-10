package org.monogram.data.infra

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.core.coRunCatching
import org.monogram.data.datasource.remote.ProxyRemoteDataSource

@OptIn(FlowPreview::class)
internal class PresenceCoordinator(
    private val proxyRemoteSource: ProxyRemoteDataSource,
    authorizationReady: StateFlow<Boolean>,
    appForegroundTracker: AppForegroundTracker,
    networkSnapshotProvider: NetworkSnapshotProvider,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 750L
) {
    private val tag = "PresenceCoordinator"

    private val _presenceFlow = MutableStateFlow(false)
    val presenceFlow: StateFlow<Boolean> = _presenceFlow.asStateFlow()

    private var lastAppliedPresence: Boolean? = null

    init {
        scope.launch(dispatchers.default) {
            combine(
                authorizationReady,
                appForegroundTracker.isForeground,
                networkSnapshotProvider.snapshot
            ) { isReady, isForeground, networkSnapshot ->
                PresenceState(
                    isAuthorizationReady = isReady,
                    shouldBeOnline = isReady && isForeground && networkSnapshot.isUsable
                )
            }
                .distinctUntilChanged()
                .debounce(debounceMs)
                .collect(::applyPresence)
        }
    }

    private suspend fun applyPresence(state: PresenceState) {
        _presenceFlow.value = state.shouldBeOnline
        if (lastAppliedPresence == null && !state.isAuthorizationReady) return
        if (lastAppliedPresence == state.shouldBeOnline) return
        lastAppliedPresence = state.shouldBeOnline

        coRunCatching {
            withContext(dispatchers.io) {
                proxyRemoteSource.setOption(
                    "online",
                    TdApi.OptionValueBoolean(state.shouldBeOnline)
                )
            }
        }.onFailure { error ->
            Log.w(tag, "Failed to apply presence=${state.shouldBeOnline}", error)
        }
    }

    private data class PresenceState(
        val isAuthorizationReady: Boolean,
        val shouldBeOnline: Boolean
    )
}
