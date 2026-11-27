package org.monogram.network.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Connection state for one account's client. Idle and Connected need no UI label. */
enum class ConnectionStatus { Idle, Connected, WaitingForNetwork, Connecting, Updating, ConnectingToProxy }

internal fun Flow<ConnectionStatus>.withNetworkAvailability(available: Flow<Boolean>): Flow<ConnectionStatus> =
    combine(available) { status, online ->
        if (!online && status != ConnectionStatus.Idle) ConnectionStatus.WaitingForNetwork else status
    }.distinctUntilChanged()

internal fun defaultNetworkAvailable(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
        send(true)
        close()
        return@callbackFlow
    }
    val lock = Any()
    var current: Network? = null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                current = network
                trySend(true)
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (current == network) {
                    current = null
                    trySend(false)
                }
            }
        }
    }
    var registered = false
    try {
        synchronized(lock) {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            current = manager.activeNetwork
            trySend(current != null)
        }
        awaitClose()
    } catch (_: SecurityException) {
        send(true)
    } finally {
        if (registered) manager.unregisterNetworkCallback(callback)
    }
}.conflate()