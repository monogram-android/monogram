package org.monogram.network.bridge.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.DebugStatKind
import org.monogram.core.common.DebugStats
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.TelegramCredentials
import org.monogram.core.common.perfOp
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.common.telegram.retryShortFlood
import org.monogram.core.models.AuthSession
import org.monogram.core.models.AuthState
import org.monogram.core.models.PeerId
import org.monogram.mtproto.MtprotoNative
import org.monogram.network.bridge.MtprotoUpdate
import uniffi.monogram_mtproto.MtprotoException

internal class SessionCore(
    internal val credentials: TelegramCredentials,
    internal val sessionPath: String,
    internal val native: MtprotoNative,
    internal val historyTimeoutMs: Long,
    internal val nativeDispatcher: CoroutineDispatcher,
    internal val refreshDcSidecar: (String) -> Unit,
    internal val clock: MonotonicClock = NanoTimeClock,
) : SessionOps {
    @Volatile
    internal var handle: Long = 0L

    /** Native bootstrap (including TXT config) is valid for this handle. */
    @Volatile
    internal var connectedHandle: Long = 0L
    @Volatile
    private var updatesStartedHandle: Long = 0L
    private val updatesStartMutex = Mutex()
    internal val connectMutex = Mutex()
    private val inflightMutex = Mutex()
    private val inflight = HashMap<String, Deferred<*>>()
    @Volatile
    internal var sessionDead: Boolean = false
    @Volatile
    internal var closed: Boolean = false
    internal fun isAuthFallback(fallback: String): Boolean =
        fallback.startsWith("auth.")

    internal fun deadSessionError(): Outcome.Err {
        val telegram = TelegramError.sessionInvalidated()
        return Outcome.Err(telegram.message, telegram = telegram)
    }

    /** Covers handle create/destroy only. Never held across a native RPC. */
    internal val handleLock = Any()
    internal val userRpcs = java.util.concurrent.atomic.AtomicInteger(0)

    /** Set by [hibernate] while requests are in flight; applied on the last completion. */
    @Volatile
    internal var hibernatePending = false
    @Volatile
    internal var dialogForeground: Boolean = false
    internal val scope = CoroutineScope(SupervisorJob() + nativeDispatcher)
    internal val sessionLostFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val localUpdates = MutableSharedFlow<MtprotoUpdate>(extraBufferCapacity = 8)

    /** Wakes the update loop when a handle is created, reconnected, or closed. */
    internal val updatesWake = Channel<Unit>(capacity = Channel.CONFLATED)
    internal val updatesEvents = MutableSharedFlow<MtprotoUpdate>(
        extraBufferCapacity = 64,
    )

    init {
        PerfLog.installNative(
            setEnabled = { enabled -> runCatching { native.perfSetEnabled(enabled) } },
            snapshot = { runCatching { native.perfSnapshot(true) }.getOrDefault("{}") },
        )
    }

    internal fun activeHandleOrZero(): Long = synchronized(handleLock) {
        if (closed) 0L else handle
    }

    internal fun isCurrentHandle(activeHandle: Long): Boolean = synchronized(handleLock) {
        !closed && activeHandle != 0L && handle == activeHandle
    }

    /** Captures the client generation before the blocking native request starts. */
    internal suspend fun <T> onNative(block: suspend (Long) -> T): T =
        onNativeClass(DispatchClass.INTERACTIVE_READ, block)

    /** Media/file work: interactive priority on the media lanes. */
    internal suspend fun <T> onNativeMedia(block: suspend (Long) -> T): T =
        onNativeClass(DispatchClass.INTERACTIVE_MEDIA, block)

    internal suspend fun <T> onNativeClass(dispatchClass: Int, block: suspend (Long) -> T): T =
        nativeRequest(native, nativeDispatcher, dispatchClass) {
            val activeHandle = synchronized(handleLock) {
                ensureHandleLocked()
                handle
            }
            userRpcs.incrementAndGet()
            try {
                block(activeHandle)
            } finally {
                if (userRpcs.decrementAndGet() == 0 && hibernatePending) {
                    hibernatePending = false
                    resetHandle()
                }
            }
        }

    /** Skip idle work (media/folders) while a user-facing RPC is in flight. */
    internal suspend fun <T> onNativeIfFree(block: (Long) -> T): T? =
        onNativeIfFreeClass(DispatchClass.BACKGROUND_READ, block)

    internal suspend fun <T> onNativeIfFreeClass(dispatchClass: Int, block: (Long) -> T): T? =
        nativeRequest(native, nativeDispatcher, dispatchClass) {
            val activeHandle = synchronized(handleLock) {
                check(!closed) { "MTProto client is closed" }
                handle
            }
            if (activeHandle == 0L) return@nativeRequest null
            if (userRpcs.get() > 0) return@nativeRequest null
            block(activeHandle)
        }

    internal fun fail(
        e: Exception,
        fallback: String,
        degradeHome: Boolean = true,
    ): Outcome.Err {
        val raw = when (e) {
            is MtprotoException.Message -> e.v1
            is MtprotoException.RegistrationRequired -> "PHONE_NUMBER_UNOCCUPIED"
            is MtprotoException.PasswordRequired -> "SESSION_PASSWORD_NEEDED"
            else -> e.message
        }?.removePrefix("v1=")?.trim().orEmpty()
        val telegram = TelegramError.parse(raw.ifBlank { fallback }, fallback)
        // Perf-gated raw failure text; the summary alone cannot tell faults apart.
        if (PerfLog.isEnabled()) {
            AppLog.api("rpc raw", "op=$fallback exc=${e.javaClass.simpleName} text=$raw")
        }
        AppLog.warn(fallback, nativeFailureLogLine(telegram, raw))
        DebugStats.record(
            kind = DebugStatKind.ERROR,
            op = fallback.removeSuffix(" failed"),
            durationMs = 0L,
            result = "err",
            errorKind = telegram.kind.name,
        )
        if (degradeHome) {
            if (telegram.kind == TelegramError.Kind.Network ||
                telegram.kind == TelegramError.Kind.Internal
            ) {
                connectedHandle = 0L
            }
            if (telegram.requiresReauth) {
                markSessionDegraded(telegram, raw)
            }
        }
        return Outcome.Err(telegram.message, e, telegram)
    }

    internal fun markSessionDegraded(telegram: TelegramError, raw: String = "") {
        if (sessionDead) return
        sessionDead = true
        AppLog.warn("session", "degraded ${nativeFailureLogLine(telegram, raw)}")
        updatesWake.trySend(Unit)
        sessionLostFlow.tryEmit(Unit)
    }

    internal suspend fun <T> rpc(fallback: String, block: suspend (Long) -> T): Outcome<T> =
        rpc(fallback, DispatchClass.INTERACTIVE_READ, block)

    internal suspend fun <T> rpcBackground(fallback: String, block: suspend (Long) -> T): Outcome<T> =
        rpc(fallback, DispatchClass.BACKGROUND_READ, block)

    internal suspend fun <T> rpcWrite(fallback: String, block: suspend (Long) -> T): Outcome<T> =
        rpc(fallback, DispatchClass.INTERACTIVE_WRITE, block)

    /** One native call per key while identical reads overlap. */
    internal suspend fun <T> coalesce(key: String, block: suspend () -> T): T {
        val deferred = inflightMutex.withLock {
            @Suppress("UNCHECKED_CAST")
            (inflight[key] as Deferred<T>?) ?: scope.async {
                try {
                    block()
                } finally {
                    inflightMutex.withLock { inflight.remove(key) }
                }
            }.also { inflight[key] = it }
        }
        return deferred.await()
    }

    internal suspend fun <T> rpc(
        fallback: String,
        dispatchClass: Int,
        block: suspend (Long) -> T,
    ): Outcome<T> =
        retryShortFlood {
            try {
                perfOp("bridge:${fallback.removeSuffix(" failed")}") {
                    Outcome.Ok(onNativeClass(dispatchClass, block))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isStaleHandleFailure(e)) {
                    AppLog.api("mtproto", "stale handle; recreating")
                    resetHandle()
                    try {
                        perfOp("bridge:${fallback.removeSuffix(" failed")}") {
                            Outcome.Ok(onNativeClass(dispatchClass, block))
                        }
                    } catch (retry: CancellationException) {
                        throw retry
                    } catch (retry: Exception) {
                        fail(retry, fallback)
                    }
                } else {
                    fail(e, fallback)
                }
            }
        }

    /** Skip native.connect (main lock) after a successful connect on this handle. */
    internal suspend fun ensureConnected(): Outcome<Unit> {
        if (sessionDead) {
            AppLog.api("media", "ensureConnected dead")
            return deadSessionError()
        }
        val active = if (closed) 0L else handle
        if (active != 0L && connectedHandle == active) {
            maybeStartUpdates(active)
            return Outcome.Ok(Unit)
        }
        AppLog.api("media", "ensureConnected connect handle=$active connected=$connectedHandle")
        return connect()
    }

    internal fun ensureHandleLocked() {
        check(!closed) { "MTProto client is closed" }
        if (handle == 0L) {
            handle = native.createClient(
                apiId = credentials.apiId,
                apiHash = credentials.apiHash,
                sessionPath = sessionPath,
            )
            updatesWake.trySend(Unit)
        }
    }

    internal suspend fun maybeStartUpdates(activeHandle: Long) {
        if (sessionDead || activeHandle == 0L) return
        if (updatesStartedHandle == activeHandle) {
            updatesWake.trySend(Unit)
            return
        }
        withContext(nativeDispatcher) {
            updatesStartMutex.withLock {
                if (sessionDead || !isCurrentHandle(activeHandle)) return@withLock
                if (updatesStartedHandle == activeHandle) {
                    updatesWake.trySend(Unit)
                    return@withLock
                }
                try {
                    if (native.isAuthorized(activeHandle)) {
                        native.startUpdates(activeHandle)
                        if (isCurrentHandle(activeHandle)) updatesStartedHandle = activeHandle
                        PerfLog.trace("updates", "start", handle = activeHandle)
                        updatesWake.trySend(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val err = fail(e, "startUpdates failed", degradeHome = false)
                    if (err.telegramError.requiresReauth) throw e
                }
            }
        }
    }

    override suspend fun setTestDc(enabled: Boolean): Outcome<Unit> =
        rpc("setTestDc failed") { activeHandle ->
            native.setTestDc(activeHandle, enabled)
        }

    override suspend fun connect(): Outcome<Unit> {
        if (sessionDead) return deadSessionError()
        val already = if (closed) 0L else handle
        if (already != 0L && connectedHandle == already) {
            maybeStartUpdates(already)
            return Outcome.Ok(Unit)
        }
        connectMutex.withLock {
            if (sessionDead) return deadSessionError()
            val locked = if (closed) 0L else handle
            if (locked != 0L && connectedHandle == locked) {
                maybeStartUpdates(locked)
                return Outcome.Ok(Unit)
            }
            AppLog.api("connect", "start handle=$locked")
            val connected = rpc("connect failed") { activeHandle ->
                refreshDcSidecar(sessionPath)
                try {
                    native.connect(activeHandle)
                    if (isCurrentHandle(activeHandle)) connectedHandle = activeHandle
                } catch (e: Exception) {
                    connectedHandle = 0L
                    throw e
                } finally {
                    DcTxtBootstrap.logNativeStatus(sessionPath)
                }
                activeHandle
            }
            if (connected is Outcome.Ok) maybeStartUpdates(connected.value)
            return when (connected) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Err -> connected
            }
        }
    }

    /** Reads the restored native session without waiting for a network connection. */
    suspend fun isLocallyAuthorized(): Outcome<Boolean> =
        rpc("isLocallyAuthorized failed") { activeHandle -> native.isAuthorized(activeHandle) }

    suspend fun isAuthorized(): Outcome<Boolean> {
        when (val connected = ensureConnected()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return rpc("isAuthorized failed") { activeHandle -> native.isAuthorized(activeHandle) }
    }

    override suspend fun sendAuthCode(phone: String): Outcome<AuthState.AwaitingCode> {
        sessionDead = false
        AppLog.api("auth.sendCode", "start")
        // Do not probe a revoked persisted session before sendCode. The native
        // auth entry point detects session_dead and creates a fresh auth key;
        // calling connect() first would return AUTH_KEY_UNREGISTERED and make
        // the login form appear permanently stuck.
        val result = rpc("auth.sendCode failed") { activeHandle ->
            val sent = native.sendAuthCode(activeHandle, phone)
            AuthState.AwaitingCode(
                phone = sent.phone,
                phoneCodeHash = sent.phoneCodeHash,
                codeType = sent.codeType,
                codeLength = if (sent.codeLength > 0) sent.codeLength else 5,
            )
        }
        if (result is Outcome.Ok) {
            AppLog.api("auth.sendCode", "ok type=${result.value.codeType} length=${result.value.codeLength}")
        }
        return result
    }

    override suspend fun resendAuthCode(
        phone: String,
        phoneCodeHash: String,
    ): Outcome<AuthState.AwaitingCode> {
        AppLog.api("auth.resendCode", "start")
        val result = rpc("auth.resendCode failed") { activeHandle ->
            val sent = native.resendAuthCode(activeHandle, phone, phoneCodeHash)
            AuthState.AwaitingCode(
                phone = sent.phone,
                phoneCodeHash = sent.phoneCodeHash,
                codeType = sent.codeType,
                codeLength = if (sent.codeLength > 0) sent.codeLength else 5,
            )
        }
        if (result is Outcome.Ok) {
            AppLog.api("auth.resendCode", "ok type=${result.value.codeType} length=${result.value.codeLength}")
        }
        return result
    }

    override suspend fun signIn(
        phone: String,
        phoneCodeHash: String,
        code: String,
    ): Outcome<AuthState> {
        sessionDead = false
        AppLog.api("auth.signIn", "start")
        when (val connected = connect()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return try {
            val signedIn = onNative { activeHandle ->
                val result = native.signIn(activeHandle, phone, phoneCodeHash, code)
                maybeStartUpdates(activeHandle)
                result
            }
            AppLog.api("auth.signIn", "ok")
            Outcome.Ok(
                AuthState.Authorized(
                    AuthSession(
                        userId = PeerId(signedIn.userId),
                        dcId = signedIn.dcId,
                    ),
                ),
            )
        } catch (e: MtprotoException.RegistrationRequired) {
            fail(e, "auth.signIn failed")
        } catch (e: MtprotoException.PasswordRequired) {
            Outcome.Ok(AuthState.AwaitingPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e, "auth.signIn failed")
        }
    }

    override suspend fun checkPassword(password: String): Outcome<AuthState.Authorized> {
        sessionDead = false
        when (val connected = connect()) {
            is Outcome.Err -> return connected
            is Outcome.Ok -> Unit
        }
        return rpc("auth.checkPassword failed") { activeHandle ->
            val signedIn = native.checkPassword(activeHandle, password)
            maybeStartUpdates(activeHandle)
            AuthState.Authorized(
                AuthSession(
                    userId = PeerId(signedIn.userId),
                    dcId = signedIn.dcId,
                ),
            )
        }
    }

    override fun libraryVersion(): String = native.libraryVersion()
    override fun applyDownloadConcurrency(lanes: Int, parts: Int) {
        runCatching { native.setDownloadConcurrency(lanes, parts) }
            .onSuccess { AppLog.api("download concurrency", "lanes=$lanes parts=$parts") }
            .onFailure { AppLog.warn("download concurrency", "apply failed: ${it.message}") }
    }

    override fun setFilePartKib(kib: Int) {
        runCatching { native.setFilePartKib(kib) }
            .onSuccess { AppLog.api("upload part", "kib=$kib") }
            .onFailure { AppLog.warn("upload part", "apply failed: ${it.message}") }
    }

    override fun setDownloadChunkKib(kib: Int) {
        runCatching { native.setDownloadChunkKib(kib) }
            .onSuccess { AppLog.api("download chunk", "kib=$kib") }
            .onFailure { AppLog.warn("download chunk", "apply failed: ${it.message}") }
    }

    override fun downloadChunkKib(): Int =
        runCatching { native.downloadChunkKib() }.getOrDefault(0)

    override fun setDownloadProgressListener(listener: ((String, Long, Long) -> Unit)?) {
        runCatching { native.setDownloadProgressListener(listener) }
            .onFailure { AppLog.warn("download progress", "listener failed: ${it.message}") }
    }

    override fun downloadConcurrency(): List<Int> =
        runCatching { native.downloadConcurrency() }.getOrDefault(listOf(0, 0))

    override fun setDialogForeground(active: Boolean) {
        dialogForeground = active
        if (active) return
        val activeHandle = synchronized(handleLock) {
            if (closed || handle == 0L) 0L else handle
        }
        if (activeHandle == 0L) return
        scope.launch {
            try {
                nativeRequest(native, nativeDispatcher, DispatchClass.BACKGROUND_READ) {
                    if (!dialogForeground && handle == activeHandle) {
                        native.clearActiveDialog(activeHandle)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // This is a best-effort priority hint; the next update drain
                // still performs authoritative channel recovery.
            }
        }
    }

    override suspend fun logout(): Outcome<Unit> {
        sessionDead = false
        val activeHandle = activeHandleOrZero()
        val remote = if (activeHandle == 0L) {
            Outcome.Ok(Unit)
        } else {
            try {
                Outcome.Ok(nativeRequest(native, nativeDispatcher, DispatchClass.INTERACTIVE_READ) {
                    native.logout(activeHandle)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e, "auth.logOut failed")
            }
        }
        // Local teardown is mandatory even when the server already revoked the
        // key or the network is unavailable. The caller clears Room only after
        // this method returns.
        resetHandle()
        runCatching {
            val file = java.io.File(sessionPath)
            if (file.exists()) file.delete()
            val tmp = java.io.File("$sessionPath.tmp")
            if (tmp.exists()) tmp.delete()
            // A path with no extension, or one ending in a bare dot, is left unchanged.
            val lastDot = sessionPath.lastIndexOf('.')
            val altTmpPath = if (lastDot in 0 until sessionPath.length - 1) {
                sessionPath.substring(0, lastDot) + ".tmp"
            } else {
                sessionPath
            }
            val altTmp = java.io.File(altTmpPath)
            if (altTmp.exists() && altTmp.absolutePath != tmp.absolutePath) altTmp.delete()
        }
        return remote
    }

    override fun hibernate() {
        if (closed) return
        if (userRpcs.get() > 0) {
            // Dropping the handle mid-request kills it; hibernate on last completion.
            hibernatePending = true
            AppLog.api("mtproto", "idle deferred")
            return
        }
        AppLog.api("mtproto", "idle")
        resetHandle()
    }

    override fun close() {
        synchronized(handleLock) {
            if (closed) return
            closed = true
        }
        scope.cancel()
        resetHandle()
        updatesWake.close()
    }

    internal fun resetHandle() {
        val oldHandle = synchronized(handleLock) {
            handle.also { handle = 0L }
        }
        connectedHandle = 0L
        if (updatesStartedHandle == oldHandle) updatesStartedHandle = 0L
        if (oldHandle != 0L) native.destroyClient(oldHandle)
        updatesWake.trySend(Unit)
    }
}
