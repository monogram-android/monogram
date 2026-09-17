package org.monogram.sponsor

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.SponsorRegistry
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.database.dao.SponsorDao
import org.monogram.core.database.entity.SponsorEntity
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.SponsorState
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate

private const val SPONSOR_CHANNEL_ID = -1003640797855L
private const val SPONSOR_CHANNEL_USERNAME = "ahhfjfbdnejjfbfjdjdj"
private const val HISTORY_LIMIT = 100
private const val HISTORY_BATCHES_LIMIT = 20
private const val AUTH_CHECK_INTERVAL_MS = 60L * 1000L
private const val POST_LOGIN_SYNC_DELAY_MS = 15L * 1000L
private const val PERIODIC_SYNC_INTERVAL_MS = 60L * 60L * 1000L
private const val EMPTY_CACHE_RETRY_INTERVAL_MS = 10L * 60L * 1000L
private const val EVENT_DEBOUNCE_MS = 5L * 1000L
private const val RETRY_WEAK_RESULT_DELAY_MS = 1500L
private const val WEAK_RESULT_MIN_OLD_IDS = 3
private const val LOG_TAG = "sponsor sync"

class SponsorSyncManager(
    private val scope: CoroutineScope,
    private val client: MtprotoClient,
    private val sponsorDao: SponsorDao,
    private val sessionStore: SessionMetadataStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val started = AtomicBoolean(false)
    private val syncInProgress = AtomicBoolean(false)
    private val _sponsorState = MutableStateFlow(SponsorState())
    val sponsorState: StateFlow<SponsorState> = _sponsorState.asStateFlow()

    @Volatile
    private var sponsorChatId = SPONSOR_CHANNEL_ID
    private var eventSyncJob: Job? = null
    private var failureCount = 0

    init {
        start()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch(ioDispatcher) {
            loadFromDatabase()
            watchUpdates()

            var wasAuthorized = sessionStore.isAuthorized()
            if (wasAuthorized) {
                runScheduledSync(force = true, reason = "startup")
            }

            while (isActive) {
                if (!sessionStore.isAuthorized()) {
                    wasAuthorized = false
                    failureCount = 0
                    delay(AUTH_CHECK_INTERVAL_MS)
                    continue
                }

                if (!wasAuthorized) {
                    wasAuthorized = true
                    delay(POST_LOGIN_SYNC_DELAY_MS)
                    runScheduledSync(force = true, reason = "auth_ready")
                    continue
                }

                val hasCachedSponsors = sponsorDao.getAllIds().isNotEmpty()
                delay(nextPeriodicDelayMs(hasCachedSponsors))
                runScheduledSync(force = !hasCachedSponsors, reason = "periodic")
            }
        }
    }

    fun forceSync() {
        scope.launch(ioDispatcher) {
            syncOnce(force = true, reason = "manual")
        }
    }

    private suspend fun loadFromDatabase() {
        val cachedIds = sponsorDao.getAllIds().toSet()
        SponsorRegistry.updateSponsorIds(cachedIds)
        _sponsorState.value = _sponsorState.value.copy(
            supporterIds = cachedIds,
            supportersCount = cachedIds.size,
            isLoaded = cachedIds.isNotEmpty(),
        )
        AppLog.api(LOG_TAG, "loaded=${cachedIds.size}")
    }

    private suspend fun runScheduledSync(force: Boolean, reason: String) {
        when (syncOnce(force = force, reason = reason)) {
            SyncOutcome.SUCCESS, SyncOutcome.SKIPPED -> failureCount = 0

            SyncOutcome.FAILED -> {
                failureCount++
                val delayMs = failureBackoffMs(failureCount)
                AppLog.warn(LOG_TAG, "failed reason=$reason retryIn=${delayMs}ms")
                delay(delayMs)
                when (syncOnce(force = force, reason = reason + "_retry")) {
                    SyncOutcome.SUCCESS, SyncOutcome.SKIPPED -> failureCount = 0
                    SyncOutcome.FAILED -> failureCount++
                    SyncOutcome.BUSY -> Unit
                }
            }

            SyncOutcome.BUSY -> Unit
        }
    }

    private fun nextPeriodicDelayMs(hasCachedSponsors: Boolean): Long =
        if (hasCachedSponsors) PERIODIC_SYNC_INTERVAL_MS else EMPTY_CACHE_RETRY_INTERVAL_MS

    private fun failureBackoffMs(failures: Int): Long {
        val minutes = when (failures.coerceAtMost(4)) {
            1 -> 2L
            2 -> 5L
            3 -> 15L
            else -> 30L
        }
        return minutes * 60L * 1000L
    }

    private fun watchUpdates() {
        scope.launch(ioDispatcher) {
            client.updates().collect { update ->
                when (update) {
                    is MtprotoUpdate.NewMessage -> {
                        if (update.message.id.chatId.value == sponsorChatId) {
                            requestEventSync("new_message", force = true)
                        }
                    }

                    is MtprotoUpdate.MessageEdited -> {
                        if (update.message.id.chatId.value == sponsorChatId) {
                            requestEventSync("message_edited", force = true)
                        }
                    }

                    is MtprotoUpdate.MessagesDeleted -> {
                        if (update.chatId?.value == sponsorChatId) {
                            requestEventSync("messages_deleted", force = true)
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun requestEventSync(reason: String, force: Boolean) {
        eventSyncJob?.cancel()
        eventSyncJob = scope.launch(ioDispatcher) {
            delay(EVENT_DEBOUNCE_MS)
            syncOnce(force = force, reason = reason)
        }
    }

    private suspend fun syncOnce(force: Boolean, reason: String): SyncOutcome {
        if (!syncInProgress.compareAndSet(false, true)) return SyncOutcome.BUSY

        try {
            _sponsorState.value = _sponsorState.value.copy(isSyncInProgress = true)
            if (!sessionStore.isAuthorized()) {
                AppLog.api(LOG_TAG, "skipped reason=$reason unauthorized")
                return SyncOutcome.SKIPPED
            }

            val latestUpdatedAt = sponsorDao.getLatestUpdatedAt() ?: 0L
            val age = System.currentTimeMillis() - latestUpdatedAt
            if (!force && latestUpdatedAt > 0L && age < PERIODIC_SYNC_INTERVAL_MS) {
                AppLog.api(LOG_TAG, "skipped reason=$reason age=${age}ms")
                return SyncOutcome.SKIPPED
            }

            sponsorChatId = resolveSponsorChatId()
            var messages = when (val history = loadSponsorHistoryMessages(sponsorChatId)) {
                is HistoryLoadResult.Success -> history.messages
                is HistoryLoadResult.Failure -> {
                    AppLog.warn(LOG_TAG, "history failed reason=$reason")
                    return SyncOutcome.FAILED
                }
            }

            val oldIds = sponsorDao.getAllIds().toSet()
            var parsedIds = parseSponsorIds(messages)
            if (isWeakResult(parsedIds, oldIds)) {
                AppLog.warn(
                    LOG_TAG,
                    "weak result parsed=${parsedIds.size} cached=${oldIds.size} retrying",
                )
                delay(RETRY_WEAK_RESULT_DELAY_MS)
                when (val retry = loadSponsorHistoryMessages(sponsorChatId)) {
                    is HistoryLoadResult.Success -> {
                        val retryParsedIds = parseSponsorIds(retry.messages)
                        if (retryParsedIds.size > parsedIds.size) {
                            messages = retry.messages
                            parsedIds = retryParsedIds
                            AppLog.api(LOG_TAG, "retry parsed=${parsedIds.size}")
                        } else {
                            AppLog.warn(LOG_TAG, "retry parsed=${retryParsedIds.size} no improvement")
                        }
                    }

                    is HistoryLoadResult.Failure -> AppLog.warn(LOG_TAG, "retry failed")
                }
            }

            val now = System.currentTimeMillis()
            if (parsedIds.isEmpty()) {
                AppLog.warn(LOG_TAG, "parsed empty, keeping=${oldIds.size}")
                SponsorRegistry.updateSponsorIds(oldIds)
                _sponsorState.value = _sponsorState.value.copy(
                    supporterIds = oldIds,
                    supportersCount = oldIds.size,
                    isLoaded = true,
                    lastSyncAt = now,
                )
                return SyncOutcome.SUCCESS
            }

            val actualIds = oldIds + parsedIds
            sponsorDao.insertAll(
                actualIds.map { userId ->
                    SponsorEntity(
                        userId = userId,
                        sourceChannelId = sponsorChatId,
                        updatedAt = now,
                    )
                },
            )

            SponsorRegistry.updateSponsorIds(actualIds)
            _sponsorState.value = _sponsorState.value.copy(
                supporterIds = actualIds,
                supportersCount = actualIds.size,
                isLoaded = true,
                lastSyncAt = now,
            )

            AppLog.api(
                LOG_TAG,
                "done reason=$reason messages=${messages.size} parsed=${parsedIds.size} " +
                    "ids=${actualIds.size} added=${(actualIds - oldIds).size}",
            )
            return SyncOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppLog.warn(LOG_TAG, "failed reason=$reason error=${t.javaClass.simpleName}")
            return SyncOutcome.FAILED
        } finally {
            _sponsorState.value = _sponsorState.value.copy(isSyncInProgress = false)
            syncInProgress.set(false)
        }
    }

    private fun isWeakResult(parsedIds: Set<Long>, oldIds: Set<Long>): Boolean =
        parsedIds.isNotEmpty() &&
            oldIds.size >= WEAK_RESULT_MIN_OLD_IDS &&
            parsedIds.size < oldIds.size / 2

    private suspend fun resolveSponsorChatId(): Long {
        val resolved = when (val outcome = client.resolveUsername(SPONSOR_CHANNEL_USERNAME)) {
            is Outcome.Ok -> outcome.value.peerId.value
            is Outcome.Err -> {
                AppLog.warn(LOG_TAG, "resolve failed, using fallback chatId")
                null
            }
        }
        return resolved ?: SPONSOR_CHANNEL_ID
    }

    private suspend fun loadSponsorHistoryMessages(chatId: Long): HistoryLoadResult {
        val result = mutableListOf<Message>()
        val seenIds = HashSet<Int>()
        var fromMessageId = 0

        repeat(HISTORY_BATCHES_LIMIT) {
            val batch = when (
                val outcome = client.getHistoryPage(
                    chatId = PeerId(chatId),
                    limit = HISTORY_LIMIT,
                    offsetId = fromMessageId,
                )
            ) {
                is Outcome.Ok -> outcome.value
                is Outcome.Err -> return HistoryLoadResult.Failure(outcome)
            }

            if (batch.isEmpty()) return HistoryLoadResult.Success(result)

            val oldestInBatch = batch.minOfOrNull { it.id.id } ?: return HistoryLoadResult.Success(result)
            batch.forEach { message ->
                if (seenIds.add(message.id.id)) result.add(message)
            }

            if (batch.size < HISTORY_LIMIT || oldestInBatch <= 0 || oldestInBatch == fromMessageId) {
                return HistoryLoadResult.Success(result)
            }

            fromMessageId = oldestInBatch
        }

        return HistoryLoadResult.Success(result)
    }

    private fun parseSponsorIds(messages: List<Message>): Set<Long> {
        var invalidTokens = 0
        val ids = messages.asSequence()
            .mapNotNull { message -> message.text?.takeIf { it.isNotBlank() } }
            .flatMap { text -> text.splitToSequence(",") }
            .mapNotNull { token ->
                val value = token.trim()
                if (value.isEmpty()) return@mapNotNull null
                val id = value.toLongOrNull()
                if (id == null || id <= 0L) {
                    invalidTokens++
                    null
                } else {
                    id
                }
            }
            .toSet()

        if (invalidTokens > 0) {
            AppLog.warn(LOG_TAG, "skipped invalid tokens=$invalidTokens")
        }

        return ids
    }

    private enum class SyncOutcome {
        SUCCESS,
        FAILED,
        SKIPPED,
        BUSY,
    }

    private sealed class HistoryLoadResult {
        data class Success(val messages: List<Message>) : HistoryLoadResult()
        data class Failure(val error: Outcome.Err) : HistoryLoadResult()
    }
}
