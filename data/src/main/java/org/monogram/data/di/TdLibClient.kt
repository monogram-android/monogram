package org.monogram.data.di

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TdLibException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

internal class TdLibClient {
    private val TAG = "TdLibClient"
    private val globalRetryAfterUntilMs = AtomicLong(0L)
    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 3,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated = _isAuthenticated.asStateFlow()

    init {
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            Client.execute(TdApi.SetLogStream(TdApi.LogStreamEmpty()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable TDLib logs", e)
        }
    }

    val updates: SharedFlow<TdApi.Update> = _updates

    private val client = Client.create(
        { result ->
            if (result is TdApi.Update) {
                if (result is TdApi.UpdateAuthorizationState) {
                    _isAuthenticated.value = result.authorizationState is TdApi.AuthorizationStateReady
                }
                _updates.tryEmit(result)
            }
        },
        { error ->
            Log.e(TAG, "Update exception handler", error)
        },
        { error ->
            Log.e(TAG, "Default exception handler", error)
        }
    )

    fun <T : TdApi.Object> send(function: TdApi.Function<T>, callback: (TdApi.Object) -> Unit = {}) {
        client.send(function) { result ->
            if (result is TdApi.Error) {
                if (result.code != 404) {
                    Log.e(TAG, "Error in send $function: ${result.code} ${result.message}")
                } else {
                    Log.w(TAG, "Not found in send $function: ${result.message}")
                }
            }
            callback(result)
        }
    }

    suspend fun <T : TdApi.Object> sendSuspend(function: TdApi.Function<T>): T {
        var retries = 0
        while (true) {
            waitForGlobalRetryWindow()
            val result = awaitResult(function)

            if (result !is TdApi.Error) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }

            if (result.code == 429 && retries < 3) {
                retries++
                val retryAfterMs = parseRetryAfterMs(result.message)
                Log.w(TAG, "Rate limited for $function, retrying in ${retryAfterMs}ms (attempt $retries)")
                if (function is TdApi.GetUserFullInfo) {
                    delay(retryAfterMs)
                } else {
                    updateGlobalRetryWindow(retryAfterMs)
                }
                continue
            }

            val isExpectedUserFullInfoMiss =
                function is TdApi.GetUserFullInfo &&
                        result.code == 400 &&
                        result.message.contains("user not found", ignoreCase = true)

            if (isExpectedUserFullInfoMiss) {
                Log.w(TAG, "User not found in sendSuspend $function: ${result.code} ${result.message}")
            } else if (result.code != 404) {
                Log.e(TAG, "Error in sendSuspend $function: ${result.code} ${result.message}")
            } else {
                Log.w(TAG, "Not found in sendSuspend $function: ${result.message}")
            }
            throw TdLibException(result)
        }
    }

    private suspend fun <T : TdApi.Object> awaitResult(function: TdApi.Function<T>): TdApi.Object =
        suspendCancellableCoroutine { cont ->
            client.send(function) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

    private suspend fun waitForGlobalRetryWindow() {
        val waitMs = (globalRetryAfterUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)
        if (waitMs > 0L) delay(waitMs)
    }

    private fun updateGlobalRetryWindow(retryAfterMs: Long) {
        val target = System.currentTimeMillis() + retryAfterMs
        while (true) {
            val current = globalRetryAfterUntilMs.get()
            if (target <= current) return
            if (globalRetryAfterUntilMs.compareAndSet(current, target)) return
        }
    }

    private fun parseRetryAfterMs(message: String?): Long {
        val seconds = Regex("retry after\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 1L
        return (seconds * 1000L).coerceAtMost(60_000L)
    }
}