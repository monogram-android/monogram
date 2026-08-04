package org.monogram.data.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.koin.core.context.GlobalContext
import org.monogram.data.gateway.TdLibException
import org.monogram.data.gateway.TelegramGateway
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

private const val DEDUP_TTL_MS = 5 * 60_000L
private const val DEDUP_PREFS = "push_dedup"
private const val DEDUP_KEY_PREFIX = "expiry:"

interface PushDeduplicator {
    fun accept(key: String): Boolean
}

interface PushWorkScheduler {
    fun enqueue(uniqueName: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest)
}

interface PushWakeLock {
    val isHeld: Boolean
    fun release()
}

fun interface PushWakeLockProvider {
    fun acquire(context: Context, timeoutMs: Long): PushWakeLock?
}

data class PushProcessingTimeouts(
    val processMs: Long = 8_000L,
    val wakeLockMs: Long = 15_000L
)

class PushProcessingMetrics {
    data class Snapshot(
        val received: Long,
        val deduplicated: Long,
        val workerAttempts: Long,
        val outcomes: Map<PushProcessingCoordinator.Result, Long>,
        val fallbackReasons: Map<String, Long>,
        val p50LatencyMs: Long,
        val p95LatencyMs: Long,
        val p99LatencyMs: Long
    )

    private val received = AtomicLong()
    private val deduplicated = AtomicLong()
    private val workerAttempts = AtomicLong()
    private val outcomes = linkedMapOf<PushProcessingCoordinator.Result, Long>()
    private val fallbackReasons = linkedMapOf<String, Long>()
    private val latenciesMs = ArrayDeque<Long>()

    fun recordReceipt() {
        received.incrementAndGet()
    }

    fun recordDeduplicated() {
        deduplicated.incrementAndGet()
    }

    fun recordWorkerAttempt() {
        workerAttempts.incrementAndGet()
    }

    @Synchronized
    fun recordCompletion(result: PushProcessingCoordinator.Result, latencyMs: Long) {
        outcomes[result] = (outcomes[result] ?: 0) + 1
        if (latenciesMs.size == MAX_SAMPLES) latenciesMs.removeFirst()
        latenciesMs.addLast(latencyMs.coerceAtLeast(0L))
    }

    @Synchronized
    fun recordFallback(reason: String) {
        fallbackReasons[reason] = (fallbackReasons[reason] ?: 0) + 1
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val sortedLatencies = latenciesMs.sorted()
        return Snapshot(
            received = received.get(),
            deduplicated = deduplicated.get(),
            workerAttempts = workerAttempts.get(),
            outcomes = outcomes.toMap(),
            fallbackReasons = fallbackReasons.toMap(),
            p50LatencyMs = percentile(sortedLatencies, 0.50),
            p95LatencyMs = percentile(sortedLatencies, 0.95),
            p99LatencyMs = percentile(sortedLatencies, 0.99)
        )
    }

    private fun percentile(samples: List<Long>, percentile: Double): Long {
        if (samples.isEmpty()) return 0L
        val index = (ceil(samples.size * percentile).toInt() - 1).coerceIn(0, samples.lastIndex)
        return samples[index]
    }

    private companion object {
        const val MAX_SAMPLES = 256
    }
}

/** Shared, PII-safe push entry point used by FCM and UnifiedPush. */
class PushProcessingCoordinator(
    private val context: Context,
    private val gateway: TelegramGateway,
    private val pushSyncTrigger: PushSyncRequester,
    private val workScheduler: PushWorkScheduler = AndroidPushWorkScheduler(context),
    private val deduplicator: PushDeduplicator = SharedPreferencesPushDeduplicator(context),
    private val wakeLockProvider: PushWakeLockProvider = AndroidPushWakeLockProvider,
    private val timeouts: PushProcessingTimeouts = PushProcessingTimeouts(),
    val metrics: PushProcessingMetrics = PushProcessingMetrics()
) {
    enum class Provider { FCM, UNIFIED_PUSH }

    fun enqueue(
        provider: Provider,
        payload: String?,
        accountScope: String = DEFAULT_ACCOUNT_SCOPE
    ) {
        metrics.recordReceipt()
        val diagnosticId = payload?.takeIf(String::isNotBlank)?.let(::diagnosticId) ?: "empty"
        if (!deduplicator.accept("$accountScope:$diagnosticId")) {
            metrics.recordDeduplicated()
            Log.d(TAG, "Duplicate push ignored: provider=$provider id=$diagnosticId")
            return
        }
        enqueueWork(
            uniqueName = "$PUSH_WORK_PREFIX$accountScope",
            provider = provider,
            payload = payload,
            reconciliation = false
        )
    }

    fun enqueueReconciliation(accountScope: String = DEFAULT_ACCOUNT_SCOPE) {
        enqueueWork(
            uniqueName = "$RECONCILIATION_WORK_PREFIX$accountScope",
            provider = Provider.FCM,
            payload = null,
            reconciliation = true
        )
    }

    fun enqueueFcmTokenRegistration(token: String) {
        val request = OneTimeWorkRequestBuilder<PushProcessingWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_OPERATION, OPERATION_REGISTER_FCM_TOKEN)
                    .putString(KEY_FCM_TOKEN, token)
                    .build()
            )
            .build()
        workScheduler.enqueue(FCM_TOKEN_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun process(
        payload: String?,
        reconciliation: Boolean,
        receivedAtMs: Long = System.currentTimeMillis()
    ): Result {
        val startedAt = System.nanoTime()
        if (reconciliation || payload.isNullOrBlank()) {
            val reason = if (reconciliation) "push_reconciliation" else "push_empty_payload"
            requestFallback(reason)
            return Result.RetryScheduled.also {
                metrics.recordCompletion(
                    it,
                    System.currentTimeMillis() - receivedAtMs
                )
            }
        }
        if (!gateway.isAuthenticated.value) {
            return Result.AuthGone.also {
                metrics.recordCompletion(
                    it,
                    System.currentTimeMillis() - receivedAtMs
                )
            }
        }

        val wakeLock = wakeLockProvider.acquire(context, timeouts.wakeLockMs)
        var outcome = Result.Failed
        return try {
            val response = withTimeoutOrNull(timeouts.processMs) {
                runCatching { gateway.execute(TdApi.ProcessPushNotification(payload)) }
            }
            when {
                response == null -> {
                    requestFallback("push_process_timeout")
                    Result.RetryScheduled
                }

                response.isFailure -> {
                    val error = response.exceptionOrNull()
                    when {
                        error.isAuthRelated() -> Result.AuthGone
                        error.isUnsupportedPayload() -> {
                            requestFallback("push_unsupported_payload")
                            Result.UnsupportedPayload
                        }

                        else -> {
                            requestFallback("push_process_failure")
                            Result.RetryScheduled
                        }
                    }
                }

                else -> Result.Processed
            }
                .also { outcome = it }
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            metrics.recordCompletion(outcome, System.currentTimeMillis() - receivedAtMs)
            Log.d(TAG, "Push processing finished: durationMs=$elapsedMs")
        }
    }

    private fun enqueueWork(
        uniqueName: String,
        provider: Provider,
        payload: String?,
        reconciliation: Boolean
    ) {
        val request = OneTimeWorkRequestBuilder<PushProcessingWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_PROVIDER, provider.name)
                    .putString(KEY_PAYLOAD, payload)
                    .putBoolean(KEY_RECONCILIATION, reconciliation)
                    .putLong(KEY_RECEIVED_AT, System.currentTimeMillis())
                    .build()
            )
            .build()
        workScheduler.enqueue(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    enum class Result { Processed, UnsupportedPayload, RetryScheduled, AuthGone, Failed }

    internal fun recordWorkerAttempt() {
        metrics.recordWorkerAttempt()
    }

    private fun requestFallback(reason: String) {
        metrics.recordFallback(reason)
        pushSyncTrigger.requestSync(reason)
    }

    private fun Throwable?.isAuthRelated(): Boolean {
        val error = (this as? TdLibException)?.error ?: return false
        return error.code == 401 || error.message.orEmpty()
            .contains("authorization", ignoreCase = true)
    }

    private fun Throwable?.isUnsupportedPayload(): Boolean {
        val error = (this as? TdLibException)?.error ?: return false
        val message = error.message.orEmpty().lowercase()
        return error.code == 400 &&
                message.contains("push") &&
                (message.contains("unsupported") || message.contains("invalid"))
    }

    companion object {
        private const val TAG = "PushCoordinator"
        private const val DEFAULT_ACCOUNT_SCOPE = "default"
        private const val PUSH_WORK_PREFIX = "push-process:"
        private const val RECONCILIATION_WORK_PREFIX = "push-reconcile:"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_RECONCILIATION = "reconciliation"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val KEY_OPERATION = "operation"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val OPERATION_REGISTER_FCM_TOKEN = "register_fcm_token"
        private const val FCM_TOKEN_WORK_NAME = "push-register-fcm-token"
        internal fun diagnosticId(payload: String): String = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    class PushProcessingWorker(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (inputData.getString(KEY_OPERATION) == OPERATION_REGISTER_FCM_TOKEN) {
                val token = inputData.getString(KEY_FCM_TOKEN)
                    ?: return Result.failure()
                val gateway = GlobalContext.get().get<TelegramGateway>()
                if (!gateway.isAuthenticated.value) return Result.retry()
                return runCatching {
                    gateway.execute(
                        TdApi.RegisterDevice(
                            TdApi.DeviceTokenFirebaseCloudMessaging(token, true),
                            longArrayOf()
                        )
                    )
                }.fold(
                    onSuccess = { Result.success() },
                    onFailure = { error ->
                        if ((error as? TdLibException)?.error?.code == 401) {
                            Result.retry()
                        } else {
                            Result.retry()
                        }
                    }
                )
            }
            val coordinator = GlobalContext.get().get<PushProcessingCoordinator>()
            coordinator.recordWorkerAttempt()
            return when (coordinator.process(
                payload = inputData.getString(KEY_PAYLOAD),
                reconciliation = inputData.getBoolean(KEY_RECONCILIATION, false),
                receivedAtMs = inputData.getLong(KEY_RECEIVED_AT, System.currentTimeMillis())
            )) {
                PushProcessingCoordinator.Result.Processed,
                PushProcessingCoordinator.Result.UnsupportedPayload,
                PushProcessingCoordinator.Result.AuthGone ->
                    Result.success()

                PushProcessingCoordinator.Result.RetryScheduled,
                PushProcessingCoordinator.Result.Failed ->
                    Result.retry()
            }
        }
    }
}

private class AndroidPushWorkScheduler(context: Context) : PushWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueue(
        uniqueName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    ) {
        workManager.enqueueUniqueWork(uniqueName, policy, request)
    }
}

private class SharedPreferencesPushDeduplicator(context: Context) : PushDeduplicator {
    private val preferences = context.getSharedPreferences(DEDUP_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun accept(key: String): Boolean {
        val now = System.currentTimeMillis()
        val storageKey = "$DEDUP_KEY_PREFIX${PushProcessingCoordinator.diagnosticId(key)}"
        val expiry = preferences.getLong(storageKey, 0L)
        if (expiry > now) return false
        preferences.edit().putLong(storageKey, now + DEDUP_TTL_MS).apply()
        return true
    }
}

private object AndroidPushWakeLockProvider : PushWakeLockProvider {
    override fun acquire(context: Context, timeoutMs: Long): PushWakeLock? {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monogram:PushWorker")
            ?: return null
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(timeoutMs)
        return object : PushWakeLock {
            override val isHeld: Boolean get() = wakeLock.isHeld
            override fun release() = wakeLock.release()
        }
    }
}
