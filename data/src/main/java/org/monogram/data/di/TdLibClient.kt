package org.monogram.data.di

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdLibException(val error: TdApi.Error) : Exception(error.message)

class TdLibClient(private val context: Context) {
    private val TAG = "TdLibClient"

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

    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 10,
        extraBufferCapacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates = _updates

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

    suspend fun <T : TdApi.Object> sendSuspend(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client.send(function) { result ->
                if (cont.isActive) {
                    if (result is TdApi.Error) {
                        if (result.code == 404 && function is TdApi.GetChatPinnedMessage) {
                            @Suppress("UNCHECKED_CAST")
                            cont.resume(null as T)
                        } else {
                            if (result.code != 404) {
                                Log.e(TAG, "Error in sendSuspend $function: ${result.code} ${result.message}")
                            } else {
                                Log.w(TAG, "Not found in sendSuspend $function: ${result.message}")
                            }
                            cont.resumeWithException(TdLibException(result))
                        }
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as T)
                    }
                }
            }
        }

    fun getContext() = context
}
