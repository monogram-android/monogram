package org.monogram.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.monogram.core.common.Outcome
import org.monogram.core.common.push.IncomingPush
import org.monogram.core.common.push.normalizeDecrypted
import org.monogram.core.common.push.normalizeIncomingBody
import org.monogram.core.common.push.parsePushPayload
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Routes encrypted push payloads to the account whose secret and user id own them. */
class AccountPushRouter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinators = ConcurrentHashMap<Long, AccountPushHandler>()

    @Volatile
    private var foreground = false

    @Volatile
    private var foregroundUserId: Long? = null

    @Synchronized
    fun register(accountUserId: Long, coordinator: AccountPushHandler) {
        if (accountUserId != 0L) {
            coordinators[accountUserId] = coordinator
            coordinator.setForeground(
                isAccountForeground(
                    foreground, foregroundUserId, accountUserId
                )
            )
        }
    }

    suspend fun unregister(accountUserId: Long) {
        coordinators.remove(accountUserId)?.let { coordinator ->
            try {
                coordinator.unregisterPush()
            } finally {
                coordinator.stop()
            }
        }
    }

    fun coordinator(accountUserId: Long): AccountPushHandler? = coordinators[accountUserId]

    @Synchronized
    fun setForeground(foreground: Boolean, activeUserId: Long?) {
        this.foreground = foreground
        foregroundUserId = activeUserId
        coordinators.forEach { (userId, coordinator) ->
            coordinator.setForeground(isAccountForeground(foreground, activeUserId, userId))
        }
    }

    fun onFcmToken(token: String, onComplete: () -> Unit) {
        scope.launch {
            try {
                coordinators.values.toList().forEach { coordinator ->
                    suspendCancellableCoroutine<Unit> { continuation ->
                        coordinator.onFcmToken(token) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            } finally {
                onComplete()
            }
        }
    }

    fun handleFcm(payload: String?, onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                when (val incoming = normalizeIncomingBody(true, payload?.encodeToByteArray())) {
                    IncomingPush.Wake -> wakeAll()
                    is IncomingPush.Decrypt -> routeEncrypted(incoming.cipher)
                    is IncomingPush.Present -> routePlain(incoming.json)
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private suspend fun routeEncrypted(cipher: String) {
        val candidates = coordinators.entries.toList()
        for ((userId, coordinator) in candidates) {
            when (val decrypted = coordinator.decryptFcmPayload(cipher)) {
                is Outcome.Ok -> when (val plain = normalizeDecrypted(decrypted.value)) {
                    is IncomingPush.Present -> {
                        val payload = parsePushPayload(plain.json)
                        if (payload.userId == userId) {
                            awaitCoordinator(coordinator, plain.json)
                            return
                        }
                    }

                    IncomingPush.Wake -> {
                        wakeAll()
                        return
                    }

                    is IncomingPush.Decrypt -> Unit
                }

                is Outcome.Err -> Unit
            }
        }
    }

    private suspend fun routePlain(json: String) {
        val userId = parsePushPayload(json).userId ?: return
        coordinators[userId]?.let { coordinator ->
            awaitCoordinator(coordinator, json)
        }
    }

    private suspend fun awaitCoordinator(coordinator: AccountPushHandler, json: String) =
        suspendCancellableCoroutine { continuation ->
            coordinator.handleDecryptedFcmPayload(json) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }

    private suspend fun wakeAll() {
        coordinators.values.toList().forEach { coordinator ->
            suspendCancellableCoroutine<Unit> { continuation ->
                coordinator.handleFcm(null) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }
}

internal fun isAccountForeground(
    appForeground: Boolean,
    activeUserId: Long?,
    accountUserId: Long,
): Boolean = appForeground && activeUserId == accountUserId
